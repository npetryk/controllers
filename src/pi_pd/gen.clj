(ns pi-pd.gen
  (:require [com.rpl.specter :as specter]
            [clojure.test.check
             [generators :as gen]]))

(comment

  (clojure.pprint/pprint)
 ;
)

(defn gen-sim
  [{:keys [gen-duration
           gen-work-duration
           gen-interarrival-time]
    :as opts}]
  (let [avg-interarrival-time
        (->> (gen/sample (gen/vector gen-interarrival-time 100))
             (mapcat identity)
             (apply +)
             (* (/ 1 1000)))]

    (gen/let [duration
              gen-duration
              interarrival-times
              (gen/vector gen-interarrival-time (/ duration avg-interarrival-time))
              durations
              (gen/vector gen-work-duration (/ duration avg-interarrival-time))]
      (map
       (fn [arrival-time duration]
         {:arrival-time arrival-time
          :finish-time (+ arrival-time duration)
          :duration duration})
       (drop 1 (reductions + 0 interarrival-times))
       durations))))

(defn make-simulation []
  (->> (gen-sim
        {:gen-duration
         (gen/choose 100 100)
         :gen-work-duration
         (gen/frequency
          [[10 (gen/choose 10 100)]
           [90 (gen/choose 1 10)]])
         :gen-interarrival-time
         (gen/frequency
          [[50 (gen/choose 0 0)]
           [50 (gen/choose 1 10)]])})
       (gen/sample)))

