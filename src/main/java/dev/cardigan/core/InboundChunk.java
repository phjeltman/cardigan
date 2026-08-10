// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.IntConsumer;

/**
 * Owns one buffer selected from an io_uring provided-buffer ring.
 *
 * Ownership is exclusive and may move between virtual threads. A final release
 * is marshalled onto the owning event loop before the buffer is returned to the
 * kernel-visible ring. Plaintext provided-buffer wrappers are reused by their
 * owning loop only after that return has been published.
 */
class InboundChunk implements AutoCloseable, Runnable {
    private static final int PROVIDED = 0;
    private static final int LEASED = 1;
    private static final int RETURN_PENDING = 2;
    private static final int CLOSED = 3;
    private static final VarHandle STATE;

    static {
        try {
            STATE = MethodHandles.lookup()
                .findVarHandle(InboundChunk.class, "state", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final UringEventLoop owner;
    private final IntConsumer testReleaser;
    private final int bufferId;
    private final MemorySegment segment;
    private final boolean reusable;
    private int state;
    private int length;

    InboundChunk(UringEventLoop owner, MemorySegment segment,
                 int bufferId, int length) {
        this(owner, segment, bufferId, length, null, false);
    }

    InboundChunk(UringEventLoop owner, MemorySegment segment, int bufferId) {
        this(owner, segment, bufferId, 0, null, true);
    }

    InboundChunk(MemorySegment segment, int bufferId, int length, IntConsumer releaser) {
        this(null, segment, bufferId, length, releaser, false);
    }

    private InboundChunk(UringEventLoop owner, MemorySegment segment,
                         int bufferId, int length, IntConsumer testReleaser,
                         boolean reusable) {
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
        this.reusable = reusable;
        this.state = reusable ? PROVIDED : LEASED;
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

    InboundChunk lease(int newLength) {
        if (!reusable) {
            throw new IllegalStateException("Inbound chunk is not reusable");
        }
        validateLength(newLength);
        int current = state();
        if (current != PROVIDED) {
            throw new IllegalStateException(
                "Provided buffer " + bufferId + " is already leased");
        }
        length = newLength;
        STATE.setRelease(this, LEASED);
        return this;
    }

    void beginOwnerReturn() {
        int current = state();
        if (current != LEASED) {
            throw alreadyReleased();
        }
        STATE.setRelease(this, reusable ? RETURN_PENDING : CLOSED);
    }

    void beginExternalReturn() {
        int next = reusable ? RETURN_PENDING : CLOSED;
        if (!STATE.compareAndSet(this, LEASED, next)) {
            throw alreadyReleased();
        }
    }

    void markProvided() {
        if (!reusable || state() != RETURN_PENDING) {
            throw new IllegalStateException(
                "Inbound buffer " + bufferId + " was not pending return");
        }
        length = 0;
        STATE.setRelease(this, PROVIDED);
    }

    boolean isProvided() {
        return reusable && state() == PROVIDED;
    }

    private int state() {
        return (int) STATE.getAcquire(this);
    }

    @Override
    public void close() {
        if (owner != null) {
            owner.releaseInboundChunk(this);
        } else {
            beginExternalReturn();
            testReleaser.accept(bufferId);
        }
    }

    @Override
    public void run() {
        owner.returnInboundChunk(this);
    }

    private void validateLength(int newLength) {
        if (newLength < 0 || newLength > segment.byteSize()) {
            throw new IllegalArgumentException(
                "Invalid chunk length: " + newLength);
        }
    }

    private IllegalStateException alreadyReleased() {
        return new IllegalStateException(
            "Inbound chunk " + bufferId + " was already released");
    }
}
