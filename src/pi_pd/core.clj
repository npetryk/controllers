(ns pi-pd.core
  (:require [com.rpl.specter :as specter]
            [incanter
             [core :as incanter]
             [stats :as i-stats]
             [charts :as i-charts]]))

(defn clamp
  [mx n mn]
  (if n
    (min (max n mn) mx)
    mn))

(defn perc
  [n]
  (float (* 100 (min (max n 0) 1))))

(def ^:dynamic *ds*
  (atom []))
(def ^:dynamic *consts*
  nil)

(defn pid
  [{last-i :di
    last-∑ :∑}
   Qsamples]
  (let [Qref (:Qref *consts*)
        Qk   (first Qsamples)
        Qk-1 (second Qsamples)
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
                          ;(= S 1) (* Yk (/ Yk (if (zero? Yk-1) 1 Yk-1)))
                          :else Yk)]
               (+ Yk+1 Qk))

        e (/ (- Qk Qref)
             Qref)
        d (* (:Kd *consts*)
             (/ (- (- Qk Qref)
                   (- Qk-1 Qref))
                #_
                (- (- Qk+1 Qref)
                   (- Qk Qref))
                Qref))

        i-time-scale (/ 1 (/ 1000 (:Ts *consts*)))

        Δi (if true #_(or (> Qk Qref)
                   (> Qk (* Qref 8/10)))
             (clamp 1
                    (* (:Ki *consts*) i-time-scale e)
                    -1)
             0)

        p (* (:Kp *consts*)
             e)

        i (+ Δi (or last-i 0))
        i (clamp (- 1 p) i 0)
        ]

    {:∑ (+ p i d)
     :dp p
     :di i
     :dd d
     :acc i
     :Qk+1 Qk+1}))

(defn pi-pd
  [{:keys [pd pi Qk+1 p]}
   Qsamples]
  (let [{α :gain
         B :buffer-size
         Qref :Qref} *consts*

        pd-1 pd
        pi-1 pi

        Qk     (nth Qsamples 0)
        Qk-1   (nth Qsamples 1)

        pi-0
        (cond-> (- (/ (+ Qk Qk-1)
                      2)
                   Qref)
          (not (zero? Qref))
          (/ Qref)
          true
          (* α)
          pi-1
          (+ pi-1))

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

        pd-0
        (cond-> (* (/ (- Qk+1 Qref)
                      (- B Qref))
                   α)
          pd-1
          (+ pd-1))]

    {:pd pd-0
     :pi pi-0
     :Qk+1 Qk+1
     :∑ (+ pd-0 pi-0)}))

(defn drop-rate-control-loop
  [output-atom sample-fn prob-fn]
  (let [ultimate-stime (System/currentTimeMillis)]
    (loop [samples []
           pd-1 {}]
      (let [stime (System/currentTimeMillis)
            sample (sample-fn)
            ; pd-1 (if (>= (count samples) 4)
            ;        (prob-fn {} samples)
            ;        {})
            samples (-> (into () (reverse (take 3 samples)))
                        (conj sample))
            pd-0 (if (>= (count samples) 4)
                   (prob-fn pd-1 samples)
                   {:∑ 0})
            pd-0-data (assoc pd-0
                             :t (- (System/currentTimeMillis) ultimate-stime)
                             :sample sample)]
        (reset! output-atom pd-0-data)
        (swap! *ds* conj pd-0-data)
        (Thread/sleep (- (:Ts *consts*) (- (System/currentTimeMillis) stime)))
        (recur
         samples
         pd-0)))))

(defn abq
  [{:keys []}]
  (let [_drop-rate
        (atom {:∑ 0})

        arb
        (proxy [java.util.concurrent.ArrayBlockingQueue] [1000 #_buffer-size]
          (add [e]
            (let [dr @_drop-rate]
              (if (or (> 1e-1 (:∑ dr))
                      (> (Math/random) (:∑ dr)))
                (proxy-super add e)
                (throw (IllegalStateException. "dropped"))))))]

    (future (drop-rate-control-loop
             _drop-rate
             #(.size arb)
             (:control-fn *consts*)))
    arb))

(defn simulate+graph [iterations]
  (let [push-speed [1 5]
        pull-speed [5 10]

        abq (abq {})]

    (Thread/sleep (* 10 (:Ts *consts*)))

    (future
      (doseq [_ (range (int (/ iterations 8)))]
        (Thread/sleep (int
                       (+ (* (- (second pull-speed) (first pull-speed))
                             (Math/random))
                          (first pull-speed))))
        (.poll abq))

    (Thread/sleep (* 30 (:Ts *consts*)))

      (doseq [_ (range (int (/ iterations 10)))]
        (Thread/sleep (int
                       (+ (* (- (second pull-speed) (first pull-speed))
                             (Math/random))
                          (first pull-speed))))
        (.poll abq)))

    

    (doseq [i (range iterations)]
      (Thread/sleep (int
                     (+ (* (- (second push-speed) (first push-speed))
                           (Math/random))
                        (first push-speed))))
      (try
        (.add abq 5)
        (catch Exception e
          nil)))

    (let [ds (map
              (fn [d]
                (-> d
                    (update :∑ (fnil perc 0))
                    (update :di (fnil perc 0))
                    (update :dd (fnil perc 0))
                    (update :dp (fnil perc 0))
                    (update :Qk+1 (fnil #(clamp 100 % 0) 0))
                    ))
              @*ds*)]
      (incanter/with-data
        (incanter/to-dataset ds)
        (-> (doto (i-charts/xy-plot :t :∑ :legend true)
              (i-charts/add-lines :t :sample)
              (i-charts/add-lines :t :∑)
              (i-charts/add-lines :t :dd)
              (i-charts/add-lines :t :di)
              (i-charts/add-lines :t :dp)
              (i-charts/add-lines :t :Qk+1)
              )
            #_(incanter/view)
            (incanter/save
             "/home/nathan/smbshare/imgs/test.png"))))))

(comment



  (binding [*ds* (atom [])
            *consts* {:gain 1/10
                      :buffer-size 100
                      :Qref 50
                      :Ts 100
                      :control-fn pid #_pi-pd
                      :Kd 1
                      :Ki 1
                      :Kp 1}]
    #_(pid {} [60 60 60 60])
    (simulate+graph 3000)))
