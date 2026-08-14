# Shared egress buffer pool design

Status: design for future implementation.

## Objective

Egress capacity should follow observed concurrent output rather than reserving
the per-loop worst case for every event loop. The steady-state path must remain
owner-local, allocation-free, and compatible with
`IORING_SETUP_DEFER_TASKRUN`.

## Structure

A server owns one lazily grown `EgressBufferPool`. Each event loop owns a small
LIFO magazine of integer buffer IDs. Acquiring and releasing a buffer normally
touches only that magazine. An empty magazine refills a batch from the shared
pool; a magazine above its high-water mark spills a batch back.

The shared pool allocates native memory from a server-owned shared arena in
fixed-size chunks and creates each `MemorySegment` view once. Chunks remain
alive until server shutdown. Growth is bounded by a process-wide maximum and
retained at the observed high-water mark; runtime shrinking is deliberately
excluded from the first implementation.

The shared free-ID structure may use an ordinary lock. It is reached per batch,
not per response, so a simple critical section is preferable to a complicated
lock-free queue. Statistics must verify that refills and spills remain rare.

## io_uring ownership

The pool performs no ring operation. A buffer is acquired while executing on an
event loop's carrier, submitted only to that loop's ring, and released by that
loop after the terminal CQE. An ID may cross loops only between leases, after
the kernel no longer owns the corresponding memory. Closing the server first
drains and closes every loop, then closes the shared pool.

`DEFER_TASKRUN` therefore retains its single-issuer discipline: sharing spare
memory does not share submission or completion work.

## Locality and pressure

The initial magazine is intentionally small. A new chunk should be allocated by
the already-pinned event-loop thread requesting growth so Linux first-touch
placement follows the consumer. Batch sizes and magazine watermarks are
structural defaults, not workload-specific pool capacities.

When the configured global maximum is reached, acquisition reports exhaustion
and the existing bounded fallback/backpressure path remains authoritative. The
pool must expose:

- local peak leases and magazine misses;
- shared refills and spills;
- allocated chunks and global peak leases;
- capacity exhaustion and fallback counts.

## Validation

Tests must cover exclusive leasing, terminal-CQE release, cancellation and send
failure, batch transfer between loops, shutdown with outstanding sends, and
HTTP/2 bursts large enough to exhaust a local magazine. Performance validation
must confirm that the steady-state acquire/release path contains no atomic or
shared-memory operation.
