// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.tls;

import dev.cardigan.core.UringEventLoop;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CountDownLatch;

/**
 * One nonblocking OpenSSL connection. Network readiness is awaited through
 * the owning io_uring loop, so an OpenSSL WANT_READ/WANT_WRITE never blocks a
 * carrier thread.
 */
public final class TlsConnection implements AutoCloseable {
    public static final int HTTP1 = 1;
    public static final int HTTP2 = 2;

    private static final int ERROR = -1;
    private static final int WANT_READ = -2;
    private static final int WANT_WRITE = -3;
    private static final int CONTROL_PENDING = -4;
    private static final int READ_CONTROL_FLAG = 1 << 30;
    private static final int POLLIN = 0x001;
    private static final int POLLOUT = 0x004;

    private final TlsContext context;
    private final UringEventLoop loop;
    private final int fd;
    private final int fixedSlot;
    private final Object sslLock = new Object();
    private PanamaTls.Connection panama;
    private int selectedProtocol;
    private boolean ktlsSend;
    private boolean ktlsRecv;
    private boolean gracefulShutdown = true;
    private boolean inputShutdownPrepared;
    private volatile CountDownLatch directSendBarrier;
    private Runnable directSendWaiter;

    private TlsConnection(TlsContext context, PanamaTls.Connection panama,
                          UringEventLoop loop, int fd, int fixedSlot) {
        this.context = context;
        this.panama = panama;
        this.loop = loop;
        this.fd = fd;
        this.fixedSlot = fixedSlot;
    }

    static TlsConnection accept(TlsContext context,
                                PanamaTls.Context contextHandle,
                                UringEventLoop loop, int fd, int fixedSlot) {
        long handshakeStart = TlsStats.ENABLED ? System.nanoTime() : 0;
        try {
            PanamaTls.Connection panama = contextHandle.createConnection(fd);
            TlsConnection connection = new TlsConnection(
                context, panama, loop, fd, fixedSlot);
            return finishAccept(connection, handshakeStart);
        } catch (TlsException e) {
            throw e;
        } catch (Throwable t) {
            throw new TlsException("Failed to create TLS connection", t);
        }
    }

    private static TlsConnection finishAccept(
            TlsConnection connection, long handshakeStart) {
        try {
            if (!connection.handshake()) {
                connection.close();
                return null;
            }
            PanamaTls.Connection direct = connection.requirePanama();
            connection.selectedProtocol = direct.selectedProtocol();
            connection.ktlsSend = direct.ktlsSend();
            connection.ktlsRecv = direct.ktlsRecv();
            direct.trackKeyUpdates(
                connection.ktlsSend
                    && connection.context.directKtlsSend());
            if (TlsStats.ENABLED) {
                TlsStats.handshakeCompleted(
                    System.nanoTime() - handshakeStart,
                    connection.ktlsSend,
                    connection.ktlsRecv
                );
            }
            return connection;
        } catch (Throwable t) {
            connection.close();
            throw t;
        }
    }

    private boolean handshake() {
        while (true) {
            int result = requirePanama().handshake();
            if (result == 1) {
                return true;
            }
            if (result == 0) {
                return false;
            }
            awaitRetry(result, "TLS handshake");
        }
    }

    public int read(MemorySegment destination, int length) {
        if (length < 0 || length > destination.byteSize()) {
            throw new IllegalArgumentException("Invalid TLS read length");
        }
        while (true) {
            int result;
            try {
                synchronized (sslLock) {
                    if (inputShutdownPrepared) {
                        return 0;
                    }
                    result = requirePanama().read(
                        destination, (long) length);
                }
            } catch (Throwable t) {
                throw new TlsException("TLS read failed", t);
            }
            if (result == CONTROL_PENDING) {
                flushPendingControl();
                continue;
            }
            if (result >= 0 && (result & READ_CONTROL_FLAG) != 0) {
                result &= ~READ_CONTROL_FLAG;
                flushPendingControl();
            }
            if (TlsStats.ENABLED) {
                TlsStats.readResult(result);
            }
            if (result >= 0) {
                return result;
            }
            awaitRetry(result, "TLS read");
        }
    }

    public int writeFully(MemorySegment source, int length) {
        if (length < 0 || length > source.byteSize()) {
            throw new IllegalArgumentException("Invalid TLS write length");
        }
        return writeFully(source.address(), length);
    }

    public int writeFully(long address, int length) {
        int total = 0;
        while (total < length) {
            int result;
            try {
                MemorySegment pointer = MemorySegment.ofAddress(address + total);
                synchronized (sslLock) {
                    result = requirePanama().write(
                        pointer, (long) (length - total));
                }
            } catch (Throwable t) {
                throw new TlsException("TLS write failed", t);
            }
            if (TlsStats.ENABLED) {
                TlsStats.writeResult(result);
            }
            if (result > 0) {
                total += result;
                continue;
            }
            if (result == 0) {
                return 0;
            }
            awaitRetry(result, "TLS write");
        }
        return total;
    }

    public int selectedProtocol() {
        return selectedProtocol;
    }

    public boolean ktlsSend() {
        return ktlsSend;
    }

    public boolean ktlsRecv() {
        return ktlsRecv;
    }

    /**
     * Reports whether the socket can frame io_uring writes as TLS
     * application-data records with keys installed by OpenSSL.
     */
    public boolean directKtlsSend() {
        return ktlsSend && context.directKtlsSend();
    }

    /** Defers an owning-loop direct send behind a pending TLS control write. */
    public boolean deferDirectSend(Runnable waiter) {
        if (directSendBarrier == null) {
            return false;
        }
        synchronized (sslLock) {
            if (directSendBarrier == null) {
                return false;
            }
            if (directSendWaiter != null && directSendWaiter != waiter) {
                throw new IllegalStateException(
                    "Multiple direct TLS send waiters");
            }
            directSendWaiter = waiter;
            return true;
        }
    }

    /** Parks a fallback writer until a pending reciprocal KeyUpdate is sent. */
    public void awaitDirectSendReady() {
        boolean interrupted = false;
        CountDownLatch barrier;
        while ((barrier = directSendBarrier) != null) {
            try {
                barrier.await();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * TLS 1.2 has no post-handshake KeyUpdate. Once OpenSSL installs the
     * receive key, io_uring can consume application records; recvmsg handles
     * control records.
     */
    public boolean directKtlsReceive() {
        return context.directKtlsReceive() && ktlsRecv;
    }

    /** Records a close_notify consumed directly through kTLS. */
    public void peerCloseNotifyReceived() {
        int result;
        synchronized (sslLock) {
            result = requirePanama().markReceivedShutdown();
        }
        if (result != 1) {
            throw new TlsException(
                "Failed to synchronize peer TLS shutdown state");
        }
    }

    /** Prevents SSL_shutdown after a fatal or malformed direct-RX record. */
    public void directReceiveFailed() {
        synchronized (sslLock) {
            gracefulShutdown = false;
        }
    }

    private void flushPendingControl() {
        CountDownLatch barrier = new CountDownLatch(1);
        synchronized (sslLock) {
            if (directSendBarrier != null) {
                throw new TlsException("TLS control write already pending");
            }
            directSendBarrier = barrier;
        }
        try {
            while (true) {
                int result;
                synchronized (sslLock) {
                    result = requirePanama().flushControl();
                }
                if (result == 1) {
                    return;
                }
                awaitRetry(result, "TLS KeyUpdate");
            }
        } finally {
            Runnable waiter;
            synchronized (sslLock) {
                directSendBarrier = null;
                waiter = directSendWaiter;
                directSendWaiter = null;
            }
            barrier.countDown();
            if (waiter != null) {
                try {
                    loop.executeProtocol(waiter);
                } catch (Throwable ignored) {
                    // The owning loop is already shutting down.
                }
            }
        }
    }

    private void awaitRetry(int result, String operation) {
        int events;
        if (result == WANT_READ) {
            events = POLLIN;
        } else if (result == WANT_WRITE) {
            events = POLLOUT;
        } else if (result == ERROR) {
            throw new TlsException(errorMessage());
        } else {
            throw new TlsException(operation + " returned " + result);
        }
        int readiness = loop.awaitSocketReady(fd, fixedSlot, events);
        if (readiness < 0) {
            throw new TlsException(
                operation + " readiness failed: " + readiness);
        }
    }

    private PanamaTls.Connection requirePanama() {
        PanamaTls.Connection current = panama;
        if (current == null) {
            throw new IllegalStateException("TLS connection is closed");
        }
        return current;
    }

    private String errorMessage() {
        return requirePanama().errorMessage();
    }

    /**
     * Prepares OpenSSL for Cardigan deliberately stopping the transport read
     * side. Marking receive shutdown before SHUT_RD lets the parser observe
     * EOF without entering SSL_read's fatal unexpected-EOF path. Application
     * writes remain valid until the connection owner sends close_notify and
     * frees the native state.
     */
    public void prepareInputShutdown() {
        synchronized (sslLock) {
            PanamaTls.Connection current = panama;
            if (current == null || inputShutdownPrepared) {
                return;
            }
            if (current.markReceivedShutdown() != 1) {
                throw new TlsException(
                    "Failed to synchronize local TLS input shutdown");
            }
            inputShutdownPrepared = true;
        }
    }

    /** Suppresses close_notify after a forced transport shutdown. */
    public void abortTransportShutdown() {
        synchronized (sslLock) {
            gracefulShutdown = false;
            inputShutdownPrepared = true;
        }
    }

    @Override
    public void close() {
        PanamaTls.Connection current = panama;
        if (current == null) {
            return;
        }
        try {
            int result;
            synchronized (sslLock) {
                if (!gracefulShutdown) {
                    return;
                }
                result = current.shutdown();
            }
            while (result == WANT_WRITE) {
                int readiness = loop.awaitSocketReady(fd, fixedSlot, POLLOUT);
                if (readiness < 0) {
                    break;
                }
                synchronized (sslLock) {
                    if (!gracefulShutdown) {
                        break;
                    }
                    result = current.shutdown();
                }
            }
        } catch (Throwable ignored) {
            // The TCP connection is being closed regardless; close_notify is
            // best effort after an I/O or protocol failure.
        } finally {
            panama = null;
            current.close();
        }
    }
}
