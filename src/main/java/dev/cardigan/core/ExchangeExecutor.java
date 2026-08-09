// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs protocol-neutral exchanges on virtual threads belonging to one event
 * loop. Workers are shared by every connection on that loop, allowing a hot
 * worker to consume depth-one requests from different connections without a
 * park/unpark transition between every handler invocation.
 *
 * The event loop has one carrier, so worker continuations consume the queue
 * serially. The event loop checks progress whenever a continuation returns
 * and starts another worker only when queued work remains while every current
 * worker is actually waiting or blocked. Budgeted scheduler mode yields after
 * a bounded batch; epoch mode instead shares the handler phase's absolute
 * queue-tail cutoff across every worker.
 */
final class ExchangeExecutor implements AutoCloseable {
    private static final int DEFAULT_QUEUE_CAPACITY = 65_536;
    private static final int DEFAULT_MAX_BATCH = 64;
    private static final int DEFAULT_MAX_IDLE_WORKERS = 64;

    private final UringEventLoop loop;
    private final TaskQueue tasks;
    private final boolean epochScheduler;
    private final int maxBatch;
    private final int maxIdleWorkers;
    private final ArrayDeque<WorkerRunner> availableWorkers = new ArrayDeque<>();
    private final ArrayDeque<Integer> freeWorkerSlots = new ArrayDeque<>();
    private final Object lifecycleLock = new Object();

    private WorkerRunner[] workers = new WorkerRunner[8];
    private int workerSlots;
    private int nextWorkerId;
    private volatile int workerCount;
    private final AtomicInteger scheduledContinuations = new AtomicInteger();
    private long handlerEpochCutoff;
    private volatile boolean closed;

    ExchangeExecutor(UringEventLoop loop) {
        this.loop = loop;
        int requestedCapacity = Integer.getInteger(
            "cardigan.exchange.queue.capacity",
            DEFAULT_QUEUE_CAPACITY
        );
        this.tasks = new TaskQueue(Math.max(2, requestedCapacity));
        this.epochScheduler = loop.usesEpochScheduler();
        this.maxBatch = Math.max(
            1,
            Integer.getInteger("cardigan.exchange.max.batch", DEFAULT_MAX_BATCH)
        );
        this.maxIdleWorkers = Math.max(
            1,
            Integer.getInteger(
                "cardigan.exchange.max.idle.workers",
                DEFAULT_MAX_IDLE_WORKERS
            )
        );
    }

    boolean submit(Runnable task) {
        if (closed || !tasks.offer(task)) {
            return false;
        }
        ensureProgress();
        return true;
    }

    int workerCount() {
        return workerCount;
    }

    /** Freezes the handler work admitted to the phase about to run. */
    void beginHandlerEpoch() {
        if (!epochScheduler || closed) {
            return;
        }
        handlerEpochCutoff = tasks.tailSnapshot();
        ensureProgress();
    }

    /**
     * Reports work intentionally left beyond the current phase's cutoff. The
     * event loop uses this to start another turn instead of sleeping with an
     * epoch-deferred request in the queue.
     */
    boolean hasDeferredEpochWork() {
        return epochScheduler && !closed
            && tasks.hasDeferredWork(handlerEpochCutoff);
    }

    @Override
    public void close() {
        WorkerRunner[] snapshot;
        int count;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            snapshot = workers;
            count = workerSlots;
        }
        for (int i = 0; i < count; i++) {
            WorkerRunner worker = snapshot[i];
            Thread thread = worker == null ? null : worker.thread;
            if (thread != null) {
                LockSupport.unpark(thread);
            }
        }
    }

    boolean awaitTermination(long timeoutMillis) {
        long timeoutNanos = timeoutMillis >= Long.MAX_VALUE / 1_000_000L
            ? Long.MAX_VALUE
            : Math.max(0L, timeoutMillis) * 1_000_000L;
        long deadline = System.nanoTime() + timeoutNanos;
        boolean interrupted = false;
        synchronized (lifecycleLock) {
            while (workerCount != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return false;
                }
                try {
                    long millis = remaining / 1_000_000L;
                    int nanos = (int) (remaining % 1_000_000L);
                    lifecycleLock.wait(millis, nanos);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    /**
     * Ensures queued work has a runnable consumer. This is called both when a
     * task is submitted and after the event loop regains its carrier from a
     * continuation, which is the point where a parked handler can be observed.
     */
    void ensureProgress() {
        if (closed || scheduledContinuations.get() != 0
                || !hasRunnableWork()) {
            return;
        }

        WorkerRunner worker = availableWorkers.pollLast();
        if (worker != null) {
            worker.available = false;
            LockSupport.unpark(worker.thread);
            return;
        }

        startWorker();
    }

    private boolean hasRunnableWork() {
        return epochScheduler
            ? tasks.hasWorkBefore(handlerEpochCutoff)
            : !tasks.isEmpty();
    }

    private void startWorker() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            Integer freeSlot = freeWorkerSlots.pollLast();
            int slot;
            if (freeSlot == null) {
                if (workerSlots == workers.length) {
                    workers = Arrays.copyOf(workers, workers.length << 1);
                }
                slot = workerSlots++;
            } else {
                slot = freeSlot;
            }
            WorkerRunner worker = new WorkerRunner(slot);
            workers[slot] = worker;
            int workerId = ++nextWorkerId;
            worker.thread = loop.newVirtualThread(
                worker,
                worker,
                "cardigan-exchange-core" + loop.getCpuId() + "-" + workerId
            );
            workerCount++;
            if (Http2ResourceStats.ENABLED) {
                Http2ResourceStats.exchangeWorkerStarted();
            }
            try {
                worker.thread.start();
            } catch (Throwable failure) {
                worker.thread = null;
                workers[slot] = null;
                workerCount--;
                freeWorkerSlots.addLast(slot);
                if (Http2ResourceStats.ENABLED) {
                    Http2ResourceStats.exchangeWorkerStopped();
                }
                throw failure;
            }
        }
    }

    private void retireWorker(WorkerRunner worker) {
        synchronized (lifecycleLock) {
            worker.available = false;
            worker.thread = null;
            worker.continuation = null;
            workers[worker.slot] = null;
            workerCount--;
            lifecycleLock.notifyAll();
            if (!closed) {
                freeWorkerSlots.addLast(worker.slot);
            }
        }
        if (Http2ResourceStats.ENABLED) {
            Http2ResourceStats.exchangeWorkerStopped();
        }
    }

    private final class WorkerRunner implements Runnable, Executor {
        private final int slot;
        private boolean available;
        private Thread thread;
        private volatile Runnable continuation;
        private final UringEventLoop.HandlerContinuation scheduledContinuation =
            this::runContinuation;

        private WorkerRunner(int slot) {
            this.slot = slot;
        }

        @Override
        public void execute(Runnable command) {
            continuation = command;
            scheduledContinuations.incrementAndGet();
            try {
                loop.executeHandler(scheduledContinuation);
            } catch (Throwable t) {
                scheduledContinuations.decrementAndGet();
                throw t;
            }
        }

        private void runContinuation() {
            scheduledContinuations.decrementAndGet();
            try {
                continuation.run();
            } finally {
                ensureProgress();
            }
        }

        @Override
        public void run() {
            try {
                int completedInBatch = 0;
                while (!closed) {
                    Runnable task = epochScheduler
                        ? tasks.pollBefore(handlerEpochCutoff)
                        : tasks.poll();
                    if (task == null) {
                        if (!awaitWork()) {
                            return;
                        }
                        completedInBatch = 0;
                        continue;
                    }

                    try {
                        task.run();
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }

                    if (!epochScheduler) {
                        completedInBatch++;
                        if (completedInBatch == maxBatch && !tasks.isEmpty()) {
                            completedInBatch = 0;
                            Thread.yield();
                        }
                    }
                }
            } finally {
                retireWorker(this);
            }
        }

        private boolean awaitWork() {
            if (!available) {
                if (availableWorkers.size() >= maxIdleWorkers) {
                    return false;
                }
                available = true;
                availableWorkers.addLast(this);
            }
            while (available && !closed) {
                LockSupport.park(this);
            }
            return !closed;
        }
    }

    /**
     * The event loop's single carrier serializes every producer and consumer,
     * even though each operation belongs to a different virtual Thread. The
     * acquire/release indices publish ownership without the CAS required by a
     * general MPSC queue.
     */
    static final class TaskQueue {
        private static final VarHandle ARRAY =
            MethodHandles.arrayElementVarHandle(Object[].class);
        private static final VarHandle HEAD;
        private static final VarHandle TAIL;

        static {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                HEAD = lookup.findVarHandle(TaskQueue.class, "head", long.class);
                TAIL = lookup.findVarHandle(TaskQueue.class, "tail", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final Object[] elements;
        private final int mask;
        private long head;
        private long tail;

        TaskQueue(int requestedCapacity) {
            int capacity = 1;
            while (capacity < requestedCapacity) {
                capacity <<= 1;
            }
            this.elements = new Object[capacity];
            this.mask = capacity - 1;
        }

        boolean offer(Runnable task) {
            long currentTail = (long) TAIL.getAcquire(this);
            long currentHead = (long) HEAD.getAcquire(this);
            if (currentTail - currentHead >= elements.length) {
                return false;
            }

            ARRAY.setRelease(elements, (int) currentTail & mask, task);
            TAIL.setRelease(this, currentTail + 1);
            return true;
        }

        private Runnable poll() {
            long currentHead = (long) HEAD.getAcquire(this);
            if (currentHead == (long) TAIL.getAcquire(this)) {
                return null;
            }

            int index = (int) currentHead & mask;
            Runnable task = (Runnable) ARRAY.getAcquire(elements, index);
            ARRAY.setRelease(elements, index, null);
            HEAD.setRelease(this, currentHead + 1);
            return task;
        }

        Runnable pollBefore(long cutoff) {
            long currentHead = (long) HEAD.getAcquire(this);
            if (currentHead >= cutoff
                    || currentHead == (long) TAIL.getAcquire(this)) {
                return null;
            }

            int index = (int) currentHead & mask;
            Runnable task = (Runnable) ARRAY.getAcquire(elements, index);
            ARRAY.setRelease(elements, index, null);
            HEAD.setRelease(this, currentHead + 1);
            return task;
        }

        long tailSnapshot() {
            return (long) TAIL.getAcquire(this);
        }

        boolean hasWorkBefore(long cutoff) {
            return (long) HEAD.getAcquire(this) < cutoff;
        }

        boolean hasDeferredWork(long cutoff) {
            long currentHead = (long) HEAD.getAcquire(this);
            return currentHead == cutoff
                && currentHead != (long) TAIL.getAcquire(this);
        }

        private boolean isEmpty() {
            return (long) HEAD.getAcquire(this) == (long) TAIL.getAcquire(this);
        }
    }
}
