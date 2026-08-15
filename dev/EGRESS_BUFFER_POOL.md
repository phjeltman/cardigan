# Shared egress buffer pool design

Status: implemented.

## Objective

Egress capacity should follow observed concurrent output rather than reserving
the per-loop worst case for every event loop. The steady-state path must remain
owner-local, allocation-free, and compatible with
`IORING_SETUP_DEFER_TASKRUN`.

## Structure

A server owns one lazily grown `EgressBufferPool`. Each event loop owns a small
LIFO magazine of integer buffer IDs. Acquiring and releasing a buffer normally
touches only that magazine and an owner-local publication epoch. An empty
magazine refills 32 IDs from the shared pool; a full 64-ID magazine spills 32
IDs back.

The carrier serializes physical execution, but successive virtual threads are
still distinct Java threads. Short non-parking mutation sections therefore use
a release/acquire epoch to publish magazine state between them. A shared-pool
operation is never performed inside such a section, so waiting for another
loop cannot strand the carrier behind a local lock.

The shared pool allocates native memory from a server-owned shared arena in
256-buffer chunks (about 4 MiB) and creates each `MemorySegment` view once.
Chunks remain alive until server shutdown. Growth is bounded by a process-wide
maximum and retained at the observed high-water mark; runtime shrinking is
deliberately excluded. The default maximum is the larger of 4,096 buffers and
256 buffers per event loop, and may be bounded explicitly with
`cardigan.egress.buffers.max`.

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

The initial magazine is empty. A new chunk is allocated by the already-pinned
event-loop thread requesting growth so Linux first-touch placement follows the
consumer. Batch sizes and magazine watermarks are structural defaults, not
workload-specific pool capacities.

When the configured global maximum is reached, acquisition reports exhaustion
and the existing bounded fallback/backpressure path remains authoritative. The
pool must expose:

- local peak leases and magazine misses;
- shared refills and spills;
- allocated chunks and global peak leases;
- capacity exhaustion and fallback counts.

Exact global lease accounting is opt-in through
`cardigan.egress.pool.stats`; its atomic counters are absent from ordinary
runs. Batch and allocation counters are updated only while holding the shared
pool lock.

## Validation

Tests cover exclusive leasing, batch transfer, bounded exhaustion, shutdown
with outstanding leases, and pipelined transport recovery under a deliberately
small global pool. Protocol integration tests cover terminal-CQE release,
cancellation, and send failure. Performance validation must confirm that the
steady-state acquire/release path contains no atomic read-modify-write or
cross-core shared operation when statistics are disabled.
