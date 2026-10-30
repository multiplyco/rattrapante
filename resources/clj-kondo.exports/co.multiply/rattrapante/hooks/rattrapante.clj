(ns hooks.rattrapante
  (:require [clj-kondo.hooks-api :as api]))


(defn with-change
  [{:keys [node]}]
  (let [[_ binding & body] (:children node)
        [tx doc options] (:children binding)]
    {:node (api/list-node
             (list* (api/token-node 'let)
               (api/vector-node
                 [tx (api/list-node
                       (list (api/token-node 'do) (or options (api/token-node nil)) doc))])
               body))}))
