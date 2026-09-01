// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.RequestBody;
import dev.cardigan.http.RequestBodyException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.IntConsumer;

/** Bounded SPSC body ring between the HTTP/2 frame pump and one handler. */
final class Http2RequestBody implements RequestBody {
    private static final VarHandle HEAD;
    private static final VarHandle TAIL;
    private static final VarHandle OWNERS;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            HEAD = lookup.findVarHandle(
                Http2RequestBody.class, "head", long.class);
            TAIL = lookup.findVarHandle(
                Http2RequestBody.class, "tail", long.class);
            OWNERS = lookup.findVarHandle(
                Http2RequestBody.class, "owners", int.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private final Arena arena = Arena.ofShared();
    private final MemorySegment buffer;
    private final int capacity;
    private final long length;
    private final IntConsumer consumed;
    private final BlockingSupport blockingSupport;
    private long head;
    private long tail;
    private long delivered;
    private int owners = 1;
    private volatile boolean ended;
    private volatile boolean failed;
    private volatile boolean discarding;
    private volatile Runnable waiter;

    Http2RequestBody(long length, int capacity, IntConsumer consumed) {
        this(
            length,
            capacity,
            consumed,
            BlockingSupport.nonBlocking()
        );
    }

    Http2RequestBody(
            long length,
            int capacity,
            IntConsumer consumed,
            BlockingSupport blockingSupport) {
        this.length = length;
        this.capacity = capacity;
        this.consumed = consumed;
        this.blockingSupport = blockingSupport;
        this.buffer = arena.allocate(capacity, 64);
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public long remaining() {
        return length < 0 ? -1 : Math.max(0, length - delivered);
    }

    @Override
    public int read(MemorySegment destination) {
        if (destination.byteSize() == 0) {
            return 0;
        }
        long available = awaitAvailable();
        if (available == 0) {
            return -1;
        }
        int count = Math.toIntExact(Math.min(
            Math.min(available, destination.byteSize()), Integer.MAX_VALUE));
        long currentHead = (long) HEAD.getAcquire(this);
        int index = (int) (currentHead % capacity);
        int first = Math.min(count, capacity - index);
        MemorySegment.copy(buffer, index, destination, 0, first);
        if (first != count) {
            MemorySegment.copy(buffer, 0, destination, first, count - first);
        }
        HEAD.setRelease(this, currentHead + count);
        delivered += count;
        consumed.accept(count);
        return count;
    }

    @Override
    public void close() {
        if (discarding) {
            return;
        }
        discarding = true;
        if (length >= 0) {
            delivered = length;
        }
        drainDiscarded();
        signalWaiter();
    }

    boolean offer(MemorySegment source, int sourceOffset, int length) {
        if (length == 0) {
            return true;
        }
        if (discarding) {
            delivered += length;
            consumed.accept(length);
            return true;
        }
        long currentTail = (long) TAIL.getAcquire(this);
        long currentHead = (long) HEAD.getAcquire(this);
        if (length > capacity - (currentTail - currentHead)) {
            return false;
        }
        int index = (int) (currentTail % capacity);
        int first = Math.min(length, capacity - index);
        MemorySegment.copy(source, sourceOffset, buffer, index, first);
        if (first != length) {
            MemorySegment.copy(
                source, sourceOffset + first, buffer, 0, length - first);
        }
        TAIL.setRelease(this, currentTail + length);
        // close() can race the publication above. Whichever side observes
        // the new tail drains it with one atomic head exchange, so receive
        // window credit is neither lost nor issued twice.
        if (discarding) {
            drainDiscarded();
        }
        signalWaiter();
        return true;
    }

    boolean discarding() {
        return discarding;
    }

    void end() {
        ended = true;
        signalWaiter();
    }

    void fail() {
        failed = true;
        drainDiscarded();
        signalWaiter();
    }

    void dispose() {
        // Single-owner streaming bodies close directly. Isolated handlers
        // retain the ring and require the atomic release path.
        if ((int) OWNERS.getAcquire(this) == 1) {
            arena.close();
        } else {
            releaseOwner();
        }
    }

    /** Retains the ring until an isolated handler has actually stopped. */
    void retainHandler() {
        OWNERS.getAndAdd(this, 1);
    }

    void handlerComplete() {
        releaseOwner();
    }

    private void releaseOwner() {
        if ((int) OWNERS.getAndAdd(this, -1) == 1) {
            arena.close();
        }
    }

    private long awaitAvailable() {
        if (failed) {
            throw new RequestBodyException(
                "HTTP/2 request body aborted");
        }
        try {
            blockingSupport.awaitInterruptibly(
                this,
                () -> !failed && !ended && !discarding
                    && (long) TAIL.getAcquire(this)
                        == (long) HEAD.getAcquire(this),
                wakeup -> waiter = wakeup,
                wakeup -> waiter = null
            );
        } catch (InterruptedException failure) {
            throw new RequestBodyException(
                "Interrupted while reading HTTP/2 request body");
        }
        if (failed) {
            throw new RequestBodyException(
                "HTTP/2 request body aborted");
        }
        return (long) TAIL.getAcquire(this)
            - (long) HEAD.getAcquire(this);
    }

    private void signalWaiter() {
        Runnable waiting = waiter;
        if (waiting != null) {
            waiting.run();
        }
    }

    private void drainDiscarded() {
        long currentTail = (long) TAIL.getAcquire(this);
        long previousHead = (long) HEAD.getAndSet(this, currentTail);
        int buffered = Math.toIntExact(currentTail - previousHead);
        if (buffered > 0) {
            consumed.accept(buffered);
        }
    }
}
