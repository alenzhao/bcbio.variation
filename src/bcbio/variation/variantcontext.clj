(ns bcbio.variation.variantcontext
  "Helper functions to retrieve information from Picard/GATK VariantContext
   objects, which represent variant data stored in VCF files."
  (:import [htsjdk.tribble.index IndexFactory]
           [htsjdk.tribble AbstractFeatureReader]
           [htsjdk.tribble.util LittleEndianOutputStream]
           [htsjdk.variant.vcf
            VCFCodec VCFUtils VCFHeader VCFFilterHeaderLine]
           [htsjdk.variant.variantcontext VariantContextBuilder
            GenotypeBuilder GenotypesContext]
           [htsjdk.variant.variantcontext.writer VariantContextWriterFactory
            Options]
           [java.util EnumSet])
  (:use [clojure.java.io]
        [clojure.set :only [intersection union]]
        [lazymap.core :only [lazy-hash-map]]
        [ordered.set :only [ordered-set]]
        [bcbio.align.ref :only [get-seq-dict]])
  (:require [clojure.math.combinatorics :as combo]
            [clojure.string :as string]
            [lonocloud.synthread :as ->]
            [bcbio.run.fsp :as fsp]
            [bcbio.run.itx :as itx]))

;; ## Represent VariantContext objects
;;
;; Provide simple map-based access to important attributes of
;; VariantContexts. There are 3 useful levels of abstraction:
;;
;;  - VariantContext: Details about a variation. This captures a
;;    single line in a VCF file
;;  - Genotype: An individual genotype for a sample, at a variant position.
;;  - Allele: The actual alleles at a genotype.

(defn from-genotype
  "Represent a sample genotype including alleles.
   :genotype stores the original java genotype object for direct access."
  [g]
  (lazy-hash-map
   :sample-name (.getSampleName g)
   :qual (.getPhredScaledQual g)
   :type (-> g .getType .name)
   :phased? (.isPhased g)
   :attributes (merge {"DP" (.getDP g) "AD" (vec (.getAD g))
                       "GQ" (.getGQ g) "PL" (vec (.getPL g))}
                      (into {} (.getExtendedAttributes g)))
   :alleles (vec (.getAlleles g))
   :genotype g))

(defn from-vc
  "Provide a top level map of information from a variant context.
   :vc stores the original java VariantContext object for direct access."
  [vc]
  (lazy-hash-map
   :chr (.getChr vc)
   :start (.getStart vc)
   :end (.getEnd vc)
   :id (when (.hasID vc) (.getID vc))
   :ref-allele (.getReference vc)
   :alt-alleles (vec (.getAlternateAlleles vc))
   :type (-> vc .getType .name)
   :filters (set (.getFilters vc))
   :attributes (into {} (.getAttributes vc))
   :qual (.getPhredScaledQual vc)
   :num-samples (.getNSamples vc)
   :genotypes (map from-genotype
                   (-> vc .getGenotypes .toArray vec))
   :vc vc))

;; ## Parsing VCF files

(defn- create-vcf-index
  "Retrieve a Tribble index for a VCF file, read/writing from/to idx file."
  [vcf-file]
  (let [idx-file (str vcf-file ".idx")]
    (if (or (itx/needs-run? idx-file)
            (not (itx/up-to-date? idx-file vcf-file)))
      (let [idx (IndexFactory/createDynamicIndex (file vcf-file) (VCFCodec.))]
        (itx/with-tx-file [tx-idx-file idx-file]
          (with-open [wtr (LittleEndianOutputStream. (output-stream tx-idx-file))]
            (.write idx wtr)))
        idx)
      (IndexFactory/loadIndex (.getAbsolutePath (file idx-file))))))

(defn get-vcf-source
  "Create a Tribble FeatureSource for VCF file.
   Handles indexing and parsing of VCF into VariantContexts.
   We treat gzipped files as tabix indexed VCFs."
  ([in-file]
      (if (.endsWith in-file ".gz")
        (AbstractFeatureReader/getFeatureReader in-file (VCFCodec.) false)
        (AbstractFeatureReader/getFeatureReader (.getAbsolutePath (file in-file)) (VCFCodec.)
                                                (create-vcf-index in-file))))
  ([in-file ref-file]
     (get-vcf-source in-file)))

(defn get-vcf-iterator
  "Create an iterator over VCF VariantContexts."
  ([in-file]
     (.iterator (get-vcf-source in-file)))
  ([in-file ref-file]
     (get-vcf-iterator in-file)))

(defn variants-in-region
  "Retrieve variants located in potentially multiple variant files"
  ([retriever vc]
     (variants-in-region retriever (:chr vc) (:start vc) (:end vc)))
  ([retriever space start end]
     (letfn [(get-vcs-in-source [[source fname]]
               (with-open [vcf-iter (.query source space start end)]
                 (doall (map #(assoc (from-vc %) :fname fname) (iterator-seq vcf-iter)))))]
       (mapcat get-vcs-in-source (map vector (:sources retriever) (:fnames retriever))))))

(defn has-variants?
  "Look for matching variants present in any of the variant files."
  [retriever space start end ref alt]
  (some #(and (= start (:start %))
              (= end (:end %))
              (= ref (:ref-allele %))
              (seq (intersection (set (:alt-alleles %)) (set alt))))
        (variants-in-region retriever space start end)))

(defrecord VariantRetriever [sources fnames]
  java.io.Closeable
  (close [_]
    (doseq [x sources]
      (.close x))))

(defn get-vcf-retriever
  "Indexed variant file retrieval for zero to multiple files with clean handle closing."
  [ref & vcf-files]
  (let [fnames (remove nil? vcf-files)]
    (VariantRetriever. (map #(get-vcf-source % ref) fnames)
                       fnames)))

(defn parse-vcf
  "Lazy iterator of VariantContext information from VCF file."
  [vcf-source]
  (map from-vc (iterator-seq (.iterator vcf-source))))

(defn get-vcf-header
  "Retrieve header from input VCF file."
  [vcf-file]
  (with-open [vcf-reader (AbstractFeatureReader/getFeatureReader vcf-file (VCFCodec.) false)]
    (.getHeader vcf-reader)))

(defn get-samples
  "Retrieve samples from VCF header"
  [vcf-file]
  (.getGenotypeSamples (get-vcf-header vcf-file)))

;; ## Writing VCF files

(defn merge-headers
  [& merge-files]
  (fn [_ header]
    (VCFHeader. (VCFUtils/smartMergeHeaders (cons header (map get-vcf-header merge-files))
                                            true)
                (.getGenotypeSamples header))))

(defn header-w-md
  "Update a header with new INFO and FILTER metadata."
  [header new-md]
  (VCFHeader. (apply ordered-set (concat (.getMetaDataInInputOrder header) new-md))
              (.getGenotypeSamples header)))

(defn write-vcf-w-template
  "Write VCF output files starting with an original input template VCF.
   Handles writing to multiple VCF files simultaneously with the different
   file handles represented as keywords. This allows lazy splitting of VCF files:
   `vc-iter` is a lazy sequence of `(writer-keyword variant-context)`.
   `out-file-map` is a map of writer-keywords to output filenames."
  [tmpl-file out-file-map vc-iter ref & {:keys [header-update-fn]}]
  (letfn [(make-vcf-writer [f ref]
            (VariantContextWriterFactory/create (file f) (get-seq-dict ref)
                                                (EnumSet/of Options/INDEX_ON_THE_FLY
                                                            Options/ALLOW_MISSING_FIELDS_IN_HEADER)))
          (convert-to-output [info]
            [(if (and (coll? info) (= 2 (count info))) (first info) :out)
             (if (coll? info) (last info) info)])]
    (itx/with-tx-files [tx-out-files out-file-map (keys out-file-map) [".idx"]]
      (let [tmpl-header (get-vcf-header tmpl-file)
            writer-map (zipmap (keys tx-out-files)
                               (map #(make-vcf-writer % ref) (vals tx-out-files)))]
        (doseq [[key out-vcf] writer-map]
          (.writeHeader out-vcf (if-not (nil? header-update-fn)
                                  (header-update-fn key tmpl-header)
                                  tmpl-header)))
        (doseq [[fkey item] (map convert-to-output vc-iter)]
          (let [ready-vc (if (and (map? item) (contains? item :vc)) (:vc item) item)]
            (when-not (nil? ready-vc)
              (.add (get writer-map fkey) ready-vc))))
        (doseq [x (vals writer-map)]
          (.close x))))))

(defn- add-filter-header
  [fname fdesc]
  (fn [_ header]
    (header-w-md header
                 #{(VCFFilterHeaderLine. fname fdesc)})))

(defn- maybe-add-filter
  [fname passes? vc]
  (if (passes? vc)
    (:vc vc)
    (-> (VariantContextBuilder. (:vc vc))
        (.filters (union #{fname} (:filters vc)))
        .make)))

(defn write-vcf-from-filter
  "Write VCF file from input using a filter function."
  [vcf ref out-part fname fdesc passes?]
  (let [out-file (fsp/add-file-part vcf out-part)]
    (when (itx/needs-run? out-file)
      (with-open [vcf-iter (get-vcf-iterator vcf)]
        (write-vcf-w-template vcf {:out out-file}
                              (map (partial maybe-add-filter fname passes?) (parse-vcf vcf-iter))
                              ref
                              :header-update-fn (add-filter-header fname fdesc))))
    out-file))

(defn select-variants
  "Select variants from an input file with supplied filter."
  [in-file passes? file-out-part ref-file & {:keys [out-dir]}]
  (let [out-file (fsp/add-file-part in-file file-out-part out-dir)]
    (when (itx/needs-run? out-file)
      (with-open [in-iter (get-vcf-iterator in-file)]
        (write-vcf-w-template in-file {:out out-file}
                              (map :vc (filter passes? (parse-vcf in-iter)))
                              ref-file)))
    out-file))

;; ## Genotype manipulation

(defmulti consistent-attr?
  "Ensure attributes are consistent with merged alleles"
  (fn [attr & args]
    (keyword attr)))

(defmethod consistent-attr? :PL
  ^{:doc "Ensure PL likelihood match new alleles. These can get out of skew during ensemble calling
          when the final genotype has less alternative alleles than the original.
          galleles -- actual genotype alleles (to infer ploidy)
          talleles -- total alleles for variant call (to ensure matches number of PL combos)"}
  [attr val galleles talleles]
  (= (count val) (count (set (map sort (combo/selections talleles (count galleles)))))))

(defmethod consistent-attr? :default
  [attr val & args]
  true)

(defn create-genotypes
  "Create Genotype objects for a samples with defined alleles,
   optionally including attributes from the parent genotype.
   gs is a list of genotype dictionaries, with the alleles modified
   to the new values desired. This converts them into GATK-ready objects."
  [gs alleles & {:keys [attrs]}]
  (let [all-attrs [["PL" seq (fn [x _ v] (.PL x (int-array v)))]
                   ["PVAL" identity (fn [x k v] (.attribute x k v))]
                   ["GQ" identity (fn [x _ v] (.GQ x v))]
                   ["DP" identity (fn [x _ v] (.DP x v))]
                   ["AD" seq (fn [x _ v] (.AD x (int-array v)))]
                   ["AO" identity (fn [x a v] (.attribute x a v))]]]
    (letfn [(alleles->genotype [g]
              (-> (GenotypeBuilder. (:sample-name g) (:alleles g))
                  (->/for [[attr val-fn add-fn] all-attrs]
                    (->/when (contains? attrs attr)
                      (->/when-let [val (val-fn (get-in g [:attributes attr]))]
                        (->/when (consistent-attr?  attr val (:alleles g) alleles)
                          (#(add-fn % attr val))))))
                  .make))]
      (->> gs
           (map alleles->genotype)
           java.util.ArrayList.
           GenotypesContext/create))))

(defn genotypes->refcall
  "Convert variant context genotypes into all reference calls (0/0)."
  [vc & {:keys [attrs num-alleles]}]
  (letfn [(make-refcall [g]
            (assoc g :alleles
                   (repeat (if (nil? num-alleles)
                             (count (:alleles g))
                             num-alleles)
                           (:ref-allele vc))))]
    (-> (VariantContextBuilder. (:vc vc))
        (.genotypes (create-genotypes (map make-refcall (:genotypes vc))
                                      (cons (:ref-allele vc) (:alt-alleles vc))
                                      :attrs attrs))
        .make)))

(defn -main [vcf ref approach]
  (with-open [vcf-iter (get-vcf-iterator vcf)]
    (letfn [(item-iter []
              (case approach
                "gatk" (iterator-seq (.iterator vcf-iter))
                "orig" (map :vc (parse-vcf vcf-iter))))]
      (write-vcf-w-template vcf {:out "vctest.vcf"} (item-iter) ref)
      ;; (doseq [[i x] (map-indexed vector (item-iter))]
      ;;   (when (= 0 (mod i 10000))
      ;;      (println x)))
      )))
