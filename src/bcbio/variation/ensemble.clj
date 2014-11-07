(ns bcbio.variation.ensemble
  "Generate a single set of variant calls from multiple approaches.
   Provides a nicer front end to ensemble based calling which exposes
   the most useful knobs for combining multiple VCFs.
   Automates the two step configuration process for ensemble calling:
    - combine all variant calls together into a consensus set
    - filter this consensus set using ensemble approaches"
  (:require [clojure.java.io :as io]
            [clj-yaml.core :as yaml]
            [lonocloud.synthread :as ->]
            [me.raynes.fs :as fs]
            [bcbio.run.fsp :as fsp]
            [bcbio.run.itx :as itx]
            [bcbio.variation.compare :as compare]
            [bcbio.variation.vcfsample :as vcfsample]
            [bcbio.variation.variantcontext :as gvc]))

(defn- setup-work-dir
  "Create working directory for ensemble consensus calling."
  [out-file]
  (let [work-dir (str (fsp/file-root out-file) "-work")
        config-dir (str (io/file work-dir "config"))]
    (doseq [dir [config-dir work-dir]]
      (when-not (fs/exists? dir)
        (fs/mkdirs dir)))
    {:config config-dir :out work-dir :base work-dir
     :prep (str (io/file work-dir "prep"))}))

(defn- prep-sample
  "Prepare base information for preparing a variation sample.
   Normalization ensures all samples are similarly represented for comparison."
  [config i vrn-file]
  (-> {:name (if (string? i) i
                 (or (get (vec (:names config)) i)
                     (str "v" i)))
       :file vrn-file
       :normalize true}
      (->/when (get config :prep-inputs true)
        (merge {:preclean true :prep true}))))

(defn- get-combo-recall
  "Prepare a combined set of recalled variants from all inputs."
  [config n vrn-file]
  (-> (prep-sample config n vrn-file)
      (assoc :recall true)
      (assoc :annotate true)
      (->/when-let [ffilters (get-in config [:ensemble :format-filters])]
        (assoc :format-filters ffilters))))

(defn- create-ready-config
  "Create a ensemble configuration to prep and combine the input variation files.
   config specifies ensemble specific parameters to use."
  [vrn-files ref-file config dirs]
  (let [combo-name "combo"
        out-file (str (fs/file (:config dirs) "ensemble.yaml"))
        calls (map-indexed (partial prep-sample config) vrn-files)]
    (->> {:dir dirs
          :experiments
          [(-> {:ref ref-file
                :calls (cons (get-combo-recall config combo-name (first vrn-files)) calls)
                :finalize [{:method "multiple"
                            :target combo-name}
                           {:method "recal-filter"
                            :target [combo-name (-> calls first :name)]
                            :params {:support combo-name
                                     :classifiers (get-in config [:ensemble :classifiers])
                                     :classifier-type (get-in config [:ensemble :classifier-params :type]
                                                              "svm")
                                     :normalize "default"
                                     :log-attrs []
                                     :xspecific true
                                     :trusted {:total (get-in config [:ensemble :trusted-pct] 0.65)}}}]}
               (->/when-let [int-file (:intervals config)]
                 (assoc :intervals int-file))
               (->/when-let [sample-name (let [samples (-> vrn-files first gvc/get-vcf-header
                                                           .getGenotypeSamples)]
                                           (when (= 1 (count samples))
                                             (first samples)))]
                 (assoc :sample sample-name)))]}
         yaml/generate-string
         (spit out-file))
    out-file))

(defn first-mismatch
  "Return the first mismatched pair in the two sequences"
  [c1 c2]
  (first (drop-while (fn [[x1 x2]] (= x1 x2)) (map vector c1 c2))))

(defn- check-vcf-headers
  "Ensure consistent headers for multi-sample inputs to Ensemble calling."
  [vcf-files]
  (reduce (fn [samples cmp-vcf]
            (let [cmp-samples (gvc/get-samples cmp-vcf)]
              (if (= samples cmp-samples)
                samples
                (throw (Exception. (str "VCF files do not have consistent headers: "
                                        (vec (map fs/base-name vcf-files))
                                        "\nFirst mismatch:" (first-mismatch samples cmp-samples)))))))
          (gvc/get-samples (first vcf-files)) (rest vcf-files)))

(defn consensus-calls
  "Provide a finalized set of consensus calls from multiple inputs.
   Handles cleaning up and normalizing input files, generating consensus
   calls and returns ensemble output."
  [vrn-files ref-file out-file in-config]
  (let [out-file (fsp/abspath out-file)
        ref-file (fsp/abspath ref-file)
        dirs (setup-work-dir out-file)
        vrn-files (vcfsample/consistent-order (map fsp/abspath vrn-files)
                                              (str (fs/file (:prep dirs) "sort")))
        config-file (create-ready-config vrn-files ref-file in-config dirs)]
    (check-vcf-headers vrn-files)
    (compare/variant-comparison-from-config config-file)
    (let [prep-file (first (fs/glob (str (io/file (:prep dirs) "*cfilter.vcf"))))]
      (assert prep-file (str "Did not find prepped and filtered consensus file. "
                             "Do you have classifiers specified in the input YAML file?"))
      (fs/copy prep-file out-file)
      (fs/copy (str prep-file ".idx") (str out-file ".idx"))))
  out-file)

(defn -main [& args]
  (if (< (count args) 5)
    (do
      (println "ERROR: Incorrect arguments")
      (println "variant-ensemble: Perform ensemble calling on multiple variant calls")
      (println "Arguments:")
      (println "  config-file -- YAML configuration file with analysis parameters")
      (println "  ref-file -- The genome fasta reference")
      (println "  out-file -- Name of output VCF file to write ensemble calls.")
      (println "  [vrn-file-1 vrn-file-2] -- List of multiple inputs to use for ensemble unification."))
    (let [[config-file ref-file out-file & vrn-files] args]
      (consensus-calls vrn-files ref-file out-file
                       (-> config-file slurp yaml/parse-string)))))
