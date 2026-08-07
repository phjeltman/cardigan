// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.tls;

import dev.cardigan.core.UringEventLoop;
import dev.cardigan.ffi.Libc;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Bounds incomplete handshakes and terminates clients which stop progressing. */
final class TlsHandshakeAdmission {
    static final String LIMIT_PROPERTY =
        "cardigan.tls.handshake.max.pending.per.loop";
    static final String TIMEOUT_PROPERTY =
        "cardigan.tls.handshake.timeout.millis";

    private static final int ACTIVE = 0;
    private static final int TIMED_OUT = 1;
    private static final int COMPLETE = 2;
    private static final int SHUT_RDWR = 2;
    private static final ScheduledThreadPoolExecutor TIMER = createTimer();

    private static ScheduledThreadPoolExecutor createTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(
            1, runnable -> {
            Thread thread = new Thread(
                runnable, "cardigan-tls-handshake-timeouts");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return timer;
    }

    private final int limitPerLoop;
    private final long timeoutMillis;
    private final ConcurrentHashMap<UringEventLoop, AtomicInteger> activeByLoop =
        new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();

    TlsHandshakeAdmission() {
        this(
            Math.max(1, Integer.getInteger(LIMIT_PROPERTY, 64)),
            Math.max(1L, Long.getLong(TIMEOUT_PROPERTY, 10_000L))
        );
    }

    TlsHandshakeAdmission(int limitPerLoop, long timeoutMillis) {
        if (limitPerLoop <= 0 || timeoutMillis <= 0) {
            throw new IllegalArgumentException(
                "TLS handshake admission values must be positive");
        }
        this.limitPerLoop = limitPerLoop;
        this.timeoutMillis = timeoutMillis;
    }

    Lease tryAcquire(UringEventLoop loop, int fd) {
        AtomicInteger loopActive = activeByLoop.computeIfAbsent(
            loop, ignored -> new AtomicInteger());
        int current = loopActive.get();
        while (current < limitPerLoop) {
            if (loopActive.compareAndSet(current, current + 1)) {
                active.incrementAndGet();
                admitted.incrementAndGet();
                return new Lease(loopActive, fd);
            }
            current = loopActive.get();
        }
        rejected.incrementAndGet();
        return null;
    }

    TlsHandshakeStats snapshot() {
        return new TlsHandshakeStats(
            limitPerLoop,
            timeoutMillis,
            active.get(),
            admitted.get(),
            rejected.get(),
            timedOut.get()
        );
    }

    final class Lease implements AutoCloseable {
        private final AtomicInteger loopActive;
        private final int fd;
        private final AtomicInteger state = new AtomicInteger(ACTIVE);
        private final ScheduledFuture<?> timeout;

        private Lease(AtomicInteger loopActive, int fd) {
            this.loopActive = loopActive;
            this.fd = fd;
            timeout = TIMER.schedule(
                this::expire, timeoutMillis, TimeUnit.MILLISECONDS);
        }

        private void expire() {
            if (!state.compareAndSet(ACTIVE, TIMED_OUT)) {
                return;
            }
            timedOut.incrementAndGet();
            try {
                int unused = (int) Libc.shutdown.invokeExact(fd, SHUT_RDWR);
            } catch (Throwable ignored) {
                // The connection may already be unwinding after peer close.
            }
        }

        @Override
        public void close() {
            int previous = state.getAndSet(COMPLETE);
            if (previous == COMPLETE) {
                return;
            }
            timeout.cancel(false);
            active.decrementAndGet();
            loopActive.decrementAndGet();
        }
    }
}
