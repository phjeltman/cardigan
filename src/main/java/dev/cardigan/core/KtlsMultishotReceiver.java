// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.tls.TlsConnection;
import dev.cardigan.tls.TlsStats;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;

/**
 * Bounded direct-kTLS receive queue using multishot RECVMSG so TLS control
 * records remain visible without putting OpenSSL on the application path.
 */
final class KtlsMultishotReceiver
        implements UringEventLoop.CompletionHandler, InboundReceiver {
    private static final VarHandle QUEUE_SIZE;
    private static final VarHandle RECEIVE_WAITER;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            QUEUE_SIZE = lookup.findVarHandle(
                KtlsMultishotReceiver.class, "queueSize", int.class);
            RECEIVE_WAITER = lookup.findVarHandle(
                KtlsMultishotReceiver.class, "receiveWaiter", Thread.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final int ECANCELED = -125;
    private static final int ENOBUFS = -105;
    private static final int EINVAL = -22;
    private static final int ALERT_WARNING = 1;
    private static final int ALERT_CLOSE_NOTIFY = 0;

    interface Observer extends MultishotReceiver.Observer {
        void tlsCloseNotifyObserved();

        void tlsDirectReceiveFailed();
    }

    private final UringEventLoop loop;
    private final int clientFd;
    private final int fixedSlot;
    private final TlsConnection tls;
    private final Observer observer;
    private final InboundChunk[] queue;
    private final int queueMask;
    private final int highWatermark;
    private final int lowWatermark;

    private int queueHead;
    private int queueSize;
    private long receiveToken = -1;
    private int observedCompletions;
    private boolean receivedDirectRecord;
    private boolean active;
    private boolean cancelPending;
    private boolean fallback;
    private boolean fallbackNotified;
    private boolean waitingForBuffer;
    private boolean eof;
    private boolean failed;
    private boolean failureReported;
    private boolean closed;
    private Thread receiveWaiter;
    private volatile Thread closeWaiter;
    private TlsReceiver fallbackReceiver;

    KtlsMultishotReceiver(
            UringEventLoop loop, int clientFd, int fixedSlot,
            TlsConnection tls, Observer observer) {
        this.loop = loop;
        this.clientFd = clientFd;
        this.fixedSlot = fixedSlot;
        this.tls = tls;
        this.observer = observer;

        int requestedCapacity = Integer.getInteger(
            "cardigan.receive.max.queued.chunks", 64);
        int capacity = 2;
        while (capacity < Math.max(2, requestedCapacity)) {
            capacity <<= 1;
        }
        this.queue = new InboundChunk[capacity];
        this.queueMask = capacity - 1;

        int requestedHigh = Integer.getInteger(
            "cardigan.receive.pause.chunks", 16);
        this.highWatermark = Math.max(
            1, Math.min(requestedHigh, capacity - 1));
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
            InboundChunk chunk = poll();
            if (chunk != null) {
                maybeArm();
                return chunk;
            }
            if (fallback) {
                return fallbackReceiver.receive();
            }
            if (eof || failed) {
                return null;
            }

            maybeArm();
            setReceiveWaiter(current);
            if (queueSize() != 0 || fallback || eof || failed || closed) {
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
        if ((flags & Opcodes.IORING_CQE_F_MORE) != 0
                && observedCompletions < 2) {
            observedCompletions++;
            observer.multishotCompletionObserved();
        }

        if (result > 0) {
            processSelectedBuffer(result, flags);
        }

        if (!terminal) {
            if (eof || failed) {
                pause();
            } else if (queueSize() >= highWatermark) {
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

        if (!closed && !eof && !failed) {
            if (result == EINVAL) {
                if (!receivedDirectRecord) {
                    activateFallback();
                } else {
                    failDirectReceive();
                }
            } else if (result == 0) {
                eof = true;
            } else if (result == ECANCELED || result == ENOBUFS) {
                if (result == ENOBUFS && !waitingForBuffer) {
                    waitingForBuffer = true;
                    loop.whenKtlsBufferAvailable(this::bufferAvailable);
                }
            } else if (result < 0) {
                failDirectReceive();
            }

            if (!waitForReturnedBuffer && !fallback && !eof && !failed
                    && queueSize() <= lowWatermark) {
                maybeArm();
            }
        }

        signalReceiveWaiter();
        signalCloseWaiter();
    }

    private void processSelectedBuffer(int result, int flags) {
        int bufferId = (flags & Opcodes.IORING_CQE_F_BUFFER) != 0
            ? (flags >>> Opcodes.IORING_CQE_BUFFER_SHIFT) & 0xffff
            : -1;
        if (bufferId < 0) {
            failDirectReceive();
            return;
        }

        MemorySegment buffer = loop.getKtlsBufferSegment(bufferId);
        long decoded = KtlsRecordParser.decode(buffer, result);
        if (decoded == KtlsRecordParser.INVALID) {
            loop.returnKtlsBuffer(bufferId);
            failDirectReceive();
            return;
        }

        if (!receivedDirectRecord) {
            receivedDirectRecord = true;
        }
        if (TlsStats.ENABLED) {
            TlsStats.directReceiveRecord();
        }
        int recordType = KtlsRecordParser.recordType(decoded);
        int payloadLength = KtlsRecordParser.payloadLength(decoded);
        if (recordType == KtlsRecordParser.APPLICATION_DATA) {
            if (payloadLength == 0) {
                loop.returnKtlsBuffer(bufferId);
                eof = true;
                return;
            }
            InboundChunk chunk = new KtlsInboundChunk(
                loop,
                buffer.asSlice(
                    KtlsRecordParser.PAYLOAD_OFFSET,
                    UringEventLoop.BUFFER_SIZE),
                bufferId,
                payloadLength
            );
            if (closed || !offer(chunk)) {
                chunk.close();
                if (!closed) {
                    failDirectReceive();
                }
            }
            return;
        }

        if (recordType == KtlsRecordParser.ALERT) {
            if (TlsStats.ENABLED) {
                TlsStats.directReceiveAlert();
            }
            processAlert(buffer, payloadLength);
        } else {
            failDirectReceive();
        }
        loop.returnKtlsBuffer(bufferId);
    }

    private void processAlert(MemorySegment buffer, int payloadLength) {
        if (payloadLength == 0 || (payloadLength & 1) != 0) {
            failDirectReceive();
            return;
        }

        boolean closeNotify = false;
        long payloadOffset = KtlsRecordParser.PAYLOAD_OFFSET;
        for (int offset = 0; offset < payloadLength; offset += 2) {
            int level = buffer.get(
                ValueLayout.JAVA_BYTE, payloadOffset + offset) & 0xff;
            int description = buffer.get(
                ValueLayout.JAVA_BYTE, payloadOffset + offset + 1L) & 0xff;
            if (level != ALERT_WARNING
                    || description != ALERT_CLOSE_NOTIFY) {
                failDirectReceive();
                return;
            }
            closeNotify = true;
        }

        if (closeNotify) {
            try {
                tls.peerCloseNotifyReceived();
                eof = true;
                observer.tlsCloseNotifyObserved();
                if (TlsStats.ENABLED) {
                    TlsStats.directReceiveCloseNotify();
                }
            } catch (RuntimeException e) {
                failDirectReceive();
            }
        }
    }

    private void failDirectReceive() {
        failed = true;
        if (!failureReported) {
            failureReported = true;
            tls.directReceiveFailed();
            observer.tlsDirectReceiveFailed();
            if (TlsStats.ENABLED) {
                TlsStats.directReceiveFailure();
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        InboundChunk chunk;
        while ((chunk = poll()) != null) {
            chunk.close();
        }
        if (fallbackReceiver != null) {
            fallbackReceiver.close();
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

    private void pause() {
        if (!active || cancelPending) {
            return;
        }
        cancelPending = true;
        if (!loop.cancelAsync(receiveToken, (result, flags, terminal) -> {
        })) {
            cancelPending = false;
            failDirectReceive();
            active = false;
            signalReceiveWaiter();
            signalCloseWaiter();
        }
    }

    private void maybeArm() {
        if (closed || active || cancelPending || fallback || eof || failed
                || waitingForBuffer || queueSize() > lowWatermark) {
            return;
        }
        long token = loop.recvMsgMultishot(clientFd, fixedSlot, this);
        if (token < 0) {
            activateFallback();
            return;
        }
        receiveToken = token;
        active = true;
    }

    private void activateFallback() {
        fallback = true;
        fallbackReceiver = new TlsReceiver(tls);
        fallbackReceiver.start();
        if (!fallbackNotified) {
            fallbackNotified = true;
            observer.fallbackActivated();
        }
    }

    private void bufferAvailable() {
        waitingForBuffer = false;
        maybeArm();
    }

    private void signalReceiveWaiter() {
        Thread waiter = receiveWaiter();
        if (waiter != null) {
            LockSupport.unpark(waiter);
            setReceiveWaiter(null);
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
