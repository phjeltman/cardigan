# HttpArena integration

This directory is an independent Maven application that depends on the
`dev.cardigan:cardigan` framework artifact. Cardigan itself owns no HttpArena
routes or dataset assumptions; this project supplies only the routes required
by the arena and selects its listener protocol through `ProtocolMode`.

Build and test it after installing the framework artifact locally:

```sh
mvn -q -DskipTests install
mvn -q -f dev/httparena/pom.xml test
```

The submission exposes the profiles that fit Cardigan's current transport
model as six independent framework variants. They deliberately share the
display name `Cardigan`, while each container starts exactly one listener and
therefore does not spend benchmark CPUs or memory on unrelated protocols.

| Variant | Listener | Profiles |
| --- | --- | --- |
| `cardigan` | HTTP/1.1 `:8080` | baseline, pipelined, limited-conn |
| `cardigan-json-tls` | HTTP/1.1 + TLS `:8081` | json-tls |
| `cardigan-h2` | HTTP/2 + TLS `:8443` | baseline-h2, static-h2 |
| `cardigan-h2c` | prior-knowledge h2c `:8082` | baseline-h2c |
| `cardigan-grpc` | prior-knowledge h2c `:8080` | unary-grpc, stream-grpc |
| `cardigan-grpc-tls` | HTTP/2 + TLS `:8443` | unary-grpc-tls, stream-grpc-tls |

The launcher consumes HttpArena's standard read-only mounts:
`/data/dataset.json`, `/data/static`, and `/certs/server.{crt,key}`. Static
assets are copied once at startup into process-lifetime native `StaticBody`
storage. JSON responses are generated per request from immutable dataset item
fragments; only the source data is preloaded. The gRPC variants implement the
canonical `benchmark.BenchmarkService` wire contract directly: requests are
decoded from their protobuf envelope and replies are streamed into Cardigan's
HTTP/2 egress buffers without introducing grpc-java or generated-message
allocation machinery. This is permitted for HttpArena's Engine category.

From the Cardigan repository root, each variant's `build.sh` produces the image
name expected by HttpArena. The image build installs the framework artifact,
then builds this consumer using its own POM. For an HttpArena submission,
vendor Cardigan as `frameworks/cardigan`, overlay that variant's `meta.json`,
`build.sh`, and the shared Dockerfile, then place the other three variant
directories beside it. Their build scripts detect both the in-repository and
vendored layouts.

Cardigan requires the three io_uring system calls that Docker's default
seccomp profile blocks. HttpArena's benchmark runner already starts framework
containers with `--security-opt seccomp=unconfined`, and its validator applies
the same option to entries whose `meta.json` declares
`"engine": "io_uring"`. When running an image directly, supply that option
yourself; full `--privileged` access is not required.

Static content is currently served uncompressed. That is conformant—the arena
accepts an origin representation when `Accept-Encoding` is present—but adding
response headers and precompressed `.br`/`.gz` selection is an obvious future
score optimization.
