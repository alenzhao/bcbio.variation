(ns bcbio.variation.config
  "Load and prepare inputs from YAML configuration files."
  (:use [clojure.java.io]
        [clj-time.local :only [format-local-time local-now]])
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clj-stacktrace.repl :as stacktrace]
            [clj-yaml.core :as yaml]
            [me.raynes.fs :as fs]
            [pallet.algo.fsm.fsm :as fsm-base]
            [pallet.algo.fsm.fsm-dsl :as fsm]
            [pallet.algo.fsm.event-machine :as event-machine]))

;; ## Logging

(defn- get-log-file [config]
  (let [out-dir (get-in config [:dir :out])]
    (when-not (nil? out-dir)
      (when-not (fs/exists? out-dir)
        (fs/mkdirs out-dir))
      (file out-dir "processing-status.log"))))

(defn get-log-status
  "Retrieve current processing status information from state machine log file."
  [config]
  (when-let [log-file (get-log-file config)]
    (when (fs/exists? log-file)
      (with-open [rdr (reader log-file)]
        (let [[_ state-str info-str] (string/split (last (line-seq rdr)) #" :: ")]
          (-> (read-string info-str)
              (assoc :state (read-string (last (string/split state-str #" "))))))))))

(defn traceback-to-log
  "Write an error exception to the processing log file"
  [e config]
  (with-open [wtr (writer (get-log-file config) :append true)]
    (binding [*out* wtr]
      (stacktrace/pst e)
      (println (str (format-local-time (local-now) :date-hour-minute-second)
                    " :: State :error :: {:desc \"Exception during processing: "
                    e "\"}")))))

(defn prep-comparison-fsm
  "Define a finite state machine of transitions during comparison processes."
  [config]
  (let [out-file (get-log-file config)]
    (letfn [(log-transition [_ new-state]
              (let [out (format "State %s :: %s" (:state-kw new-state)
                                (:state-data new-state))]
                (log/log :info out)
                (when out-file
                  (spit out-file (str (format-local-time (local-now) :date-hour-minute-second)
                                      " :: " out "\n") :append true))))]
      (log-transition nil {:state-kw :begin :state-data {:desc "Starting variation analysis"}})
      (event-machine/event-machine
       (fsm/event-machine-config
        (fsm/using-fsm-features (fsm-base/with-transition-observer log-transition))
        (fsm/initial-state :begin)
        (fsm/initial-state-data {})
        (fsm/state :begin
                   (fsm/valid-transitions :clean))
        (fsm/state :clean
                   (fsm/valid-transitions :merge))
        (fsm/state :merge
                   (fsm/valid-transitions :prep))
        (fsm/state :prep
                   (fsm/valid-transitions :normalize))
        (fsm/state :normalize
                   (fsm/valid-transitions :combine :clean))
        (fsm/state :combine
                   (fsm/valid-transitions :annotate))
        (fsm/state :annotate
                   (fsm/valid-transitions :filter))
        (fsm/state :filter
                   (fsm/valid-transitions :compare))
        (fsm/state :compare
                   (fsm/valid-transitions :compare :finalize :summary :clean :finished))
        (fsm/state :finalize
                   (fsm/valid-transitions :finalize :compare :summary :clean))
        (fsm/state :summary
                   (fsm/valid-transitions :finished :clean))
        (fsm/state :finished))))))

(defn do-transition
  "Perform a transition on configured finite state machine moving to the provided state"
  [config state desc]
  (if-let [do-trans (get-in config [:fsm :transition])]
    (do-trans #(assoc % :state-kw state :state-data {:desc desc}))
    (println state desc)))

;; ## Configuration

(defn- add-dir-files
  "Add files of interest in a directory with the given extension.
  This allows batch processing of directories."
  [config exts]
  (letfn [(files-from-dir [dir]
            (->> (fs/list-dir dir)
                 (filter #(contains? exts (fs/extension %)))
                 (map #(str (fs/file dir %)))))
          (process-call [call]
            (if-let [dir (:dir call)]
              (assoc call :file (files-from-dir dir))
              call))
          (process-exp [exp]
            (assoc exp :calls (map process-call (:calls exp))))]
    (assoc config :experiments
           (map process-exp (:experiments config)))))

(defn- no-duplicate-names?
  "Do not allow duplicate names in experiments."
  [config]
  (letfn [(exp-no-duplicate? [exp]
            (every? (fn [[_ x]] (= 1 x)) (frequencies (map :name (:calls exp)))))]
    (every? exp-no-duplicate? (:experiments config))))

(defn- check-keys
  "Check input keys for potential typos."
  [config config-file]
  (let [exp-keys #{:sample :ref :intervals :align :summary-level :params :approach :calls :finalize}
        call-keys #{:name :file :align :ref :type :intervals :metadata :filters :format-filters
                    :recall :annotate :normalize :prep :prep-sort-pos :fix-sample-header
                    :prep-sv-genotype :prep-allele-count :preclean :remove-refcalls :make-haploid
                    :summary}
        problems (reduce (fn [coll [item allowed]]
                           (concat coll
                                   (map (fn [k] {:name (get item :sample (:name item)) :key k})
                                        (remove #(contains? allowed %) (keys item)))))
                         [] (concat (map (fn [e] [e exp-keys]) (:experiments config))
                                    (map (fn [c] [c call-keys]) (mapcat :calls (:experiments config)))))]
    (if (empty? problems)
      config
      (throw (Exception. (format "Unexpected keys found in configuration input file %s:\n%s\n%s\n%s\n%s\n"
                                 config-file
                                 "Available configuration inputs documented at:"
                                 "https://github.com/chapmanb/bcbio.variation#configuration-file"
                                 "Problems:"
                                 (string/join "\n" (map #(format "key '%s' in section '%s'"
                                                                 (name (:key %)) (:name %))
                                                        problems))))))))

(defn load-config
  "Load configuration file, handling conversion of relative to absolute paths."
  [config-file]
  {:post [(no-duplicate-names? %)]}
  (let [config (-> config-file slurp yaml/parse-string)
        base-dir (fs/file (get-in config [:dir :base] "."))
        to-process #{[:dir :out] [:dir :prep]
                     [:experiments :ref] [:experiments :intervals]
                     [:experiments :align] [:experiments :calls :file]
                     [:experiments :calls :align] [:experiments :calls :annotate]
                     [:experiments :calls :dir]}]
    (letfn [(make-absolute [x]
              (if (.isAbsolute (file x))
                x
                (str (fs/file base-dir x))))
            (maybe-process [val path]
              (if (contains? to-process path)
                (cond
                 (seq? val) (map make-absolute val)
                 (string? val) (make-absolute val)
                 :else val)
                val))
            (sample? [path k]
              (or (= k :sample) (= (last path) :sample)))
            (update-tree [config path]
              (cond (map? config)
                    (reduce (fn [item [k v]]
                              (assoc item k (cond
                                             (map? v) (update-tree v (conj path k))
                                             (seq? v) (map #(update-tree % (conj path k)) v)
                                             (sample? path k) (str v)
                                             :else (maybe-process v (conj path k)))))
                            config
                            (vec config))
                    (contains? to-process path) (maybe-process config path)
                    :else config))]
      (-> config
          (update-tree [])
          (add-dir-files #{".vcf"})
          (check-keys config-file)
          (#(assoc % :fsm (prep-comparison-fsm %)))))))
