// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.reflect.Constructor;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Owns the virtual-thread machinery attached to one event-loop carrier.
 * Transport scheduling enters this layer through the application lane and
 * receives opaque continuations back through the event loop.
 */
final class LoomRuntime implements ApplicationRuntime {
    static final boolean STATS_ENABLED =
        Boolean.getBoolean("cardigan.virtual.thread.stats");

    private final UringEventLoop loop;
    private final CarrierDomain carrierDomain;
    private final ThreadFactory coreThreadFactory;
    private final ExchangeExecutor exchanges;
    private final ThreadLocal<CompletionWaiter> completionWaiter =
        ThreadLocal.withInitial(CompletionWaiter::new);
    private final ThreadLocal<ParkingWaiter> parkingWaiter =
        ThreadLocal.withInitial(ParkingWaiter::new);

    private long coreMounts;
    private long coreUnmounts;
    private long handlerMounts;
    private long handlerUnmounts;

    LoomRuntime(UringEventLoop loop, ReactorRunner runner, int cpuId) {
        this.loop = loop;
        this.carrierDomain = new CarrierDomain(runner.carrier());
        Executor scheduler = STATS_ENABLED
            ? this::executeCountedCoreContinuation
            : loop::enqueueCoreContinuation;
        Thread.Builder.OfVirtual builder = newBuilder(scheduler);
        this.coreThreadFactory = builder
            .name("cardigan-vt-core" + cpuId + "-", 0)
            .factory();
        this.exchanges = new ExchangeExecutor(loop, this);
    }

    @Override
    public RuntimeTask startTask(Runnable task) {
        return new LoomTask(start(task));
    }

    @Override
    public void executeTask(Runnable task) {
        start(task);
    }

    private Thread start(Runnable task) {
        Thread thread = coreThreadFactory.newThread(task);
        thread.start();
        return thread;
    }

    Thread newVirtualThread(
            Executor scheduler, Runnable task, String name) {
        return newBuilder(scheduler).name(name).unstarted(task);
    }

    @Override
    public boolean inCarrierDomain() {
        return carrierDomain.containsCurrentThread();
    }

    ExchangeExecutor exchanges() {
        return exchanges;
    }

    @Override
    public ApplicationLane applicationLane() {
        return exchanges;
    }

    @Override
    public int workerCount() {
        return exchanges.workerCount();
    }

    @Override
    public CompletionWaiter beginCompletionWait() {
        CompletionWaiter waiter = completionWaiter.get();
        waiter.prepare();
        return waiter;
    }

    @Override
    public void await(
            Object blocker,
            BooleanSupplier blocked,
            Consumer<Runnable> registerWakeup,
            Consumer<Runnable> unregisterWakeup) {
        try {
            await(
                blocker,
                blocked,
                registerWakeup,
                unregisterWakeup,
                false
            );
        } catch (InterruptedException impossible) {
            throw new AssertionError(impossible);
        }
    }

    @Override
    public void awaitInterruptibly(
            Object blocker,
            BooleanSupplier blocked,
            Consumer<Runnable> registerWakeup,
            Consumer<Runnable> unregisterWakeup)
            throws InterruptedException {
        await(
            blocker,
            blocked,
            registerWakeup,
            unregisterWakeup,
            true
        );
    }

    private void await(
            Object blocker,
            BooleanSupplier blocked,
            Consumer<Runnable> registerWakeup,
            Consumer<Runnable> unregisterWakeup,
            boolean interruptible) throws InterruptedException {
        ParkingWaiter waiter = parkingWaiter.get();
        boolean interrupted = false;
        boolean registered = false;
        try {
            while (blocked.getAsBoolean()) {
                waiter.prepare();
                registerWakeup.accept(waiter);
                registered = true;
                if (blocked.getAsBoolean()) {
                    interrupted |= waiter.awaitWakeup(
                        blocker, interruptible);
                    if (interrupted && interruptible) {
                        throw new InterruptedException();
                    }
                } else {
                    waiter.abandon();
                }
                unregisterWakeup.accept(waiter);
                registered = false;
            }
        } finally {
            waiter.abandon();
            if (registered) {
                unregisterWakeup.accept(waiter);
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void runCountedApplicationTask(Runnable continuation) {
        handlerMounts++;
        try {
            continuation.run();
        } finally {
            handlerUnmounts++;
        }
    }

    @Override
    public ApplicationRuntime.VirtualThreadStats stats() {
        return new ApplicationRuntime.VirtualThreadStats(
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

    @Override
    public boolean awaitTermination(long timeoutMillis) {
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

    static final class CompletionWaiter
            implements ApplicationRuntime.CompletionWait {
        private volatile Thread thread;
        private int result;
        private int flags;

        private void prepare() {
            if (thread != null) {
                throw new IllegalStateException(
                    "A completion wait is already active on this thread");
            }
            result = -11;
            flags = 0;
            thread = Thread.currentThread();
        }

        @Override
        public void onCompletion(int result, int flags, boolean terminal) {
            if (!terminal) {
                return;
            }
            this.result = result;
            this.flags = flags;
            Thread waitingThread = thread;
            thread = null;
            LockSupport.unpark(waitingThread);
        }

        @Override
        public int awaitResult() {
            awaitCompletion();
            return result;
        }

        @Override
        public int flags() {
            return flags;
        }

        @Override
        public void abandon() {
            thread = null;
        }

        private void awaitCompletion() {
            boolean interrupted = false;
            while (thread != null) {
                LockSupport.park(this);
                if (thread != null && Thread.interrupted()) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class ParkingWaiter implements Runnable {
        private volatile Thread thread;

        private void prepare() {
            if (thread != null) {
                throw new IllegalStateException(
                    "A protocol wait is already active on this thread");
            }
            thread = Thread.currentThread();
        }

        @Override
        public void run() {
            Thread waitingThread = thread;
            thread = null;
            LockSupport.unpark(waitingThread);
        }

        private boolean awaitWakeup(
                Object blocker, boolean interruptible) {
            boolean interrupted = false;
            while (thread != null) {
                LockSupport.park(blocker);
                if (thread != null && Thread.interrupted()) {
                    interrupted = true;
                    if (interruptible) {
                        thread = null;
                        break;
                    }
                }
            }
            return interrupted;
        }

        private void abandon() {
            thread = null;
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

    private record LoomTask(Thread thread) implements RuntimeTask {
        @Override
        public void join(long timeoutMillis) {
            boolean interrupted = false;
            long deadline = timeoutMillis <= 0
                ? Long.MAX_VALUE
                : System.nanoTime() + timeoutMillis * 1_000_000L;
            while (thread.isAlive()) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    break;
                }
                try {
                    if (timeoutMillis <= 0) {
                        thread.join();
                    } else {
                        thread.join(Math.max(
                            1, remainingNanos / 1_000_000L));
                    }
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public boolean isAlive() {
            return thread.isAlive();
        }

        @Override
        public void wake() {
            LockSupport.unpark(thread);
        }
    }
}
