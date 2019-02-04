[circuitbreaker]: https://github.com/luchiniatwork/resilience4clj-circuitbreaker
[timelimiter]: https://github.com/luchiniatwork/resilience4clj-timelimiter
[cache]: https://github.com/luchiniatwork/resilience4clj-cache
[retry]: https://github.com/luchiniatwork/resilience4clj-retry

# What is Resilience4clj?

Resilience4clj is a lightweight fault tolerance set of libraries built
on top of GitHub's Resilience4j and inspired by Netflix Hystrix. It
was designed for Clojure and functional programming with composability
in mind.

The three core benefits of Resilience4clj are:

1. The libraries have very few dependencies making them extremely
   lightweight.
2. You don’t have to go all-in; you can pick what features you need
   (See [All modules](#all-modules) for a list of what you can cherry
   pick from).
3. Functional and highly composable interfaces by default. You can mix
   and match as you see fit.

# Motivation

Resiliency is the ability to recover from failures and continue to
function. It is not about avoiding failures but accepting the fact
that failures will happen and responding to them in a way that avoids
downtime or data loss. The goal of resiliency is to return the
application to a fully functioning state after a failure.

Building resilient systems requires inculcating a strong sense of
acceptance of failures to the team since the very first day of a
project. The team will need to create solutions to make sure that the
system either recovers from failures or that it still functions
despite ongoing failures.

Some of these potential failures may involve lost computing or data
nodes, inaccessible clusters, network losses, among many
other. Sometimes there are ripple effects due to natural latency of
the involved dependencies.

Most complex systems have several (sometimes hundreds) points of
access to remote systems, services, APIs, and 3rd party libraries. Any
one (or many) of these may fail or be slow to respond. In order to
reach a level of acceptable resilience it is important to isolate
these issues and stop cascading them through the bigger system.

> Resilience4clj enables resilience in complex distributed systems
> where failure is inevitable.

# All Modules

* [resilience4clj-circuitbreaker][circuitbreaker] - lets you protect
  an external call with a safety mechanism to interrupt the
  propagation of failures.
* [resilience4clj-timelimiter][timelimiter] - lets you decorate an
  external call with a specified time limit.
* [resilience4clj-cache][cache] - lets you create distributed memoized
  copies of your exchanged payloads in order to reduce failure and
  latency impacts.
* [resilience4clj-retry][retry] - lets you estabilish rules for
  retrying failed calls.
