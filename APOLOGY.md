# Why Cardigan opens `java.lang`

Cardigan makes two deliberate uses of unsupported JDK internals.

The important one is virtual-thread scheduling. Loom does not expose a public
way to choose a virtual thread's scheduler, but Cardigan needs resumptions to
return to the io_uring event loop that owns the connection. It therefore uses
the internal virtual-thread builder, requiring:

```text
--add-opens java.base/java.lang=ALL-UNNAMED
```

This is an architectural dependency, not an accidental shortcut. Cardigan is
an experiment in a design that Java cannot yet express through public APIs;
the internal access should disappear when the platform provides that hook.

The same opening currently permits direct access to compact `String` bytes on
response hot paths. That is only a micro-optimization. It must continue to
justify itself through measurement and can be removed without changing the
architecture.

Neither use should be mistaken for a claim of production compatibility across
JDK releases. Cardigan is an alpha intended to make the missing capability—and
its potential—concrete.
