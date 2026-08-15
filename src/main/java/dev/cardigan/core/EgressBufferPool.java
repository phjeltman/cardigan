// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Server-wide native egress storage with owner-local buffer magazines. */
final class EgressBufferPool implements AutoCloseable {
    static final boolean STATS_ENABLED =
        Boolean.getBoolean("cardigan.egress.pool.stats");
    private static final int DEFAULT_CHUNK_BUFFERS = 256;
    private static final int MINIMUM_DEFAULT_BUFFERS = 4096;
    private static final int DEFAULT_BUFFERS_PER_LOOP = 256;

    private final Arena arena = Arena.ofShared();
    private final int bufferSize;
    private final int maxBuffers;
    private final int chunkBuffers;
    private final MemorySegment[] buffers;
    private final int[] sharedFreeIds;
    private final AtomicInteger activeLeases = new AtomicInteger();
    private final AtomicInteger peakActiveLeases = new AtomicInteger();
    private final LongAdder externalAcquires = new LongAdder();
    private final LongAdder externalReleases = new LongAdder();

    private int sharedFreeCount;
    private int allocatedBuffers;
    private int allocatedChunks;
    private long refillOperations;
    private long spillOperations;
    private long refilledBuffers;
    private long spilledBuffers;
    private long capacityExhaustions;
    private int openLocals;
    private int aggregateLocalPeak;
    private long localRefillMisses;
    private long localSpills;
    private long localAcquisitions;
    private long localReleases;
    private long localRefilledBuffers;
    private long localSpilledBuffers;
    private boolean closed;

    EgressBufferPool(int maxBuffers, int bufferSize) {
        this(
            maxBuffers,
            bufferSize,
            Math.min(DEFAULT_CHUNK_BUFFERS, maxBuffers));
    }

    EgressBufferPool(int maxBuffers, int bufferSize, int chunkBuffers) {
        if (maxBuffers <= 0) {
            throw new IllegalArgumentException(
                "Egress buffer capacity must be positive");
        }
        if (bufferSize <= 0) {
            throw new IllegalArgumentException(
                "Egress buffer size must be positive");
        }
        if (chunkBuffers <= 0 || chunkBuffers > maxBuffers) {
            throw new IllegalArgumentException(
                "Invalid egress chunk size: " + chunkBuffers);
        }
        this.maxBuffers = maxBuffers;
        this.bufferSize = bufferSize;
        this.chunkBuffers = chunkBuffers;
        this.buffers = new MemorySegment[maxBuffers];
        this.sharedFreeIds = new int[maxBuffers];
    }

    static int configuredMaxBuffers(int eventLoops) {
        if (eventLoops <= 0) {
            throw new IllegalArgumentException(
                "Event-loop count must be positive");
        }
        long calculated = Math.max(
            MINIMUM_DEFAULT_BUFFERS,
            (long) eventLoops * DEFAULT_BUFFERS_PER_LOOP);
        int defaultCapacity = Math.toIntExact(Math.min(
            calculated, Integer.MAX_VALUE));
        int configured = Integer.getInteger(
            "cardigan.egress.buffers.max", defaultCapacity);
        if (configured <= 0) {
            throw new IllegalArgumentException(
                "cardigan.egress.buffers.max must be positive");
        }
        return configured;
    }

    synchronized void registerLocal() {
        ensureOpen();
        openLocals++;
    }

    synchronized int refill(int[] destination, int offset, int requested) {
        ensureOpen();
        Objects.checkFromIndexSize(offset, requested, destination.length);
        refillOperations++;
        int supplied = 0;
        while (supplied < requested) {
            if (sharedFreeCount == 0 && allocatedBuffers < maxBuffers) {
                allocateChunk();
            }
            if (sharedFreeCount == 0) {
                if (supplied == 0) {
                    capacityExhaustions++;
                }
                break;
            }
            destination[offset + supplied] =
                sharedFreeIds[--sharedFreeCount];
            supplied++;
        }
        refilledBuffers += supplied;
        return supplied;
    }

    synchronized void spill(int[] source, int offset, int count) {
        ensureOpen();
        Objects.checkFromIndexSize(offset, count, source.length);
        if (sharedFreeCount + count > allocatedBuffers) {
            throw new IllegalStateException(
                "Egress buffer pool received too many free buffers");
        }
        for (int i = 0; i < count; i++) {
            int bufferId = source[offset + i];
            requireAllocated(bufferId);
            sharedFreeIds[sharedFreeCount++] = bufferId;
        }
        spillOperations++;
        spilledBuffers += count;
    }

    synchronized void spill(int bufferId) {
        ensureOpen();
        if (sharedFreeCount >= allocatedBuffers) {
            throw new IllegalStateException(
                "Egress buffer pool received too many free buffers");
        }
        requireAllocated(bufferId);
        sharedFreeIds[sharedFreeCount++] = bufferId;
        spillOperations++;
        spilledBuffers++;
    }

    MemorySegment segment(int bufferId) {
        if (bufferId < 0 || bufferId >= buffers.length) {
            throw new IllegalArgumentException(
                "Invalid egress bufferId: " + bufferId);
        }
        MemorySegment buffer = buffers[bufferId];
        if (buffer == null) {
            throw new IllegalArgumentException(
                "Unallocated egress bufferId: " + bufferId);
        }
        return buffer;
    }

    synchronized void closeLocal(
            int[] localFreeIds,
            int localFreeCount,
            int active,
            int peakActive,
            long refillMisses,
            long spills,
            long acquisitions,
            long releases,
            long refilledBuffers,
            long spilledBuffers) {
        ensureOpen();
        if (openLocals <= 0) {
            throw new IllegalStateException(
                "Egress pool has no registered local magazine");
        }
        if (active != 0 || acquisitions != releases) {
            throw new IllegalStateException(
                "Cannot close egress magazine with " + active
                    + " outstanding buffer(s)");
        }
        if (localFreeCount != 0) {
            spill(localFreeIds, 0, localFreeCount);
        }
        openLocals--;
        aggregateLocalPeak += peakActive;
        localRefillMisses += refillMisses;
        localSpills += spills + (localFreeCount == 0 ? 0 : 1);
        localAcquisitions += acquisitions;
        localReleases += releases;
        localRefilledBuffers += refilledBuffers;
        localSpilledBuffers += spilledBuffers + localFreeCount;
    }

    synchronized Stats stats() {
        return new Stats(
            maxBuffers,
            allocatedBuffers,
            allocatedChunks,
            (long) allocatedBuffers * bufferSize,
            sharedFreeCount,
            openLocals == 0 ? 0 : -1,
            STATS_ENABLED ? activeLeases.get() : -1,
            aggregateLocalPeak,
            STATS_ENABLED ? peakActiveLeases.get() : -1,
            refillOperations,
            spillOperations,
            refilledBuffers,
            spilledBuffers,
            STATS_ENABLED ? externalAcquires.sum() : -1,
            STATS_ENABLED ? externalReleases.sum() : -1,
            localRefillMisses,
            localSpills,
            localAcquisitions,
            localReleases,
            localRefilledBuffers,
            localSpilledBuffers,
            capacityExhaustions);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        if (openLocals != 0) {
            throw new IllegalStateException(
                "Cannot close egress pool with " + openLocals
                    + " open local magazine(s)");
        }
        if (sharedFreeCount != allocatedBuffers) {
            throw new IllegalStateException(
                "Cannot close egress pool with "
                    + (allocatedBuffers - sharedFreeCount)
                    + " outstanding buffer(s)");
        }
        closed = true;
        arena.close();
    }

    private void allocateChunk() {
        int count = Math.min(
            chunkBuffers, maxBuffers - allocatedBuffers);
        MemorySegment chunk = arena.allocate(
            (long) count * bufferSize, 64);
        int firstId = allocatedBuffers;
        for (int i = 0; i < count; i++) {
            int bufferId = firstId + i;
            buffers[bufferId] = chunk.asSlice(
                (long) i * bufferSize, bufferSize);
            sharedFreeIds[sharedFreeCount++] = bufferId;
        }
        allocatedBuffers += count;
        allocatedChunks++;
    }

    private void requireAllocated(int bufferId) {
        int allocated = allocatedBuffers;
        if (bufferId < 0 || bufferId >= allocated
            || buffers[bufferId] == null) {
            throw new IllegalArgumentException(
                "Invalid egress bufferId: " + bufferId);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Egress buffer pool is closed");
        }
    }

    void leaseAcquired() {
        if (!STATS_ENABLED) {
            return;
        }
        int active = activeLeases.incrementAndGet();
        int peak = peakActiveLeases.get();
        while (active > peak
            && !peakActiveLeases.compareAndSet(peak, active)) {
            peak = peakActiveLeases.get();
        }
    }

    void leaseReleased() {
        if (STATS_ENABLED) {
            activeLeases.decrementAndGet();
        }
    }

    void externalAcquire() {
        if (STATS_ENABLED) {
            externalAcquires.increment();
        }
    }

    void externalRelease() {
        if (STATS_ENABLED) {
            externalReleases.increment();
        }
    }

    record Stats(
        int maxBuffers,
        int allocatedBuffers,
        int allocatedChunks,
        long allocatedBytes,
        int sharedFreeBuffers,
        int localFreeBuffers,
        int activeBuffers,
        int aggregateLocalPeak,
        int peakActiveBuffers,
        long sharedRefills,
        long sharedSpills,
        long refilledBuffers,
        long spilledBuffers,
        long externalAcquires,
        long externalReleases,
        long localRefillMisses,
        long localSpills,
        long localAcquisitions,
        long localReleases,
        long localRefilledBuffers,
        long localSpilledBuffers,
        long capacityExhaustions
    ) {
        String summary() {
            return "capacity=" + maxBuffers
                + ", allocated=" + allocatedBuffers
                + ", chunks=" + allocatedChunks
                + ", native-bytes=" + allocatedBytes
                + ", shared-free=" + sharedFreeBuffers
                + ", local-free="
                + (localFreeBuffers < 0
                    ? "unavailable" : localFreeBuffers)
                + ", active="
                + (activeBuffers < 0 ? "disabled" : activeBuffers)
                + ", aggregate-local-peak=" + aggregateLocalPeak
                + ", peak-active="
                + (peakActiveBuffers < 0 ? "disabled" : peakActiveBuffers)
                + ", shared-refills=" + sharedRefills
                + ", shared-spills=" + sharedSpills
                + ", refilled-buffers=" + refilledBuffers
                + ", spilled-buffers=" + spilledBuffers
                + ", external-acquires="
                + (externalAcquires < 0 ? "disabled" : externalAcquires)
                + ", external-releases="
                + (externalReleases < 0 ? "disabled" : externalReleases)
                + ", local-refill-misses=" + localRefillMisses
                + ", local-spills=" + localSpills
                + ", local-acquisitions=" + localAcquisitions
                + ", local-releases=" + localReleases
                + ", local-refilled-buffers=" + localRefilledBuffers
                + ", local-spilled-buffers=" + localSpilledBuffers
                + ", exhaustions=" + capacityExhaustions;
        }
    }

}
