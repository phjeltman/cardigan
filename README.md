# Cardigan

Cardigan is an experimental Java HTTP server built around direct io_uring,
Panama FFI and memory access, the Java Vector API, and virtual threads. It
currently implements HTTP/1.1, cleartext and ALPN-negotiated HTTP/2, streaming
request and response bodies, and an experimental OpenSSL/kTLS transport.

The project is deliberately Linux-specific and is not yet a production-ready
framework. The current focus is validating its execution model, protocol
correctness, resource bounds, and performance.

## Requirements

- 64-bit Linux; x86-64 is the regularly exercised target.
- Linux 6.1 or newer. Cardigan requires `IORING_SETUP_SINGLE_ISSUER` and
  `IORING_SETUP_DEFER_TASKRUN`, registered files, provided-buffer rings,
  multishot accept/receive, pinned event loops, and its TCP listener options.
  It will not start with a degraded transport. Direct kTLS remains an
  optional capability.
- JDK 26 with the incubating Vector API.
- Maven 3.9 or newer.
- OpenSSL 3 at runtime when TLS is enabled.
- `wrk` and `h2load` for live benchmarks; `h2spec` for HTTP/2 conformance.

## Build and test

Compile and run the portable unit tier:

```sh
mvn test
```

Live integration tests require a compatible Linux/io_uring host:

```sh
mvn -Pintegration-tests test
```

TLS and deliberately heavy stress tests can be selected independently:

```sh
mvn -Ptls-tests test
mvn -Pstress-tests test
mvn -Padvanced-tls-tests test
mvn -Pall-tests test
```

## Run

```sh
./dev/example-server/run.sh
```

The development executable starts demonstration and benchmark routes on port
8080. Its classes live under `dev/example-server` and are not part of the
framework artifact. Consumers configure their own listener and route set:

```java
try (CardiganServer server = CardiganServer.builder()
        .port(8080)
        .eventLoops(4)
        .protocol(ProtocolMode.HTTP1_AND_HTTP2)
        .routes(new ApplicationController())
        .build()) {
    server.start();
    Thread.currentThread().join();
}
```

Event loops are assigned one hardware thread from each physical core before
SMT siblings are used, always within the process affinity mask. Applications
that need an exact NUMA or NIC-queue placement can replace `.eventLoops(4)`
with `.eventLoopCpus("0,2,4,6")`; the same override is available through
`-Dcardigan.eventloop.cpus=0,2,4,6`.

The server installs no implicit routes. TLS is selected with `.tls(config)`;
the example launcher alone opts into `.tlsFromSystemProperties()` for script
compatibility. The HttpArena consumer is an independent Maven project under
[`dev/httparena`](dev/httparena/README.md).

Small, bounded binary protocol messages can bind a sole primitive `long`
handler argument with `@DecodedBody(SomeLongBodyDecoder.class)`. The decoder
runs against the complete buffered body on the connection owner before
handover, avoiding request retention and copying. It must be bounded,
non-blocking, thread-safe, and must not retain the supplied memory segment.

## Benchmark and conformance

```sh
./dev/benchmarks/benchmark.sh --cpus=2 --duration=10s --endpoint=1
./dev/benchmarks/benchmark.sh --http2 --http2-streams=16 --cpus=2 --duration=10s --endpoint=1
./dev/benchmarks/benchmark.sh --tls --http2 --http2-streams=16 --cpus=2 --duration=10s --endpoint=1
./dev/verification/h2spec.sh
```

`dev/benchmarks/benchmark.sh --help` documents protocol, pipeline, streaming,
microbenchmark and profiling modes. Benchmark results are meaningful only when
the server and client CPU placement, protocol concurrency and payload are kept
comparable.

Microbenchmark and probe sources live outside the runtime artifact under
`dev/benchmarks/src/main/java`. They are compiled by the benchmark script, or explicitly with:

```sh
mvn -f dev/pom.xml -pl :cardigan-benchmarks -am test
```

## Documentation

- [DOCUMENTATION](DOCUMENTATION.md) — execution model, API contracts,
  configuration, TLS, validation and publication
- [APOLOGY](APOLOGY.md) — why the experiment deliberately uses sharp edges
- [RATIONALE](RATIONALE.md) — the architectural thesis behind Cardigan

## Status

The wire protocols and core transport have substantial correctness and stress
coverage, but the public API, configuration surface, packaging and operational
diagnostics are still evolving. Bidirectional gRPC-style handler streaming is
currently a non-goal; handlers consume a request body before returning their
response.

## License

Cardigan-authored source is licensed under the
[Mozilla Public License 2.0](LICENSE). Ports and adaptations of third-party
code retain their applicable upstream licenses and notices; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
