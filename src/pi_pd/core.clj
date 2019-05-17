(ns pi-pd.core
  (:require [com.rpl.specter :as specter]
            [clojure.core.async :as a]
            [incanter
             [core :as incanter]
             [stats :as i-stats]
             [charts :as i-charts]]
            [pi-pd.gen :as gen]))

(defn clamp
  [mx n mn]
  (if n
    (min (max n mn) mx)
    mn))

(defn perc
  [n]
  (float (* 100 (min (max n -1) 1))))

(def ^:dynamic *ds*
  (atom []))
(def ^:dynamic *consts*
  nil)


;;;; Clock Stuff ;;;;


(def ^:dynamic *clock-time*
  (atom 0))

(def _clock-c (a/chan 100))
(def clock-c (a/mult _clock-c))

(defmacro with-clock
  [t & body]
  `(binding [*clock-time* (atom ~t)]
     (a/put! _clock-c ~t)
     ~@body))

(defn increment-clock
  [by-t]
  (a/>!! _clock-c (+ by-t @*clock-time*))
  (swap! *clock-time* + by-t))

(defn run-at! [f t]
  (let [local-clock-c (a/tap clock-c (a/chan))]
    (a/go-loop []
      (if-let [clock-time (a/<! local-clock-c)]
        (if (>= clock-time t)
          (do (a/untap clock-c local-clock-c)
              (f))
          (recur))
        nil))))

(declare ^:dynamic *metrics*)

(defn simulate-work!
  [{:keys [arrival-time finish-time]}]
  (run-at! (fn []
             (swap! *metrics*
                    #(-> %
                         (update :running (fnil inc 0)))))
           arrival-time)

  (run-at! (fn []
             (swap! *metrics*
                    #(-> %
                         (update :successes (fnil inc 0))
                         (update :running (fnil dec 0)))))
           finish-time))

(comment
  (binding [*metrics* (atom {:running 0, :successes 0, :errors 0})]
    (with-clock 0
      (doseq [e (first (gen/make-simulation))]
        (simulate-work! e))
      (doseq [i (range 200)]
        (increment-clock 1)
        (Thread/sleep 1)
        (println @*metrics*))
      (Thread/sleep 100))))

;;;;;;;;;;;;;;;;;;;;;


(defn run-sim
  [sim])

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
                   #_α)
          pd-1
          (+ pd-1))]

    {:pd pd-0
     :pi pi-0
     :Qk+1 Qk+1
     :p (+ pd-0 pi-0)}))

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
                   (prob-fn {}  #_pd-1 samples)
                   {:p 0})
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
        (atom {:p 0})

        arb
        (proxy [java.util.concurrent.ArrayBlockingQueue] [1000 #_buffer-size]
          (add [e]
            (let [dr @_drop-rate]
              (if (or (> 1e-1 (:p dr))
                      (> (Math/random) (:p dr)))
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

    (Thread/sleep (* 4 (:Ts *consts*)))

    (future
      (doseq [_ (range (int (/ iterations 4)))]
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
                    (update :p perc)
                    (update :pi (fnil perc 0))
                    (update :pd (fnil perc 0))
                    (update :Qk+1 (fnil identity 0))))
              @*ds*)]
      (incanter/with-data
        (incanter/to-dataset ds)
        (-> (doto (i-charts/xy-plot :t :p :legend true)
              (i-charts/add-lines :t :sample)
              (i-charts/add-lines :t :pi)
              (i-charts/add-lines :t :pd)
              (i-charts/add-lines :t :p)
              (i-charts/add-lines :t :Qk+1))
            (incanter/view)
            #_(incanter/save
               "/home/nathan/smbshare/imgs/test.png"))))))

(comment

  (binding [*ds* (atom [])
            *consts* {:gain 1/10
                      :buffer-size 100
                      :Qref 50
                      :Ts 200
                      :control-fn pi-pd}]
    (simulate+graph 5000)))
