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
scheduler lanes on that carrier. The default `budgeted` scheduler places
configured count limits on those lanes and a cooperative time limit on a
running protocol pump. Its manual limits are retained as diagnostics rather
than production tuning recommendations.

The alternative `epoch` scheduler is experimental. It materializes deferred
kernel task work, then advances through snapshots of visible CQEs, the external
inbox, completion-unblocked continuations, protocol continuations, handler
continuations, returned buffers, and egress work. Work appended to its current
or an earlier phase waits for the next epoch; work offered to a later phase may
run in the current epoch. Handler workers share the exchange-queue tail captured
at the start of their phase. Protocol input yields at an RX-chunk boundary only
when another scheduler, kernel, submission, returned-buffer, or deferred-handler
source is actually ready. Pending SQEs are normally submitted after the final
phase, although SQ exhaustion or kernel-task-work materialization can require
an earlier entry.

Both modes keep ordinary handlers on the fused ring-owner/Loom carrier.
`IORING_SQ_TASKRUN` and CQ-overflow state are readiness sources and cause a
`GETEVENTS` entry when necessary, even when no SQE is pending.

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
| `cardigan.exchange.queue.capacity` | 65536 | Queued ordinary exchanges per event loop |
| `cardigan.exchange.max.idle.workers` | 64 | Retained idle exchange workers per event loop |
| `cardigan.scheduler.mode` | `budgeted` | `budgeted` or experimental `epoch` |
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

The fixed-file table, io_uring task pool, exchange queue, and retained-worker
limit are resource capacities. They are distinct from the scheduling policy
below and should be held constant when comparing scheduler modes.

### Legacy budgeted-scheduler diagnostics

These properties preserve the original budgeted scheduler for experiments and
regression diagnosis. They are not production sizing guidance. The `epoch`
scheduler does not use them to govern epoch scheduling; the benchmark launcher
rejects the explicit equivalents it exposes when `--scheduler-mode=epoch` is
selected.

| Property | Default | Budgeted-mode meaning |
| --- | ---: | --- |
| `cardigan.scheduler.boundedTurns` | `true` | Apply lane limits and cooperative protocol checkpoints; `false` removes numeric caps, while CQ/completion/protocol/egress still retain entry snapshots and the external/handler lanes retain their legacy append behavior |
| `cardigan.scheduler.cqesPerTurn` | 256 | CQEs reaped per turn |
| `cardigan.scheduler.completionsPerTurn` | 256 | CQ-unblocked continuations per turn |
| `cardigan.scheduler.protocolTasksPerTurn` | 128 | Protocol continuations per turn |
| `cardigan.scheduler.handlerContinuationsPerTurn` | 32 | Exchange-worker continuation mounts per turn |
| `cardigan.scheduler.egressTasksPerTurn` | 256 | Egress-ready connections per turn |
| `cardigan.scheduler.externalTasksPerTurn` | 64 | External-inbox tasks admitted per turn |
| `cardigan.scheduler.protocolQuantumMicros` | 50 | Protocol-pump deadline when competing work exists |
| `cardigan.scheduler.protocolCheckpointInterval` | 16 | Requests or frames between deadline checks |
| `cardigan.exchange.max.batch` | 64 | Exchanges a hot worker runs before a cooperative yield |

The handler budget counts continuation mounts, not exchanges. Under continuous
backlog, the defaults allow 32 mounts and up to 64 exchanges per uninterrupted
worker batch, so roughly 2,048 trivial exchanges can run before egress. Parking
or yielding changes that arithmetic. This explains the diagnostic; it is not a
recommended target. Epoch mode instead runs the work offered through the
handler phase's captured queue-tail cutoff.

Other lower-level tuning properties remain intentionally undocumented
experimental diagnostics.

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

The benchmark launcher exposes `budgeted` and `epoch` directly and prints the
effective scheduler and fixed-file layout before each case, plus the io_uring
task-pool capacity when it is pinned. The following is a controlled HTTP/2
scheduler A/B. It holds the
fixed-file layout, fixed-file capacity, and io_uring task-pool capacity
constant, selects only endpoint 1, and reverses run order across four pairs:

```bash
common=(
  --scheduler-stats
  --fixed-file-stats
  --fixed-files=direct
  --fixed-files-capacity=8192
  --uring-max-tasks=16896
  --http2
  --http2-streams=16
  --cpus=1
  --threads=4
  --connections=200
  --warmup=10s
  --duration=30s
  1
)

for pair in 1 2 3 4; do
  if (( pair % 2 == 1 )); then
    modes=(budgeted epoch)
  else
    modes=(epoch budgeted)
  fi
  for mode in "${modes[@]}"; do
    ./dev/benchmarks/benchmark.sh \
      --scheduler-mode="$mode" "${common[@]}"
  done
done
```

The pinned task count, 16,896, is `2 * 8192 + 512`: the runtime-derived default
for this fixed-file capacity and the launcher's 512-entry rings. Run the pair
from one immutable source revision and JDK on an otherwise quiet host with the
same disjoint server/client CPU placement. The launcher pins server loops but
not the client, so arrange client affinity externally. Treat it as an
experiment, not a performance guarantee. The
scheduler and fixed-file counters are cumulative over server startup, warm-up,
measurement, and shutdown; use them to explain a run, not as measurement-window
rates. Repeat the comparison with `--pipeline --pipeline-depth=16` in place of
the two HTTP/2 flags to cover HTTP/1 pipelining; the launcher enables wrk's
latency distribution for those runs.

Do not combine an epoch run with the legacy budget flags. To study fixed-file
lifecycle separately, vary one fixed-file mode at a time and use a
connection-churn workload rather than a persistent cohort. Use
`--fixed-files-capacity` to exercise admission pressure. When sweeping that
capacity for throughput rather than overload behavior, use `--uring-max-tasks`
to keep the task-pool size constant; otherwise its default scales with the
fixed-file table.

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
