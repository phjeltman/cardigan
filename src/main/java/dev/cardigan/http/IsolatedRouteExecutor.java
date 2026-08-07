// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import dev.cardigan.ffi.ThreadAffinity;
import java.lang.reflect.Constructor;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runs isolated routes away from the io_uring event-loop carriers.
 */
final class IsolatedRouteExecutor {
    private static final AtomicInteger CARRIER_IDS = new AtomicInteger();
    private static final String CONFIGURED_CPUS =
        System.getProperty("cardigan.isolated.cpus", "").trim();
    static {
        ThreadAffinity.initialize();
        if (!CONFIGURED_CPUS.isEmpty()) {
            ThreadAffinity.validateCpuList(CONFIGURED_CPUS);
        }
    }
    private static final int PARALLELISM = Math.max(
        1,
        Integer.getInteger(
            "cardigan.isolated.carriers",
            Runtime.getRuntime().availableProcessors()
        )
    );
    private static final int MAX_TASKS = Math.max(
        1,
        Integer.getInteger("cardigan.isolated.max.tasks", 4_096)
    );
    private static final AtomicInteger ACTIVE_TASKS = new AtomicInteger();
    private static final LongAdder ADMITTED_TASKS = new LongAdder();
    private static final LongAdder CANCELLED_TASKS = new LongAdder();
    private static final LongAdder REJECTED_TASKS = new LongAdder();
    private static final ForkJoinPool CARRIERS = new ForkJoinPool(
        PARALLELISM,
        IsolatedRouteExecutor::newCarrier,
        null,
        true
    );
    private static final ThreadFactory VIRTUAL_THREADS =
        newVirtualThreadFactory(CARRIERS);

    private IsolatedRouteExecutor() {
    }

    static Thread newThread(Runnable task) {
        return VIRTUAL_THREADS.newThread(task);
    }

    static boolean tryAcquireTask() {
        int active = ACTIVE_TASKS.get();
        while (active < MAX_TASKS) {
            if (ACTIVE_TASKS.compareAndSet(active, active + 1)) {
                ADMITTED_TASKS.increment();
                return true;
            }
            active = ACTIVE_TASKS.get();
        }
        REJECTED_TASKS.increment();
        return false;
    }

    static void releaseTask() {
        ACTIVE_TASKS.decrementAndGet();
    }

    static void recordCancellation() {
        CANCELLED_TASKS.increment();
    }

    static IsolatedRouteStats stats() {
        return new IsolatedRouteStats(
            MAX_TASKS,
            ACTIVE_TASKS.get(),
            ADMITTED_TASKS.sum(),
            CANCELLED_TASKS.sum(),
            REJECTED_TASKS.sum()
        );
    }

    static ForkJoinWorkerThread newCarrier(ForkJoinPool pool) {
        return new IsolatedCarrier(pool, CARRIER_IDS.getAndIncrement());
    }

    private static ThreadFactory newVirtualThreadFactory(Executor scheduler) {
        try {
            Class<?> builderClass =
                Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder");
            Constructor<?> constructor =
                builderClass.getDeclaredConstructor(Executor.class);
            constructor.setAccessible(true);
            Thread.Builder.OfVirtual builder =
                (Thread.Builder.OfVirtual) constructor.newInstance(scheduler);
            return builder.name("cardigan-isolated-vt-", 0).factory();
        } catch (Throwable error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static final class IsolatedCarrier extends ForkJoinWorkerThread {
        private IsolatedCarrier(ForkJoinPool pool, int id) {
            super(pool);
            setName("cardigan-isolated-carrier-" + id);
        }

        @Override
        protected void onStart() {
            super.onStart();
            int result = CONFIGURED_CPUS.isEmpty()
                ? ThreadAffinity.restoreProcessAffinity()
                : ThreadAffinity.setCurrentCpus(CONFIGURED_CPUS);
            if (result != 0) {
                System.err.println(
                    "Warning: failed to restore CPU affinity for "
                        + getName() + ", ret=" + result);
            }
        }
    }
}
