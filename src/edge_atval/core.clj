(ns edge-atval.core
  (:require [instaparse.core :as insta]
            [iota :as iota-fileproc]
            [clojure.set :as clojure-set]
            [clojure.edn :as clojure-edn]
            [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))

;; functions (provided)
(declare proc-file proc-files)
;;       TODO      TODO

;; functions (for internal use)
(declare proc-asm proc-segm parse-instr mk-linker)
;;       DONE     TODO      TODO        DONE

;; dynamic vars

(def ^:dynamic *str->opcode*
  (try
    (clojure-edn/read-string
     (slurp "str->opcode.edn"))
    (catch java.io.FileNotFoundException _
      ::str->opcode_edn-unavailable)))

(def ^:dynamic *num-opcodes*
  (if (keyword? *str->opcode*)
    *str->opcode*
    ((comp count distinct vals)
     *str->opcode*)))

(def ^:dynamic *use-opcode-categories* true)

(def ^:dynamic *opcode->category*
  (if *use-opcode-categories*
    (try
      (clojure-edn/read-string
       (slurp "opcode->category.edn"))
      (catch java.io.FileNotFoundException _
        ::opcode->category_edn-unavaiable))
    (zipmap (range *num-opcodes*)
            (range *num-opcodes*))))

(def ^:dynamic *num-opcode-categories*
  (count *opcode->category*))

;; operational parameters (constants)

(def ^:dynamic *max-cond-depth* 10)
(def ^:dynamic *trampoline-channel-size* 100)

;; operational implementations (functions)

(def ^:dynamic *file-vector-implementation* iota-fileproc/vec)

;; basic specs

(s/def ::opcode (s/and int?
                       (complement neg?)
                       #(< % *num-opcodes*)))

(s/def ::opcode-category (s/and
                          int?
                          (complement neg?)
                          #(< % *num-opcode-categories*)))

(s/def ::line (s/and int?
                     (complement neg?)))

(s/def ::op-ref (s/tuple ::opcode
                         (s/keys :req [::line])))
(s/def ::mem-ref (s/tuple string?
                          (s/keys :req [::line])))
(s/def ::opcode-category-ref (s/tuple ::opcode-category
                                      ::line))

(s/def ::stack-ops (s/coll-of ::op-ref
                              :kind vector?))
(s/def ::call-stack (s/coll-of ::line
                               :kind vector?))
(s/def ::cmp-refs (s/tuple ::mem-ref ::mem-ref))
(s/def ::op-sources (s/map-of ::mem-ref ::op-ref))
(s/def ::flow-sources (s/coll-of ::op-ref
                                 :kind set?))
(s/def ::mov-sources (s/map-of ::mem-ref
                               ::mem-ref))
(s/def ::cond-depth (s/and int?
                           (complement neg?)))

(s/def ::state
  (s/tuple
   (s/keys :req [::op-sources])
   (s/keys :req [
                ::stack-ops ::call-stack
                ::cmp-refs
                ::flow-sources ::mov-sources
                ::cond-depth])))

(s/def ::target ::opcode-category-ref)
(s/def ::sources (s/coll-of
                  ::opcode-category-ref
                  :kind set?))

(s/def ::graph (s/map-of ::target ::sources))

(s/def ::mut (s/map-of ::mem-ref
                       (s/coll-of
                        (s/fspec
                         :args (s/cat
                                ::state ::state)
                         :ret ::state)
                        :kind set?)))

(s/def ::cond (s/keys :req [
                            ::op-ref ::sources
                            ::line]))

(s/def ::redir ::line)

(s/def ::flow (s/keys :req [::redir]
                      :opt [::cond]))

(s/def ::instr (s/keys :req [::target ::mut]
                       :opt [::flow]))

(s/def ::graph-agent (s/and
                      any? ;; Agent<::graph>
                      (comp
                       (partial s/valid?
                                ::graph)
                       deref)))

(s/def ::num-active (s/and
                     any? ;; Atom<int?>
                     (comp
                      int?
                      deref)))

(s/def ::jump-history (s/map-of ::line ::line))

(s/def ::track-mut
  (s/keys :req [::graph-agent ::num-active ::jump-history]))

;; function specs

(s/fdef proc
  :args (s/cat ::fname-bin string?)
  :ret ::graph)

(s/fdef proc-asm
  :args (s/cat ::fname-asm string?)
  :ret (partial satisfies? ;; impl WritePort<::graph>
                clojure.core.async.impl.protocols/WritePort))

(s/def ::proc-segm-fn
  (s/fspec :args (s/cat ::state ::state
                        ::state-meta ::state-meta
                        ::track-mut ::track-mut)
           :ret (s/coll-of ::proc-segm-fn
                           :kind set?)))

(s/fdef proc-segm
  :args (s/cat ::state ::state
               ::state-meta ::state-meta
               ::track-mut ::track-mut)
  :ret (s/coll-of ::proc-segm-fn
                  :kind set?))

(s/fdef parse-instr
  :args string?
  :ret ::instr)

(s/fdef mk-linker
  :args (s/cat ::sources ::sources
               ::target ::target)
  :ret (s/fspec
        :args (s/cat ::graph ::graph)
        :ret ::graph))

(s/fdef acyclic?
  :args (s/cat ::graph ::graph)
  :ret boolean?)

;; function definitions

(defn proc-asm
  ([fname-asm]
   (let [
         file-vec (*file-vector-implementation* fname-asm)
         graph-agent (-> (repeat *num-opcodes* #{})
                         (vec)
                         (with-meta {
                                     ::graph-complete false})
                         (agent))
         num-active (atom 1)
         jump-history (atom {})]
     (let [
           segm-proc (partial proc-segm file-vec)
           trampoline-channel (async/chan
                               *trampoline-channel-size*)
           result-channel (async/chan 1)]
       (do
         (async/put! trampoline-channel
                     (segm-proc {} {
                                    ::flow-src []
                                    ::cond-depth 0
                                    ::pos 0}
                                {
                                 ::graph-agent
                                 graph-agent
                                 ::num-active
                                 num-active
                                 ::jump-history
                                 jump-history}))
         (async/go-loop []
           (when-let [
                      fns (async/<! trampoline-channel)]
             (do
               (run! #(async/go
                        (async/>!
                         trampoline-channel
                         (%)))
                     fns))
             (recur)))
         (async/go-loop [
                         curr-ref ::trampoline-channel
                         active? true]
           (async/<! (async/timeout 500 #_ms))
           (case curr-ref
             ::trampoline-channel
             (if (zero? @num-active)
               (do
                 (async/close! trampoline-channel)
                 (recur ::graph-agent false))
               (recur ::trampoline-channel true))
             ::graph-agent
             (if-not active?
               (do
                 (send graph-agent vary-meta
                       #(assoc % ::graph-complete true))
                 (recur ::graph-agent true))
               (if ((comp ::graph-complete meta) @graph-agent)
                 (do
                   (await graph-agent)
                   (async/>! result-channel @graph-agent))
                 (recur ::graph-agent true)))))
           result-channel)))))

(defn mk-linker
  [sources target]
  #(assoc % target
          (clojure-set/union
           sources
           (or
            (% target)
            #{}))))

