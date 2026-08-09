// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.IntConsumer;

/**
 * Owns one buffer selected from an io_uring provided-buffer ring.
 *
 * References may cross virtual-thread boundaries. The final release is
 * marshalled onto the owning event loop before the buffer is returned to the
 * kernel-visible ring.
 */
class InboundChunk implements AutoCloseable, Runnable {
    private static final VarHandle REFERENCES;

    static {
        try {
            REFERENCES = MethodHandles.lookup()
                .findVarHandle(InboundChunk.class, "references", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final UringEventLoop owner;
    private final IntConsumer testReleaser;
    private final int bufferId;
    private final MemorySegment segment;
    private int references = 1;
    private int length;

    InboundChunk(UringEventLoop owner, int bufferId, int length) {
        this(owner, owner.getBufferSegment(bufferId), bufferId, length, null);
    }

    InboundChunk(UringEventLoop owner, MemorySegment segment,
                 int bufferId, int length) {
        this(owner, segment, bufferId, length, null);
    }

    InboundChunk(MemorySegment segment, int bufferId, int length, IntConsumer releaser) {
        this(null, segment, bufferId, length, releaser);
    }

    private InboundChunk(UringEventLoop owner, MemorySegment segment,
                         int bufferId, int length, IntConsumer testReleaser) {
        if (owner == null && testReleaser == null) {
            throw new IllegalArgumentException("A buffer owner is required");
        }
        if (length < 0 || length > segment.byteSize()) {
            throw new IllegalArgumentException("Invalid chunk length: " + length);
        }
        this.owner = owner;
        this.testReleaser = testReleaser;
        this.bufferId = bufferId;
        this.segment = segment;
        this.length = length;
    }

    int bufferId() {
        return bufferId;
    }

    short bufferGroup() {
        return UringEventLoop.BUF_GROUP;
    }

    MemorySegment segment() {
        return segment;
    }

    int length() {
        return length;
    }

    void length(int newLength) {
        if (newLength < 0 || newLength > segment.byteSize()) {
            throw new IllegalArgumentException("Invalid chunk length: " + newLength);
        }
        length = newLength;
    }

    InboundChunk retain() {
        int current;
        do {
            current = referenceCount();
            if (current <= 0) {
                throw new IllegalStateException("Cannot retain a released inbound chunk");
            }
        } while (!REFERENCES.compareAndSet(this, current, current + 1));
        return this;
    }

    int referenceCount() {
        return (int) REFERENCES.getAcquire(this);
    }

    @Override
    public void close() {
        int current;
        do {
            current = referenceCount();
            if (current <= 0) {
                throw new IllegalStateException("Inbound chunk released more than retained");
            }
        } while (!REFERENCES.compareAndSet(this, current, current - 1));

        int remaining = current - 1;
        if (remaining == 0) {
            if (owner != null) {
                owner.releaseInboundChunk(this);
            } else {
                testReleaser.accept(bufferId);
            }
        }
    }

    @Override
    public void run() {
        owner.returnBuffer(bufferId);
    }
}
