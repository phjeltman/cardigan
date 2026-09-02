# HttpArena integration

This directory is a submission tree ready to be copied directly under
HttpArena's `frameworks/` directory. Its canonical `cardigan` directory is an
independent Maven application pinned to the public
`dev.cardigan:cardigan:0.1.0-alpha.5` artifact. Cardigan itself owns no
HttpArena routes or dataset assumptions; this application supplies only the
arena routes and selects its listener protocol through `ProtocolMode`.

The submission exposes the profiles that fit Cardigan's current transport
model as six independent framework variants. They deliberately share the
display name `cardigan`, while each container starts exactly one listener and
therefore does not spend benchmark CPUs or memory on unrelated protocols.

| Variant | Listener | Profiles |
| --- | --- | --- |
| `cardigan` | HTTP/1.1 `:8080` | baseline, latency-1m, pipelined, limited-conn |
| `cardigan-json-tls` | HTTP/1.1 + TLS `:8081` | json-tls |
| `cardigan-h2` | HTTP/2 + TLS `:8443` | baseline-h2, static-h2 |
| `cardigan-h2c` | prior-knowledge h2c `:8082` | baseline-h2c |
| `cardigan-grpc` | prior-knowledge h2c `:8080` | unary-grpc |
| `cardigan-grpc-tls` | HTTP/2 + TLS `:8443` | unary-grpc-tls |

The launcher consumes HttpArena's standard read-only mounts:
`/data/dataset.json`, `/data/static`, and `/certs/server.{crt,key}`. Static
assets are copied once at startup into process-lifetime native `StaticBody`
storage. Supplied `.br` sidecars are also preloaded and selected through
`Accept-Encoding`; identity responses remain available for other clients.
JSON responses are generated per request from immutable dataset item fragments;
only the source data is preloaded.

The gRPC variants implement the canonical
`benchmark.BenchmarkService` wire contract directly: requests are decoded from
their protobuf envelope and replies are streamed into Cardigan's HTTP/2 egress
buffers without introducing grpc-java or generated-message allocation
machinery. This is permitted for HttpArena's Engine category.

Each variant's `build.sh` produces the image name expected by HttpArena. The
canonical Docker build downloads Cardigan from Maven Central and then builds
the small submission application; it does not copy or compile the Cardigan
source repository. To prepare a submission, copy all six `cardigan*`
directories from this directory into an HttpArena checkout's `frameworks/`
directory.

From the HttpArena repository root, validate every subscribed profile with:

```sh
for variant in \
    cardigan cardigan-json-tls cardigan-h2 cardigan-h2c \
    cardigan-grpc cardigan-grpc-tls
do
    ./scripts/validate.sh "$variant"
done
```

The variants share the display name `cardigan`; HttpArena groups their
non-overlapping results into one leaderboard and composite-score entry.

Cardigan requires the three io_uring system calls that Docker's default
seccomp profile blocks. HttpArena starts framework containers with
`--security-opt seccomp=unconfined` and an unlimited memlock ulimit. When
running an image directly, supply those options yourself; full `--privileged`
access is not required.
