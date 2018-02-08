(ns leiningen.licenses
  (:require [leiningen.core.classpath :as classpath]
            [leiningen.core.main :as main]
            [cemerick.pomegranate.aether :as aether]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as string]
            [clojure.xml :as xml]
            [clojure.zip :as zip])
  (:import (java.util.jar JarFile)))

;; This code is a mess; sorry! But it gets the job done.

(defn- tag [tag]
  #(= (:tag %) tag))

(defn- get-entry [^JarFile jar ^String name]
  (.getEntry jar name))

(def ^:private tag-content (juxt :tag (comp first :content)))

(defn- pom->coordinates [pom-xml]
  (let [coords (->> pom-xml
                    :content
                    (filter #(#{:groupId :artifactId :version} (:tag %)))
                    (map tag-content)
                    (into {}))]
    {:group (:groupId coords)
     :artifact (:artifactId coords)
     :version (:version coords)}))

(defn- depvec->coordinates [[dep version]]
  {:group (or (namespace dep) (name dep))
   :artifact (name dep)
   :version version})

(defn- fetch-pom [{:keys [group artifact version repositories]}]
  (try
    (let [dep (symbol group artifact)
          [file] (->> (aether/resolve-dependencies
                       :coordinates [[dep version :extension "pom"]]
                       :repositories repositories)
                      (aether/dependency-files)
                      ;; possible we could get the wrong one here?
                      (filter #(.endsWith (str %) ".pom")))]
      (xml/parse file))
    (catch Exception e
      (binding [*out* *err*]
        (println "#   " (str group) (str artifact) (class e) (.getMessage e))))))

(defn- get-parent [pom]
  (if-let [parent-tag (->> pom
                           :content
                           (filter (tag :parent))
                           first)]
    (if-let [parent-coords (->> parent-tag
                                pom->coordinates)]
      (fetch-pom parent-coords))))

(defn- pom-license-elems [pom]
  (for [elem (:content pom)
        :when (= (:tag elem) :licenses)
        license (:content elem)]
    license))

(defn- pom->license-names [pom]
  (for [license-elem (pom-license-elems pom)
        elem (:content license-elem)
        :when (= (:tag elem) :name)]
    (first (:content elem))))

(defn- get-pom [dep file]
  (let [{:keys [group artifact]} (depvec->coordinates dep)
        pom-path (format "META-INF/maven/%s/%s/pom.xml" group artifact)
        pom (get-entry file pom-path)]
    (and pom (xml/parse (.getInputStream file pom)))))

(def ^:private license-file-names #{"LICENSE" "LICENSE.txt" "META-INF/LICENSE"
                                    "META-INF/LICENSE.txt" "license/LICENSE"
                                    "COPYING"})

(defn- try-raw-license [dep file opts]
  (try
    (if-let [entry (some (partial get-entry file) license-file-names)]
      (with-open [rdr (io/reader (.getInputStream file entry))]
        (->> rdr
             line-seq
             (remove string/blank?)
             first)))
    (catch Exception e
      (binding [*out* *err*]
        (println "#   " (str file) (class e) (.getMessage e))))))

(defn try-pom [dep file opts]
  (let [packaged-poms (->> (get-pom dep file) (iterate get-parent) (take-while identity))
        source-poms (->> (fetch-pom (merge opts (depvec->coordinates dep))) (iterate get-parent) (take-while identity))]
    (->> (concat packaged-poms source-poms)
         (map pom->license-names)
         (apply concat)
         (some identity))))

(defn try-fallback [dep file {:keys [fallbacks]}]
  (get fallbacks (str (first dep)) "unknown"))

(defn normalize-license [license {:keys [synonyms]}]
  (let [normalized-license (-> license
                               string/lower-case
                               (string/replace #" +" " "))]
    (or
      (some (fn [[check-fn license-name]] (when (check-fn normalized-license) license-name)) synonyms)
      license)))

(defn- get-licenses [[dep file] opts]
  (let [fns [try-pom
             try-raw-license
             try-fallback]]
    (-> (some #(% dep file opts) fns)
        string/trim
        (normalize-license opts))))

(defn copyright-line?
  "True if the string looks like a copyright line."
  [s]
  (and (re-find #"(?i)copyright\s+(\(c\)|©)?\s*\d+" s)
       (not (re-find #"Copyright \(C\) 1989, 1991 Free Software Foundation, Inc. 59 Temple Place," s))))

(defn- try-raw-copyright
  "Scan license files for copyright."
  [dep file opts]
  (try
    (if-let [entry (some (partial get-entry file) license-file-names)]
      (with-open [rdr (io/reader (.getInputStream file entry))]
        (->> rdr
             line-seq
             (remove string/blank?)
             (filter copyright-line?)
             (string/join " | "))))
    (catch Exception e
      (binding [*out* *err*]
        (println "#   " (str file) (class e) (.getMessage e))))))

(defn try-fallback-copyright [dep file {:keys [copyright-fallbacks]}]
  (get copyright-fallbacks (str (first dep)) "unknown"))

(defn- get-copyrights [[dep file] opts]
  "Get the copyrights from each dependency if possible."
  (let [fns [try-raw-copyright
             try-fallback-copyright]]
    (-> (some #(% dep file opts) fns)
        string/trim)))

(defn safe-slurp [filename]
  (try
    (read-string (slurp filename))
    (catch java.io.FileNotFoundException e
            {})))

(def formatters
  {":text"
   (fn [lines]
     (->> lines
          (map (partial string/join " - "))
          (string/join "\n")))

   ":csv"
   (fn [lines]
     (->> lines
          (map (fn [parts] (string/join "," (map (partial format "\"%s\"") parts))))
          (string/join "\n")))

   ":edn"
   (fn [lines]
     (with-out-str (pp/pprint (map
                                (fn [[artifact version license copyright]]
                                  [[artifact version] [license copyright]])
                                lines))))})

(defn prepare-synonyms [synonyms]
  (reduce (fn [running [syns license]]
            (assoc running (set (map (comp string/trim string/lower-case) syns)) license))
          {} synonyms))

(defn licenses
  "List the license of each of your dependencies.

USAGE: lein licenses [:format]

Supported output formats: :text (default), :csv, :edn"

  ([project]
     (licenses project ":text"))

  ([project output-style]
     (if-let [format-fn (formatters output-style)]
       (let [deps (#'classpath/get-dependencies :dependencies :managed-dependencies project)
             deps (zipmap (keys deps) (map #(JarFile. %) (aether/dependency-files deps)))
             opts {:repositories (:repositories project)
                   :fallbacks (safe-slurp "fallbacks.edn")
                   :synonyms (prepare-synonyms (safe-slurp "synonyms.edn"))}]
            (->> deps
                 (map #(conj % (get-licenses % opts)))
                 (map #(conj % (get-copyrights % opts)))
                 (map (fn [[[artifact version] file license copyright]]
                        [artifact version license copyright]))
                 format-fn
                 println))
       (main/abort "unknown formatter"))))
