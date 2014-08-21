(ns bcbio.variation.phasing
  "Support phased haplotype comparisons between variant calls.
   Compares a phased set of calls versus haploid reference calls.

   The comparison logic is:

   - Group calls into regions based on phasing
   - For each phase region:
     - Determine which set of haploid alleles to compare with the reference
     - With each position in this haploid:
       - Compare to reference allele
       - If mismatch and alternate allele matches reference, then phasing error
       - If mismatch and neither allele matches, then calling error"
  (:import [org.broadinstitute.gatk.utils.interval IntervalUtils IntervalSetRule]
           [org.broadinstitute.gatk.utils GenomeLocParser GenomeLoc])
  (:use [bcbio.variation.callable :only [get-bed-source features-in-region
                                         limit-bed-intervals get-bed-iterator]]
        [bcbio.variation.filter.intervals :only [intersection-of-bed-files
                                                 select-by-sample]]
        [bcbio.variation.structural :only [prep-itree get-itree-overlap
                                           remove-itree-vc get-itree-all]]
        [bcbio.variation.variantcontext :only [parse-vcf get-vcf-retriever get-vcf-iterator
                                               variants-in-region merge-headers
                                               write-vcf-w-template]]
        [bcbio.align.ref :only [get-seq-dict]]
        [ordered.map :only [ordered-map]])
  (:require [me.raynes.fs :as fs]
            [bcbio.run.itx :as itx]))

;; ## Find phased haplotypes in VCF

(defn- is-phased?
  "Check for phasing on a single genotype variant context based on:
   - variant has a single allele
   - variant has phasing specified (VCF | notation)
   - variant range overlaps previous variant (overlapping indels)"
  [vc prev-vc bed-s]
  {:pre [(= 1 (:num-samples vc))]}
  (letfn [(safe-same-regions? [[a b]]
            (if (not-any? nil? [a b]) (= a b) true))
          (same-regions? [prev cur]
            (if (nil? bed-s)
              true
              (safe-same-regions?
               (map #((juxt :chr :start :end)
                      (first (features-in-region bed-s (:chr %) (:start %) (:end %))))
                    [prev cur]))))]
    (let [g (-> vc :genotypes first)]
      (and (= (:chr vc) (:chr prev-vc))
           (same-regions? prev-vc vc)
           (or (= 1 (count (:alleles g)))
               (.isPhased (:genotype g))
               (<= (:start vc) (:end prev-vc)))))))

(defn parse-phased-haplotypes
  "Separate phased haplotypes provided in diploid input genome.
   We split at each phase break, returning a lazy list of variant
   contexts grouped into phases."
  [vcf-iter ref-file & {:keys [intervals]}]
  (let [prev (atom nil)
        bed-source (when intervals
                     (get-bed-source intervals ref-file))]
    (letfn [(split-at-phased [vc]
              (let [continue-phase (or (nil? @prev)
                                       (is-phased? vc @prev bed-source))]
                (reset! prev vc)
                continue-phase))]
      (partition-by split-at-phased (parse-vcf vcf-iter)))))

;; ## Compare phased variants

(defn highest-count
  "Retrieve the item with the highest count in the supplied list.
  We break ties by sorting by the actual list items"
  [xs]
  (->> (frequencies xs)
       (sort-by val >)
       (partition-by second)
       first
       (sort-by first)
       ffirst))

(defn- get-alleles
  "Convenience function to get alleles for a single genotype variant context."
  [vc]
  {:pre [(= 1 (:num-samples vc))]}
  (-> vc :genotypes first :alleles))

(defn- matching-allele
  "Determine allele index where the variant context matches haploid reference."
  [vc ref-vcs]
  {:pre [(every? #(= 1 (:num-samples %)) ref-vcs)
         (= 1 (:num-samples vc))]}
  (cond
   (= 1 (count (get-alleles vc))) 0
   (empty? ref-vcs) (.indexOf (get-alleles vc) (:ref-allele vc))
   :else (highest-count
          (remove neg?
                  (map #(.indexOf (get-alleles vc) (-> % get-alleles first)) ref-vcs)))))

(defn cmp-allele-to-expected
  "Compare the haploid allele of a variant against the expected call."
  [vc e-vc i]
  (letfn [(is-ref-allele? [x]
            (apply = (map #(.getDisplayString (% x)) [:cmp :ref])))
          (get-cmp-allele [i x]
            (when (< i (count (get-alleles x)))
              {:ref (:ref-allele x)
               :cmp (nth (get-alleles x) i)}))
          (get-all-alleles [x]
            (map #(get-cmp-allele % x) (range (count (get-alleles x)))))]
    (let [e-allele (when-not (nil? e-vc)
                     (get-cmp-allele 0 e-vc))
          call-hap (when-not (or (nil? i) (nil? vc) (neg? i))
                     (get-cmp-allele i vc))]
      (cond
       (nil? call-hap) :discordant
       (and (is-ref-allele? call-hap)
            (or (nil? e-allele)
                (= e-allele call-hap))) :ref-concordant
       (nil? e-allele) :discordant
       (= e-allele call-hap) :concordant
       (some (partial = e-allele) (get-all-alleles vc)) :phasing-error
       :else :discordant))))

(defn get-variant-type
  "Retrieve the type of a set of variants involved in a comparison.

  - `:indel` -- insertions or deletions of more than 1bp
  - `:snp` -- Single nucleotide changes or single basepair changes
  - `:unknown` -- Other classs of variations (structural)"
  [vcs]
  (letfn [(is-indel? [x]
            (contains? #{"MIXED" "INDEL"} (:type x)))
          (is-multi-indel? [x]
            (and (is-indel? x)
                 (not-every? #(contains? #{0 1} %)
                             (map #(-> % .getDisplayString count) (cons (:ref-allele x)
                                                                     (:alt-alleles x))))))
          (is-snp? [x]
            (= "SNP" (:type x)))]
    (cond
     (some is-multi-indel? vcs) :indel
     (some is-indel? vcs) :indel
     (every? is-snp? (remove nil? vcs)) :snp
     :else :unknown)))

(defn- nomatch-het-alt?
  "Determine if the variant has a non-matching heterozygous alternative allele."
  [vc e-vc]
  {:pre [(not (nil? vc))]}
  (let [match-allele-i (matching-allele vc (if (nil? e-vc) [] [e-vc]))
        no-match-alleles (remove nil? (map-indexed
                                       (fn [i x] (if-not (= i match-allele-i) x))
                                       (get-alleles vc)))]
    (and (= "HET" (-> vc :genotypes first :type))
         (not-every? #(.isReference %) no-match-alleles))))

(defn- deleted-bases
  [vc]
  (letfn [(is-deletion? [vc]
            (and (= (:type vc) "INDEL")
                 (pos? (.length (:ref-allele vc)))))]
    (if (is-deletion? vc)
      (map vector (repeat (:chr vc)) (range (:start vc) (inc (:end vc))))
      [])))

(defn- comparison-metrics
  "Provide metrics for comparison of haploid expected alleles to variant calls."
  [cmp-itree i e-vc]
  (let [cmp-vc (->> (get-itree-overlap cmp-itree (:chr e-vc) (:start e-vc) (:end e-vc))
                    (filter #(= (:start %) (:start e-vc)))
                    first)]
    {:comparison (cmp-allele-to-expected cmp-vc e-vc i)
     :variant-type (get-variant-type [cmp-vc e-vc])
     :nomatch-het-alt (when-not (nil? cmp-vc) (nomatch-het-alt? cmp-vc e-vc))
     :start (if (nil? cmp-vc) (:start e-vc) (:start cmp-vc))
     :end (:end cmp-vc)
     :end-ref (:end e-vc)
     :deleted (deleted-bases e-vc)
     :vc (:vc cmp-vc)
     :ref-vc (:vc e-vc)}))

(defn- score-phased-region
  "Provide scoring metrics for a phased region against expected haplotype variants.
    - Fetch all expected variants in the phased region.
    - Iterate over expected variants comparing to the called variants:
       - Keep IntervalTree of called variants, removing variants as evaluated.
       - Keep coordinates of expected deletion regions.
    - Add discordant variants for extra calls not in expected variants, avoiding
      variants in deleted regions."
  [expect-get vcs]
  (let [vc-itree (atom (prep-itree vcs :start :end))]
    (letfn [(get-ref-vcs [x]
              (variants-in-region expect-get (:chr x) (:start x) (:end x)))
            (ref-match-allele [x]
              (matching-allele x (variants-in-region expect-get (:chr x) (:start x) (:end x))))
            (get-regional-expected-vcs
              [itree]
              {:pre [(= 1 (count (keys itree)))]}
              (let [[chr tree] (first itree)]
                (let [start (-> tree .min .getStart)]
                  (->> (variants-in-region expect-get chr start (dec (-> tree .max .getEnd)))
                       (remove #(< (:start %) start))
                       (sort-by :start)))))
            (compare-and-update [cmp-i info e-vc]
              (let [cmp (comparison-metrics @vc-itree cmp-i e-vc)]
                (reset! vc-itree (remove-itree-vc @vc-itree (:chr e-vc)
                                                  (:start cmp) (:end cmp)))
                (-> info
                    (assoc :out (cons cmp (:out info)))
                    (assoc :deleted (concat (:deleted info) (:deleted cmp))))))
            (in-deleted-region? [regions vc]
              (contains? regions [(:chr vc) (:start vc)]))
            (add-unmapped-cmps [cmp-i info]
              (concat (:out info)
                      (map (fn [vc] {:comparison (cmp-allele-to-expected vc nil cmp-i)
                                     :variant-type (get-variant-type [vc])
                                     :nomatch-het-alt (nomatch-het-alt? vc nil)
                                     :start (:start vc)
                                     :vc (:vc vc)
                                     :ref-vc nil})
                           (remove (partial in-deleted-region? (set (:deleted info)))
                                   (get-itree-all @vc-itree)))))]
      (let [cmp-allele-i (highest-count (remove #(or (nil? %) (neg? %))
                                                (map ref-match-allele vcs)))]
        (->> (reduce (partial compare-and-update cmp-allele-i)
                     {:deleted [] :out []}
                     (get-regional-expected-vcs @vc-itree))
             (add-unmapped-cmps cmp-allele-i)
             (sort-by :start))))))

(defn score-phased-calls
  "Score a called VCF against expected haploid variants based on phased regions.
  Partitions phased regions into blocks of two concurrent regions. For each block:
   - Evaluate second region with standard scoring: expected to called
   - Collect expected variants in the intervening region between phased blocks,
     report those missing in the comparison input as errors."
  [call-vcf-iter expect-get ref-file & {:keys [intervals]}]
  (let [prev (atom nil)]
    (letfn [(get-intervene-expect [region1 region2]
              (let [vc1 (last region1)
                    vc2 (first region2)
                    filter-end (if (nil? vc1) (dec (:start vc2)) (:end vc1))
                    vcs (cond
                         (nil? vc1)
                         (variants-in-region expect-get (:chr vc2) 0 (dec (:start vc2)))
                         (not= (:chr vc1) (:chr vc2))
                         (concat (variants-in-region expect-get (:chr vc1) (inc (:end vc1)) 1e10)
                                 (variants-in-region expect-get (:chr vc2) 0 (dec (:start vc2))))
                         :else
                         (variants-in-region expect-get (:chr vc1) (inc (:end vc1))
                                             (dec (:start vc2))))]
                (->> vcs
                     (remove #(< (:start %) filter-end))
                     (map (fn [x] {:comparison :discordant
                                   :variant-type (get-variant-type [x])
                                   :nomatch-het-alt false
                                   :start (:start x)
                                   :vc nil
                                   :ref-vc (:vc x)}))
                     (sort-by :start))))
            (score-phased-and-intervene [region]
              (let [out (concat (get-intervene-expect @prev region)
                                (when (not= (:chr (first region)) "finished_sentinel")
                                  (score-phased-region expect-get region)))]
                (reset! prev region)
                out))]
      (map score-phased-and-intervene
           (concat (parse-phased-haplotypes call-vcf-iter ref-file
                                            :intervals intervals)
                   [[{:chr "finished_sentinel" :start 1}]])))))

;; ## Summarize phased comparisons

(defn- write-concordance-output
  "Write concordant and discordant variants to VCF output files."
  [vc-info to-capture sample-name base-info other-info out-dir ref]
  (let [base-dir (if (nil? out-dir) (fs/parent (:file base-info)) out-dir)
        gen-file-name (fn [x] (str (fs/file base-dir (format "%s-%s-%s-%s.vcf"
                                                             sample-name (:name base-info)
                                                             (:name other-info) (name x)))))
        out-files (apply ordered-map (flatten (map (juxt identity gen-file-name)
                                                   to-capture)))]
    (if-not (fs/exists? base-dir)
      (fs/mkdirs base-dir))
    (when (itx/needs-run? (vals out-files))
      (write-vcf-w-template (:file base-info) out-files
                            (->> (flatten vc-info)
                                 (map (juxt :comparison :vc))
                                 (filter #(contains? (set to-capture) (first %))))
                            ref
                            :header-update-fn (merge-headers (:file other-info))))
    out-files))

(defn count-comparison-bases
  "Provide counts for comparison: entire region plus user specified regions"
  [total-bed call-bed ref-file]
  (letfn [(feature-size [x]
            (cond
             (instance? GenomeLoc x) (- (.getStop x) (dec (.getStart x)))
             :else (- (.getEnd x) (dec (.getStart x)))))
          (count-bases [xs]
            (apply + (map feature-size xs)))
          (merge-intervals [x y]
            (intersection-of-bed-files [x y] ref-file (GenomeLocParser. (get-seq-dict ref-file))))]
    (if (nil? total-bed)
      {:percent 0.0 :compared 0 :total 0}
      (with-open [bed-iter (get-bed-iterator total-bed ref-file)]
        (let [total (count-bases bed-iter)
              compared (if (or (nil? call-bed) (= total-bed call-bed)) total
                           (count-bases (merge-intervals total-bed call-bed)))]
          {:percent (* 100.0 (/ compared total))
           :compared compared
           :total total})))))

(defn- get-phasing-metrics
  "Collect summary metrics for concordant/discordant and phasing calls"
  [vc-info exp-interval-file call-interval-file ref-file]
  (letfn [(count-nomatch-het-alt [xs]
            (count (filter #(and (contains? #{:concordant :ref-concordant} (:comparison %))
                                 (:nomatch-het-alt %))
                           (flatten vc-info))))
          (blank-count-dict []
            {:snp 0 :indel 0})
          (add-current-count [coll x]
            (let [cur-val (map x [:comparison :variant-type])]
              (assoc-in coll cur-val (inc (get-in coll cur-val)))))]
    (reduce add-current-count
            {:haplotype-blocks (count vc-info)
             :total-bases (count-comparison-bases exp-interval-file call-interval-file ref-file)
             :nonmatch-het-alt (count-nomatch-het-alt vc-info)
             :concordant (blank-count-dict)
             :ref-concordant (blank-count-dict)
             :discordant (blank-count-dict)
             :phasing-error (blank-count-dict)}
            (flatten vc-info))))

;; ## Entry point for phased haploid VCF comparisons

(defmulti compare-two-vcf-phased
  "Compare two VCF files including phasing with a haplotype reference
  Handle grading special case as well as standard comparisons."
  (fn [_ exp _] (keyword (get exp :approach "compare"))))

(defn- convert-cmp-to-grade
  "Convert comparison into ready to write keywords for grading.
  Deals with discordant comparisons where the competition call
  is missing."
  [cmp]
  (if (and (= (:comparison cmp) :discordant)
           (nil? (:vc cmp)))
    (-> cmp
        (assoc :comparison :discordant-missing)
        (assoc :vc (:ref-vc cmp)))
    cmp))

(defn- add-grading-info
  "Associate a grading category with each variant based on comparison."
  [cmp]
  (letfn [(assign-discordant-cat [{:keys [vc ref-vc]}]
            (println vc)
            (println ref-vc))]
    (let [cat (case (:comparison cmp)
                :ref-concordant "concordant"
                :concordant "concordant"
                :discordant (assign-discordant-cat cmp) 
                :discordant-missing "discordant-nocall"
                :phasing-error "discordant-phasing")])
    cmp))

(defn- get-compare-file
  "Retrieve the comparison file, filtering by intervals if present."
  [in-file cmp-name exp intervals]
  (if (nil? intervals)
    in-file
    (select-by-sample (:sample exp) in-file nil (:ref exp)
                      :intervals intervals :ext (str "cmp" cmp-name))))

(defmethod compare-two-vcf-phased :grade
  [phased-calls exp config]
  {:pre [(= 1 (count (get phased-calls true)))
         (= 1 (count (get phased-calls false)))]}
  (let [ref (first (get phased-calls true))
        call (first (get phased-calls false))
        call-intervals (when-let [f (get call :intervals (:intervals exp))]
                         (limit-bed-intervals f call exp config))]
    (with-open [ref-get (get-vcf-retriever (:ref exp)
                                           (get-compare-file (:file ref) (:name call)
                                                             exp call-intervals))
                call-vcf-iter (get-vcf-iterator (:file call) (:ref exp))]
      (let [compared-calls (score-phased-calls call-vcf-iter ref-get (:ref exp)
                                               :intervals call-intervals)]
        {:c-files (write-concordance-output (->> compared-calls
                                                 flatten
                                                 (map convert-cmp-to-grade)
                                                 (map add-grading-info))
                                            [:concordant :discordant
                                             :discordant-missing :phasing-error]
                                            (:sample exp) call ref
                                            (get-in config [:dir :out]) (:ref exp))
         :metrics (get-phasing-metrics compared-calls (:intervals exp)
                                       call-intervals (:ref exp))
         :c1 (assoc call :intervals call-intervals)
         :c2 ref :sample (:sample exp) :exp exp :dir (:dir config)}))))

(defn- convert-cmps-to-compare
  "Convert stream of variant context haploid comparison to standard,
  keyed by :concordant and :discordant-name keywords."
  [cmps name1 name2]
  (letfn [(update-keyword [coll x]
            (let [ref-x (-> x
                            (assoc :vc (:ref-vc x))
                            (dissoc :ref-vc))
                  [dis-kw1 dis-kw2] (map #(keyword (format "%s-discordant" %)) [name1 name2])]
              (case (:comparison x)
                :concordant (conj coll ref-x)
                (:discordant :phasing-error) (-> coll
                                                 (conj (assoc x :comparison dis-kw2))
                                                 (conj (assoc ref-x :comparison dis-kw1)))
                coll)))
          (update-keyword-hapblock [xs]
            (remove #(or (nil? %) (nil? (:vc %)))
                    (reduce update-keyword [] xs)))]
    (map update-keyword-hapblock cmps)))

(defmethod compare-two-vcf-phased :compare
  [phased-calls exp config]
  {:pre [(= 2 (count (flatten (vals phased-calls))))
         (pos? (count (get phased-calls true)))]}
  (let [cmp1 (first (get phased-calls true))
        cmp2 (if-let [nophased (get phased-calls false)]
               (first nophased)
               (second (get phased-calls true)))
        to-capture (concat [:concordant]
                           (map #(keyword (format "%s-discordant" (:name %)))
                                [cmp1 cmp2]))
        cmp-intervals (when-let [f (get cmp2 :intervals (:intervals exp))]
                        (limit-bed-intervals f cmp2 exp config))]
    (with-open [vcf1-get (get-vcf-retriever (:ref exp)
                                            (get-compare-file (:file cmp1) (:name cmp2)
                                                              exp cmp-intervals))
                vcf2-iter (get-vcf-iterator (:file cmp2) (:ref exp))]
      {:c-files (-> (score-phased-calls vcf2-iter vcf1-get
                                        (:ref exp) :intervals cmp-intervals)
                    (convert-cmps-to-compare (:name cmp1) (:name cmp2))
                    (write-concordance-output to-capture (:sample exp) cmp1 cmp2
                                              (get-in config [:dir :out]) (:ref exp)))
       :c1 cmp1 :c2 cmp2 :sample (:sample exp) :exp exp :dir (:dir config)})))

;; ## Utility functions

(defn is-haploid?
  "Is the provided VCF file a haploid genome (one genotype or all homozygous).
  Samples the first set of variants, checking for haploid calls.
  Avoids calling haploid based on chrM-only calls, used during testing."
  [vcf-file ref-file]
  (let [sample-size 10]
    (letfn [(is-vc-haploid? [vc]
              (when-not (= 0 (:num-samples vc))
                (when-not (contains? #{"chrM" "MT" "M" "chrMT"} (:chr vc))
                  (= 1 (apply max (map #(count (:alleles %)) (:genotypes vc)))))))]
      (with-open [vcf-iter (get-vcf-iterator vcf-file ref-file)]
        (let [vcf-iter (parse-vcf vcf-iter)]
          (if-not (empty? vcf-iter)
            (every? is-vc-haploid? (take sample-size vcf-iter))
            false))))))
