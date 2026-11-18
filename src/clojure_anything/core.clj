(ns clojure-anything.core
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [java.io PushbackReader StringReader]
           [java.nio ByteBuffer]
           [java.nio.charset CharacterCodingException StandardCharsets]
           [java.nio.file CopyOption Files]
           [java.util Base64]))

(def source-limit (* 512 1024))
(def recording-limit (* 2 1024 1024))
(def ^:private file-limit 128)

(defn fail [message]
  (throw (ex-info message {})))

(defn- read-bytes [path limit]
  (let [bytes (if (= path "-")
                (.readNBytes System/in (inc limit))
                (do
                  (when-not (fs/regular-file? path)
                    (fail (str "Expected a regular file: " path)))
                  (with-open [in (io/input-stream (fs/file path))]
                    (.readNBytes in (inc limit)))))]
    (when (> (alength bytes) limit)
      (fail (str path " exceeds " limit " bytes. Choose a smaller input.")))
    bytes))

(defn utf8 [bytes]
  (str (.decode (.newDecoder StandardCharsets/UTF_8) (ByteBuffer/wrap bytes))))

(defn read-text [path limit]
  (try
    (utf8 (read-bytes path limit))
    (catch CharacterCodingException _
      (fail (str path " is not UTF-8 text.")))))

(defn read-edn [text]
  (with-open [reader (PushbackReader. (StringReader. text))]
    (let [eof (Object.)
          value (edn/read {:eof eof} reader)]
      (when (or (identical? eof value)
                (not (identical? eof (edn/read {:eof eof} reader))))
        (fail "Expected a single EDN value."))
      value)))

(defn read-json [text & {:keys [bigdec]}]
  (json/read-str text :bigdec bigdec
                :extra-data-fn (fn [value reader]
                                 (when-not (re-matches #"[ \t\r\n]*" (slurp reader))
                                   (fail "Expected a single JSON value."))
                                 value)))

(defn data-source [text format target namespace]
  (let [value (case format
                "json" (read-json text :bigdec (= "clj" target))
                "edn" (read-edn text)
                "text" text)]
    (when (= "cljs" target)
      (doseq [item (tree-seq coll? seq value)]
        (when (or (decimal? item) (ratio? item)
                  (and (integer? item) (not (<= -9007199254740991 item 9007199254740991)))
                  (and (float? item) (not (Double/isFinite (double item)))))
          (fail (str "This number cannot be emitted as a ClojureScript literal: " item
                     ". Use --to clj to preserve it.")))))
    (str "(ns " namespace ")\n\n(def data\n  '"
         (str/replace (str/trim-newline (with-out-str (pprint/pprint value))) "\n" "\n   ")
         ")\n")))

(def ^:private ignored-names
  #{"node_modules" "vendor" "target" "build" "dist" "__pycache__"})

(defn- visible-source? [path]
  (let [name (fs/file-name path)]
    (and (not (str/starts-with? name "."))
         (not (ignored-names name))
         (not (re-find #"(?i)\.(?:png|jpe?g|gif|webp|ico|pdf|zip|tar|gz|jar|wasm|pem|key|p12|pfx)$" name)))))

(defn- source-directory [root]
  (let [paths (volatile! [])]
    (fs/walk-file-tree
     root
     {:pre-visit-dir (fn [path _]
                       (if (or (= root path) (visible-source? path)) :continue :skip-subtree))
      :visit-file (fn [path attrs]
                    (when (and (.isRegularFile attrs) (visible-source? path))
                      (when (= file-limit (count @paths))
                        (fail (str "Source directory exceeds " file-limit " files. Choose a smaller unit.")))
                      (vswap! paths conj path))
                    :continue)
      :visit-file-failed (fn [_ error] (throw error))})
    (let [files (reduce
                 (fn [{:keys [size] :as result} path]
                   (let [name (str/replace (str (fs/relativize root path)) "\\" "/")
                         bytes (read-bytes path source-limit)
                         content (try (utf8 bytes) (catch CharacterCodingException _ nil))]
                     (if (or (nil? content) (str/includes? content "\u0000"))
                       (update result :skipped-binary (fnil conj []) name)
                       (let [size (+ size (alength bytes))]
                         (when (> size source-limit)
                           (fail (str "Source directory exceeds " source-limit " bytes. Choose a smaller unit.")))
                         (-> result
                             (assoc :size size)
                             (update :files conj {:path name :content content}))))))
                 {:size 0 :files []} (sort-by str @paths))]
      (when (empty? (:files files))
        (fail "The source directory contains no readable text files."))
      (dissoc files :size))))

(defn read-source [input]
  (if (and (not= "-" input) (fs/directory? input))
    (source-directory (fs/path input))
    (let [content (read-text input source-limit)]
      (when (str/includes? content "\u0000")
        (fail (str input " contains binary data.")))
      {:files [{:path (if (= "-" input) "stdin" (fs/file-name input))
                :content content}]})))

(defn- safe-path? [path]
  (and (string? path)
       (re-matches #"[a-zA-Z0-9_.-]+(?:/[a-zA-Z0-9_.-]+)*" path)
       (not-any? #(or (= ".git" %) (str/ends-with? % "."))
                 (str/split (str/lower-case path) #"/"))))

(defn generated-files [response target]
  (let [files (get (read-json response) "files")]
    (when-not (and (vector? files) (<= 1 (count files) file-limit)
                   (every? #(and (map? %) (safe-path? (get % "path"))
                                 (string? (get % "content"))) files))
      (fail "The agent must return a files array with safe relative paths and text contents."))
    (let [names (mapv #(str/lower-case (get % "path")) files)]
      (when (or (not (apply distinct? names))
                (some (fn [name] (some #(str/starts-with? % (str name "/")) names)) names))
        (fail "The agent returned conflicting file paths.")))
    (when-not (some (fn [file]
                     (and (some #(str/ends-with? (get file "path") %) [(str "." target) ".cljc"])
                          (not (str/blank? (get file "content")))))
                   files)
      (fail (str "The agent returned no ." target " or .cljc source.")))
    (into {} (map (juxt #(get % "path") #(get % "content"))) files)))

(defn require-new! [path]
  (when (fs/exists? path {:nofollow-links true})
    (fail (str "Output already exists: " path))))

(defn- publish! [path write-stage!]
  (let [destination (fs/absolutize path)
        parent (fs/parent destination)]
    (require-new! destination)
    (fs/create-dirs parent)
    (fs/with-temp-dir [stage {:dir parent :prefix ".clojure-anything-"}]
      (let [staged (fs/path stage "output")]
        (write-stage! staged)
        (Files/move staged destination (make-array CopyOption 0))))))

(defn write-project! [path files]
  (publish! path
            (fn [staged]
              (doseq [[name content] files]
                (let [file (fs/path staged name)]
                  (fs/create-dirs (fs/parent file))
                  (spit (fs/file file) content :encoding "UTF-8"))))))

(defn- kill-command! [{:keys [proc]}]
  (let [root (.toHandle proc)]
    (with-open [descendants (.descendants root)]
      (let [children (vec (iterator-seq (.iterator descendants)))]
        (.destroyForcibly root)
        (doseq [child children]
          (.destroyForcibly child))))))

(defn run-command! [command timeout {:keys [stdin env dir]}]
  (fs/with-temp-dir [capture {:prefix "clojure-anything-capture-"}]
    (let [out (fs/file capture "stdout")
          err (fs/file capture "stderr")
          child (process/process command {:in (or stdin "") :in-enc "UTF-8"
                                          :out out :err err :dir dir :extra-env env
                                          :shutdown process/destroy-tree})
          result (deref child (* 1000 timeout) ::timeout)]
      (when (= ::timeout result)
        (kill-command! child)
        (fail (str (first command) " timed out after " timeout "s.")))
      {:exit (:exit result)
       :stdout (read-bytes out recording-limit)
       :stderr (read-bytes err recording-limit)})))

(defn- valid-case? [{:keys [name args stdin env] :as case}]
  (and (map? case) (string? name) (not (str/blank? name))
       (or (nil? args) (and (vector? args) (every? string? args)))
       (or (nil? stdin) (string? stdin))
       (or (nil? env) (and (map? env) (every? string? (keys env)) (every? string? (vals env))))))

(defn- validate-cases! [cases allowed]
  (when-not (and (vector? cases) (seq cases)
                 (every? #(and (map? %) (valid-case? %) (every? allowed (keys %))) cases)
                 (apply distinct? (map :name cases)))
    (fail "Expected a nonempty vector of cases with unique names and valid :args, :stdin and :env."))
  cases)

(def ^:private case-keys #{:name :args :stdin :env})

(defn- observation [command timeout {:keys [args] :as case}]
  (let [{:keys [exit stdout stderr]} (run-command! (into command args) timeout case)
        encode (fn [bytes]
                 (try (utf8 bytes)
                      (catch CharacterCodingException _
                        {:base64 (.encodeToString (Base64/getEncoder) bytes)})))]
    {:exit exit :stdout (encode stdout) :stderr (encode stderr)}))

(defn record! [case-path output command timeout]
  (require-new! output)
  (let [cases (validate-cases! (read-edn (read-text case-path recording-limit)) case-keys)
        recorded (mapv #(assoc % :expected (observation command timeout %)) cases)
        serialized (with-out-str (pprint/pprint {:version 1 :cases recorded}))]
    (when (> (alength (.getBytes serialized "UTF-8")) recording-limit)
      (fail "The recording exceeds 2 MiB. Use smaller cases or split them into multiple recordings."))
    (publish! output #(spit (fs/file %) serialized :encoding "UTF-8"))
    (count recorded)))

(defn read-recording [path]
  (let [{:keys [version cases] :as recording} (read-edn (read-text path recording-limit))]
    (when-not (= 1 version)
      (fail "Expected a version 1 recording produced by record."))
    (validate-cases! cases (conj case-keys :expected))
    (doseq [{:keys [name expected]} cases]
      (when-not (and (= #{:exit :stdout :stderr} (set (keys expected)))
                     (integer? (:exit expected))
                     (every? #(or (string? %) (and (map? %) (string? (:base64 %))))
                             [(:stdout expected) (:stderr expected)]))
        (fail (str "Invalid observation for " (pr-str name) "."))))
    recording))

(defn replay [cases command timeout]
  (into []
        (keep (fn [{:keys [name expected] :as case}]
                (let [actual (observation command timeout case)]
                  (when-not (= expected actual)
                    {:name name :expected expected :actual actual}))))
        cases))
