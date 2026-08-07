// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.foreign.MemorySegment;

/**
 * Preserves stream order while copying only request fragments which span
 * independently owned multishot receive buffers.
 */
final class InboundChunkStream implements AutoCloseable {
    private final InboundReceiver receiver;
    private InboundChunk pending;
    private int pendingOffset;

    InboundChunkStream(InboundReceiver receiver) {
        this.receiver = receiver;
    }

    InboundChunk nextChunk() {
        if (pending == null) {
            return receiver.receive();
        }

        InboundChunk chunk = pending;
        int remaining = chunk.length() - pendingOffset;
        if (pendingOffset != 0) {
            MemorySegment.copy(chunk.segment(), pendingOffset, chunk.segment(), 0, remaining);
            chunk.length(remaining);
        }
        pending = null;
        pendingOffset = 0;
        return chunk;
    }

    int appendOnce(InboundChunk destination, int destinationLength, int maximumLength) {
        if (destinationLength >= maximumLength) {
            return destinationLength;
        }

        InboundChunk source = nextChunk();
        if (source == null) {
            return -1;
        }

        int copied = Math.min(source.length(), maximumLength - destinationLength);
        MemorySegment.copy(source.segment(), 0, destination.segment(), destinationLength, copied);
        if (copied == source.length()) {
            source.close();
        } else {
            pending = source;
            pendingOffset = copied;
        }

        int newLength = destinationLength + copied;
        destination.length(newLength);
        return newLength;
    }

    int copyOnce(MemorySegment destination, int destinationOffset, int maximumLength) {
        if (destinationOffset >= maximumLength) {
            return destinationOffset;
        }

        InboundChunk source = nextChunk();
        if (source == null) {
            return -1;
        }

        int copied = Math.min(source.length(), maximumLength - destinationOffset);
        MemorySegment.copy(source.segment(), 0, destination, destinationOffset, copied);
        if (copied == source.length()) {
            source.close();
        } else {
            pending = source;
            pendingOffset = copied;
        }
        return destinationOffset + copied;
    }

    @Override
    public void close() {
        if (pending != null) {
            pending.close();
            pending = null;
            pendingOffset = 0;
        }
    }
}
