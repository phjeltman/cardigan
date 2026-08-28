# TODO

## Revisit async database support on Java 27

After Java 27 is generally available benchmark pg-java-backed HttpArena `async-db` with
`-Djdk.pollerMode=3`. This mode assigns read pollers per carrier and may fit Cardigan's
thread-per-core event-loop topology better than the polling modes available in Java 26.
