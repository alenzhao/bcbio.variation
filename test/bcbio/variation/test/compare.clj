(ns bcbio.variation.test.compare
  (:import [org.broadinstitute.gatk.utils.exceptions UserException$BadInput])
  (:use [midje.sweet]
        [bcbio.variation.annotation]
        [bcbio.variation.callable]
        [bcbio.variation.combine]
        [bcbio.variation.compare :exclude [-main]]
        [bcbio.variation.config]
        [bcbio.variation.evaluate :exclude [-main]]
        [bcbio.variation.filter]
        [bcbio.variation.filter.util]
        [bcbio.variation.normalize]
        [bcbio.variation.phasing]
        [bcbio.variation.metrics]
        [bcbio.variation.report]
        [bcbio.variation.recall :exclude [-main]]
        [bcbio.variation.variantcontext :exclude [-main]])
  (:require [me.raynes.fs :as fs]
            [bcbio.run.fsp :as fsp]
            [bcbio.run.itx :as itx]))

(background
 (around :facts
         (let [data-dir (str (fs/file "." "test" "data"))
               ref (str (fs/file data-dir "GRCh37.fa"))
               intervals (str (fs/file data-dir "target-regions.bed"))
               vcf1 (str (fs/file data-dir "gatk-calls.vcf"))
               vcf2 (str (fs/file data-dir "freebayes-calls.vcf"))
               nocall-vcf (str (fs/file data-dir "gatk-nocalls.vcf"))
               nocall-out (fsp/add-file-part nocall-vcf "wrefs")
               pvcf (str (fs/file data-dir "phasing-contestant.vcf"))
               ref-vcf (str (fs/file data-dir "phasing-reference.vcf"))
               ref2-vcf (str (fs/file data-dir "phasing-reference2.vcf"))
               align-bam (str (fs/file data-dir "aligned-reads.bam"))
               sample "Test1"
               annotated-out (fsp/add-file-part vcf2 "annotated")
               combo-out (fsp/add-file-part vcf1 "combine")
               compare-out (str (fsp/file-root vcf1) ".eval")
               out-sum-compare (str (fsp/file-root vcf1) "-summary.eval")
               filter-out (fsp/add-file-part vcf1 "filter")
               nofilter-out (fsp/add-file-part filter-out "nofilter")
               combine-out [(fsp/add-file-part vcf1 "fullcombine-wrefs-cleaned")]
               combine-out-cons [(fsp/add-file-part vcf1
                                                    "mincombine-fix-consensus-cleaned")]
               combine-out-xtra [(fsp/add-file-part vcf1 "mincombine")
                                 (fsp/add-file-part vcf1 "mincombine-fix")
                                 (fsp/add-file-part vcf1 "mincombine-fix-consensus")
                                 (fsp/add-file-part vcf1 "mincombine-fix-consensus-cleaned")
                                 (fsp/add-file-part vcf1 "mincombine-fix-consensus-singles")
                                 (fsp/add-file-part vcf1 "mincombine-fix-consensus-recallfilter")
                                 (fsp/add-file-part vcf1 "mincombine-fix-consensus-recallfilter-cleaned")
                                 (fsp/add-file-part vcf1 "mincombine-fix-consensus-singles-wrefs")
                                 (fsp/add-file-part vcf1 "fullcombine")
                                 (fsp/add-file-part vcf1 "fullcombine-Test1-called")
                                 (fsp/add-file-part vcf1 "fullcombine-Test1-nocall")
                                 (fsp/add-file-part vcf1 "fullcombine-Test1-nocall-wrefs")
                                 (fsp/add-file-part vcf2 "fullcombine")
                                 (fsp/add-file-part vcf1 "fullcombine-wrefs")
                                 (fsp/add-file-part vcf2 "fullcombine-wrefs")
                                 (fsp/add-file-part vcf2 "fullcombine-Test1-called")
                                 (fsp/add-file-part vcf2 "fullcombine-Test1-nocall")
                                 (fsp/add-file-part vcf2 "fullcombine-Test1-nocall-wrefs")]
               match-out {:concordant (fsp/add-file-part combo-out "concordant")
                          :discordant (fsp/add-file-part combo-out "discordant")}
               select-out (doall (map #(str (fs/file data-dir (format "%s-%s.vcf" sample %)))
                                      ["freebayes-gatk-concordance"
                                       "gatk-freebayes-discordance"
                                       "freebayes-gatk-discordance"]))
               out-callable (map #(format "%s-%s.bed" (fsp/file-root align-bam) %)
                                 ["callable" "callable-intervals" "callable-sorted"
                                  "callable-intervals-sorted"])
               out-intervals (fsp/add-file-part (first out-callable) "intervals")]
           (doseq [x (concat [combo-out compare-out annotated-out filter-out nofilter-out
                              out-sum-compare out-intervals nocall-out]
                             (map str (fs/glob (str (fs/file (fs/parent nocall-vcf)
                                                             (fs/name nocall-vcf)) "-*vcf")))
                             out-callable combine-out combine-out-cons combine-out-xtra (vals match-out) select-out)]
             (fsp/remove-path x)
             (when (.endsWith x ".vcf")
               (fsp/remove-path (str x ".idx"))))
           ?form)))

(facts "Variant manipulation with GATK: selection, combination and annotation."
  (select-by-concordance sample {:name "gatk" :file vcf1}
                         {:name "freebayes" :file vcf2} ref
                         :interval-file intervals) => select-out
  (combine-variants [vcf1 vcf2] ref) => combo-out
  (add-gatk-annotations vcf2 align-bam ref) => annotated-out)

(facts "Variant assessment with GATK"
  (calc-variant-eval-metrics vcf1 vcf2 ref :intervals intervals) => compare-out
  (-> (summary-eval-metrics vcf1 ref :cmp-intervals intervals) first :nSamples) => 1
  (get (concordance-report-metrics sample compare-out)
       :Non-Reference_Sensitivity) => 0.8)

(facts "Create merged VCF files for comparison"
  (let [config {:sample "Test1"
                :ref ref
                :calls [{:recall true :name "gatk" :file vcf1}
                        {:recall false :name "freebayes" :file vcf2}]}]
    (create-merged [vcf1 vcf2] [align-bam align-bam] config) => (conj combine-out-cons vcf2)))

(facts "Filter variant calls avoiding false positives."
  (variant-filter vcf1 ["QD < 2.0" "MQ < 40.0"] ref) => filter-out
  (remove-cur-filters filter-out ref) => nofilter-out
  (split-variants-by-match vcf1 vcf2 ref) => match-out)

(facts "Check for callability based on sequencing reads."
  (identify-callable align-bam ref) => (first out-callable)
  (with-open [call-source (get-callable-checker align-bam ref)]
    (is-callable? call-source "MT" 16 17) => true
    (is-callable? call-source "MT" 252 252) => nil
    (is-callable? call-source "MT" 5100 5200) => nil
    (is-callable? call-source "MT" 16 15) => nil)
  (get-callable-bed align-bam ref) => out-intervals)

(facts "Accumulate statistics associated with variations."
  (let [metrics (get-vcf-classifier-metrics ref [vcf1 vcf2])
        wnil-metrics (get-vcf-classifier-metrics ref [vcf1 vcf2] :remove-nil-cols false)]
    (count metrics) => 2
    (-> metrics first :cols) => ["AC" "AF" "AN" "DP" "QUAL"]
    (-> metrics first :rows count) => 10
    (-> wnil-metrics first :cols) => ["AC" "AF" "AN" "DP" "QUAL" "ReadPosRankSum"]
    (-> wnil-metrics first :rows count) => 2
    (-> metrics second :rows first) => (just (map roughly [2.0 1.0 2.0 938.0 22437.3]))
    (classify-decision-tree metrics) => ["DP"]
    (classify-decision-tree wnil-metrics) => []
    (merge-classified-metrics [["A" "B" "C"] ["C" "D"]]) => {:top-metrics ["A" "C" "B" "D"]}))

(facts "Handle haplotype phasing specified in VCF output files."
  (with-open [pvcf-iter (get-vcf-iterator pvcf ref)]
    (let [haps (parse-phased-haplotypes pvcf-iter ref)]
      (count haps) => 5
      (count (first haps)) => 6
      (-> haps first first :start) => 9
      (count (second haps)) => 1
      (-> haps (nth 2) first :start) => 16)))

(facts "Compare phased calls to haploid reference genotypes."
  (with-open [ref-vcf-get (get-vcf-retriever ref ref-vcf)
              pvcf-iter (get-vcf-iterator pvcf ref)]
    (let [cmps (score-phased-calls pvcf-iter ref-vcf-get ref)]
      (map :variant-type (first cmps)) => [:snp :snp :indel :indel :snp]
      (:comparison (ffirst cmps)) => :discordant
      (map :comparison (nth cmps 3)) => [:ref-concordant :phasing-error
                                         :ref-concordant :discordant]
      (map :comparison (nth cmps 4)) => [:discordant :concordant :concordant]
      (map :nomatch-het-alt (first cmps)) => [false true false false true])))

(facts "Compare two sets of haploid reference calls"
  (with-open [ref-vcf-get (get-vcf-retriever ref ref-vcf)
              ref2-vcf-iter (get-vcf-iterator ref2-vcf ref)]
    (let [cmps (score-phased-calls ref2-vcf-iter ref-vcf-get ref)]
      (count cmps) => 2
      (count (first cmps)) => 10
      (drop 6 (map :comparison (first cmps))) => [:ref-concordant :concordant
                                                  :ref-concordant :discordant])))

(facts "Check if a variant file is a haploid reference."
  (is-haploid? pvcf ref) => false
  (is-haploid? ref-vcf ref) => true)

(facts "Merging and count info for reference and contestant analysis regions."
  (let [cbed (str (fs/file data-dir "phasing-contestant-regions.bed"))
        rbed (str (fs/file data-dir "phasing-reference-regions.bed"))]
    (count-comparison-bases rbed cbed ref) => (contains {:compared 18 :total 19})
    (count-comparison-bases rbed nil ref) => (contains {:compared 19 :total 19})))

(facts "Calculate final accuracy score for contestant/reference comparison."
  (calc-accuracy {:total-bases {:compared 10}
                  :discordant {:indel 1 :snp 1}
                  :phasing-error {:indel 1 :snp 1}}
                 [:discordant :phasing-error]) => (roughly 40.0))

(let [data-dir (str (fs/file "." "test" "data"))
      ref (str (fs/file data-dir "GRCh37.fa"))
      vcf (str (fs/file data-dir "cg-normalize.vcf"))
      out-vcf (fsp/add-file-part vcf "prep")
      prevcf (str (fs/file data-dir "illumina-needclean.vcf"))
      out-prevcf (str (fs/file data-dir "NA12878-tester-preclean.vcf"))]
  (against-background [(before :facts (vec (map fsp/remove-path [out-vcf out-prevcf
                                                             (str vcf ".idx")])))]
    (facts "Normalize variant representation of chromosomes, order, genotypes and samples."
      (prep-vcf vcf ref "Test1" :config {:prep-sort-pos true}) => out-vcf)
    (facts "Pre-cleaning of problematic VCF input files"
      (clean-problem-vcf prevcf ref "NA12878" {:name "tester"} {:sample "NA12878"}
                         :out-dir data-dir) => out-prevcf)))

(facts "Choose a reference genome based on VCF contig"
  (pick-best-ref vcf1 [ref]) => ref)

(facts "Load configuration files, normalizing input."
  (let [config-file (fs/file "." "config" "method-comparison.yaml")
        config (load-config config-file)]
    (get-in config [:dir :out]) => (has-prefix "/")
    (-> config :experiments first :sample) => "Test1"
    (-> config :experiments first :calls first :file) => (has-prefix "/")
    (-> config :experiments first :calls second :filters first) => "HRun > 5.0"))

(facts "Manage finite state machine associated with analysis flow."
  (let [config-file (fs/file "." "config" "method-comparison.yaml")
        config (load-config config-file)]
    (do-transition config :clean "Testing")
    (:state-kw ((get-in config [:fsm :state]))) => :clean
    (get-log-status config) => {:state :clean :desc "Testing"}))

(facts "Determine the highest count of items in a list"
  (highest-count []) => nil
  (highest-count ["a" "a"]) => "a"
  (highest-count ["a" "b" "b"]) => "b"
  (highest-count ["a" "a" "b" "b"]) => "a"
  (highest-count ["b" "b" "a" "a"]) => "a")
