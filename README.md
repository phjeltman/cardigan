# Cardigan

**Vision: “Build it like it’s 2005. Scale it like it’s 2026.”**

Cardigan is a Java HTTP server supporting HTTP/1.1 and HTTP/2, designed to pair
straight-line application code with high-performance transport.

Java has acquired capabilities previously reserved for systems programming
languages: low-overhead native interop and explicit off-heap memory access
through Project Panama, SIMD through the incubating Vector API, and M:N virtual
threading through Project Loom.

Cardigan asks what happens when those capabilities are combined with a
thread-per-core, shared-nothing transport architecture built around modern
Linux io_uring. Each pinned carrier thread both drives io_uring submissions and
completions and runs application logic through virtual threads.

Cardigan lets developers write simple, blocking handlers while retaining strong
mechanical sympathy with the underlying hardware. Application code needs no
asynchronous APIs, Futures, Promises, Mono/Flux chains, or async function
coloring. Cardigan asks you to write the straight-line code you already wanted
to write, with asynchronous coordination handled by the runtime.

The machinery lives in the runtime so handlers can stay boring—in the good way:

```java
@Get("/hello")
public Response hello() {
    return Response.text("hello");
}
```

Cardigan handles transport, parsing, scheduling, ordering, and backpressure
around that straight-line code.

Slip on the cardigan; it’s peak cozy.

See [DOCUMENTATION.md](DOCUMENTATION.md) for requirements, usage, configuration,
validation, and publication. [APOLOGY.md](APOLOGY.md) explains Cardigan's
deliberate use of unsupported JDK internals.

The following song is not affiliated with the cardigan.dev project:
https://www.youtube.com/watch?v=NI6aOFI7hms
