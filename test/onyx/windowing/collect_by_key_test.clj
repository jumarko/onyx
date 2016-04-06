(ns onyx.windowing.collect-by-key-test
  (:require [clojure.core.async :refer [chan >!! <!! close! sliding-buffer]]
            [clojure.test :refer [deftest is]]
            [taoensso.timbre :refer [info error warn trace fatal] :as timbre]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.test-helper :refer [load-config with-test-env]]
            [onyx.api]))

(def input
  [{:id 1  :team :a :event-time #inst "2015-09-13T03:00:00.829-00:00"}
   {:id 2  :team :a :event-time #inst "2015-09-13T03:04:00.829-00:00"}
   {:id 3  :team :b :event-time #inst "2015-09-13T03:05:00.829-00:00"}
   {:id 4  :team :a :event-time #inst "2015-09-13T03:06:00.829-00:00"}
   {:id 5  :team :a :event-time #inst "2015-09-13T03:07:00.829-00:00"}
   {:id 6  :team :b :event-time #inst "2015-09-13T03:08:00.829-00:00"}
   {:id 7  :team :a :event-time #inst "2015-09-13T03:09:00.829-00:00"}
   {:id 8  :team :c :event-time #inst "2015-09-13T03:15:00.829-00:00"}
   {:id 9  :team :a :event-time #inst "2015-09-13T03:25:00.829-00:00"}
   {:id 10 :team :c :event-time #inst "2015-09-13T03:45:00.829-00:00"}
   {:id 11 :team :a :event-time #inst "2015-09-13T03:03:00.829-00:00"}
   {:id 12 :team :a :event-time #inst "2015-09-13T03:56:00.829-00:00"}
   {:id 13 :team :b :event-time #inst "2015-09-13T03:59:00.829-00:00"}
   {:id 14 :team :a :event-time #inst "2015-09-13T03:32:00.829-00:00"}
   {:id 15 :team :c :event-time #inst "2015-09-13T03:16:00.829-00:00"}])

(def test-state (atom []))

(defn update-atom! [event window trigger {:keys [event-type segment] :as opts} state]
  (prn segment)
  (prn state))

(def in-chan (atom nil))

(def out-chan (atom nil))

(defn inject-in-ch [event lifecycle]
  {:core.async/chan @in-chan})

(defn inject-out-ch [event lifecycle]
  {:core.async/chan @out-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(deftest min-test
  (let [id (java.util.UUID/randomUUID)
        config (load-config)
        env-config (assoc (:env-config config) :onyx/tenancy-id id)
        peer-config (assoc (:peer-config config) :onyx/tenancy-id id)
        batch-size 20
        workflow
        [[:in :identity] [:identity :out]]

        catalog
        [{:onyx/name :in
          :onyx/plugin :onyx.plugin.core-async/input
          :onyx/type :input
          :onyx/medium :core.async
          :onyx/batch-size batch-size
          :onyx/max-peers 1
          :onyx/doc "Reads segments from a core.async channel"}

         {:onyx/name :identity
          :onyx/fn :clojure.core/identity
          :onyx/type :function
          :onyx/max-peers 1
          :onyx/uniqueness-key :id
          :onyx/batch-size batch-size}

         {:onyx/name :out
          :onyx/plugin :onyx.plugin.core-async/output
          :onyx/type :output
          :onyx/medium :core.async
          :onyx/batch-size batch-size
          :onyx/max-peers 1
          :onyx/doc "Writes segments to a core.async channel"}]

        windows
        [{:window/id :collect-segments
          :window/task :identity
          :window/type :global
          :window/aggregation [:onyx.windowing.aggregation/collect-by-key :team]
          :window/window-key :event-time}]

        triggers
        [{:trigger/window-id :collect-segments
          :trigger/refinement :onyx.refinements/accumulating
          :trigger/on :onyx.triggers/segment
          :trigger/fire-all-extents? true
          :trigger/threshold [1 :elements]
          :trigger/sync ::update-atom!}]

        lifecycles
        [{:lifecycle/task :in
          :lifecycle/calls ::in-calls}
         {:lifecycle/task :in
          :lifecycle/calls :onyx.plugin.core-async/reader-calls}
         {:lifecycle/task :out
          :lifecycle/calls ::out-calls}
         {:lifecycle/task :out
          :lifecycle/calls :onyx.plugin.core-async/writer-calls}]]

    (reset! in-chan (chan (inc (count input))))
    (reset! out-chan (chan (sliding-buffer (inc (count input)))))
    (reset! test-state [])

    (with-test-env [test-env [3 env-config peer-config]]
      (onyx.api/submit-job
       peer-config
       {:catalog catalog
        :workflow workflow
        :lifecycles lifecycles
        :windows windows
        :triggers triggers
        :task-scheduler :onyx.task-scheduler/balanced})
      
      (doseq [i input]
        (>!! @in-chan i))
      (>!! @in-chan :done)

      (close! @in-chan)

      (let [results (take-segments! @out-chan)]
        (is (= (into #{} input) (into #{} (butlast results))))
        (is (= :done (last results)))))))
