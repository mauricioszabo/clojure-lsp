(ns clojure-lsp.parser
  (:require
    [clojure-lsp.clojure-core :as cc]
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.string :as string]
    [clojure.tools.logging :as log]
    [clojure.walk :as walk]
    [rewrite-clj.node :as n]
    [rewrite-clj.zip :as z]
    [rewrite-clj.custom-zipper.core :as cz]
    [rewrite-clj.zip.edit :as ze]
    [rewrite-clj.zip.find :as zf]
    [rewrite-clj.zip.move :as zm]
    [rewrite-clj.zip.walk :as zw]
    [rewrite-clj.zip.subedit :as zsub]))

(declare find-references*)

(def default-env
  {:ns 'user
   :requires #{'clojure.core}
   :refers (->> cc/core-syms
                (mapv (juxt identity (constantly 'clojure.core)))
                (into {}))
   :aliases {}
   :publics #{}
   :usages []})

(defn zspy [loc]
  (log/spy (z/sexpr loc))
  loc)

(defn qualify-ident [ident {:keys [aliases publics refers requires ns] :as context} scoped]
  (when (ident? ident)
    (let [sym-ns (some-> (namespace ident) symbol)
          sym-name (name ident)
          alias->ns (set/map-invert aliases)
          ctr (if (symbol? ident) symbol keyword)]
      (if (simple-ident? ident)
        (cond
          (keyword? ident) ident
          (contains? scoped ident) (ctr (name (get-in scoped [ident :ns])) sym-name)
          (contains? publics ident) (ctr (name ns) sym-name)
          (contains? refers ident) (ctr (name (get refers ident)) sym-name)
          :else ident)
        (cond
          (contains? alias->ns sym-ns)
          (ctr (name (alias->ns sym-ns)) sym-name)

          (contains? requires sym-ns)
          ident

          :else ident)))))

(defn add-reference [context scoped node extra]
  (vswap! context
          (fn [context]
            (update context :usages
                    (fn [usages]
                      (let [{:keys [row end-row col end-col] :as m} (meta node)
                            sexpr (n/sexpr node)
                            scope-bounds (get-in scoped [sexpr :bounds])]
                        (conj usages (cond-> {:sym (qualify-ident sexpr context scoped)
                                              :sexpr sexpr
                                              :row row
                                              :end-row end-row
                                              :col col
                                              :end-col end-col}
                                       (seq extra) (merge extra)
                                       (seq scope-bounds) (assoc :scope-bounds scope-bounds)))))))))

(defn handle-rest
  "Crawl each form from `loc` to the end of the parent-form
  `(fn [x 1] | (a) (b) (c))`
  With cursor at `|` find references for `(a)`, `(b)`, and `(c)`"
  [loc context scoped]
  (loop [sub-loc loc]
    (when sub-loc
      (find-references* (zsub/subzip sub-loc) context scoped)
      (recur (zm/right sub-loc)))))

(defmulti handle-sexpr*
  "Crawl a sexpr with `op-loc` pointing at a starting symbol and `loc` pointing at the containing list.
  `op-loc` reference will have been added already, responsible for adding all sub references."
  (fn [op-loc loc context scoped]
    ;; TODO need to handle vars? #'defn
    (qualify-ident (z/sexpr op-loc) @context scoped)))

(defmethod handle-sexpr* :default
  [op-loc loc context scoped]
  (handle-rest (zm/right op-loc) context scoped))

(defmethod handle-sexpr* 'clojure.core/comment
  [op-loc loc context scoped]
  ;; Ignore contents of comment
  nil)

(defmethod handle-sexpr* 'clojure.core/ns
  [op-loc loc context scoped]
  ;; TODO add refers to usages and add full libs to usages
  (let [conformed (s/conform (:args (s/get-spec 'clojure.core/ns)) (rest (z/sexpr loc)))
        libs (:body (second (first (filter (comp #{:require} first) (:clauses conformed)))))
        prefix-symbol (fn [prefix sym]
                        (if prefix
                          (symbol (str (name prefix) "." (name sym)))
                          sym))
        expand-lib-spec (fn [prefix [tag arg]]
                          (if (= :lib tag)
                            {:lib (prefix-symbol prefix arg)}
                            (update arg :lib #(prefix-symbol prefix %))))
        expand-prefix-list (fn [{:keys [prefix libspecs]}]
                             (mapv (partial expand-lib-spec prefix) libspecs))

        libspecs (reduce (fn [libspecs libspec-or-prefix-list]
                           (let [[tag arg] libspec-or-prefix-list]
                             (into libspecs
                                   (cond
                                     (= :libspec tag) [(expand-lib-spec nil arg)]
                                     (= :prefix-list tag) (expand-prefix-list arg)))))
                         []
                         libs)
        require-loc (-> loc
                        (z/down)
                        (z/find z/right (fn [node]
                                          (let [sexpr (z/sexpr node)]
                                            (and (seq? sexpr) (= :require (first sexpr)))))))
        require-node (-> (or require-loc loc)
                         (z/down)
                         (z/rightmost)
                         (z/node)
                         (meta))]
    (doseq [{:keys [lib options]} libspecs]
      (vswap! context (fn [env]
                        (let [{:keys [as refer]} options]
                          (cond-> env
                            :always (update :requires conj lib)
                            as (update :aliases conj [lib as])
                            (and refer (= :syms (first refer))) (update :refers into (map vector (second refer) (repeat lib))))))))
    (vswap! context assoc :ns (:name conformed) :require-pos {:add-require? (not require-loc)
                                                              :row (:end-row require-node)
                                                              :col (:end-col require-node)})))

(defmethod handle-sexpr* 'clojure.core/let
  [op-loc loc context scoped]
  (let [bindings-loc (zf/find-tag op-loc :vector)
        scoped-ns (gensym)
        {:keys [end-row end-col]} (meta (z/node loc))
        end-scope-bounds {:end-row end-row :end-col end-col}
        scoped (loop [binding-loc (zm/down bindings-loc)
                      scoped scoped]
                 ;; MUTATION Updates scoped AND adds declared param references AND adds references in binding vals
                 (if binding-loc
                   (let [node (z/node binding-loc)
                         sexpr (n/sexpr node)
                         right-side-loc (z/right binding-loc)]
                     (cond
                       ;; TODO handle map and seq destructing
                       (symbol? sexpr)
                       (let [{:keys [end-row end-col]} (meta (z/node (or (z/right right-side-loc)
                                                                         (z/up right-side-loc))))
                             scope-bounds (assoc end-scope-bounds :row end-row :col end-col)
                             new-scoped (assoc scoped sexpr {:ns scoped-ns :bounds scope-bounds})]
                         (add-reference context new-scoped node {:tags #{:declare :param}
                                                                 :scope-bounds scope-bounds
                                                                 :sym (symbol (name scoped-ns)
                                                                              (name sexpr))
                                                                 :sexpr sexpr})
                         (handle-rest (zsub/subzip right-side-loc) context scoped)
                         (recur (z/right right-side-loc) new-scoped))
                       :else
                       (recur (z/right right-side-loc) scoped)))
                   scoped))]
    (handle-rest (z/right bindings-loc) context scoped)))

(defmethod handle-sexpr* 'clojure.core/def
  [op-loc loc context scoped]
  (let [def-sym (z/node (z/right op-loc))]
    (vswap! context update :publics conj (n/sexpr def-sym))
    (add-reference context scoped def-sym {:tags #{:declare :public}})
    (handle-rest (z/right (z/right op-loc))
                 context scoped)))

(defmethod handle-sexpr* 'clojure.core/defn
  [op-loc loc context scoped]
  (let [defn-loc (z/right op-loc)
        defn-sym (z/node defn-loc)
        params-loc (z/find-tag defn-loc :vector)
        body-loc (z/right params-loc)
        {:keys [row col]} (meta (z/node body-loc))
        {:keys [end-row end-col]} (meta (z/node loc))
        scoped-ns (gensym)
        scope-bounds {:row row :col col :end-row end-row :end-col end-col}]
    (vswap! context update :publics conj (n/sexpr defn-sym))
    (add-reference context scoped defn-sym {:tags #{:declare :public}})
    (let [scoped
          (loop [param-loc (z/down params-loc)
                 scoped scoped]
            ;; MUTATION Updates scoped AND adds declared param references
            (if param-loc
              (let [node (z/node param-loc)
                    sexpr (n/sexpr node)]
                (cond
                  ;; TODO handle map and seq destructing
                  (symbol? sexpr)
                  (let [new-scoped (assoc scoped sexpr {:ns scoped-ns :bounds scope-bounds})]
                    (add-reference context new-scoped node {:tags #{:declare :param}
                                                            :scope-bounds scope-bounds
                                                            :sym (symbol (name scoped-ns)
                                                                         (name sexpr))
                                                            :sexpr sexpr})
                    (recur (z/right param-loc) new-scoped))
                  :else
                  (recur (z/right param-loc) scoped)))
              scoped))]
      (handle-rest body-loc context scoped))))

(defn handle-sexpr [loc context scoped]
  (let [op-loc (some-> loc (zm/down))]
    (when (and op-loc (symbol? (z/sexpr op-loc)))
      (add-reference context scoped (z/node op-loc) {})
      (handle-sexpr* op-loc loc context scoped))))

(defn find-references* [loc context scoped]
  (loop [loc loc
         scoped scoped]
    (if (or (not loc) (zm/end? loc))
      @context

      (let [tag (z/tag loc)]
        (cond
          (#{:quote :uneval} tag)
          (recur (zm/next (zm/rightmost (zm/down loc))) scoped)

          (= :list tag)
          (do
            (handle-sexpr loc context scoped)
            (recur (zm/right loc) scoped))

          (and (= :token tag) (symbol? (z/sexpr loc)))
          (do
            (add-reference context scoped (z/node loc) {})
            (recur (zm/next loc) scoped))

          :else
          (recur (zm/next loc) scoped))))))

(defn find-references [code]
  (-> code
      (z/of-string)
      (zm/up)
      (find-references* (volatile! default-env) {})))

(comment
  (let [code (string/join "\n"
                          ["(ns thinger.foo"
                           "  (:refer-clojure :exclude [update])"
                           "  (:require"
                           "    [thinger [my.bun :as bun]"
                           "             [bung.bong :as bb :refer [bing byng]]]))"
                           "(comment foo)"
                           "(let [x 1] (inc x))"
                           "(def x 1)"
                           "(defn y [y] (y x))"
                           "(inc x)"
                           "(bun/foo)"
                           "(bing)"])]
    (find-references code)
    #_(find-position code 16 2))

  (dissoc (find-references

            " {:requires #{'clojure.core}
                      :refers cc/core-syms
                      }")
          :refers))

