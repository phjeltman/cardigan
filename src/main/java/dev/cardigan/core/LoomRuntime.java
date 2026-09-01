// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.reflect.Constructor;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * Owns the virtual-thread machinery attached to one event-loop carrier.
 * Transport scheduling enters this layer through the application lane and
 * receives opaque continuations back through the event loop.
 */
final class LoomRuntime implements AutoCloseable {
    static final boolean STATS_ENABLED =
        Boolean.getBoolean("cardigan.virtual.thread.stats");

    private final UringEventLoop loop;
    private final CarrierDomain carrierDomain;
    private final ThreadFactory coreThreadFactory;
    private final ExchangeExecutor exchanges;

    private long coreMounts;
    private long coreUnmounts;
    private long handlerMounts;
    private long handlerUnmounts;

    LoomRuntime(UringEventLoop loop, Thread carrier, int cpuId) {
        this.loop = loop;
        this.carrierDomain = new CarrierDomain(carrier);
        Executor scheduler = STATS_ENABLED
            ? this::executeCountedCoreContinuation
            : loop::enqueueCoreContinuation;
        Thread.Builder.OfVirtual builder = newBuilder(scheduler);
        this.coreThreadFactory = builder
            .name("cardigan-vt-core" + cpuId + "-", 0)
            .factory();
        this.exchanges = new ExchangeExecutor(loop, this);
    }

    Thread startVirtualThread(Runnable task) {
        Thread thread = coreThreadFactory.newThread(task);
        thread.start();
        return thread;
    }

    Thread newVirtualThread(
            Executor scheduler, Runnable task, String name) {
        return newBuilder(scheduler).name(name).unstarted(task);
    }

    boolean inCarrierDomain() {
        return carrierDomain.containsCurrentThread();
    }

    ExchangeExecutor exchanges() {
        return exchanges;
    }

    ApplicationLane applicationLane() {
        return exchanges;
    }

    int exchangeWorkerCount() {
        return exchanges.workerCount();
    }

    void runCountedApplicationTask(Runnable continuation) {
        handlerMounts++;
        try {
            continuation.run();
        } finally {
            handlerUnmounts++;
        }
    }

    VirtualThreadStats stats() {
        return new VirtualThreadStats(
            coreMounts,
            coreUnmounts,
            handlerMounts,
            handlerUnmounts
        );
    }

    @Override
    public void close() {
        exchanges.close();
    }

    boolean awaitTermination(long timeoutMillis) {
        return exchanges.awaitTermination(timeoutMillis);
    }

    private void executeCountedCoreContinuation(Runnable continuation) {
        loop.enqueueCoreContinuation(
            new CountedCoreContinuation(continuation));
    }

    private static Thread.Builder.OfVirtual newBuilder(Executor scheduler) {
        try {
            Class<?> builderClass = Class.forName(
                "java.lang.ThreadBuilders$VirtualThreadBuilder");
            Constructor<?> constructor = builderClass.getDeclaredConstructor(
                Executor.class);
            constructor.setAccessible(true);
            return (Thread.Builder.OfVirtual) constructor.newInstance(
                scheduler);
        } catch (Throwable failure) {
            throw new RuntimeException(
                "Missing JVM argument: "
                    + "--add-opens java.base/java.lang=ALL-UNNAMED",
                failure
            );
        }
    }

    record VirtualThreadStats(
        long coreMounts,
        long coreUnmounts,
        long handlerMounts,
        long handlerUnmounts
    ) {
        long mounts() {
            return coreMounts + handlerMounts;
        }

        long unmounts() {
            return coreUnmounts + handlerUnmounts;
        }
    }

    private final class CountedCoreContinuation implements Runnable {
        private final Runnable continuation;

        private CountedCoreContinuation(Runnable continuation) {
            this.continuation = continuation;
        }

        @Override
        public void run() {
            coreMounts++;
            try {
                continuation.run();
            } finally {
                coreUnmounts++;
            }
        }
    }
}
