#!/usr/bin/env clojure

"USAGE: ./release.clj <new-version>"

(def new-v (first *command-line-args*))

(assert (re-matches #"\d+\.\d+\.\d+" (or new-v "")) "Use ./release.clj <new-version>")
(println "Releasing version" new-v)

(require '[clojure.string :as str])
(require '[clojure.java.shell :as sh])
(require '[clojure.java.io :as io])

(defn update-file [f fn]
  (print "Updating" (str f "...")) (flush)
  (spit f (fn (slurp f)))
  (println "OK"))

(defn current-version []
  (second (re-find #"def version \"([0-9\.]+)\"" (slurp "project.clj"))))

(def ^:dynamic *env* {})

(defn sh [& args]
  (apply println "Running" (if (empty? *env*) "" (str :env " " *env*)) args)
  (let [res (apply sh/sh (concat args [:env (merge (into {} (System/getenv)) *env*)]))]
    (if (== 0 (:exit res))
      (do
        (println (:out res))
        (:out res))
      (binding [*out* *err*]
        (println "Process" args "exited with code" (:exit res))
        (println (:out res))
        (println (:err res))
        (throw (ex-info (str "Process" args "exited with code" (:exit res)) res))))))

(defn update-version []
  (println "\n\n[ Updating version number ]\n")
  (let [old-v (current-version)]
    (update-file "CHANGELOG.md" #(str/replace % "# WIP" (str "# " new-v)))
    (update-file "project.clj"  #(str/replace % old-v new-v))
    (update-file "README.md"    #(str/replace % old-v new-v))))

(defn run-tests []
  (println "\n\n[ Running tests ]\n")
  (sh "lein" "test"))

(defn make-commit []
  (println "\n\n[ Making a commit ]\n")
  (sh "git" "add"
      "CHANGELOG.md"
      "project.clj"
      "README.md")

  (sh "git" "commit" "-m" (str "Version " new-v))
  (sh "git" "tag" new-v)
  (sh "git" "push" "origin" "master"))

(defn- str->json [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")))

(defn- map->json [m]
  (str "{ "
    (->>
      (map (fn [[k v]] (str "\"" (str->json k) "\": \"" (str->json v) "\"")) m)
      (str/join ",\n"))
    " }"))

(def GITHUB_AUTH (System/getenv "GITHUB_AUTH"))

(defn github-release []
  (let [changelog (->> (slurp "CHANGELOG.md")
                       str/split-lines
                       (drop-while #(not= (str "# " new-v) %))
                       next
                       (take-while #(not (re-matches #"# .+" %)))
                       (remove str/blank?)
                       (str/join "\n"))
        request  { "tag_name" new-v
                   "name"     new-v
                   "target_commitish" "master"
                   "body" changelog}]
    (sh "curl" "-u" GITHUB_AUTH
        "-X" "POST"
        "--data" (map->json request)
        "https://api.github.com/repos/juji-io/datalevin/releases")))

(defn -main []
  (sh "lein" "clean")
  (update-version)
  (run-tests)
  (make-commit)
  (github-release)
  (sh "lein" "deploy" "clojars")
  (System/exit 0))

(-main)
