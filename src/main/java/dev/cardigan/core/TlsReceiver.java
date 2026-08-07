// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.tls.TlsConnection;
import dev.cardigan.tls.TlsStats;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.function.IntConsumer;

/**
 * OpenSSL receive path backed by a small per-connection plaintext buffer
 * pool. A chunk returns its slot only after asynchronous request users have
 * released their references.
 */
final class TlsReceiver implements InboundReceiver {
    private static final int CLOSED_SLOT = -2;

    private final TlsConnection connection;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment buffers;
    private final int[] freeSlots;
    private final IntConsumer poolReleaser = this::releaseBuffer;
    private int freeCount;
    private int borrowed;
    private boolean closed;
    private boolean arenaClosed;

    TlsReceiver(TlsConnection connection) {
        this.connection = connection;
        int poolSize = Math.max(
            2,
            Integer.getInteger("cardigan.tls.receive.buffers", 4)
        );
        this.buffers = arena.allocate(
            (long) poolSize * UringEventLoop.BUFFER_SIZE);
        this.freeSlots = new int[poolSize];
        for (int i = 0; i < poolSize; i++) {
            freeSlots[i] = poolSize - i - 1;
        }
        this.freeCount = poolSize;
        if (TlsStats.ENABLED) {
            TlsStats.receivePoolAllocated(
                poolSize * UringEventLoop.BUFFER_SIZE);
        }
    }

    @Override
    public void start() {
    }

    @Override
    public InboundChunk receive() {
        int slot = acquireBuffer();
        if (slot == CLOSED_SLOT) {
            return null;
        }
        if (slot < 0) {
            return receiveFallback();
        }
        MemorySegment buffer = buffers.asSlice(
            (long) slot * UringEventLoop.BUFFER_SIZE,
            UringEventLoop.BUFFER_SIZE
        );
        boolean transferred = false;
        try {
            int result = connection.read(buffer, UringEventLoop.BUFFER_SIZE);
            if (result <= 0) {
                return null;
            }
            InboundChunk chunk = new InboundChunk(
                buffer, slot, result, poolReleaser);
            if (TlsStats.ENABLED) {
                TlsStats.receiveChunk();
            }
            transferred = true;
            return chunk;
        } finally {
            if (!transferred) {
                releaseBuffer(slot);
            }
        }
    }

    private InboundChunk receiveFallback() {
        Arena fallbackArena = Arena.ofShared();
        MemorySegment buffer = fallbackArena.allocate(
            UringEventLoop.BUFFER_SIZE);
        boolean transferred = false;
        try {
            int result = connection.read(buffer, UringEventLoop.BUFFER_SIZE);
            if (result <= 0) {
                return null;
            }
            InboundChunk chunk = new InboundChunk(
                buffer, -1, result, ignored -> fallbackArena.close());
            if (TlsStats.ENABLED) {
                TlsStats.receiveChunk();
                TlsStats.receiveFallbackAllocated(
                    UringEventLoop.BUFFER_SIZE);
            }
            transferred = true;
            return chunk;
        } finally {
            if (!transferred) {
                fallbackArena.close();
            }
        }
    }

    private synchronized int acquireBuffer() {
        if (closed) {
            return CLOSED_SLOT;
        }
        if (freeCount == 0) {
            return -1;
        }
        borrowed++;
        return freeSlots[--freeCount];
    }

    private synchronized void releaseBuffer(int slot) {
        freeSlots[freeCount++] = slot;
        borrowed--;
        closeArenaIfUnused();
    }

    @Override
    public synchronized void close() {
        closed = true;
        closeArenaIfUnused();
    }

    private void closeArenaIfUnused() {
        if (closed && borrowed == 0 && !arenaClosed) {
            arenaClosed = true;
            arena.close();
        }
    }
}
