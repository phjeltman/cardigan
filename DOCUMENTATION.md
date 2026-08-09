# Cardigan documentation

Cardigan supports HTTP/1.1 and HTTP/2, with optional TLS and kTLS acceleration
when the host stack can provide it. It is an experimental, Linux-specific
framework requiring JDK 26, the incubating Vector API, native access, OpenSSL 3
for TLS, and a Linux kernel with the io_uring features listed below.

## Transport and execution model

Cardigan drives io_uring directly through Panama. Ring geometry and mapping
offsets come from the kernel UAPI, and Java acquire/release accesses synchronize
the submission and completion rings.

Every event loop is pinned to an allowed CPU, owns its connections and native
resources, and runs application exchanges on virtual threads. Physical cores
are selected before SMT siblings. Exact placement can be supplied with
`eventLoopCpus("0,2,4,6")` or `cardigan.eventloop.cpus`.

The server requires `IORING_SETUP_SINGLE_ISSUER`, `IORING_SETUP_SUBMIT_ALL`,
`IORING_SETUP_DEFER_TASKRUN`, `IORING_SETUP_TASKRUN_FLAG`, registered files,
provided-buffer rings, and multishot accept and receive. Cardigan does not
select a slower fallback when one of these required operations is rejected.

The ring owner is also the sole carrier for that loop's ordinary virtual
threads. Mounted virtual threads, protocol continuations, completion-unblocked
continuations, handler workers, and egress connections are separate local
scheduler lanes on that carrier. Turns are bounded so a yielded continuation
cannot keep deferred kernel task work or visible CQEs from progressing.
`IORING_SQ_TASKRUN` and CQ-overflow state are treated as readiness sources and
cause a zero-submit `GETEVENTS` entry when necessary.

Plaintext listeners accept sockets directly into the registered-file table.
TLS retains a process descriptor for OpenSSL, but installs it with an
asynchronous `IORING_OP_FILES_UPDATE`; the connection virtual thread parks for
the CQE rather than pinning the carrier in `io_uring_register`. Direct sockets
also use ring-native shutdown and close operations.

Applications launch with:

```text
--enable-native-access=ALL-UNNAMED
--add-opens java.base/java.lang=ALL-UNNAMED
--add-modules jdk.incubator.vector
```

`CardiganServer.start()` returns only after every listener is ready.
`close()` is terminal: it stops admission, drains accepted work, and then
forces cancellation and socket closure after the configured deadlines.
HTTP/1 responses already admitted retain request order; HTTP/2 connections
receive `GOAWAY` while admitted streams finish.

## Handler contracts

Routes are registered explicitly through `CardiganServer.Builder.routes(...)`
or `registerController(...)`. Ordinary handlers may use straight-line blocking
code: operations such as request-body reads and flow-controlled writes park the
virtual thread without blocking the event loop.

`@Isolated` is an escape hatch for CPU-intensive or otherwise untrusted
blocking work. It runs the handler on a separate carrier pool and therefore
costs more than an ordinary route.

A route accepts an incremental request by declaring a `RequestBody` parameter.
Read it until EOF and close it; cancellation, malformed framing, and truncated
input surface as `RequestBodyException`. Fixed-length bodies, HTTP/1 chunked
framing, and HTTP/2 DATA are presented through the same API.

Responses use `StreamingBody.of(length, producer)` when the size is known and
`StreamingBody.unknownLength(producer, closeAction)` otherwise. Producers must
honour the declared length, must not return zero, and must release promptly when
closed. Cardigan supplies framing, backpressure, flow control, and cancellation.

The handler returns its `Response` after consuming the request. Cardigan does
not currently expose simultaneous request and response streaming on one
exchange, bidirectional gRPC handlers, or interactive tunnelling. Other HTTP/2
streams on the same connection continue independently.

Applications may add validated response headers and trailers, but cannot
override connection or framing fields owned by Cardigan.

## TLS

Enable TLS with `.tls(new TlsConfig(certificateChain, privateKey))`.
`.tlsFromSystemProperties()` is available for launchers that deliberately use
external configuration; supplying only one of the certificate and key is an
error.

Cardigan calls the system `libssl.so.3` and `libcrypto.so.3` directly through
Panama. OpenSSL owns handshakes, ALPN, alerts, key state, and control records;
socket readiness is integrated with io_uring so those operations park rather
than block an event loop.

kTLS is opportunistic. When transmit offload is available, application data
uses Cardigan's ordinary io_uring send path; otherwise it remains on
nonblocking `SSL_write`. Experimental direct receive is restricted to TLS 1.2.
TLS 1.3 kTLS requires OpenSSL 3.5 or newer and kernel rekey support; older
OpenSSL 3 releases transparently retain userspace TLS. TLS 1.3 control records
remain under OpenSSL control so KeyUpdate can safely replace kernel keys.
Malformed records and unsafe mid-connection transitions fail closed.

## Configuration

Listener port, event-loop placement, protocol mode, routes, and TLS are builder
settings. The system properties below must be set before constructing a server;
they are not dynamically reloadable. Defaults remain experimental.

| Property | Default | Meaning |
| --- | ---: | --- |
| `cardigan.max.request.size` | 10 MiB | Maximum retained request |
| `cardigan.max.header.size` | 8 KiB | Maximum HTTP/1 header block |
| `cardigan.http1.max.inflight` | 128 | Exchanges admitted per connection |
| `cardigan.http2.max.concurrent.streams` | 128 | Concurrent streams per connection |
| `cardigan.http2.max.header.list.size` | 16 KiB | Decoded header-list limit |
| `cardigan.http2.max.streaming.bodies.per.connection` | 16 | Streaming request bodies per connection |
| `cardigan.http2.max.streaming.buffer.bytes` | 256 MiB | Process-wide streaming-buffer budget |
| `cardigan.fixed.files.mode` | `auto` | `auto`, `legacy`, `async-explicit`, `async-alloc`, or `direct`; auto uses direct plaintext accept and async allocation for TLS |
| `cardigan.fixed.files.capacity` | 8192 | Registered socket slots per event loop |
| `cardigan.max.tasks` | 2 x fixed-file capacity + SQ entries | io_uring task slots per event loop |
| `cardigan.scheduler.boundedTurns` | `true` | Bound each reactor lane instead of exhaustive drains |
| `cardigan.scheduler.cqesPerTurn` | 256 | CQEs reaped per scheduler turn |
| `cardigan.scheduler.completionsPerTurn` | 256 | CQ-unblocked continuations per scheduler turn |
| `cardigan.scheduler.protocolTasksPerTurn` | 128 | Protocol continuations per turn |
| `cardigan.scheduler.handlerContinuationsPerTurn` | 32 | Exchange-worker continuations per turn; each worker retains its own request batch |
| `cardigan.scheduler.egressTasksPerTurn` | 256 | Egress-ready connections per scheduler turn |
| `cardigan.scheduler.externalTasksPerTurn` | 64 | Externally submitted tasks admitted per scheduler turn |
| `cardigan.scheduler.protocolQuantumMicros` | 50 | Cooperative H1/H2 pump quantum when competing work exists |
| `cardigan.scheduler.protocolCheckpointInterval` | 16 | Requests/frames between pump fairness checks |
| `cardigan.exchange.max.batch` | 64 | Exchanges run by a hot worker before it cooperatively yields |
| `cardigan.scheduler.stats` | `false` | Report lane, submit, wait, task-work, and CQ-overflow counters |
| `cardigan.fixed.files.stats` | `false` | Report fixed-file occupancy and admission counters |
| `cardigan.isolated.carriers` | available processors | Isolated carrier count |
| `cardigan.isolated.cpus` | empty | Optional isolated-carrier CPU list |
| `cardigan.isolated.max.tasks` | 4096 | Process-wide isolated-task limit |
| `cardigan.shutdown.grace.millis` | 30000 | Graceful drain deadline |
| `cardigan.shutdown.force.millis` | 2000 | Forced-close settling deadline |
| `cardigan.tls.certificate` | empty | PEM certificate chain |
| `cardigan.tls.privateKey` | empty | PEM private key |
| `cardigan.tls.version` | `1.3` | `1.3` or `1.2` |
| `cardigan.tls.ktls` | `true` | Permit kTLS when available |
| `cardigan.tls.directKtlsSend` | `true` | Use direct kTLS transmit when available |
| `cardigan.tls.directKtlsReceive` | `false` | Experimental TLS 1.2 direct receive |
| `cardigan.tls.handshake.max.pending.per.loop` | 64 | Pending TLS handshakes per loop |
| `cardigan.tls.handshake.timeout.millis` | 10000 | TLS handshake deadline |
| `cardigan.openssl.libraryDir` | empty | Optional system OpenSSL library directory |
| `cardigan.tls.stats` | `false` | Report TLS counters at shutdown |
| `cardigan.http2.resource.stats` | `false` | Report HTTP/2 resource high-water marks |

The handler budget counts continuation mounts, not individual exchanges. A
normal exchange worker retains Cardigan's 64-exchange batch, and a yielded
worker may consume another handler slot in the same turn. The default therefore
permits at most 32 mounts (roughly 2,048 trivial exchanges under continuous
backlog) before the egress phase. Smaller handler budgets are useful
latency/resource-pressure profiles, but should be benchmarked against the
target workload: they deliberately give up some of Cardigan's batching.

Lower-level tuning properties are intentionally undocumented; they are
experimental diagnostics, use at your peril.

## Validation

```sh
mvn test                         # deterministic unit tests
mvn -Pintegration-tests test     # live transport and protocol tests
mvn -Ptls-tests test             # TLS integration tests
mvn -Padvanced-tls-tests test    # TLS 1.3 KeyUpdate tests
mvn -Pstress-tests test          # resource-heavy tests
mvn -Pall-tests test             # every JUnit tier
./dev/verification/h2spec.sh     # HTTP/2 conformance
```

Contributor applications and benchmarks are built through `dev/pom.xml` and
are never part of the framework artifact:

```sh
mvn -f dev/pom.xml test
./dev/benchmarks/benchmark.sh --help
```

The benchmark launcher exposes the scheduler budgets and all fixed-file
lifecycle modes directly. For an end-to-end comparison, compare the
unbounded-budget/legacy control with the fused bounded/direct design using
`--scheduler-bounded=false --fixed-files=legacy` and
`--scheduler-bounded=true --fixed-files=direct --scheduler-stats`.
That is intentionally a composite comparison. Attribute effects with a 2x2
matrix that varies one switch at a time; use a connection-churn workload to
measure accept/register/close rather than a persistent connection cohort.
Use `--fixed-files-capacity` to exercise admission pressure. When sweeping that
capacity for throughput rather than overload behavior, use `--uring-max-tasks`
to hold the task-pool size constant; otherwise its runtime default scales with
the fixed-file table.

The adversarial JUnit suites and HTTP/2 flow-control probe cover bounded
resources, cancellation, connection survival, and recovery after hostile work.
Their thresholds are regression alarms rather than performance specifications.

## Publication

Cardigan publishes one artifact: `dev.cardigan:cardigan`. Everything under
`dev/` is internal tooling. Before a release, verify the `dev.cardigan`
namespace, choose an immutable version, review the public API and generated
Javadocs, configure GPG and a Central token, and tag the matching public source.

Build and inspect an unsigned local candidate with:

```sh
./dev/tools/verify-publication.sh
```

`mvn -Ppublication clean verify` additionally exercises signing. The separate
`central` profile is activated only for an intentional deployment:

```sh
mvn -Ppublication,central clean deploy
```

Automatic publication is disabled; an uploaded bundle still requires review
in the Central Portal.
