(ns clojure-anything.main-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [clojure-anything.core :as core])
  (:import [java.lang ProcessHandle]))

(def ^:private bb (-> (ProcessHandle/current) .info .command (.orElse "bb")))

(defn- cli [stdin & args]
  @(process/process (into [bb "-m" "clojure-anything.main"] (map str args))
                    {:in stdin :out :string :err :string}))

(deftest local-conversion
  (fs/with-temp-dir [dir {}]
    (let [out (fs/path dir "port")
          source (fs/path out "src" "test_data" "core.clj")
          input "{\"word\":\"café 🍀\",\"amount\":0.125,\"items\":[null,true]}\n"]
      (is (zero? (:exit (cli input "convert" "--from" "json" "--namespace" "test-data.core" "--out" out "-"))))
      (is (= "{:paths [\"src\"]}\n" (slurp (fs/file out "deps.edn"))))
      (let [result @(process/process [bb "-cp" (str (fs/path out "src")) "-e"
                                      "(require 'test-data.core) (prn test-data.core/data)"]
                                     {:out :string :err :string})]
        (is (zero? (:exit result)) (:err result))
        (is (= {"word" "café 🍀" "amount" 0.125M "items" [nil true]} (core/read-edn (:out result)))))
      (let [original (slurp (fs/file source))]
        (is (= 2 (:exit (cli "{}" "convert" "--from" "json" "--out" out "-"))))
        (is (= original (slurp (fs/file source)))))))
  (testing "stdin, falsey data and the ClojureScript number range"
    (is (str/includes? (:out (cli "nil" "convert" "--from" "edn" "--to" "cljs" "-")) "'nil"))
    (is (str/includes? (:out (cli "café\n" "convert" "--from" "text" "-")) "\"café\\n\""))
    (is (= 2 (:exit (cli "[9007199254740993]" "convert" "--from" "json" "--to" "cljs" "-")))))
  (testing "input is parsed as a single inert value"
    (doseq [[format input] [["json" "{} true"] ["edn" "42 43"] ["edn" "#=(+ 1 2)"]]]
      (is (= 2 (:exit (cli input "convert" "--from" format "-")))))))

(deftest agent-conversion
  (fs/with-temp-dir [dir {}]
    (let [input (fs/create-dir (fs/path dir "source"))
          output (fs/path dir "port")
          code "(ns converted.core)\n(defn add [a b] (+ a b))\n"
          agent (fs/path dir "agent.clj")]
      (spit (fs/file input "math.py") "def add(a, b): return a + b\n")
      (spit (fs/file input ".env") "SHOW_IN_TEST=false\n")
      (spit (fs/file agent)
            (str "(require '[clojure.data.json :as json] '[clojure.string :as str])\n"
                 "(let [request (json/read-str (last (str/split-lines (slurp *in*))))]\n"
                 "  (assert (= \"ClojureScript\" (get request \"target\")))\n"
                 "  (assert (= [\"math.py\"] (mapv #(get % \"path\") (get request \"files\")))))\n"
                 "(println " (pr-str (json/write-str {:files [{:path "src/converted/core.cljs" :content code}
                                                              {:path "deps.edn" :content "{:paths [\"src\"]}\n"}]})) ")\n"))
      (let [result (cli "" "convert" "--to" "cljs" "--out" output input "--" bb agent)]
        (is (zero? (:exit result)) (:err result))
        (is (= code (slurp (fs/file output "src" "converted" "core.cljs")))))))
  (testing "the agent cannot publish an invalid or escaping project"
    (fs/with-temp-dir [dir {}]
      (let [out (fs/path dir "port")]
        (doseq [paths [["../escape.cljs"] [".git/config" "ok.cljs"] ["a" "a-" "A/ok.cljs"] ["wrong.clj"]]]
          (let [response (json/write-str {:files (mapv #(hash-map :path % :content "(+ 1 2)") paths)})
                result (cli "source" "convert" "--to" "cljs" "--out" out "-" "--" bb "-e"
                            (str "(println " (pr-str response) ")"))]
            (is (= 2 (:exit result)) (:out result))
            (is (not (fs/exists? out)))))))))

(deftest behavior-replay
  (fs/with-temp-dir [dir {}]
    (let [cases (fs/path dir "cases.edn")
          recorded (fs/path dir "recording.edn")
          app (fs/path dir "original.clj")
          argv ["show" "--x; $()"]]
      (spit (fs/file app)
            "(case (first *command-line-args*)
               \"show\" (prn [(second *command-line-args*) (System/getenv \"ANYTHING_CASE\") (slurp *in*)])
               \"binary\" (.write System/out (byte-array [(unchecked-byte 255)]))
               (do (binding [*out* *err*] (println \"unknown\")) (System/exit 3)))")
      (spit (fs/file cases) (pr-str [{:name "input" :args argv :stdin "世界\n" :env {"ANYTHING_CASE" "yes"}}
                                    {:name "binary" :args ["binary"]}
                                    {:name "error"}]))
      (let [result (cli "" "record" cases recorded "--" bb app)]
        (is (zero? (:exit result)) (:err result)))
      (let [[input binary error] (:cases (core/read-recording recorded))]
        (is (= {:exit 0 :stderr "" :stdout (str (pr-str [(second argv) "yes" "世界\n"]) "\n")} (:expected input)))
        (is (= {:exit 0 :stderr "" :stdout {:base64 "/w=="}} (:expected binary)))
        (is (= {:exit 3 :stderr "unknown\n" :stdout ""} (:expected error))))
      (is (= "3/3 cases matched.\n" (:out (cli "" "check" recorded "--" bb app))))
      (let [result (cli "" "check" recorded "--" bb "-e" "(println \"different\")" "--")]
        (is (= 1 (:exit result)))
        (is (str/includes? (:out result) "0/3 cases matched."))
        (is (str/includes? (:out result) ":stderr")))
      (let [out (fs/path dir "timed-out.edn")
            result (cli "" "record" "--timeout" "1" cases out "--" bb "-e" "(Thread/sleep 5000)" "--")]
        (is (= 2 (:exit result)))
        (is (str/includes? (:err result) "timed out after 1s"))
        (is (not (fs/exists? out)))))))

(deftest timeout-stops-subprocesses
  (when (fs/executable? "/bin/sh")
    (fs/with-temp-dir [dir {}]
      (let [cases (fs/path dir "cases.edn")
            out (fs/path dir "recording.edn")
            marker (fs/path dir "still-running")]
        (spit (fs/file cases) "[{:name \"timeout\"}]")
        (let [result (cli "" "record" "--timeout" "1" cases out "--" "/bin/sh" "-c"
                          "trap '' TERM; (sleep 2; touch \"$1\") & wait" "textkit" marker)]
          (is (= 2 (:exit result)))
          (is (str/includes? (:err result) "timed out after 1s")))
        (Thread/sleep 1500)
        (is (not (fs/exists? marker)))))))

(defn run []
  (let [{:keys [fail error]} (run-tests 'clojure-anything.main-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
