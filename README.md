# Cardigan

**Vision: “Build it like it’s 2005. Scale it like it’s 2026.”**

Java has acquired capabilities previously reserved for systems programming
languages: low-overhead native interop and explicit off-heap memory access
through Project Panama, SIMD through the incubating Vector API, and M:N virtual
threading through Project Loom.

Cardigan asks what happens when those capabilities are combined with a
Seastar/Glommio-shaped, shared-nothing transport architecture built around
modern Linux io_uring.

Cardigan lets developers write simple, blocking handlers while retaining strong 
mechanical sympathy with the underlying hardware. Application code needs no asynchronous 
APIs, Futures, Promises, Mono/Flux chains, or async function coloring. Cardigan asks 
you to write the straight-line code you already wanted to write, on a runtime that
does not need the traditional workarounds.

Slip on the cardigan; it’s peak cozy.

See [DOCUMENTATION.md](DOCUMENTATION.md) for requirements, usage, configuration,
validation, and publication. [APOLOGY.md](APOLOGY.md) explains Cardigan's
deliberate use of unsupported JDK internals.

The following song is not affiliated with the cardigan.dev project:
https://www.youtube.com/watch?v=NI6aOFI7hms
