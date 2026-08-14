# Development workspace

Everything under this directory is repository tooling, not part of the
`dev.cardigan:cardigan` artifact.

- `example-server` supplies the executable and routes used by smoke tests and
  live benchmarks.
- `benchmarks` contains microbenchmarks, live-load scripts, and probes.
- `httparena` is the independent HttpArena consumer.
- `verification` contains external conformance runners.
- `tools` contains contributor-only build, profiling, and smoke-test helpers.
- `EGRESS_BUFFER_POOL.md` records the planned hierarchical shared egress-pool
  design.

Build the framework and all Maven-based development consumers with:

```sh
mvn -f dev/pom.xml test
```

The development reactor is deliberately not a parent of the framework POM.
No development project is deployable, and the root project remains the only
Maven Central component.
