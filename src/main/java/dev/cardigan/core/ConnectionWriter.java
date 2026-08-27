// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.tls.TlsConnection;
import dev.cardigan.tls.TlsStats;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;

/**
 * Serializes asynchronous response frames for a single stream socket.
 *
 * Queue mutation is confined to the owning {@link UringEventLoop}.
 * Acquire/release state publishes drain progress to a parked virtual thread;
 * it does not protect the queue itself.
 */
final class ConnectionWriter implements UringEventLoop.CompletionHandler,
        UringEventLoop.EgressTask {
    private static final int INITIAL_QUEUE_CAPACITY = 16;
    private static final int INITIAL_DRAIN_WAITER_CAPACITY = 4;
    private static final int MAX_VECTOR_BYTES =
        UringEventLoop.MAX_SEND_VECTORS * UringEventLoop.EGRESS_FRAME_SIZE;
    private static final VarHandle PENDING_FRAMES;

    static {
        try {
            PENDING_FRAMES = MethodHandles.lookup()
                .findVarHandle(ConnectionWriter.class, "pendingFrames", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final UringEventLoop loop;
    private final int clientFd;
    private final int fixedSlot;
    private final TlsConnection tls;
    private final boolean directKtlsSend;
    private final boolean completionSendChaining;

    private int[] bufferIds;
    private long[] addresses;
    private int[] lengths;
    private MemorySegment[] references;
    private int queueHead;
    private int queueSize;

    private boolean startScheduled;
    private boolean sendInFlight;
    private int inFlightFrameCount;
    private int inFlightBufferCount;
    private int[] inFlightBufferIds;
    private long[] inFlightAddresses;
    private int[] inFlightLengths;
    private MemorySegment[] inFlightReferences;
    private MemorySegment inFlightReference;
    private int pendingFrames;
    private volatile int failure;
    private Thread[] drainWaiters;
    private int drainWaiterHead;
    private int drainWaiterCount;
    private Runnable drainHandoff;

    ConnectionWriter(UringEventLoop loop, int clientFd, int fixedSlot) {
        this(loop, clientFd, fixedSlot, null, false);
    }

    ConnectionWriter(UringEventLoop loop, int clientFd, int fixedSlot,
                     TlsConnection tls) {
        this(loop, clientFd, fixedSlot, tls, false);
    }

    private ConnectionWriter(UringEventLoop loop, int clientFd, int fixedSlot,
                             TlsConnection tls,
                             boolean completionSendChaining) {
        this.loop = loop;
        this.clientFd = clientFd;
        this.fixedSlot = fixedSlot;
        this.tls = tls;
        this.directKtlsSend = tls != null && tls.directKtlsSend();
        this.completionSendChaining = completionSendChaining;
    }

    UringEventLoop eventLoop() {
        return loop;
    }

    boolean enqueue(int bufferId, int length) {
        return enqueue(
            bufferId,
            loop.getEgressBufferSegment(bufferId).address(),
            length,
            null
        );
    }

    boolean enqueueBorrowed(MemorySegment segment, int offset, int length) {
        if (segment == null
            || !segment.isNative()
            || offset < 0
            || length <= 0
            || (long) offset + length > segment.byteSize()) {
            throw new IllegalArgumentException("Invalid borrowed send segment");
        }
        return enqueue(-1, segment.address() + offset, length, segment);
    }

    private boolean enqueue(int bufferId, long address, int length,
                            MemorySegment reference) {
        if (failure != 0) {
            if (bufferId >= 0) {
                loop.releaseEgressBuffer(bufferId);
            }
            return false;
        }

        setPendingFrames(pendingFrames() + 1);
        addQueued(bufferId, address, length, reference);
        if (!sendInFlight && !startScheduled) {
            startScheduled = true;
            loop.executeEgress(this);
        }
        return true;
    }

    boolean awaitDrained() {
        if (pendingFrames() == 0 || failure != 0) {
            return failure == 0;
        }

        Thread current = Thread.currentThread();
        boolean interrupted = false;
        boolean waited = false;
        while (pendingFrames() != 0 && failure == 0) {
            addDrainWaiter(current);
            try {
                waited = true;
                LockSupport.park(this);
                if (pendingFrames() != 0 && failure == 0
                    && Thread.interrupted()) {
                    interrupted = true;
                }
            } finally {
                removeDrainWaiter(current);
            }
        }
        if (waited && failure == 0 && drainWaiterCount != 0) {
            // A resumed producer normally queues a burst whose completion
            // advances the FIFO. If cancellation returns without writing,
            // this deferred check transfers the idle writer to the next
            // producer.
            Runnable handoff = drainHandoff;
            if (handoff == null) {
                drainHandoff = handoff = this::signalIfDrained;
            }
            loop.execute(handoff);
        }
        if (interrupted) {
            current.interrupt();
        }
        return failure == 0;
    }

    int writeFully(MemorySegment buffer, int length) {
        if (!awaitDrained()) {
            return failure;
        }
        if (tls != null && !directKtlsSend) {
            return tls.writeFully(buffer, length);
        }
        if (directKtlsSend) {
            tls.awaitDirectSendReady();
        }
        int result = loop.writeFully(clientFd, buffer, length, fixedSlot);
        if (result > 0 && directKtlsSend && TlsStats.ENABLED) {
            TlsStats.directSend(1);
        }
        return result;
    }

    @Override
    public void run() {
        startScheduled = false;
        startNextSend();
    }

    @Override
    public void onCompletion(int result, int flags, boolean terminal) {
        if (!terminal) {
            return;
        }

        releaseInFlightVectorBuffers();
        inFlightReference = null;
        sendInFlight = false;
        setPendingFrames(pendingFrames() - inFlightFrameCount);
        inFlightFrameCount = 0;

        if (result <= 0) {
            fail(result == 0 ? -32 : result);
            return;
        }

        if (queueSize != 0) {
            if (completionSendChaining) {
                // Queued buffers predate this completion and belong to the
                // same causal egress range. Prepare the successor SQE
                // immediately; it is published at the epoch boundary, keeping
                // the connection's single-send pipeline full.
                startNextSend();
            } else if (!startScheduled) {
                startScheduled = true;
                loop.executeEgress(this);
            }
            return;
        }

        signalIfDrained();
    }

    private boolean submit(int bufferId, long address, int length,
                           MemorySegment reference) {
        if (tls != null && !directKtlsSend) {
            ensureVectorStorage();
            inFlightBufferIds[0] = bufferId;
            inFlightAddresses[0] = address;
            inFlightLengths[0] = length;
            inFlightReferences[0] = reference;
            inFlightBufferCount = 1;
            inFlightReference = reference;
            boolean submitted = submitTls(
                inFlightAddresses, inFlightLengths, 1);
            if (!submitted) {
                // startNextSend owns failure cleanup for a single submission.
                inFlightBufferCount = 0;
                inFlightReferences[0] = null;
            }
            return submitted;
        }
        MemorySegment buffer = bufferId >= 0
            ? loop.getEgressBufferSegment(bufferId)
            : reference.asSlice(address - reference.address(), length);
        inFlightReference = reference;
        boolean submitted = loop.writeAsync(
            clientFd, buffer, length, fixedSlot, bufferId, this);
        if (submitted && directKtlsSend && TlsStats.ENABLED) {
            TlsStats.directSend(1);
        }
        return submitted;
    }

    private boolean submitVector(int count) {
        if (tls != null && !directKtlsSend) {
            return submitTls(inFlightAddresses, inFlightLengths, count);
        }
        boolean submitted = loop.writeVectorAsync(
            clientFd,
            inFlightAddresses,
            inFlightLengths,
            count,
            fixedSlot,
            this
        );
        if (submitted && directKtlsSend && TlsStats.ENABLED) {
            TlsStats.directSend(count);
        }
        return submitted;
    }

    private boolean submitTls(long[] sendAddresses, int[] sendLengths,
                              int count) {
        try {
            if (TlsStats.ENABLED) {
                TlsStats.sendTask(count);
            }
            loop.startVirtualThread(() -> {
                int result = 0;
                try {
                    for (int i = 0; i < count; i++) {
                        int written = tls.writeFully(
                            sendAddresses[i], sendLengths[i]);
                        if (written <= 0) {
                            result = written;
                            break;
                        }
                        result += written;
                    }
                } catch (Throwable t) {
                    result = -5;
                }
                int completionResult = result;
                try {
                    loop.execute(() -> onCompletion(
                        completionResult, 0, true));
                } catch (Throwable ignored) {
                    // The owning loop is already shutting down.
                }
            });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void startNextSend() {
        if (sendInFlight || failure != 0 || queueSize == 0) {
            signalIfDrained();
            return;
        }
        if (directKtlsSend && tls.deferDirectSend(this)) {
            startScheduled = true;
            return;
        }

        int bufferId = removeBufferId();
        long address = removeAddress();
        MemorySegment reference = removeReference();
        int length = removeLength();
        int frameCount = 1;
        MemorySegment destination = bufferId >= 0
            ? loop.getEgressBufferSegment(bufferId)
            : null;

        while (destination != null && queueSize != 0) {
            int nextIndex = queueHead;
            int nextLength = lengths[nextIndex];
            if (bufferIds[nextIndex] < 0
                || length + nextLength > UringEventLoop.EGRESS_FRAME_SIZE) {
                break;
            }

            int nextBufferId = removeBufferId();
            removeAddress();
            removeReference();
            nextLength = removeLength();
            MemorySegment.copy(loop.getEgressBufferSegment(nextBufferId), 0, destination, length, nextLength);
            loop.releaseEgressBuffer(nextBufferId);
            length += nextLength;
            frameCount++;
        }

        int vectorCount = 1;
        int vectorBytes = length;
        if (queueSize != 0) {
            ensureVectorStorage();
            inFlightBufferIds[0] = bufferId;
            inFlightAddresses[0] = address;
            inFlightLengths[0] = length;
            inFlightReferences[0] = reference;
            while (queueSize != 0 && vectorCount < UringEventLoop.MAX_SEND_VECTORS) {
                int nextIndex = queueHead;
                int nextLength = lengths[nextIndex];
                if (vectorBytes + nextLength > MAX_VECTOR_BYTES) {
                    break;
                }
                inFlightBufferIds[vectorCount] = removeBufferId();
                inFlightAddresses[vectorCount] = removeAddress();
                inFlightReferences[vectorCount] = removeReference();
                inFlightLengths[vectorCount] = removeLength();
                vectorBytes += nextLength;
                vectorCount++;
                frameCount++;
            }
        }

        sendInFlight = true;
        inFlightFrameCount = frameCount;
        boolean submitted;
        if (vectorCount == 1) {
            submitted = submit(bufferId, address, length, reference);
        } else {
            inFlightBufferCount = vectorCount;
            submitted = submitVector(vectorCount);
        }
        if (!submitted) {
            sendInFlight = false;
            inFlightFrameCount = 0;
            if (vectorCount == 1) {
                inFlightReference = null;
                if (bufferId >= 0) {
                    loop.releaseEgressBuffer(bufferId);
                }
            } else {
                releaseInFlightVectorBuffers();
            }
            setPendingFrames(pendingFrames() - frameCount);
            fail(-5);
        }
    }

    private void ensureVectorStorage() {
        if (inFlightBufferIds == null) {
            inFlightBufferIds = new int[UringEventLoop.MAX_SEND_VECTORS];
            inFlightAddresses = new long[UringEventLoop.MAX_SEND_VECTORS];
            inFlightLengths = new int[UringEventLoop.MAX_SEND_VECTORS];
            inFlightReferences =
                new MemorySegment[UringEventLoop.MAX_SEND_VECTORS];
        }
    }

    private void releaseInFlightVectorBuffers() {
        for (int i = 0; i < inFlightBufferCount; i++) {
            int bufferId = inFlightBufferIds[i];
            if (bufferId >= 0) {
                loop.releaseEgressBuffer(bufferId);
            }
            inFlightReferences[i] = null;
        }
        inFlightBufferCount = 0;
    }

    private void addQueued(int bufferId, long address, int length,
                           MemorySegment reference) {
        if (bufferIds == null) {
            bufferIds = new int[INITIAL_QUEUE_CAPACITY];
            addresses = new long[INITIAL_QUEUE_CAPACITY];
            lengths = new int[INITIAL_QUEUE_CAPACITY];
            references = new MemorySegment[INITIAL_QUEUE_CAPACITY];
        } else if (queueSize == bufferIds.length) {
            growQueue();
        }

        int index = (queueHead + queueSize) & (bufferIds.length - 1);
        bufferIds[index] = bufferId;
        addresses[index] = address;
        lengths[index] = length;
        references[index] = reference;
        queueSize++;
    }

    private int removeBufferId() {
        return bufferIds[queueHead];
    }

    private long removeAddress() {
        return addresses[queueHead];
    }

    private MemorySegment removeReference() {
        MemorySegment reference = references[queueHead];
        references[queueHead] = null;
        return reference;
    }

    private int removeLength() {
        int length = lengths[queueHead];
        queueHead = (queueHead + 1) & (bufferIds.length - 1);
        queueSize--;
        return length;
    }

    private void growQueue() {
        int[] newBufferIds = new int[bufferIds.length << 1];
        long[] newAddresses = new long[addresses.length << 1];
        int[] newLengths = new int[lengths.length << 1];
        MemorySegment[] newReferences =
            new MemorySegment[references.length << 1];
        for (int i = 0; i < queueSize; i++) {
            int source = (queueHead + i) & (bufferIds.length - 1);
            newBufferIds[i] = bufferIds[source];
            newAddresses[i] = addresses[source];
            newLengths[i] = lengths[source];
            newReferences[i] = references[source];
        }
        bufferIds = newBufferIds;
        addresses = newAddresses;
        lengths = newLengths;
        references = newReferences;
        queueHead = 0;
    }

    private void fail(int error) {
        failure = error;
        sendInFlight = false;
        while (queueSize != 0) {
            int bufferId = removeBufferId();
            removeAddress();
            removeReference();
            removeLength();
            if (bufferId >= 0) {
                loop.releaseEgressBuffer(bufferId);
            }
            setPendingFrames(pendingFrames() - 1);
        }
        signalIfDrained();
    }

    private void signalIfDrained() {
        if (pendingFrames() == 0 || failure != 0) {
            Thread waiter;
            while ((waiter = pollDrainWaiter()) != null) {
                LockSupport.unpark(waiter);
                if (failure == 0) {
                    break;
                }
            }
        }
    }

    private void addDrainWaiter(Thread waiter) {
        if (drainWaiters == null) {
            drainWaiters = new Thread[INITIAL_DRAIN_WAITER_CAPACITY];
        } else if (drainWaiterCount == drainWaiters.length) {
            Thread[] expanded = new Thread[drainWaiters.length << 1];
            for (int i = 0; i < drainWaiterCount; i++) {
                expanded[i] = drainWaiters[
                    (drainWaiterHead + i) & (drainWaiters.length - 1)];
            }
            drainWaiters = expanded;
            drainWaiterHead = 0;
        }
        int index = (drainWaiterHead + drainWaiterCount)
            & (drainWaiters.length - 1);
        drainWaiters[index] = waiter;
        drainWaiterCount++;
    }

    private void removeDrainWaiter(Thread waiter) {
        for (int i = 0; i < drainWaiterCount; i++) {
            int index = (drainWaiterHead + i)
                & (drainWaiters.length - 1);
            if (drainWaiters[index] != waiter) {
                continue;
            }
            for (int j = i; j < drainWaiterCount - 1; j++) {
                int destination = (drainWaiterHead + j)
                    & (drainWaiters.length - 1);
                int source = (destination + 1)
                    & (drainWaiters.length - 1);
                drainWaiters[destination] = drainWaiters[source];
            }
            int last = (drainWaiterHead + drainWaiterCount - 1)
                & (drainWaiters.length - 1);
            drainWaiters[last] = null;
            drainWaiterCount--;
            if (drainWaiterCount == 0) {
                drainWaiterHead = 0;
            }
            return;
        }
    }

    private Thread pollDrainWaiter() {
        if (drainWaiterCount == 0) {
            return null;
        }
        Thread waiter = drainWaiters[drainWaiterHead];
        drainWaiters[drainWaiterHead] = null;
        drainWaiterHead = (drainWaiterHead + 1)
            & (drainWaiters.length - 1);
        drainWaiterCount--;
        if (drainWaiterCount == 0) {
            drainWaiterHead = 0;
        }
        return waiter;
    }

    private int pendingFrames() {
        return (int) PENDING_FRAMES.getAcquire(this);
    }

    private void setPendingFrames(int value) {
        PENDING_FRAMES.setRelease(this, value);
    }

    static ConnectionWriter forHttp2(UringEventLoop loop, int clientFd,
                                     int fixedSlot, TlsConnection tls) {
        return new ConnectionWriter(loop, clientFd, fixedSlot, tls, true);
    }
}
