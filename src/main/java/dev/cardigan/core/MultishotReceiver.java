// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;

/**
 * Bounded per-connection receive queue backed by one multishot RECV.
 */
final class MultishotReceiver implements UringEventLoop.CompletionHandler, InboundReceiver {
    private static final VarHandle QUEUE_SIZE;
    private static final VarHandle RECEIVE_WAITER;
    private static final VarHandle HANDOFF_CHUNK;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            QUEUE_SIZE = lookup.findVarHandle(MultishotReceiver.class, "queueSize", int.class);
            RECEIVE_WAITER =
                lookup.findVarHandle(MultishotReceiver.class, "receiveWaiter", Thread.class);
            HANDOFF_CHUNK = lookup.findVarHandle(
                MultishotReceiver.class, "handoffChunk", InboundChunk.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    interface Observer {
        void multishotCompletionObserved();

        void fallbackActivated();

        void backpressureActivated();

        void requiredFeatureRejected(String feature, int result);
    }

    private static final int ECANCELED = -125;
    private static final int ENOBUFS = -105;
    private static final int EINVAL = -22;

    private final UringEventLoop loop;
    private final int clientFd;
    private final int fixedSlot;
    private final Observer observer;
    private final InboundChunk[] queue;
    private final int queueMask;
    private final int highWatermark;
    private final int lowWatermark;

    private int queueHead;
    private int queueSize;
    private long receiveToken = -1;
    private int observedCompletions;
    private boolean active;
    private boolean cancelPending;
    private boolean waitingForBuffer;
    private boolean eof;
    private boolean failed;
    private boolean closed;
    private Thread receiveWaiter;
    private InboundChunk handoffChunk;
    private volatile Thread closeWaiter;

    MultishotReceiver(UringEventLoop loop, int clientFd, int fixedSlot, Observer observer) {
        this.loop = loop;
        this.clientFd = clientFd;
        this.fixedSlot = fixedSlot;
        this.observer = observer;

        int requestedCapacity = Integer.getInteger("cardigan.receive.max.queued.chunks", 64);
        int capacity = 2;
        while (capacity < Math.max(2, requestedCapacity)) {
            capacity <<= 1;
        }
        this.queue = new InboundChunk[capacity];
        this.queueMask = capacity - 1;

        int requestedHigh = Integer.getInteger("cardigan.receive.pause.chunks", 16);
        this.highWatermark = Math.max(1, Math.min(requestedHigh, capacity - 1));
        this.lowWatermark = highWatermark >>> 1;
    }

    @Override
    public void start() {
        maybeArm();
    }

    @Override
    public InboundChunk receive() {
        Thread current = Thread.currentThread();
        while (!closed) {
            InboundChunk chunk = takeHandoff();
            if (chunk == null) {
                chunk = poll();
            }
            if (chunk != null) {
                maybeArm();
                return chunk;
            }
            if (eof || failed) {
                return null;
            }

            maybeArm();
            setReceiveWaiter(current);
            if (handoffChunk() != null || queueSize() != 0
                    || eof || failed || closed) {
                setReceiveWaiter(null);
                continue;
            }
            LockSupport.park(this);
            setReceiveWaiter(null);
        }
        return null;
    }

    @Override
    public void onCompletion(int result, int flags, boolean terminal) {
        if ((flags & Opcodes.IORING_CQE_F_MORE) != 0 && observedCompletions < 2) {
            observedCompletions++;
            observer.multishotCompletionObserved();
        }

        if (result > 0) {
            int bufferId = (flags & Opcodes.IORING_CQE_F_BUFFER) != 0
                ? (flags >>> Opcodes.IORING_CQE_BUFFER_SHIFT) & 0xffff
                : -1;
            if (bufferId < 0) {
                failed = true;
            } else {
                InboundChunk chunk =
                    loop.leaseInboundChunk(bufferId, result);
                if (closed || (!tryHandoff(chunk) && !offer(chunk))) {
                    chunk.close();
                    if (!closed) {
                        failed = true;
                    }
                }
            }
        }

        if (!terminal) {
            if (queueSize() >= highWatermark) {
                if (!cancelPending) {
                    observer.backpressureActivated();
                }
                pause();
            }
            signalReceiveWaiter();
            return;
        }

        active = false;
        cancelPending = false;
        receiveToken = -1;
        boolean waitForReturnedBuffer = result == ENOBUFS;

        if (!closed) {
            if (result == EINVAL) {
                failed = true;
                observer.requiredFeatureRejected(
                    "IORING_RECV_MULTISHOT", result);
            } else if (result == 0) {
                eof = true;
            } else if (result == ECANCELED || result == ENOBUFS) {
                if (result == ENOBUFS && !waitingForBuffer) {
                    waitingForBuffer = true;
                    loop.whenInboundBufferAvailable(this::bufferAvailable);
                }
            } else if (result < 0) {
                failed = true;
            }

            if (!waitForReturnedBuffer && !eof && !failed
                && queueSize() <= lowWatermark) {
                maybeArm();
            }
        }

        signalReceiveWaiter();
        signalCloseWaiter();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        InboundChunk chunk;
        chunk = takeHandoff();
        if (chunk != null) {
            chunk.close();
        }
        while ((chunk = poll()) != null) {
            chunk.close();
        }

        if (!active) {
            return;
        }

        pause();
        if (!active) {
            return;
        }

        Thread current = Thread.currentThread();
        closeWaiter = current;
        while (active) {
            LockSupport.park(this);
        }
        closeWaiter = null;
    }

    private boolean offer(InboundChunk chunk) {
        int size = queueSize();
        if (size == queue.length) {
            return false;
        }
        int index = (queueHead + size) & queueMask;
        queue[index] = chunk;
        setQueueSize(size + 1);
        return true;
    }

    private InboundChunk poll() {
        int size = queueSize();
        if (size == 0) {
            return null;
        }
        InboundChunk chunk = queue[queueHead];
        queue[queueHead] = null;
        queueHead = (queueHead + 1) & queueMask;
        setQueueSize(size - 1);
        return chunk;
    }

    private boolean tryHandoff(InboundChunk chunk) {
        Thread waiter = receiveWaiter();
        if (waiter == null) {
            return false;
        }
        // The receive continuation and CQ callback are mounted serially on the
        // owning io_uring carrier, and there is exactly one receiver waiter.
        // An acquire read followed by a release clear therefore has no
        // intervening writer and does not need a locked compare-and-set.
        setReceiveWaiter(null);
        HANDOFF_CHUNK.setRelease(this, chunk);
        LockSupport.unpark(waiter);
        return true;
    }

    private InboundChunk takeHandoff() {
        InboundChunk chunk = handoffChunk();
        if (chunk != null) {
            // Owner-carrier serialization guarantees a single consumer here;
            // the acquire read pairs with the CQ callback's release store.
            HANDOFF_CHUNK.setRelease(this, null);
        }
        return chunk;
    }

    private InboundChunk handoffChunk() {
        return (InboundChunk) HANDOFF_CHUNK.getAcquire(this);
    }

    private void pause() {
        if (!active || cancelPending) {
            return;
        }
        cancelPending = true;
        if (!loop.cancelAsync(receiveToken, (result, flags, terminal) -> {
        })) {
            cancelPending = false;
            failed = true;
            active = false;
            signalReceiveWaiter();
            signalCloseWaiter();
        }
    }

    private void maybeArm() {
        if (closed || active || cancelPending || eof || failed || waitingForBuffer
            || queueSize() > lowWatermark) {
            return;
        }
        long token = loop.recvMultishot(clientFd, UringEventLoop.BUF_GROUP, fixedSlot, this);
        if (token < 0) {
            // Submission-resource exhaustion rejects this connection while
            // preserving the configured multishot receive model.
            failed = true;
            signalReceiveWaiter();
            return;
        }
        receiveToken = token;
        active = true;
    }

    private void bufferAvailable() {
        waitingForBuffer = false;
        maybeArm();
    }

    private void signalReceiveWaiter() {
        Thread waiter = receiveWaiter();
        if (waiter != null) {
            // See tryHandoff(): no second consumer can clear or replace this
            // single waiter while the owner callback is mounted.
            setReceiveWaiter(null);
            LockSupport.unpark(waiter);
        }
    }

    private void signalCloseWaiter() {
        Thread waiter = closeWaiter;
        if (waiter != null) {
            LockSupport.unpark(waiter);
        }
    }

    private int queueSize() {
        return (int) QUEUE_SIZE.getAcquire(this);
    }

    private void setQueueSize(int value) {
        QUEUE_SIZE.setRelease(this, value);
    }

    private Thread receiveWaiter() {
        return (Thread) RECEIVE_WAITER.getAcquire(this);
    }

    private void setReceiveWaiter(Thread waiter) {
        RECEIVE_WAITER.setRelease(this, waiter);
    }
}
