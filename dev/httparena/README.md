# HttpArena integration

This directory is a submission tree ready to be copied directly under
HttpArena's `frameworks/` directory. Its canonical `cardigan` directory is an
independent Maven application pinned to the public
`dev.cardigan:cardigan:0.1.0-alpha.3` artifact. Cardigan itself owns no
HttpArena routes or dataset assumptions; this application supplies only the
arena routes and selects its listener protocol through `ProtocolMode`.

The database variant uses the native client from `pgjdbc/pg-java`, pinned to
commit `b3e0ec4f5b289965fac9d05a67984a758f44555e`. The submission packages the
Java 21 client and protocol JARs produced after running that revision's full
Maven reactor. Its Docker build verifies their SHA-256 digests and installs
the binaries without fetching or compiling pg-java. To reproduce the binaries
or prepare a direct Maven test, install the same revision with Java 21 first:

```sh
git clone https://github.com/pgjdbc/pg-java.git /tmp/pg-java
git -C /tmp/pg-java checkout b3e0ec4f5b289965fac9d05a67984a758f44555e
mvn -q -f /tmp/pg-java/pom.xml install
mvn -q -f dev/httparena/cardigan/pom.xml test
```

The submission exposes the profiles that fit Cardigan's current transport
model as seven independent framework variants. They deliberately share the
display name `cardigan`, while each container starts exactly one listener and
therefore does not spend benchmark CPUs or memory on unrelated protocols.

| Variant | Listener | Profiles |
| --- | --- | --- |
| `cardigan` | HTTP/1.1 `:8080` | baseline, latency-1m, pipelined, limited-conn |
| `cardigan-db` | HTTP/1.1 `:8080` | async-db |
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
only the source data is preloaded. The database variant reads `DATABASE_URL`
and sizes its lazy connection pool from `DATABASE_MAX_CONN`. Every pg-java
connection owns one server-prepared range query, and completed rows are
encoded once as final UTF-8 JSON before `EncodedBody` copies that representation
into Cardigan's response storage.
The gRPC variants implement the canonical
`benchmark.BenchmarkService` wire contract directly: requests are decoded from
their protobuf envelope and replies are streamed into Cardigan's HTTP/2 egress
buffers without introducing grpc-java or generated-message allocation
machinery. This is permitted for HttpArena's Engine category.

Each variant's `build.sh` produces the image name expected by HttpArena. The
canonical Docker build downloads Cardigan from Maven Central, installs the
packaged pg-java binaries, and then builds the small submission application;
it does not copy or compile either source repository. To prepare a submission,
copy all seven `cardigan*` directories from this directory into an HttpArena
checkout's `frameworks/` directory.

From the HttpArena repository root, validate every subscribed profile with:

```sh
for variant in \
    cardigan cardigan-db cardigan-json-tls cardigan-h2 cardigan-h2c \
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
