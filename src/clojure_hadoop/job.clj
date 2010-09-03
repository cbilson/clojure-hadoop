(ns clojure-hadoop.job
  (:require [clojure-hadoop.gen :as gen]
            [clojure-hadoop.imports :as imp]
            [clojure-hadoop.wrap :as wrap]
            [clojure-hadoop.config :as config]
            [clojure-hadoop.load :as load])
  (:import (org.apache.hadoop.util Tool))
  (:use [clojure-hadoop.config :only (configuration)]))

(imp/import-conf)
(imp/import-io)
(imp/import-io-compress)
(imp/import-fs)
(imp/import-mapreduce)
(imp/import-mapreduce-lib)

(gen/gen-job-classes)
(gen/gen-main-method)

(def ^Job *job* nil)

(def ^{:private true} method-fn-name
     {"map" "mapper-map"
      "reduce" "reducer-reduce"
      "combiner" "combiner-reduce"})

(def ^{:private true} wrapper-fn
     {"map" wrap/wrap-map
      "reduce" wrap/wrap-reduce
      "combiner" wrap/wrap-reduce})

(def ^{:private true} default-reader
     {"map" wrap/clojure-map-reader
      "reduce" wrap/clojure-reduce-reader
      "combiner" wrap/clojure-reduce-reader})

(defn set-job [job]
  (alter-var-root (var *job*) (fn [_] job)))

(defn- configure-functions
  "Preps the mapper or reducer with a Clojure function read from the
  job configuration.  Called from Mapper.configure and
  Reducer.configure."
  [type ^Job job]
  (set-job job)
  (let [function (load/load-name (.get (configuration job) (str "clojure-hadoop.job." type)))
        reader (if-let [v (.get (configuration job) (str "clojure-hadoop.job." type ".reader"))]
                 (load/load-name v)
                 (default-reader type))
        writer (if-let [v (.get (configuration job) (str "clojure-hadoop.job." type ".writer"))]
                 (load/load-name v)
                 wrap/clojure-writer)]
    (assert (fn? function))
    (alter-var-root (ns-resolve (the-ns 'clojure-hadoop.job)
                                (symbol (method-fn-name type)))
                    (fn [_] ((wrapper-fn type) function reader writer)))))

;;; CREATING AND CONFIGURING JOBS

(defn- parse-command-line [job args]
  (try
   (config/parse-command-line-args job args)
   (catch Exception e
     (prn e)
     (config/print-usage)
     (System/exit 1))))

;;; MAPPER METHODS

(defn mapper-configure [this job]
  (configure-functions "map" job))

(defn mapper-map [this wkey wvalue output reporter]
  (throw (Exception. "Mapper function not defined.")))

;;; REDUCER METHODS

(defn reducer-configure [this job]
  (configure-functions "reduce" job))

(defn reducer-reduce [this wkey wvalues output reporter]
  (throw (Exception. "Reducer function not defined.")))

;;; COMBINER METHODS

(gen-class
 :name ~(str the-name "_combiner")
 :extends "org.apache.hadoop.mapred.MapReduceBase"
 :implements ["org.apache.hadoop.mapred.Reducer"]
 :prefix "combiner-"
 :main false)

(defn combiner-configure [this job]
  (configure-functions "combiner" job))

(defn combiner-reduce [this wkey wvalues output reporter]
  (throw (Exception. "Combiner function not defined.")))

(defn- handle-replace-option [^Job job]
  (when (= "true" (.get job "clojure-hadoop.job.replace"))
    (let [fs (FileSystem/get job)
          output (FileOutputFormat/getOutputPath job)]
      (.delete fs output true))))

(defn- set-default-config [^Job job]
  (doto job
    (.setJobName "clojure_hadoop.job")
    (.setOutputKeyClass Text)
    (.setOutputValueClass Text)
    (.setMapperClass (Class/forName "clojure_hadoop.job_mapper"))
    (.setReducerClass (Class/forName "clojure_hadoop.job_reducer"))
    (.setInputFormat SequenceFileInputFormat)
    (.setOutputFormat SequenceFileOutputFormat)
    (FileOutputFormat/setCompressOutput true)
    (SequenceFileOutputFormat/setOutputCompressionType
     SequenceFile$CompressionType/BLOCK)))

(defn run
  "Runs a Hadoop job given the Job object."
  [job]
  (doto job
    (handle-replace-option)
    (.waitForCompletion true)))

(defn run-job-fn
  "Runs a Hadoop job given the job-fn."
  ([job-fn]
     (run-job-fn (clojure_hadoop.job.) job-fn))
  ([tool job-fn]
     (doto (Job. (.getConf tool) (.getClass tool))      
       (set-default-config)
       (config/conf :job-fn job-fn)
       (run))))

;;; TOOL METHODS

(gen/gen-conf-methods)

(defn tool-run [^Tool this args]
  (doto (Job. (.getConf this) (.getClass this))
    (set-default-config)
    (parse-command-line args)
    (run))
  0)