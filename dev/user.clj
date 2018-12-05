(ns user
  (:require [clojure.repl :refer :all]
            [clojure.tools.namespace.repl :as repl]))

(repl/set-refresh-dirs "src" "dev")

#_(defn start [] (mount/start))
#_(defn stop [] (mount/stop))
(defn reset [] (repl/refresh))
(defn reset-all [] (repl/refresh-all))
