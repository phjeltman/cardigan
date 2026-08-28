# TODO

## Revisit HttpArena async database support on Java 27

After Java 27 is generally available in HttpArena's build and runtime images,
benchmark pg-java-backed `async-db` with `-Djdk.pollerMode=3`. This mode assigns
read pollers per carrier and may fit Cardigan's thread-per-core event-loop
topology better than the polling modes available in Java 26.

Reconsider adding the `async-db` profile to the HttpArena submission only after
that configuration has been profiled and shown to be competitive.
