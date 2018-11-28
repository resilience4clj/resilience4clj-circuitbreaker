(ns resilience4clj-circuibreaker.core
  (:import
   (io.github.resilience4j.circuitbreaker CircuitBreakerConfig
                                          CircuitBreaker)
   (java.util.function Supplier)
   (io.vavr.control Try)))

(defn ^:private get-failure-handler [{:keys [fallback]}]
  (if fallback
    (fn [e] (fallback e))
    (fn [e] (throw e))))

                                        ; FIXME: needs to deal with CircuitBreakerEventListener, Predicate and
                                        ; undocumented isAutomaticTransitionFromOpenToHalfOpenEnabled
(defn ^:private get-circuit-breaker-config
  "the failure rate threshold in percentage above which the
  CircuitBreaker should trip open and start short-circuiting calls

  the wait duration which specifies how long the CircuitBreaker should
  stay open, before it switches to half open

  the size of the ring buffer when the CircuitBreaker is half open

  the size of the ring buffer when the CircuitBreaker is closed

  a custom CircuitBreakerEventListener which handles CircuitBreaker
  events

  a custom Predicate which evaluates if an exception should be
  recorded as a failure and thus increase the failure rate"
  [{:keys [failure-rate-threshold
           ring-buffer-size-in-closed-state
           ring-buffer-size-in-half-open-state
           wait-duration-in-open-state]}]
  (println "AQUI")
  (.build
   (doto (CircuitBreakerConfig/custom)
     (#(if failure-rate-threshold
         (.failureRateThreshold % failure-rate-threshold) %))
     (#(if ring-buffer-size-in-closed-state
         (.ringBufferSizeInClosedState % ring-buffer-size-in-closed-state) %))
     (#(if ring-buffer-size-in-half-open-state
         (.ringBufferSizeInHalfOpenState % ring-buffer-size-in-half-open-state) %))
     #_(#(if wait-duration-in-open-state
           (.waitDurationInOpenState % wait-duration-in-open-state) %))
     .build)))

;; FIXME: probably create a "datafy" kind of interface to and from CircuitBreakerConfig


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create
  ([n]
   (create n nil))
  ([n opts]
   (if opts
     (CircuitBreaker/of ^String n ^CircuitBreakerConfig (get-circuit-breaker-config opts))
     (CircuitBreaker/ofDefaults n))))

(defn decorate
  ([f cb]
   (decorate f cb nil)) 
  ([f cb opts]
   (fn [& args]
     (let [supplier (reify Supplier
                      (get [this] (apply f args)))
           decorated-supplier (CircuitBreaker/decorateSupplier cb supplier)
           failure-handler (get-failure-handler opts)
           result (Try/ofSupplier decorated-supplier)]
       (if (.isSuccess result)
         (.get result)
         (failure-handler (.getCause result)))))))

(defn metrics
  "the failure rate in percentage.
  the current number of buffered calls.
  the current number of failed calls."
  [cb]
  (let [metrics (.getMetrics cb)]
    {:failure-rate             (.getFailureRate metrics)
     :number-of-buffered-calls (.getNumberOfBufferedCalls metrics)
     :number-of-failed-calls   (.getNumberOfFailedCalls metrics)}))

(defn reset
  [cb]
  (.reset cb))

(defn info
  [cb]
  (let [config (.getCircuitBreakerConfig cb)]
    {:failure-rate-threshold (.getFailureRateThreshold config)
     :ring-buffer-size-in-closed-state (.getRingBufferSizeInClosedState config)
     :ring-buffer-size-in-half-open-state (.getRingBufferSizeInHalfOpenState config)
     :wait-duration-in-open-state (.getWaitDurationInOpenState config)
     :record-failure-predicate (.getRecordFailurePredicate config)
     :automatic-transition-from-open-to-half-open-enabled? (.isAutomaticTransitionFromOpenToHalfOpenEnabled config)}))






(comment
  ;; set up a breaker for the service (possibly from states/mount)
  (def service-breaker (create "MyService"))

  ;; mock for an external call
  (defn external-call []
    "Does external call here")

  ;; circuit breaker protecetd function
  (def protected-call (decorate external-call
                                service-breaker))

  ;; call the protected version (will throw if failure)
  (protected-call) ;; => "Does external call here"

  (dotimes [n 10]
    (protected-call))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  (defn external-call-failure []
    (throw (ex-info "Nope!" {})))

  ;; protected with fallback (will return fallback if failure/timeout/open cb/etc)
  (def protected-call-safe (decorate external-call-failure
                                     service-breaker
                                     {:fallback (fn [e] (str "Default for service! " (.getMessage e)))}))

  ;; call the protected version
  (protected-call-safe) ;; => "Default for service" (if failure)

  (dotimes [n 100]
    (protected-call-safe))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  (metrics service-breaker)

  (reset service-breaker)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  ;; create a breaker with custom settigns
  (def service-breaker2 (create "MyService2"
                                {:failure-rate-threshold 20.0
                                 :ring-buffer-size-in-closed-state 2
                                 :ring-buffer-size-in-half-open-state 2
                                 :wait-duration-in-open-state 1000}))


  (info service-breaker2)
  )
