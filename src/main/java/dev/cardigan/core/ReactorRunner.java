// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.ffi.UnsupportedKernelException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Owns the platform carrier and the lifecycle of one reactor instance. */
final class ReactorRunner {
    private final UringEventLoop reactor;
    private final int entries;
    private final int cpuId;
    private final Thread carrier;
    private final CountDownLatch initialized = new CountDownLatch(1);
    private final AtomicReference<Throwable> initializationFailure =
        new AtomicReference<>();

    ReactorRunner(UringEventLoop reactor, int entries, int cpuId) {
        this.reactor = reactor;
        this.entries = entries;
        this.cpuId = cpuId;
        this.carrier = Thread.ofPlatform()
            .daemon(true)
            .name("cardigan-loop-" + cpuId)
            .unstarted(this::run);
    }

    Thread carrier() {
        return carrier;
    }

    void startAndAwaitInitialization() {
        carrier.start();
        try {
            initialized.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                "Interrupted initializing UringEventLoop for CPU " + cpuId,
                failure
            );
        }

        Throwable failure = initializationFailure.get();
        if (failure == null) {
            return;
        }
        if (failure instanceof UnsupportedKernelException unsupported) {
            throw unsupported;
        }
        throw new RuntimeException(
            "Failed to initialize UringEventLoop for CPU " + cpuId
                + ": " + failure.getMessage(),
            failure
        );
    }

    void interrupt() {
        carrier.interrupt();
    }

    void awaitTermination() {
        try {
            carrier.join(2_000);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }

        if (!carrier.isAlive()) {
            return;
        }
        StackTraceElement[] stack = carrier.getStackTrace();
        String location = stack.length == 0
            ? "unknown"
            : stack[0].toString();
        throw new IllegalStateException(
            "Event-loop carrier for CPU " + cpuId
                + " did not terminate; retaining its live io_uring "
                + "and native memory (state=" + carrier.getState()
                + ", at=" + location + ")"
        );
    }

    static void onSpinWait() {
        Thread.onSpinWait();
    }

    static void yieldCarrier() {
        Thread.yield();
    }

    private void run() {
        try {
            reactor.initialize(entries);
        } catch (Throwable failure) {
            reactor.abortInitialization();
            initializationFailure.set(failure);
            initialized.countDown();
            return;
        }

        initialized.countDown();
        reactor.runLoop();
    }
}
