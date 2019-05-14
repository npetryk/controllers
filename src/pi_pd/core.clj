(ns pi-pd.core
  (:require [com.rpl.specter :as specter]
            [incanter
             [core :as incanter]
             [stats :as i-stats]
             [charts :as i-charts]]
            ))

(def ^:dynamic *ds* (atom []))

(defn drop-prob
  [{:keys [gain buffer-size Qref pi pd p]}
   Qsamples]
  (let [pd-d-1 pd
        pd-i-1 pi
        α      gain
        B      buffer-size
        Qk     (nth Qsamples 0)
        Qk-1   (nth Qsamples 1)

        pd-i-0
        (cond-> (- (/ (+ Qk Qk-1)
                      2)
                   Qref)
          (not (zero? Qref))
          (/ Qref)
          true
          (* α)
          pi
          (+ pi))

        Qk+1 (let [Yi (fn [i]
                        (- (nth Qsamples i)
                           (nth Qsamples (+ i 1))))
                   Si (fn [i]
                        (if (>= (Yi i) 0)
                          1
                          -1))
                   Yk   (Yi 0)
                   Yk-1 (Yi 1)
                   Sk   (Si 0)
                   Sk-1 (Si 1)
                   S    (* Sk Sk-1)
                   Yk+1 (cond
                         (= S 1) (* Yk (/ Yk (if (zero? Yk-1) 1 Yk-1)))
                         :else Yk)]
               (+ Yk+1 Qk))

        pd-d-0
        (cond-> (/ (- Qk+1 Qref)
                   (- B Qref))
          true
          (* α)
          pd
          (+ pd))]
    {:pd pd-d-0
     :pi pd-i-0
     :Qk+1 Qk+1
     :p (+ pd-d-0 pd-i-0)}))

(defprotocol AsyncController)

(defn drop-rate-control-loop
  [output-atom sample-fn {:keys [Ts] :as consts}]
  (let [ultimate-stime (System/currentTimeMillis)]
    (loop [samples []
           pd-1 {}]
      (let [stime (System/currentTimeMillis)
            sample (sample-fn)
            pd-1 (if (>= (count samples) 4)
                   (drop-prob consts samples)
                   {:p 0, :pi 0, :pd 0})
            samples (-> (into () (reverse (take 3 samples)))
                        (conj sample))
            pd-0 (if (>= (count samples) 4)
                   (drop-prob (merge consts pd-1) samples)
                   {:p 0, :pi 0, :pd 0})
            data (assoc pd-0
                        :samples samples
                        :t (- (System/currentTimeMillis) ultimate-stime)
                        :sample sample
                        :perc (perc (:p pd-0))
                        :perci (perc (:pi pd-0))
                        :percd (perc (:pd pd-0))
                        )]
        (reset! output-atom data)
        (swap! *ds* conj data)
        (Thread/sleep (- Ts (- (System/currentTimeMillis) stime)))
        (recur samples pd-0)))))

(defn clamp
  [mx n mn]
  (if n
    (min (max n mn) mx)
    mn))

(defn perc
  [n]
  (float (* 100 (min (max n 0) 1))))

(defn pipd-abq
  [{:keys [Qref gain buffer-size] :as consts}]
  (let [_drop-rate
        (atom {:p 0})

        arb
        (proxy [java.util.concurrent.ArrayBlockingQueue] [1000 #_buffer-size]
         (add [e]
           (let [dr @_drop-rate]
             (if (or (> 1e-1 (:p dr))
                     (> (Math/random) (:p dr)))
               (do #_(printf "YEP  - %3.0f%% - %s\n"
                           (perc (:p  dr))
                           (specter/transform [specter/MAP-VALS number?] (comp int (partial * 100)) dr))
                   (proxy-super add e))
               (do #_(printf "NOPE - %3.0f%% - %s\n"
                           (perc (:p dr))
                           (specter/transform [specter/MAP-VALS number?] (comp int (partial * 100)) dr))
                   (throw (IllegalStateException. "dropped")))))))]

    (future (drop-rate-control-loop
             _drop-rate
             #(.size arb)
             consts))
    arb))


(comment

 (binding [*ds* (atom [])]

   (let [push-speed [1 2]
         pull-speed [2 3]

         Qref 50
         gain 1/10
         C (/ 1 (min (second push-speed) (second pull-speed)))
         Ts (* (/ Qref C)
               2)

         abq (pipd-abq
              {:gain gain
               :buffer-size (* 4 Qref)
               :Ts Ts
               :Qref Qref})
         its 5000]

    (Thread/sleep (* 4 Ts))

    (future
     (doseq [_ (range its)]
       (Thread/sleep (int
                      (+ (* (- (second pull-speed) (first pull-speed))
                            (Math/random))
                         (first pull-speed))))
       (.poll abq)))

    (reduce
     (fn [successes i]
       (Thread/sleep (int
                      (+ (* (- (second push-speed) (first push-speed))
                            (Math/random))
                         (first push-speed))))
       (try
        (.add abq 5)
        (inc successes)
        (catch Exception e
          successes)))
     0
     (range its)))

    (def ds @*ds*)

    (incanter/with-data
     (incanter/to-dataset ds)
     (incanter/save
      (doto (i-charts/xy-plot :t :perc :legend true)
        (i-charts/add-lines :t :perc)
        (i-charts/add-lines :t :perci)
        (i-charts/add-lines :t :percd)
        (i-charts/add-lines :t :sample)
        )
      "/home/nathan/smbshare/imgs/test.png")))

 )

