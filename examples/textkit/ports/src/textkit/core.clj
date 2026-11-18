(ns textkit.core
  (:require [clojure.string :as str]))

(defn -main [& args]
  (cond
    (and (= "echo" (first args)) (= 2 (count args)))
    (println (str (System/getenv "TEXTKIT_PREFIX") (second args)))

    (= args ["lines"])
    (let [text (slurp *in*)
          partial-line? (and (seq text) (not (str/ends-with? text "\n")))]
      (println (+ (count (re-seq #"\n" text)) (if partial-line? 1 0))))

    :else
    (do
      (binding [*out* *err*]
        (println "usage: textkit echo TEXT | lines"))
      (System/exit 2))))
