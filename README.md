[about]: ./docs/ABOUT.md
[cache-effect]: https://github.com/resilience4clj/resilience4clj-cache#using-as-an-effect
[circleci-badge]: https://circleci.com/gh/resilience4clj/resilience4clj-circuitbreaker.svg?style=shield&circle-token=3f807e42b18707a5a9b2a8b0e59561af1a4a1b67
[circleci]: https://circleci.com/gh/resilience4clj/resilience4clj-circuitbreaker
[clojars-badge]: https://img.shields.io/clojars/v/resilience4clj/resilience4clj-circuitbreaker.svg
[clojars]: http://clojars.org/resilience4clj/resilience4clj-circuitbreaker
[github-issues]: https://github.com/resilience4clj/resilience4clj-circuitbreaker/issues
[license-badge]: https://img.shields.io/badge/license-MIT-blue.svg
[license]: ./LICENSE
[status-badge]: https://img.shields.io/badge/project%20status-alpha-brightgreen.svg
[time-limiter]: https://github.com/resilience4clj/resilience4clj-timelimiter/

# Resilience4Clj Circuit Breaker

[![CircleCI][circleci-badge]][circleci]
[![Clojars][clojars-badge]][clojars]
[![License][license-badge]][license]
![Status][status-badge]

Resilience4clj is a lightweight fault tolerance library set built on
top of GitHub's Resilience4j and inspired by Netflix Hystrix. It was
designed for Clojure and functional programming with composability in
mind.

Read more about the [motivation and details of Resilience4clj
here][about].

Resilience4Clj circuit breaker lets you decorate a function call
(usually with a potential of external failure) with a safety mechanism
to interrupt the propagation of failures. Once a specified failure
rate is reached, the "circuit" is open and no more calls go through
until a "grace" period has passed.

## Benefits Inherited from Resilience4j

Resilience4Clj circuit breaker is implemented on top of GitHub's
Resilience4j and therefore inherets some of its benefits:

* Storage of call results in a Ring Bit Buffer without a statistical
  rolling time window. A successful call is stored as a `0 bit` and a
  failed call is stored as a `1 bit`. The Ring Bit Buffer has a
  configurable fixed-size and stores the bits in a long[] array.  This
  saves memory compared to a boolean array (example: the Ring Bit
  Buffer only needs an array of 16 long values - 64-bit each - to
  store the status of 1024 calls.
* The advantage is that this Circuit Breaker works out-of-the-box for
  low and high frequency backend systems, because execution results
  are not dropped when a time window is passed.
* To determine whether a Circuit Breaker can be closed again once it
  reaches the half-open state, this library allows to perform a
  configurable number of executions and compares the results against a
  configurable threshold.

Refer to [Implementation Details](#implementation-details) for a
deeper look at the power behind Resilience4Clj.

## Table of Contents

* [Getting Started](#getting-started)
* [Circuit Breaker Settings](#circuit-breaker-settings)
* [Fallback Strategies](#fallback-strategies)
* [Effects](#effects)
* [Metrics](#metrics)
* [Events](#events)
* [Exception Handling](#exception-handling)
* [Composing Further](#composing-further)
* [Implementation Details](#implementation-details)
* [Bugs](#bugs)
* [Help!](#help)

## Getting Started

Add `resilience4clj/resilience4clj-circuitbreaker` as a dependency to
your `deps.edn` file:

``` clojure
resilience4clj/resilience4clj-circuitbreaker {:mvn/version "0.1.3"}
```

If you are using `lein` instead, add it as a dependency to your
`project.clj` file:

``` clojure
[resilience4clj/resilience4clj-circuitbreaker "0.1.3"]
```

Require the library:

``` clojure
(require '[resilience4clj-circuitbreaker.core :as cb])
```

Then create a circuit breaker calling the function `create`. We give
it a reference name so that we can later identify any event in case
you are interested in the health of your breaker:

``` clojure
(def breaker (cb/create "my-first-breaker"))
```

Now you can decorate any function you have with the circuit breaker
you just defined.

For the sake of this example, let's create a function that fails if we
tell it to, otherwise it's a simple "Hello World":

``` clojure
(defn may-fail-hello
  ([]
   "Hello World!")
  ([fail?]
   (throw (ex-info "Hello service failed!" {:reason :was-told-to}))))
```

The function `decorate` will take the potentially failing function
just above and the circuit breaker you created and return a protected
function:

``` clojure
(def protected-hello (cb/decorate may-fail-hello breaker))
```

When you call `protected-hello`, it should eval to `"Hello World!"` as
you would expect:

``` clojure
(protected-hello) ;; => "Hello World!"
```

When you extract the metrics from your circuit break (function
`metrics`), this is what you get:

``` clojure
(cb/metrics breaker)
=> {:failure-rate -1.0,
    :number-of-buffered-calls 1,
    :number-of-failed-calls 0,
    :number-of-not-permitted-calls 0,
    :max-number-of-buffered-calls 100,
    :number-of-successful-calls 1}
```

The key `:number-of-successful-calls` shows that your breaker has had
`1` successful call.

Let's force a failure:

``` clojure
(protected-hello true) ;; => throws ExceptionInfo "Hello service failed!"
```

Now extracting the metrics would give you:

``` clojure
(cb/metrics breaker)
=> {:failure-rate -1.0,
    :number-of-buffered-calls 2,
    :number-of-failed-calls 1,
    :number-of-not-permitted-calls 0,
    :max-number-of-buffered-calls 100,
    :number-of-successful-calls 1}
```

The key `:number-of-failed-calls` went from `0` to `1` and
`:number-of-buffered-calls` from `1` to `2`.

By default, the breaker will only open with a failure rate of
50%. Failure rates are only calculated once the full bit ring is
completed (default it 100 calls). Threfore, in order to open the
breaker, one needs a series of failures:

``` clojure
(cb/state breaker) ;;=> :CLOSED

(dotimes [n 98]
  (try
    (protected-hello true)
    (catch Exception e)))

(cb/metrics breaker)

=> {:failure-rate 99.0,
    :number-of-buffered-calls 100,
    :number-of-failed-calls 99,
    :number-of-not-permitted-calls 2,
    :max-number-of-buffered-calls 100,
    :number-of-successful-calls 1}

(cb/state breaker) ;;=> :OPEN
```

Now that the breaker is open, any call to `protected-hello` will throw
a `CircuitBreakerOpenException` exception:

``` clojure
(protected-hello) ;;=> throws CircuitBreakerOpenException
```

You will need to wait 1 minute [configurable] for the circuit breaker
to transition from `:OPEN` to `:HALF_OPEN` (where it will tentatively
decide whether the backend is back online).

Refer to [Exception Handling](#exception-handling) below for more
details.

## Circuit Breaker Settings

When creating a circuit breaker, you can fine tune its settings with
these:

1. `:ring-buffer-size-in-closed-state` - the size of the main ring
   buffer. The ring needs to be filled before failure rates are
   calculated. Default `100`.
2. `:failure-rate-threshold` - the failure rate at which the breaker
   will open (in percent). Once the circuit breaker transitions from
   open to half-open, this failure rate is used to decide whether the
   circuit is good to be closed again (below failure rate) or to
   remain open (above failure rate). Default `50.0`.
3. `:ring-buffer-size-in-half-open-state` - the size of the half-open
   state ring buffer. This ring buffer is used when the breaker
   transitions from open to half-open to decide whether the circuit is
   healthy or not. It's usually smaller than the main ring
   buffer. Default `10`.
4. `:wait-duration-in-open-state` - the time in milliseconds that the
   breaker should wait before transitioning from open to
   half-open. Default `60000` (1 minute).
5. `:automatic-transition-from-open-to-half-open-enabled?` - if set to
   `true` it means that the breaker will automatically transition from
   open to half open state without any waiting time. Default `false`.

These two options can be sent to `create` as a map. In the following
example, any function decorated with `breaker` will have a ring buffer
of `20` and a failure rate of `10.0`:

``` clojure
(def breaker (cb/create "MyBreaker" {:ring-buffer-size-in-closed-state 20
                                     :failure-rate-threshold 10.0}))
```

The function `config` returns the configuration of a breaker in case
you need to inspect it. Example:

``` clojure
(cb/config breaker)
=> {:failure-rate-threshold 10.0,
    :ring-buffer-size-in-closed-state 20,
    :ring-buffer-size-in-half-open-state 10,
    :wait-duration-in-open-state 60000,
    :automatic-transition-from-open-to-half-open-enabled? false}
```

## Fallback Strategies

When decorating your function with a circuit breaker you can opt to
have a fallback function. This function will be called instead of an
exception being thrown both when the circuit breaker is open or when
the call would fail (traditional throw). This feature can be seen as
an obfuscation of a try/catch to consumers.

This is particularly useful if you want to obfuscate from consumers
that the circuit breaker is open and/or that the external dependency
failed. Example:

``` clojure
(def breaker (cb/create "hello-service"))

(defn hello [person]
  (str "Hello " person))

(def protected-hello
  (cb/decorate hello breaker
               {:fallback (fn [e person]
                            (str "Hello from fallback to " person))}))
```

The signature of the fallback function is the same as the original
function plus an exception as the first argument (`e` on the example
above). This exception is an `ExceptionInfo` wrapping around the real
cause of the error. You can inspect the `:cause` node of this
exception to learn about the inner exception:

``` clojure
(defn fallback-fn [e]
  (str "The cause is " (-> e :cause)))
```

For more details on [Exception Handling](#exception-handling) see the
section below.

When considering fallback strategies there are usually three major
strategies:

1. **Failure**: the default way for Resilience4clj - just let the
   exceptiohn flow - is called a "Fail Fast" approach (the call will
   fail fast once the breaker is open). Another approach is "Fail
   Silently". In this approach the fallback function would simply hide
   the exception from the consumer (something that can also be done
   conditionally).
2. **Content Fallback**: some of the examples of content fallback are
   returning "static content" (where a failure would always yield the
   same static content), "stubbed content" (where a failure would
   yield some kind of related content based on the paramaters of the
   call), or "cached" (where a cached copy of a previous call with the
   same parameters could be sent back).
3. **Advanced**: multiple strategies can also be combined in order to
   create even better fallback strategies.

For more details on some of these strategies, read the section
[Effects](#effects) below.

## Effects

A common issue for some fallback strategies is to rely on a cache or
other content source (see Content Fallback above). In these cases, it
is good practice to persist the successful output of the function call
as a side-effect of the call itself.

Resilience4clj retry supports this behavior in the folling way:

``` clojure
(def breaker (cb/create "hello-service"))

(defn hello [person]
  (str "Hello " person))

(def protected-hello
  (cb/decorate hello breaker
               {:effect (fn [ret person]
                          ;; ret will have the successful return from `hello`
                          ;; you can save it on a memory cache, disk, etc
                          )}))
```

The signature of the effect function is the same as the original
function plus a "return" argument as the first argument (`ret` on the
example above). This argument is the successful return of the
encapsulated function.

The effect function is called on a separate thread so it is
non-blocking.

You can see an example of how to use effects for caching purposes at
[using Resilience4clj cache as an effect][cache-effect].

## Metrics

The function `metrics` returns a map with the metrics of the circuit breaker:

``` clojure
(cb/metrics breaker)

=> {:failure-rate 20.0,
    :number-of-buffered-calls 100,
    :number-of-failed-calls 20,
    :number-of-not-permitted-calls 0,
    :max-number-of-buffered-calls 100,
    :number-of-successful-calls 80}
```

The nodes should be self-explanatory. The trickiest one is
`:failure-rate`. It is only computed once the ring buffer is full (in
the example above it is because `80` out of `100` calls succeeded and
`20` out of `100` failed therefore the failure rate is `20.0`. If the
ring buffer was not full, failure rate would be "not-computed" which
is indicated as `-1.0`.

Another interesting node is `:number-of-not-permitted-calls`. It shows
the number of calls that were "blocked" by the circuit breaker when it
was open. In the above example, no call has been rejected.

Another useful metadata function is `state`. It returns the current
state of the circuit breaker:

``` clojure
(cb/state breaker) ;;=> :CLOSED
```

There are three normal states: `:CLOSED`, `:OPEN` and `:HALF_OPEN` and
two special states `:DISABLED` and `:FORCED_OPEN`. For more details
about them read [implementation details](#implementation-details)
below.

The state, ring buffer, and metrics can be reset with a call to the
`reset!` function:

``` clojure
(cb/reset! breaker)
```

## Events

You can listen to events generated by your circuit breakers. This is
particularly useful for logging, debugging, or monitoring the health
of your breakers.

``` clojure
(def breaker (cb/create "my-breaker"))

(cb/listen-event breaker
                 (fn [evt]
                   (println (str "Received event " (:event-type evt)))))
```

There are six types of events:

1. `:SUCCESS` - the call has been successful
2. `:ERROR` - the call has failed
3. `:NOT_PERMITTED` - the call has been rejected due to an open
   breaker
4. `:STATE_TRANSITION` - the breaker transitioned from one state to
   another
5. `:IGNORED_ERROR` - an error has been ignored by the circuit breaker
6. `:RESET` - the circuit breaker has been reset

Alternatively to listening to all events as we have done before, you
can listen to one specific type of event by specifyin the event-type
you want to:

``` clojure
(def breaker (cb/create "my-breaker"))

(cb/listen-event breaker
                 :ERROR
                 (fn [evt]
                   (println "An error has occurred")))
```

All events receive a map containing the `:event-type`, the
`:circuit-breaker-name` and the event `:creation-time`. Error events
also carry a node `:throwable` containing a copy of the exception
thrown. Successful and error events have `:ellapsed-duration` in
nanoseconds. Ultimately, state transition events also have
`:from-state` and `:to-state`.

## Exception Handling

If you are not using a fallback function (see [Fallback
Strategies](#fallback-strategies) for more details) and the circuit
breaker is open, an instance of `CircuitBreakerOpenException` will be
thrown. If this is too intrusive on your systems, do consider using a
fallback function instead.

When using the fallback function, be aware that its signature is the
same as the original function plus an exception (`e` on the example
above). This exception is an `ExceptionInfo` wrapping around the real
cause of the error. You can inspect the `:cause` node of this
exception to learn about the inner exception:

## Composing Further

Resilience4clj is composed of [several modules][about] that easily
compose together. For instance, if you are also using the [time
limiter][time-limiter] and assuming your import and basic settings
look like this:

``` clojure
(ns my-app
  (:require [resilience4clj-circuitbreaker.core :as cb]
            [resilience4clj-timelimiter.core :as tl]))

;; create time limiter with default settings
(def limiter (tl/create))

;; create circuit breaker with default settings
(def breaker (cb/create "HelloService"))

;; slow function you want to limit
(defn slow-hello []
  (Thread/sleep 1500)
  "Hello World!")
```

Then you can create a protected call that combines both the time
limiter and the circuit breaker:

``` clojure
(def protected-hello (-> slow-hello
                         (tl/decorate limiter)
                         (cb/decorate breaker)))
```

The resulting function on `protected-hello` will trigger the breaker
in case of a timeout now.

## All Modules

TBD: list and links to all modules

## Implementation Details

The Circuit Breaker is implemented via a finite state machine with
three normal states: `:CLOSED`, `:OPEN` and `:HALF_OPEN` and two
special states `:DISABLED` and `:FORCED_OPEN`.

![State Machine](./docs/state_machine.jpg)

The state of the Circuit Breaker changes from `:CLOSED` to `:OPEN`
when the failure rate is above a [configurable] threshold. Then, all
access to the function call is blocked for a [configurable] amount of
time.

The Circuit Breaker uses a Ring Bit Buffer in the `:CLOSED` state to
store the success or failure statuses of the calls. A successful call
is stored as a `0 bit` and a failed call is stored as a `1 bit`. The
Ring Bit Buffer has a [configurable] fixed-size. The Ring Bit Buffer
uses internally a BitSet-like data structure to store the bits which
is saving memory compared to a boolean array. The BitSet uses a long[]
array to store the bits. That means the BitSet only needs an array of
16 long (64-bit) values to store the status of 1024 calls.

The following diagram shows what a Ring Buffer would look like for
only 12 results:

![Ring Buffer](./docs/ring_buffer.jpg)

The Ring Bit Buffer must be full, before the failure rate can be
calculated.  For example, if the size of the Ring Buffer is 10, then
at least 10 calls must evaluated, before the failure rate can be
calculated. If only 9 calls have been evaluated the CircuitBreaker
will not trip open even if all 9 calls have failed.

After the time duration has elapsed, the Circuit Breaker state changes
from `:OPEN` to `:HALF_OPEN` and allows calls to see if the backend is
still unavailable or has become available again. The Circuit Breaker
uses another [configurable] Ring Bit Buffer to evaluate the failure
rate in the `:HALF_OPEN` state. If the failure rate is above the
configured threshold, the state changes back to `:OPEN`. If the
failure rate is below or equal to the threshold, the state changes
back to `:CLOSED`.

The Circuit Breaker supports resetting to its original state, losing
all the metrics and effectively resetting its Ring Bit Buffer.

The Circuit Breaker supports two more special states, `:DISABLED` (always
allow access) and `:FORCED_OPEN` (always deny access). In these two
states no Circuit Breaker events (apart from the state transition) are
generated, and no metrics are recorded. The only way to exit from
those states are to trigger a state transition or to reset the Circuit
Breaker.

## Bugs

If you find a bug, submit a [Github issue][github-issues].

## Help

This project is looking for team members who can help this project
succeed!  If you are interested in becoming a team member please open
an issue.

## License

Copyright © 2019 Tiago Luchini

Distributed under the MIT License.
