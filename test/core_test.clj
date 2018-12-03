(ns core-test
  (:require [resilience4clj-circuitbreaker.core :as breaker]
            [clojure.test :refer :all]))

;; mock for an external call
(defn ^:private external-call
  ([n]
   (external-call n nil))
  ([n {:keys [fail? wait]}]
   (when wait
     (Thread/sleep wait))
   (if-not fail?
     (str "Hello " n "!")
     (throw (ex-info "Couldn't say hello" {:extra-info :here})))))

(defn ^:private rand-str []
  (let [size (inc (rand-int 49))]
    (loop [s ""]
      (if (= (count s) size)
        s (recur (str s (char (+ 32 (rand-int 94)))))))))

(deftest breaker-creation
  (testing "default config"
    (let [cb (breaker/create "MyService")]
      (is (= {:automatic-transition-from-open-to-half-open-enabled? false
              :failure-rate-threshold 50.0
              :ring-buffer-size-in-closed-state 100
              :ring-buffer-size-in-half-open-state 10
              :wait-duration-in-open-state 60000}
             (breaker/config cb)))))

  (testing "custom config"
    (let [cb (breaker/create "MyService" {:automatic-transition-from-open-to-half-open-enabled? true
                                          :failure-rate-threshold 20.0
                                          :ring-buffer-size-in-closed-state 2
                                          :ring-buffer-size-in-half-open-state 2
                                          :wait-duration-in-open-state 10000})]
      (is (= {:automatic-transition-from-open-to-half-open-enabled? true
              :failure-rate-threshold 20.0
              :ring-buffer-size-in-closed-state 2
              :ring-buffer-size-in-half-open-state 2
              :wait-duration-in-open-state 10000}
             (breaker/config cb))))))

(deftest breaks-and-metrics-work
  (testing "simple successful series of calls"
    (let [cb (breaker/create "MyService" {:wait-duration-in-open-state 1000})
          decorated (breaker/decorate external-call cb)]
      (is (= {:failure-rate -1.0
              :max-number-of-buffered-calls 100
              :number-of-buffered-calls 0
              :number-of-failed-calls 0
              :number-of-not-permitted-calls 0
              :number-of-successful-calls 0}
             (breaker/metrics cb)))
      (is (= :CLOSED
             (breaker/state cb)))
      (dotimes [n 50]
        (decorated "World"))
      (is (= {:failure-rate -1.0
              :max-number-of-buffered-calls 100
              :number-of-buffered-calls 50
              :number-of-failed-calls 0
              :number-of-not-permitted-calls 0
              :number-of-successful-calls 50}
             (breaker/metrics cb)))
      (is (= :CLOSED
             (breaker/state cb)))
      (dotimes [n 50]
        (decorated "World"))
      (is (= {:failure-rate 0.0
              :max-number-of-buffered-calls 100
              :number-of-buffered-calls 100
              :number-of-failed-calls 0
              :number-of-not-permitted-calls 0
              :number-of-successful-calls 100}
             (breaker/metrics cb)))
      (is (= :CLOSED
             (breaker/state cb)))))

  (testing "reseting metrics"
    (let [cb (breaker/create "MyService" {:wait-duration-in-open-state 1000})
          decorated (breaker/decorate external-call cb)]
      (dotimes [n 50]
        (decorated "World")) 
      (is (= {:failure-rate -1.0
              :max-number-of-buffered-calls 100
              :number-of-buffered-calls 50
              :number-of-failed-calls 0
              :number-of-not-permitted-calls 0
              :number-of-successful-calls 50}
             (breaker/metrics cb)))
      (breaker/reset cb)
      (is (= {:failure-rate -1.0
              :max-number-of-buffered-calls 100
              :number-of-buffered-calls 0
              :number-of-failed-calls 0
              :number-of-not-permitted-calls 0
              :number-of-successful-calls 0}
             (breaker/metrics cb)))))

  (testing "breakeage and return from breakeage"
    (let [cb (breaker/create "MyService" {:wait-duration-in-open-state 1000})
          decorated (breaker/decorate external-call cb)]
      (dotimes [n 100]
        (decorated "World"))
      (dotimes [n 20]
        (try
          (decorated "World" {:fail? true})
          (catch Throwable _)))
      (is (= {:failure-rate 20.0
              :max-number-of-buffered-calls 100
              :number-of-buffered-calls 100
              :number-of-failed-calls 20
              :number-of-not-permitted-calls 0
              :number-of-successful-calls 80 ;;within the ring of 100
              }
             (breaker/metrics cb)))
      ;; it should still be closed... threshold not met
      (is (= :CLOSED
             (breaker/state cb)))
      (dotimes [n 20]
        (try
          (decorated "World" {:fail? true})
          (catch Throwable _)))
      (is (= {:failure-rate 40.0
              :max-number-of-buffered-calls 100
              :number-of-buffered-calls 100
              :number-of-failed-calls 40
              :number-of-not-permitted-calls 0
              :number-of-successful-calls 60 ;;the successful ones are going dowm
              }
             (breaker/metrics cb)))
      ;; still closed
      (is (= :CLOSED
             (breaker/state cb)))
      (dotimes [n 20]
        (try
          (decorated "World" {:fail? true})
          (catch Throwable _)))
      (is (= {:failure-rate 50.0 ;;caps at the limit of 50
              :max-number-of-buffered-calls 100
              :number-of-buffered-calls 100
              :number-of-failed-calls 50 ;; only 50, the other 10 were not permitted
              :number-of-not-permitted-calls 10
              :number-of-successful-calls 50 ;;the successful ones are going dowm
              }
             (breaker/metrics cb)))
      ;; now it's open
      (is (= :OPEN
             (breaker/state cb)))
      (dotimes [n 20]
        (try
          (decorated "World" {:fail? true})
          (catch Throwable _)))
      (is (= {:failure-rate 50.0 ;;caps at the limit of 50
              :max-number-of-buffered-calls 100
              :number-of-buffered-calls 100
              :number-of-failed-calls 50 ;; only 50, the other were not permitted
              :number-of-not-permitted-calls 30
              :number-of-successful-calls 50 ;;the successful ones are going dowm
              }
             (breaker/metrics cb)))
      ;; service restored
      (dotimes [n 20]
        (try
          (decorated "World")
          (catch Throwable _)))
      (is (= {:failure-rate 50.0 ;;caps at the limit of 50
              :max-number-of-buffered-calls 100
              :number-of-buffered-calls 100
              :number-of-failed-calls 50 ;; only 50, the other were not permitted
              :number-of-not-permitted-calls 50 ;; still blocking
              :number-of-successful-calls 50 ;;the successful ones are going dowm
              }
             (breaker/metrics cb)))
      ;; because it's still open
      (is (= :OPEN
             (breaker/state cb)))
      ;; let's wait 1150ms for the timeout on the circuit to pass
      (Thread/sleep 1150)
      (is (= :OPEN
             (breaker/state cb)))
      ;; tiny succseful call
      (decorated "World")
      ;; should be half open now
      (is (= :HALF_OPEN
             (breaker/state cb)))
      ;; metrics should be back to zero
      (is (= {:failure-rate -1.0
              :max-number-of-buffered-calls 10
              :number-of-buffered-calls 1
              :number-of-failed-calls 0
              :number-of-not-permitted-calls 0
              :number-of-successful-calls 1
              }
             (breaker/metrics cb))))))


#_(deftest fallback-function)

#_(deftest breaker-events)
