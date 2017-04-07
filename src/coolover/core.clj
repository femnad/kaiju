(ns coolover.core
  (:gen-class)
  (:require [clj-http.lite.client :as client])
  (:require [clojure.data.json :as json])
  (:require [maailma.core :as m])
  (:require [clojure.tools.cli :refer [parse-opts]])
  (:require [clansi :refer [style]])
  (:require [clj-time.format :as f])
  (:require [clojure.string :as s]))

(def api-suffix "rest/api/2")

(def config-resource "config.edn")

(def default-content-type :json)

(def exec-name "coolover")

(defn get-config []
  (m/build-config (m/resource config-resource)))

(defn get-issue-field [issue field]
  (get-in issue ["fields" field]))

(defn get-browse-url [issue-key]
  (format "%s/browse/%s" (get-in (get-config) [:service :url]) issue-key))

(defn- format-date [a-date]
  (f/unparse (f/formatters :mysql) (f/parse (f/formatters :date-time) a-date)))

(defn get-issue-map [issue]
  (let [get-field #(get-issue-field issue %)
        issue-key (get issue "key")]
    {:title issue-key
     :summary (get-field "summary")
     :description (get-field "description")
     :created (format-date (get-field "created"))
     :updated (format-date (get-field "updated"))
     :browse-url (get-browse-url issue-key)}))

(defn- style-items [container item-style-pairs]
  (map #(style ((first %) container) (second %)) item-style-pairs))

(defn- style-issue [issue-map]
  (style-items issue-map
               {:title :green
                :summary :yellow
                :browse-url :magenta
                :created :cyan
                :updated :cyan
                :description :normal}))

(defn format-issue [issue]
  (let [issue-map (get-issue-map issue)
        styled-issue (style-issue issue-map)]
    (apply format "%s: %s [%s]\n<%s - %s>\n%s\n" styled-issue)))

(defn get-search-body [query max-results]
  (json/write-str {:jql query :maxResults max-results}))

(defn get-url [endpoint]
  (let [service-url (get-in (get-config) [:service :url])]
    (format "%s/%s/%s" service-url api-suffix endpoint)))

(defn get-basic-auth-header []
  {:basic-auth (map #(get-in (get-config) [:credentials %]) [:user :password])})

(defn- has-auth []
  (contains? (get-config) :credentials))

(defn- build-args-with-headers [url headers]
  (if (has-auth)
    (list url (merge headers (get-basic-auth-header)))
    (list url headers)))

(defn- build-args [url]
  (if (has-auth)
    (list url (get-basic-auth-header))
    (list url)))

(defn request
  ([url body]
   (apply client/post
         (build-args-with-headers url
                      {:body body
                       :content-type default-content-type})))
   ([url]
    (apply client/get (build-args url))))

(defn- get-resource [resource resource-id]
  (let [resource-endpoint
        (-> resource
            name
            get-url)
        resource-url (format "%s/%s" resource-endpoint resource-id)]
    (-> resource-url
        request
        :body
        json/read-str)))

(defn- get-issue [issue-id]
  (get-resource "issue" issue-id))

(defn show-issue [issue-key]
  (println (format-issue (get-resource "issue" issue-key))))

(defn- get-issue-attachments [issue]
  (->> (get-in issue ["fields" "attachment"])
       (map #(get % "content"))))

(defn- download-attachment [attachment-link]
  (:body (request attachment-link)))

(defn- download-issue-attachment [issue-id]
  (let [issue-attachments
        (-> issue-id
            get-issue
            get-issue-attachments)]
    (map download-attachment issue-attachments)))

(defn basename [url]
  (apply str (drop (inc (s/last-index-of url "/")) url)))

(defn- save-attachment [link path-prefix]
  (spit (format "%s/%s" path-prefix (basename link))
        (download-attachment link)))

(defn download-attachments-of-issues [issues path-prefix]
  (let [attachment-links (->> issues
                              (map #(get % "key"))
                              (map #(get-issue %))
                              (map #(get-issue-attachments %))
                              (filter #(not (empty? %)))
                              flatten)]
    (doseq [link attachment-links]
      (save-attachment link path-prefix))))

(defn search-issues [config query max-results]
  (let [search-url (get-url "search")
        basic-auth (map #(get-in config [:credentials %]) [:user :password])
        search-body (get-search-body query max-results)]
    (->
     search-url
     (request search-body)
     :body
     json/read-str
     (get "issues"))))

(defn get-query [query-map order-by]
  (str (s/join
      " and " (map
               #(format "%s = %s"
                        (first %)
                        (second %))
               (into-array query-map)))
     " order by " order-by))

(defn format-projects []
  (let [search-url (get-url "project")]
    (let [projects
      (-> search-url
          request
          :body
          json/read-str)]
      (map #(format "%s - %s" (get % "name") (get % "key")) projects))))

(defn list-projects []
  (doseq [project (format-projects)]
    (println project)))

(defn- get-issues [project order-by max-results]
  (let [config (get-config)
        query (get-query {"project" project} order-by)]
        (search-issues config query max-results)))

(defn list-issues [project order-by max-results]
  (let [issues (get-issues project order-by max-results)]
    (doseq [issue issues]
      (println (format-issue issue)))))

(def cli-options
  [["-p" "--project <project>" "project key"]
   ["-o" "--order-by <field>" "order by field"
    :default "created"]
   ["-m" "--max-results <number>" "maximum number of results"
    :default 10]
   ["-i" "--issue-key <issue-key>" "issue key"]])

(def modes
  {:list-projects {:fn list-projects :args []}
   :list-issues {:fn list-issues :args [:project :order-by :max-results]}
   :show-issue {:fn show-issue :args [:issue-key]}})

(defn get-mode [mode-name]
  ((keyword mode-name) modes))

(defn- get-mode-from-parsed-args [parsed-args]
  (->
   parsed-args
   :arguments
   first
   get-mode))

(defn- parse-args [args]
  (parse-opts args cli-options))

(defn get-usage [parsed-args]
  (let [error-message (s/join "" (:errors parsed-args))]
    (format "usage: %s <modes> <options>\n\nModes:\n%s\n\nOptions:\n%s\n%s"
            exec-name
            (s/join " | " (map name (keys modes)))
            (:summary parsed-args)
            error-message)))

(defn- get-selected-mode [parsed-args]
  (let [mode (get-mode-from-parsed-args parsed-args)
        mode-fn (:fn mode)
        formal-args (:args mode)
        actual-args (map #(% (:options parsed-args)) formal-args)]
    (list mode-fn actual-args)))

(defn- invalid-mode? [mode]
  (some nil? mode))

(defn run-mode [mode]
  (apply apply mode))

(defn -main [& args]
  (let [parsed-args (parse-args args)]
    (if (or (= (count args) 0)
            (:errors parsed-args))
      (println (get-usage parsed-args))
      (let [mode (get-selected-mode parsed-args)]
        (if (invalid-mode? mode)
          (println (get-usage parsed-args))
          (run-mode mode))))))
