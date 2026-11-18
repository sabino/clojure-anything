(ns textkit.core
  (:require [clojure.string :as str]))

(defn -main [& args]
  (cond
    (and (= "echo" (first args)) (= 2 (count args)))
    (println (str (.. js/process -env -TEXTKIT_PREFIX) (second args)))

    (= args ["lines"])
    (let [text (.readFileSync (js/require "node:fs") 0 "utf8")
          partial-line? (and (seq text) (not (str/ends-with? text "\n")))]
      (println (+ (count (re-seq #"\n" text)) (if partial-line? 1 0))))

    :else
    (do
      (.write (.-stderr js/process) "usage: textkit echo TEXT | lines\n")
      (set! (.-exitCode js/process) 2))))

(set! *main-cli-fn* -main)
