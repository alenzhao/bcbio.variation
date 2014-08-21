(ns bcbio.variation.combine
  "Combine variant files, handling no-calls versus reference calls

   1. Combine the variants to create a merged set of positions to call at
   2. For each variant file:
      a. Generate callability at each position
      b. Combine original calls with merged positions
      c. Walk through each no-call and set as reference if callable"
  (:import [htsjdk.variant.variantcontext 
            VariantContextBuilder])
  (:use [clojure.tools.cli :only [cli]]
        [bcbio.variation.complex :only [normalize-variants]]
        [bcbio.variation.filter.intervals :only [vcf-sample-name select-by-sample]]
        [bcbio.variation.haploid :only [diploid-calls-to-haploid]]
        [bcbio.variation.multisample :only [get-out-basename multiple-samples?]]
        [bcbio.variation.normalize :only [prep-vcf clean-problem-vcf]]
        [bcbio.variation.phasing :only [is-haploid?]]
        [bcbio.variation.structural :only [write-non-svs]]
        [bcbio.variation.variantcontext :only [get-vcf-header write-vcf-w-template
                                               get-vcf-iterator parse-vcf
                                               get-vcf-retriever variants-in-region]])
  (:require [me.raynes.fs :as fs]
            [clojure.string :as string]
            [bcbio.run.fsp :as fsp]
            [bcbio.run.itx :as itx]
            [bcbio.run.broad :as broad]))

(defn combine-variants
  "Combine multiple variant files with GATK CombineVariants.
   Only correctly handles all-by-all comparisons with the same ploidy level."
  [vcfs ref & {:keys [merge-type out-dir intervals unsafe name-map base-ext check-ploidy? quiet-out?]
               :or {merge-type :unique
                    unsafe false
                    name-map {}
                    check-ploidy? true}}]
  (when (and check-ploidy?
             (> (count (set (remove nil? (map #(is-haploid? % ref) vcfs)))) 1))
    (throw (Exception. (format "Haploid and non-haploid combinations not supported: %s %s"
                               (vec vcfs) (vec (map #(is-haploid? % ref) vcfs))))))
  (letfn [(unique-name [i f]
            (if quiet-out?
              (str "v" i)
              (string/replace (get name-map f
                                   (-> f fs/base-name fsp/file-root))
                              "-" "_")))]
    (let [base-dir (if (nil? out-dir) (fs/parent (first vcfs)) out-dir)
          full-base-name (-> vcfs first fs/base-name fsp/remove-zip-ext)
          base-name (if (nil? base-ext) full-base-name
                        (format "%s-%s.vcf" (first (string/split full-base-name #"-"))
                                base-ext))
          file-info {:out-vcf (fsp/add-file-part base-name
                                                 (case merge-type
                                                   :minimal "mincombine"
                                                   :full "fullcombine"
                                                   "combine")
                                                 base-dir)}
          args (concat ["-R" ref
                        "-o" :out-vcf
                        "--rod_priority_list" (string/join "," (map-indexed unique-name vcfs))]
                       ;(if unsafe ["--unsafe" "ALLOW_SEQ_DICT_INCOMPATIBILITY"] [])
                       (if unsafe ["--unsafe" "ALL"] [])
                       (if quiet-out? ["--suppressCommandLineHeader" "--setKey" "null"] [])
                       (flatten (map-indexed #(list (str "--variant:" (unique-name %1 %2)) %2) vcfs))
                       (broad/gatk-cl-intersect-intervals intervals ref)
                       (case merge-type
                         :full ["--genotypemergeoption" "PRIORITIZE"]
                         :unique ["--genotypemergeoption" "UNIQUIFY"]
                         :minimal ["--sites_only" "--minimalVCF"]))]
      (if-not (fs/exists? base-dir)
        (fs/mkdirs base-dir))
      (broad/run-gatk "CombineVariants" args file-info {:out [:out-vcf]})
      (:out-vcf file-info))))

;; ## Clean multi-alleles

(defn- clean-multialleles
  "Clean up variant contexts with multi-allele, consolidating calls and removing unused alleles."
  [retriever vcs]
  (letfn [(others-at-pos [vcs retriever]
            (filter #(apply = (map (juxt :chr :start) [% (first vcs)]))
                    (apply variants-in-region
                           (cons retriever ((juxt :chr :start :end) (first vcs))))))
          (get-ref-alt-alleles [vc]
            (let [ref (.getDisplayString (:ref-allele vc))]
              (map (fn [x] [ref (.getDisplayString x)]) (:alt-alleles vc))))
          (sort-by-allele-count [xs]
            (let [count-groups (group-by val xs)
                  topcount-alleles (keys (get count-groups (apply max (keys count-groups))))]
              (first (sort-by #(count (first %)) topcount-alleles))))]
    (let [alleles (reduce (fn [coll x]
                            (assoc coll x (inc (get coll x 0))))
                          {} (mapcat get-ref-alt-alleles (others-at-pos vcs retriever)))
          final-alleles (if (empty? alleles)
                          (-> vcs first get-ref-alt-alleles first)
                          (sort-by-allele-count alleles))]
      (-> (VariantContextBuilder. (:vc (first vcs)))
          (.alleles final-alleles)
          (.stop (+ (:start (first vcs)) (if (= 0 (count (second final-alleles)))
                                           (count (first final-alleles))
                                           (max 0 (dec (count (first final-alleles)))))))
          .make))))

(defn fix-minimal-combined
  "Fix multiple alleles in a VCF produced by combining multiple inputs.
   This combines calls present at multiple positions and removes multi-alleles
   not present in input calls."
  [combined-vcf vcfs ref]
  (let [out-file (fsp/add-file-part combined-vcf "fix")]
    (when (itx/needs-run? out-file)
      (with-open [vcf-iter (get-vcf-iterator combined-vcf ref)]
        (write-vcf-w-template combined-vcf {:out out-file}
                              (map (partial clean-multialleles (apply get-vcf-retriever (cons ref vcfs)))
                                   (partition-by (juxt :chr :start)
                                                 (parse-vcf vcf-iter)))
                              ref)))
    out-file))

(defn- genome-safe-intervals
  "Check if interval BED files overlap with current analysis genome build.
  This is useful when an input VCF is from an alternate genome and needs
  conversion. In this case we shouldn't yet be using interval selection."
  [intervals ref-file exp]
  (if (or (nil? ref-file) (= ref-file (:ref exp)))
    intervals
    []))

(defn- dirty-prep-work
  "Prepare input file for comparisons based on configuration:
    - Selecting a single sample from multi-sample files
    - Resorting and fixing chromosome naming
    - Removing reference call genotypes
   This organizes the logic which get convoluted for different cases.
   The approach is to select a single sample and remove refcalls if we have
   a multiple sample file, so the sample name will be correct."
  [in-file call exp intervals out-dir out-fname]
  (letfn [(run-sample-select [in-file ref-file ext]
            (select-by-sample (:sample exp) in-file (str (:name call) ext)
                              ref-file :out-dir out-dir
                              :intervals (genome-safe-intervals intervals ref-file exp)
                              :remove-refcalls (get call :remove-refcalls false)))]
    (let [sample-file (if (and (multiple-samples? in-file) (:sample exp))
                        (run-sample-select in-file (get call :ref (:ref exp)) "")
                        in-file)
          prep-file (if (and (true? (:prep call))
                             (not= (:ref exp) (:ref call)))
                      (prep-vcf sample-file (:ref exp) (:sample exp) :out-dir out-dir
                                :out-fname out-fname :orig-ref-file (:ref call)
                                :config call)
                      sample-file)
          hap-file (if (true? (:make-haploid call))
                     (diploid-calls-to-haploid prep-file (:ref exp) :out-dir out-dir)
                     prep-file)
          noref-file (if (or (and (not (multiple-samples? in-file)) (:remove-refcalls call))
                             (and (not (nil? (:ref call))) (not (empty? intervals))))
                       (run-sample-select hap-file (:ref exp) "-noref")
                       hap-file)]
      noref-file)))

(defn gatk-normalize
  "Prepare call information for VCF comparisons by normalizing through GATK.
  Handles:

   1. Combining multiple input files
   2. Fixing reference and sample information.
   3. Splitting combined MNPs into phased SNPs"
  [call exp intervals out-dir transition]
  (if-not (fs/exists? out-dir)
    (fs/mkdirs out-dir))
  (letfn [(merge-call-files [call in-files]
            (let [ref (get call :ref (:ref exp))]
              (combine-variants in-files ref
                                :merge-type :full :out-dir out-dir
                                :intervals (genome-safe-intervals intervals ref exp)
                                :check-ploidy? false
                                :unsafe true)))]
    (let [in-files (if (coll? (:file call)) (:file call) [(:file call)])
          out-fname (str (get-out-basename exp call in-files) ".vcf")
          _ (transition :clean (str "Cleaning input VCF: " (:name call)))
          clean-files (vec (map #(if-not (:preclean call) %
                                         (clean-problem-vcf % (:ref exp) (:sample exp) call :out-dir out-dir))
                                in-files))
          _ (transition :merge (str "Merging multiple input files: " (:name call)))
          merge-file (if (> (count clean-files) 1)
                       (merge-call-files call clean-files)
                       (first clean-files))
          _ (transition :prep (str "Prepare VCF, resorting to genome build: " (:name call)))
          prep-file (dirty-prep-work merge-file call exp intervals out-dir out-fname)]
      (transition :normalize (str "Normalize MNP and indel variants: " (:name call)))
      (assoc call :file (if (true? (get call :normalize true))
                          (normalize-variants prep-file (:ref exp) out-dir
                                              :out-fname out-fname)
                          prep-file)))))

;; ## Top-level entry points

(defn full-prep-vcf
  "Provide convenient entry to fully normalize a variant file for comparisons."
  [vcf-file ref-file & {:keys [max-indel resort keep-ref tmp-dir keep-filtered]}]
  (let [out-file (string/replace (fsp/add-file-part vcf-file "fullprep") ".vcf.gz" ".vcf")]
    (when (itx/needs-run? out-file)
      (itx/with-temp-dir [out-dir (or tmp-dir (fs/parent vcf-file))]
        (let [exp {:sample (when-not (multiple-samples? vcf-file)
                             (-> vcf-file get-vcf-header .getGenotypeSamples first))
                   :ref ref-file :params {:max-indel max-indel}}
              call {:name "fullprep" :file vcf-file :preclean true
                    :prep true :normalize true :prep-sv-genotype false
                    :fix-sample-header true
                    :prep-sort-pos resort
                    :remove-refcalls (not keep-ref)}
              out-info (gatk-normalize call exp [] out-dir
                                       (fn [_ x] (println x)))
              nosv-file (if max-indel
                          (write-non-svs (:file out-info) (:ref exp) (:params exp))
                          (:file out-info))]
          (fs/rename nosv-file out-file))))
    out-file))

(defn -main [& args]
  (let [[options [vcf-file ref-file] banner]
        (cli args
             ["-i" "--max-indel" "Maximum indel size to include" :default nil
              :parse-fn #(Integer. %)]
             ["-s" "--resort" "Resort input file by coordinate position" :default false :flag true]
             ["-r" "--keep-ref" "Keep reference (0/0) and filtered (not PASS) calls"
              :default false :flag true]
             ["-t" "--tmpdir" "Temporary directory to work in" :default nil])]
    (when (or (:help options) (nil? vcf-file) (nil? ref-file))
      (println "Required arguments:")
      (println "    <vcf-file> VCF input file to prepare.")
      (println "    <ref-file> Genome reference file (GRCh37/b37 coordinates)")
      (println)
      (println banner)
      (System/exit 0))
    (let [out-file (full-prep-vcf vcf-file ref-file :max-indel (:max-indel options)
                                  :resort (:resort options) :keep-ref (:keep-ref options)
                                  :tmp-dir (:tmpdir options))]
      (println out-file)
      (System/exit 0))))
