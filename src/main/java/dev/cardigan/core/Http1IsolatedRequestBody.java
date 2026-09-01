// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.RequestBody;
import dev.cardigan.http.RequestBodyException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded SPSC handoff between an HTTP/1 connection continuation and an
 * isolated request handler. The producer remains on the io_uring scheduler;
 * only copied body bytes cross the carrier boundary.
 */
final class Http1IsolatedRequestBody implements RequestBody {
    private static final VarHandle HEAD;
    private static final VarHandle TAIL;

    private static final int CAPACITY = Math.max(
        1_024,
        Integer.getInteger(
            "cardigan.http1.isolated.streaming.buffer.bytes", 65_536));
    private static final long MAX_ACTIVE_BYTES = Math.max(
        CAPACITY,
        Long.getLong(
            "cardigan.http1.max.isolated.streaming.buffer.bytes",
            64L * 1_024 * 1_024));
    private static final AtomicLong ACTIVE_BYTES = new AtomicLong();

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            HEAD = lookup.findVarHandle(
                Http1IsolatedRequestBody.class, "head", long.class);
            TAIL = lookup.findVarHandle(
                Http1IsolatedRequestBody.class, "tail", long.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private final Arena arena;
    private final MemorySegment buffer;
    private final long length;
    private final AtomicInteger owners = new AtomicInteger(2);
    private final BlockingSupport blockingSupport;

    private long head;
    private long tail;
    private long delivered;
    private volatile boolean ended;
    private volatile boolean failed;
    private volatile boolean discarding;
    private volatile String failureMessage;
    private volatile Runnable readerWaiter;
    private volatile Runnable writerWaiter;

    static Http1IsolatedRequestBody acquire(
            long length, BlockingSupport blockingSupport) {
        long active = ACTIVE_BYTES.get();
        while (active <= MAX_ACTIVE_BYTES - CAPACITY) {
            if (ACTIVE_BYTES.compareAndSet(active, active + CAPACITY)) {
                try {
                    return new Http1IsolatedRequestBody(
                        length, blockingSupport);
                } catch (Throwable failure) {
                    ACTIVE_BYTES.addAndGet(-CAPACITY);
                    throw failure;
                }
            }
            active = ACTIVE_BYTES.get();
        }
        return null;
    }

    private Http1IsolatedRequestBody(
            long length, BlockingSupport blockingSupport) {
        this.length = length;
        this.blockingSupport = blockingSupport;
        this.arena = Arena.ofShared();
        this.buffer = arena.allocate(CAPACITY, 64);
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
        long available = awaitReadable();
        if (available == 0) {
            return -1;
        }

        int count = Math.toIntExact(Math.min(
            Math.min(available, destination.byteSize()), Integer.MAX_VALUE));
        long currentHead = (long) HEAD.getAcquire(this);
        int index = (int) (currentHead % CAPACITY);
        int first = Math.min(count, CAPACITY - index);
        MemorySegment.copy(buffer, index, destination, 0, first);
        if (first != count) {
            MemorySegment.copy(
                buffer, 0, destination, first, count - first);
        }
        HEAD.setRelease(this, currentHead + count);
        delivered += count;
        signalWriter();
        return count;
    }

    /** Writes all bytes unless the handler has discarded or failed the body. */
    boolean write(MemorySegment source, int sourceOffset, int length) {
        int written = 0;
        while (written < length) {
            if (discarding || failed) {
                return false;
            }

            long currentTail = (long) TAIL.getAcquire(this);
            long currentHead = (long) HEAD.getAcquire(this);
            int available = Math.toIntExact(
                CAPACITY - (currentTail - currentHead));
            if (available == 0) {
                try {
                    blockingSupport.awaitInterruptibly(
                        this,
                        () -> (long) TAIL.getAcquire(this)
                                - (long) HEAD.getAcquire(this) >= CAPACITY
                            && !discarding && !failed,
                        wakeup -> writerWaiter = wakeup,
                        wakeup -> writerWaiter = null
                    );
                } catch (InterruptedException failure) {
                    fail("HTTP/1 body handoff interrupted");
                    return false;
                }
                continue;
            }

            int count = Math.min(available, length - written);
            int index = (int) (currentTail % CAPACITY);
            int first = Math.min(count, CAPACITY - index);
            MemorySegment.copy(
                source, sourceOffset + written,
                buffer, index, first);
            if (first != count) {
                MemorySegment.copy(
                    source, sourceOffset + written + first,
                    buffer, 0, count - first);
            }
            TAIL.setRelease(this, currentTail + count);
            written += count;
            signalReader();
        }
        return true;
    }

    void end() {
        ended = true;
        signalReader();
    }

    void fail(String message) {
        failureMessage = message;
        failed = true;
        signalReader();
        signalWriter();
    }

    @Override
    public void close() {
        if (discarding) {
            return;
        }
        discarding = true;
        HEAD.setRelease(this, (long) TAIL.getAcquire(this));
        signalReader();
        signalWriter();
    }

    void producerComplete() {
        releaseOwner();
    }

    void handlerComplete() {
        releaseOwner();
    }

    private long awaitReadable() {
        while (true) {
            if (failed) {
                throw new RequestBodyException(failureMessage != null
                    ? failureMessage
                    : "HTTP/1 request body aborted");
            }
            long available = (long) TAIL.getAcquire(this)
                - (long) HEAD.getAcquire(this);
            if (available != 0) {
                return available;
            }
            if (ended || discarding) {
                return 0;
            }

            try {
                blockingSupport.awaitInterruptibly(
                    this,
                    () -> (long) TAIL.getAcquire(this)
                            == (long) HEAD.getAcquire(this)
                        && !ended && !discarding && !failed,
                    wakeup -> readerWaiter = wakeup,
                    wakeup -> readerWaiter = null
                );
            } catch (InterruptedException failure) {
                throw new RequestBodyException(
                    "Interrupted while reading HTTP/1 request body");
            }
        }
    }

    private void signalReader() {
        Runnable waiter = readerWaiter;
        if (waiter != null) {
            waiter.run();
        }
    }

    private void signalWriter() {
        Runnable waiter = writerWaiter;
        if (waiter != null) {
            waiter.run();
        }
    }

    private void releaseOwner() {
        if (owners.decrementAndGet() != 0) {
            return;
        }
        arena.close();
        ACTIVE_BYTES.addAndGet(-CAPACITY);
    }
}
