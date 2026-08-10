// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.ffi.Libc;
import dev.cardigan.ffi.RawUring;
import dev.cardigan.ffi.ThreadAffinity;
import dev.cardigan.ffi.UnsupportedKernelException;
import dev.cardigan.http2.Http2Frames;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.RejectedExecutionException;
import java.util.ArrayDeque;

public class UringEventLoop implements AutoCloseable, java.util.concurrent.Executor {
    private static final int EINTR = 4;
    private static final String[] REMOVED_SCHEDULER_PROPERTIES = {
        "cardigan.scheduler.mode",
        "cardigan.scheduler.localReady",
        "cardigan.scheduler.boundedTurns",
        "cardigan.scheduler.cqesPerTurn",
        "cardigan.scheduler.completionsPerTurn",
        "cardigan.scheduler.protocolTasksPerTurn",
        "cardigan.scheduler.handlerContinuationsPerTurn",
        "cardigan.scheduler.egressTasksPerTurn",
        "cardigan.scheduler.externalTasksPerTurn",
        "cardigan.scheduler.protocolQuantumMicros",
        "cardigan.scheduler.protocolCheckpointInterval",
        "cardigan.exchange.max.batch"
    };
    private static final boolean TASK_POOL_STATS_ENABLED =
        Boolean.getBoolean("cardigan.uring.task.stats");
    private static final boolean SCHEDULER_STATS_ENABLED =
        Boolean.getBoolean("cardigan.scheduler.stats");
    private static final boolean FIXED_FILE_STATS_ENABLED =
        Boolean.getBoolean("cardigan.fixed.files.stats");
    private static final VarHandle INT_HANDLE = ValueLayout.JAVA_INT.varHandle();
    private static final VarHandle SHORT_HANDLE = ValueLayout.JAVA_SHORT.varHandle();
    private static final VarHandle TASK_THREAD_HANDLE;
    static {
        try {
            TASK_THREAD_HANDLE = MethodHandles.lookup().findVarHandle(UringTask.class, "thread", Thread.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static final int BUFFER_SIZE = Integer.getInteger("cardigan.buffer.size", 16 * 1024);
    public static final short BUF_GROUP = 1;
    static final short KTLS_BUF_GROUP = 2;
    static final int KTLS_BUFFER_SIZE =
        BUFFER_SIZE + KtlsRecordParser.PAYLOAD_OFFSET;

    public static final int IORING_REGISTER_PBUF_RING = 22;
    public static final int IORING_UNREGISTER_PBUF_RING = 23;

    public record RecvResult(int bytesRead, int bufferId) {}

    @FunctionalInterface
    public interface CompletionHandler {
        void onCompletion(int result, int flags, boolean terminal);
    }

    /** Stable marker used to retain the handler lane across an external wake. */
    @FunctionalInterface
    interface HandlerContinuation extends Runnable {}

    /** Stable marker used to retain the egress lane across an external wake. */
    interface EgressTask extends Runnable {}

    private final int cpuId;
    private final Arena arena;
    private RawUring ring;
    private MemorySegment kheadSegment;
    private MemorySegment ktailSegment;
    private MemorySegment cqesSegment;
    private int kmask;
    private final UringTask[] tasks;
    private final IntIdPool freeIds;
    private int activeTasks;
    private int peakActiveTasks;
    private long taskPoolExhaustions;

    private final Thread loopThread;
    private final CarrierDomain carrierDomain;

    /** Continuations made runnable by the current CQE batch. */
    private final ArrayDeque<Runnable> completionReadyTasks =
        new ArrayDeque<>(256);
    /** Connection/protocol and generic owner-domain continuations. */
    private final ArrayDeque<Runnable> protocolReadyTasks =
        new ArrayDeque<>(1024);
    /** Exchange worker continuations, separate from protocol progress. */
    private final ArrayDeque<Runnable> handlerReadyTasks =
        new ArrayDeque<>(256);
    /** Connections with newly publishable output. */
    private final ArrayDeque<Runnable> egressReadyTasks =
        new ArrayDeque<>(256);
    private final MpscArrayQueue<Runnable> readyTasks = new MpscArrayQueue<>(131072);
    private boolean dispatchingCompletions;
    private final java.util.concurrent.ThreadFactory virtualThreadFactory;
    private final ExchangeExecutor exchangeExecutor;
    private final int maxHttp2ParkedSenders;
    private final AtomicInteger http2ParkedSenders = new AtomicInteger();

    public static final class IntIdPool {
        private final int[] elements;
        private int head;

        public IntIdPool(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            this.elements = new int[capacity];
            for (int i = 0; i < capacity; i++) {
                elements[i] = i;
            }
            this.head = capacity;
        }

        public int poll() {
            if (head <= 0) {
                return -1;
            }
            return elements[--head];
        }

        public void offer(int id) {
            if (head < elements.length) {
                elements[head++] = id;
            }
        }
    }

    public static final class MpscArrayQueue<T> {
        private static final VarHandle ARRAY_HANDLE = MethodHandles.arrayElementVarHandle(Object[].class);
        private static final VarHandle SEQUENCE_HANDLE = MethodHandles.arrayElementVarHandle(long[].class);
        private final Object[] buffer;
        private final long[] sequences;
        private final int mask;
        private final java.util.concurrent.atomic.AtomicLong tail = new java.util.concurrent.atomic.AtomicLong(0);
        private long head;

        public MpscArrayQueue(int capacity) {
            int cap = 1;
            while (cap < capacity) {
                cap <<= 1;
            }
            this.buffer = new Object[cap];
            this.sequences = new long[cap];
            this.mask = cap - 1;
            for (int i = 0; i < cap; i++) {
                sequences[i] = i;
            }
        }

        public boolean offer(T value) {
            if (value == null) throw new NullPointerException();
            while (true) {
                long t = tail.get();
                int index = (int) (t & mask);
                long sequence = (long) SEQUENCE_HANDLE.getAcquire(
                    sequences, index);
                long difference = sequence - t;
                if (difference < 0) {
                    return false;
                }
                if (difference == 0 && tail.compareAndSet(t, t + 1)) {
                    ARRAY_HANDLE.set(buffer, index, value);
                    SEQUENCE_HANDLE.setRelease(sequences, index, t + 1);
                    return true;
                }
                Thread.onSpinWait();
            }
        }

        @SuppressWarnings("unchecked")
        public T poll() {
            long h = head;
            int index = (int) (h & mask);
            long sequence = (long) SEQUENCE_HANDLE.getAcquire(
                sequences, index);
            if (sequence != h + 1) {
                return null;
            }
            Object value = ARRAY_HANDLE.get(buffer, index);
            ARRAY_HANDLE.set(buffer, index, null);
            SEQUENCE_HANDLE.setRelease(
                sequences, index, h + buffer.length);
            head = h + 1;
            return (T) value;
        }

        public boolean isEmpty() {
            long h = head;
            int index = (int) (h & mask);
            long sequence = (long) SEQUENCE_HANDLE.getAcquire(
                sequences, index);
            return sequence != h + 1;
        }

        /**
         * Captures the contiguous published prefix visible to the sole
         * consumer. Positions claimed after the tail snapshot, and positions
         * hidden behind a claimed-but-unpublished slot, belong to a later
         * scheduler epoch.
         */
        int publishedSnapshotSize() {
            long position = head;
            long snapshotTail = tail.get();
            int count = 0;
            while (position != snapshotTail && count < buffer.length) {
                int index = (int) (position & mask);
                long sequence = (long) SEQUENCE_HANDLE.getAcquire(
                    sequences, index);
                if (sequence != position + 1) {
                    break;
                }
                position++;
                count++;
            }
            return count;
        }
    }
    
    private final int numBuffers;
    private final int pbufMask;
    private final MemorySegment bufRingSegment;
    private final MemorySegment ringBuffers;
    private final MemorySegment[] ringBufferSegments;
    /** One exclusive reusable wrapper per plaintext provided-buffer ID. */
    private final InboundChunk[] inboundChunks;
    private final ArrayDeque<Runnable> inboundBufferWaiters = new ArrayDeque<>();
    private final int[] returnedInboundBufferIds;
    private int returnedInboundBufferCount;
    private short pbufTail = 0;
    private final boolean ktlsReceiveBuffers;
    private final MemorySegment ktlsBufRingSegment;
    private final MemorySegment ktlsRingBuffers;
    private final MemorySegment[] ktlsRingBufferSegments;
    private final MemorySegment ktlsRecvmsgHeader;
    private final ArrayDeque<Runnable> ktlsBufferWaiters = new ArrayDeque<>();
    private final int[] returnedKtlsBufferIds;
    private int returnedKtlsBufferCount;
    private short ktlsPbufTail;

    /**
     * Holds a full default-sized HTTP/2 DATA payload plus its frame header
     * while keeping every pooled buffer on a cache-line-aligned stride.
     */
    public static final int EGRESS_FRAME_SIZE =
        Http2Frames.DEFAULT_MAX_FRAME_SIZE + 64;
    static final int MAX_SEND_VECTORS = 16;
    private static final long MSGHDR_IOV_OFFSET = 16;
    private static final long MSGHDR_IOV_COUNT_OFFSET = 24;
    private static final long MSGHDR_CONTROL_LENGTH_OFFSET = 40;
    private static final long MSGHDR_SIZE = 56;
    private static final long VECTOR_IOV_OFFSET = 64;
    private static final long IOV_SIZE = 16;
    private static final long VECTOR_SLOT_SIZE = VECTOR_IOV_OFFSET + MAX_SEND_VECTORS * IOV_SIZE;
    private final int numEgressBuffers;
    private final MemorySegment egressBufferRing;
    private final MemorySegment[] egressBufferSegments;
    private final IntIdPool freeEgressIds;
    private final MemorySegment vectorScratch;
    private final IntIdPool freeVectorSlots;

    private int evfd = -1;
    private final MemorySegment evfdBuf;
    private final MemorySegment wakeupBuf;
    private final AtomicInteger wakeupPending = new AtomicInteger();
    private final AtomicInteger wakeupWriteFailures = new AtomicInteger();
    private volatile Throwable wakeupFailure;
    
    public static final int MAX_FIXED_FILES = Math.max(
        1,
        Integer.getInteger("cardigan.fixed.files.capacity", 8192));
    private final MemorySegment registeredFds;
    private final MemorySegment emptyFdSegment;
    private final IntIdPool freeFixedSlots;
    private final MemorySegment fileUpdateValues;
    private final ArrayDeque<Runnable> fixedFileWaiters = new ArrayDeque<>();
    private int activeFixedFiles;
    private int peakActiveFixedFiles;
    private long fixedFileCapacityMisses;
    private boolean useFixedFiles;

    private volatile boolean closed = false;
    private boolean resourcesClosed;
    private volatile boolean isSleeping = false;

    private long schedulerEpochs;
    private long schedulerCqes;
    private long schedulerCompletionTasks;
    private long schedulerProtocolTasks;
    private long schedulerHandlerTasks;
    private long schedulerHandlerRanges;
    private long schedulerHandlerRangeBoundaries;
    private long schedulerEgressTasks;
    private long schedulerExternalTasks;
    private long schedulerTaskWorkEnters;
    private long schedulerSubmits;
    private long schedulerWaits;
    private volatile long schedulerEpoch;

    public UringEventLoop(int cpuId, int entries) {
        this(cpuId, entries, Math.max(entries, 512), false);
    }

    public UringEventLoop(int cpuId, int entries, int numBuffers) {
        this(cpuId, entries, numBuffers, false);
    }

    public UringEventLoop(
            int cpuId, int entries, int numBuffers,
            boolean ktlsReceiveBuffers) {
        validateSchedulerConfiguration();
        this.cpuId = cpuId;
        int bufCap = 1;
        while (bufCap < numBuffers) {
            bufCap <<= 1;
        }
        this.numBuffers = bufCap;
        this.pbufMask = bufCap - 1;
        this.arena = Arena.ofShared();
        this.evfdBuf = arena.allocate(8);
        this.wakeupBuf = arena.allocate(8);
        this.wakeupBuf.set(ValueLayout.JAVA_LONG, 0, 1L);

        this.ringBuffers = arena.allocate((long) this.numBuffers * BUFFER_SIZE);
        this.ringBufferSegments = new MemorySegment[this.numBuffers];
        this.inboundChunks = new InboundChunk[this.numBuffers];
        this.returnedInboundBufferIds = new int[this.numBuffers];
        for (int i = 0; i < this.numBuffers; i++) {
            ringBufferSegments[i] = ringBuffers.asSlice(
                (long) i * BUFFER_SIZE, BUFFER_SIZE);
            inboundChunks[i] = new InboundChunk(
                this, ringBufferSegments[i], i);
        }

        this.bufRingSegment = arena.allocate((long) this.numBuffers * 16, 4096);
        this.bufRingSegment.fill((byte) 0);

        this.ktlsReceiveBuffers = ktlsReceiveBuffers;
        if (ktlsReceiveBuffers) {
            this.ktlsRingBuffers = arena.allocate(
                (long) this.numBuffers * KTLS_BUFFER_SIZE, 64);
            this.ktlsRingBufferSegments =
                new MemorySegment[this.numBuffers];
            for (int i = 0; i < this.numBuffers; i++) {
                ktlsRingBufferSegments[i] = ktlsRingBuffers.asSlice(
                    (long) i * KTLS_BUFFER_SIZE, KTLS_BUFFER_SIZE);
            }
            this.ktlsBufRingSegment = arena.allocate(
                (long) this.numBuffers * 16, 4096);
            this.ktlsBufRingSegment.fill((byte) 0);
            this.ktlsRecvmsgHeader = arena.allocate(MSGHDR_SIZE, 8);
            this.returnedKtlsBufferIds = new int[this.numBuffers];
            this.ktlsRecvmsgHeader.fill((byte) 0);
            this.ktlsRecvmsgHeader.set(
                ValueLayout.JAVA_LONG, MSGHDR_CONTROL_LENGTH_OFFSET,
                (long) KtlsRecordParser.CONTROL_RESERVE);
        } else {
            this.ktlsRingBuffers = MemorySegment.NULL;
            this.ktlsRingBufferSegments = null;
            this.ktlsBufRingSegment = MemorySegment.NULL;
            this.ktlsRecvmsgHeader = MemorySegment.NULL;
            this.returnedKtlsBufferIds = null;
        }

        this.numEgressBuffers = Math.max(this.numBuffers, 4096);
        this.egressBufferRing = arena.allocate((long) this.numEgressBuffers * EGRESS_FRAME_SIZE);
        this.egressBufferSegments =
            new MemorySegment[this.numEgressBuffers];
        for (int i = 0; i < this.numEgressBuffers; i++) {
            egressBufferSegments[i] = egressBufferRing.asSlice(
                (long) i * EGRESS_FRAME_SIZE, EGRESS_FRAME_SIZE);
        }
        this.freeEgressIds = new IntIdPool(this.numEgressBuffers);
        int numVectorSlots = this.numEgressBuffers >> 1;
        this.vectorScratch = arena.allocate((long) numVectorSlots * VECTOR_SLOT_SIZE, 64);
        this.freeVectorSlots = new IntIdPool(numVectorSlots);
        this.maxHttp2ParkedSenders = configuredMaxHttp2ParkedSenders();

        java.util.concurrent.ThreadFactory factory;
        try {
            Class<?> builderClass = Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder");
            java.lang.reflect.Constructor<?> ctor = builderClass.getDeclaredConstructor(java.util.concurrent.Executor.class);
            ctor.setAccessible(true);
            Thread.Builder.OfVirtual builder = (Thread.Builder.OfVirtual) ctor.newInstance(this);
            factory = builder.name("cardigan-vt-core" + cpuId + "-", 0).factory();
        } catch (Throwable t) {
            throw new RuntimeException("Missing JVM argument: --add-opens java.base/java.lang=ALL-UNNAMED", t);
        }
        this.virtualThreadFactory = factory;
        this.exchangeExecutor = new ExchangeExecutor(this);

        int numTasks = configuredTaskCapacity(entries);
        this.tasks = new UringTask[numTasks];
        this.freeIds = new IntIdPool(numTasks);
        for (int i = 0; i < numTasks; i++) {
            tasks[i] = new UringTask(i);
        }
        this.fileUpdateValues = arena.allocate((long) numTasks * 4, 4);

        this.freeFixedSlots = new IntIdPool(MAX_FIXED_FILES);
        this.registeredFds = arena.allocate((long) MAX_FIXED_FILES * 4);
        for (int i = 0; i < MAX_FIXED_FILES; i++) {
            this.registeredFds.set(ValueLayout.JAVA_INT, (long) i * 4, -1);
        }
        this.emptyFdSegment = arena.allocate(4);
        this.emptyFdSegment.set(ValueLayout.JAVA_INT, 0, -1);

        CountDownLatch initLatch = new CountDownLatch(1);
        AtomicReference<Throwable> initError = new AtomicReference<>();

        this.loopThread = Thread.ofPlatform()
            .daemon(true)
            .name("cardigan-loop-" + cpuId)
            .unstarted(() -> runLoop(entries, initLatch, initError));
        this.carrierDomain = new CarrierDomain(loopThread);
        loopThread.start();

        try {
            initLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            arena.close();
            throw new RuntimeException("Interrupted initializing UringEventLoop for CPU " + cpuId, e);
        }

        if (initError.get() != null) {
            arena.close();
            Throwable failure = initError.get();
            if (failure instanceof UnsupportedKernelException unsupported) {
                throw unsupported;
            }
            throw new RuntimeException(
                "Failed to initialize UringEventLoop for CPU " + cpuId
                    + ": " + failure.getMessage(),
                failure);
        }
    }

    private void runLoop(int entries, CountDownLatch initLatch, AtomicReference<Throwable> initError) {
        try {
            int affinityResult = ThreadAffinity.pinCurrentThread(cpuId);
            if (affinityResult != 0) {
                throw new IllegalStateException(
                    "Failed to pin cardigan-loop-" + cpuId
                        + " to Linux CPU " + cpuId + ": error "
                        + affinityResult
                        + ". Cardigan requires pinned event loops; check the "
                        + "process cpuset and cardigan.eventloop.cpus");
            }
            System.out.println(
                "Pinned cardigan-loop-" + cpuId + " to Linux CPU " + cpuId);

            int flags = Opcodes.IORING_SETUP_SINGLE_ISSUER
                | Opcodes.IORING_SETUP_SUBMIT_ALL
                | Opcodes.IORING_SETUP_DEFER_TASKRUN
                | Opcodes.IORING_SETUP_TASKRUN_FLAG;
            this.ring = new RawUring(arena, entries, flags);

            this.evfd = (int) Libc.eventfd.invokeExact(0, 0);
            if (this.evfd < 0) {
                throw new RuntimeException("eventfd creation failed: " + this.evfd);
            }

            this.kheadSegment = ring.cqHead();
            this.ktailSegment = ring.cqTail();
            this.kmask = ring.cqMask();
            this.cqesSegment = ring.cqes();

            int registerResult = ring.registerFiles(
                registeredFds, MAX_FIXED_FILES);
            if (registerResult < 0) {
                throw new IllegalStateException(
                    "IORING_REGISTER_FILES is required but failed for CPU "
                        + cpuId + " with error " + registerResult
                        + ". Check kernel support and process resource limits");
            }
            this.useFixedFiles = true;

            submitEvfdRead();
            initProvidedBuffers();

        } catch (Throwable t) {
            if (evfd >= 0) {
                try {
                    int unused = (int) Libc.close.invokeExact(evfd);
                } catch (Throwable ignored) {
                }
                evfd = -1;
            }
            if (ring != null) {
                try {
                    ring.close();
                } catch (Throwable ignored) {
                }
            }
            initError.set(t);
            initLatch.countDown();
            return;
        }

        initLatch.countDown();

        while (!closed && wakeupFailure == null) {
            try {
                runSchedulerEpoch();
                if (hasEpochSourceExcludingPendingSq()) {
                    submitEpochBoundary();
                    continue;
                }

                // An epoch with no runnable/kernel source can combine its
                // pending SQ publication with the blocking wait. Publish
                // sleeping first so an external producer cannot arrive
                // between the source check and io_uring_enter unnoticed.
                isSleeping = true;
                VarHandle.fullFence();

                if (hasEpochSourceExcludingPendingSq()) {
                    isSleeping = false;
                    submitEpochBoundary();
                    continue;
                }

                try {
                    boolean submitted = sqePending
                        || ring.hasPendingSubmissions();
                    int ret;
                    try {
                        ret = ring.submitAndWait(1);
                    } finally {
                        sqePending = ring.hasPendingSubmissions();
                    }
                    schedulerWaits++;
                    if (submitted) {
                        schedulerSubmits++;
                    }
                    checkEnterResult(
                        ret, "waiting for the next scheduler epoch");
                } finally {
                    isSleeping = false;
                }
            } catch (Throwable t) {
                if (closed || wakeupFailure != null) break;
                System.err.println("Error in event loop for CPU " + cpuId + ": " + t.getMessage());
            }
        }
    }

    /**
     * Runs one topological scheduler epoch. Kernel work is materialized before
     * the CQ snapshot, then each downstream lane consumes exactly the work
     * present at its phase boundary. Same-lane appends therefore wait for the
     * next epoch while work produced for a later lane remains fused on this
     * carrier and can run in the current epoch.
     */
    private void runSchedulerEpoch() {
        schedulerEpochs++;
        schedulerEpoch++;

        // With DEFER_TASKRUN, task work and CQ overflow are not necessarily
        // visible through cq.tail. Materialize both before capturing the
        // kernel-source snapshot for this epoch.
        if (ring.taskWorkPending() || ring.overflowPending()) {
            boolean submitted = sqePending
                || ring.hasPendingSubmissions();
            int result;
            try {
                result = ring.enterGetEvents();
            } finally {
                sqePending = ring.hasPendingSubmissions();
            }
            schedulerTaskWorkEnters++;
            if (submitted) {
                schedulerSubmits++;
            }
            checkEnterResult(
                result, "materializing io_uring work for scheduler epoch");
        }

        int cqHead = (int) INT_HANDLE.getAcquire(kheadSegment, 0L);
        int cqTail = (int) INT_HANDLE.getAcquire(ktailSegment, 0L);
        int reaped = reapCompletionEpochSnapshot(cqHead, cqTail);
        schedulerCqes += reaped;

        int externalSnapshotSize = readyTasks.publishedSnapshotSize();
        schedulerExternalTasks += drainExternalTasks(externalSnapshotSize);

        // Handler-originated submissions are deliberately not admitted until
        // this later epoch. Seal that prior tail before current completion and
        // protocol producers append their own causal ranges.
        if (exchangeExecutor.sealDeferredHandlerRange()) {
            schedulerHandlerRanges++;
        }
        schedulerCompletionTasks += drainProducerTaskSnapshot(
            completionReadyTasks);
        schedulerProtocolTasks += drainProducerTaskSnapshot(
            protocolReadyTasks);

        // Direct protocol output has no handler range. Prepare it before the
        // range worker starts so a range boundary drains only its downstream
        // egress, not unrelated output from the producer phases.
        flushReturnedBuffers();
        schedulerEgressTasks += drainReadyTaskSnapshot(egressReadyTasks);

        exchangeExecutor.beginHandlerEpoch();
        schedulerHandlerTasks += drainReadyTaskSnapshot(handlerReadyTasks);

        // A handler continuation can park before its range frontier is crossed,
        // or can be a directly scheduled handler rather than an exchange worker.
        // Preserve the ordinary phase-end drain for output from those cases.
        flushReturnedBuffers();
        schedulerEgressTasks += drainReadyTaskSnapshot(egressReadyTasks);
    }

    private int drainProducerTaskSnapshot(ArrayDeque<Runnable> tasks) {
        int snapshotSize = tasks.size();
        int count = 0;
        while (count < snapshotSize) {
            Runnable task = tasks.pollFirst();
            if (task == null) {
                break;
            }
            long handlerTail = exchangeExecutor.handlerTailSnapshot();
            runReadyTask(task);
            if (exchangeExecutor.sealHandlerRange(handlerTail)) {
                schedulerHandlerRanges++;
            }
            count++;
        }
        return count;
    }

    /**
     * Direct downstream handoff from a mounted exchange worker. EgressTask is
     * an owner-internal marker (ConnectionWriter in production), so this path
     * prepares SQEs and wakes framework waiters but cannot invoke a route.
     * Publication through io_uring_enter remains at the normal epoch boundary,
     * except for the existing SQ-full reserve path.
     */
    void handlerRangeBoundary() {
        if (!inCarrierDomain()) {
            throw new IllegalStateException(
                "Handler range boundary escaped the epoch carrier");
        }
        schedulerHandlerRangeBoundaries++;
        flushReturnedBuffers();
        schedulerEgressTasks += drainReadyTaskSnapshot(egressReadyTasks);
    }

    private int drainReadyTaskSnapshot(ArrayDeque<Runnable> tasks) {
        int snapshotSize = tasks.size();
        int count = 0;
        while (count < snapshotSize) {
            Runnable task = tasks.pollFirst();
            if (task == null) {
                break;
            }
            runReadyTask(task);
            count++;
        }
        return count;
    }

    private void submitEpochBoundary() {
        if (sqePending || ring.hasPendingSubmissions()) {
            submitPendingOperations();
        }
    }

    private boolean hasEpochSourceExcludingPendingSq() {
        return !completionReadyTasks.isEmpty()
            || !protocolReadyTasks.isEmpty()
            || !handlerReadyTasks.isEmpty()
            || !egressReadyTasks.isEmpty()
            || !readyTasks.isEmpty()
            || returnedInboundBufferCount != 0
            || returnedKtlsBufferCount != 0
            || hasCompletions()
            || ring.taskWorkPending()
            || ring.overflowPending()
            || exchangeExecutor.hasDeferredEpochWork();
    }

    private int drainExternalTasks(int snapshotSize) {
        int count = 0;
        Runnable task;
        while (count < snapshotSize && (task = readyTasks.poll()) != null) {
            if (task instanceof HandlerContinuation) {
                handlerReadyTasks.addLast(task);
            } else if (task instanceof EgressTask) {
                egressReadyTasks.addLast(task);
            } else {
                protocolReadyTasks.addLast(task);
            }
            count++;
        }
        return count;
    }

    private void runReadyTask(Runnable task) {
        try {
            task.run();
        } catch (Throwable failure) {
            failure.printStackTrace();
        }
    }

    /**
     * Natural epoch safe-point used before an owner-domain consumer moves to
     * another receiver-owned chunk. It has no time or request-count policy:
     * it yields only when another scheduler, kernel, submission,
     * buffer-return, or deferred-exchange source already needs the fused
     * carrier.
     */
    void inboundChunkBoundary() {
        if (!inCarrierDomain()) {
            return;
        }
        if (hasEpochSourceExcludingPendingSq()
                || sqePending || ring.hasPendingSubmissions()) {
            Thread.yield();
        }
    }

    private static void checkEnterResult(int result, String operation) {
        if (result < 0) {
            throw new IllegalStateException(
                operation + " failed with error " + result);
        }
    }

    private boolean hasCompletions() {
        int head = (int) INT_HANDLE.getAcquire(kheadSegment, 0L);
        int tail = (int) INT_HANDLE.getAcquire(ktailSegment, 0L);
        return head != tail;
    }

    private void submitEvfdRead() {
        MemorySegment sqeRaw = ring.getSqe();
        if (sqeRaw.equals(MemorySegment.NULL)) {
            int submitResult;
            try {
                submitResult = ring.submit();
            } finally {
                sqePending = ring.hasPendingSubmissions();
            }
            schedulerSubmits++;
            checkEnterResult(
                submitResult, "making room for the eventfd wakeup read");
            sqeRaw = ring.getSqe();
            if (sqeRaw.equals(MemorySegment.NULL)) {
                throw new IllegalStateException(
                    "Unable to reserve the eventfd wakeup SQE");
            }
        }
        MemorySegment sqe = sqeRaw;
        sqe.fill((byte) 0);

        sqe.set(ValueLayout.JAVA_BYTE, 0, Opcodes.IORING_OP_READ);
        sqe.set(ValueLayout.JAVA_INT, 4, evfd);
        sqe.set(ValueLayout.JAVA_LONG, 16, evfdBuf.address());
        sqe.set(ValueLayout.JAVA_INT, 24, 8);
        sqe.set(ValueLayout.JAVA_LONG, 32, -99L);
        sqePending = true;
    }

    private void initProvidedBuffers() {
        try {
            initializeProvidedBufferEntries(
                bufRingSegment, ringBuffers, BUFFER_SIZE);
            this.pbufTail = (short) numBuffers;
            SHORT_HANDLE.setRelease(bufRingSegment, 14L, this.pbufTail);

            if (ktlsReceiveBuffers) {
                initializeProvidedBufferEntries(
                    ktlsBufRingSegment, ktlsRingBuffers, KTLS_BUFFER_SIZE);
                this.ktlsPbufTail = (short) numBuffers;
                SHORT_HANDLE.setRelease(
                    ktlsBufRingSegment, 14L, this.ktlsPbufTail);
            }

            try (Arena localArena = Arena.ofConfined()) {
                MemorySegment reg = localArena.allocate(48);
                registerProvidedBufferRing(
                    reg, bufRingSegment, BUF_GROUP);
                if (ktlsReceiveBuffers) {
                    registerProvidedBufferRing(
                        reg, ktlsBufRingSegment, KTLS_BUF_GROUP);
                }
            }
        } catch (Throwable t) {
            throw new RuntimeException("Failed to initialize provided buffer ring", t);
        }
    }

    private void initializeProvidedBufferEntries(
            MemorySegment bufferRing, MemorySegment buffers, int bufferSize) {
        for (int i = 0; i < numBuffers; i++) {
            long bufferAddress = buffers.address() + (long) i * bufferSize;
            long offset = (long) i * 16;
            bufferRing.set(ValueLayout.JAVA_LONG, offset, bufferAddress);
            bufferRing.set(ValueLayout.JAVA_INT, offset + 8, bufferSize);
            bufferRing.set(ValueLayout.JAVA_SHORT, offset + 12, (short) i);
            bufferRing.set(ValueLayout.JAVA_SHORT, offset + 14, (short) 0);
        }
    }

    private void registerProvidedBufferRing(
            MemorySegment registration, MemorySegment bufferRing,
            short bufferGroup) {
        registration.fill((byte) 0);
        registration.set(ValueLayout.JAVA_LONG, 0, bufferRing.address());
        registration.set(ValueLayout.JAVA_INT, 8, numBuffers);
        registration.set(ValueLayout.JAVA_SHORT, 12, bufferGroup);

        int result = ring.register(
            IORING_REGISTER_PBUF_RING, registration, 1);
        if (result < 0) {
            throw new RuntimeException(
                "Registering provided buffer group " + bufferGroup
                    + " failed: " + result);
        }
    }

    /**
     * Consumes exactly the CQ interval captured at epoch entry. Publish each
     * consumed head before invoking Java code so an unbounded snapshot cannot
     * keep a full CQ artificially occupied while a callback submits more I/O.
     */
    private int reapCompletionEpochSnapshot(int head, int snapshotTail) {
        if (head == snapshotTail) {
            return 0;
        }
        dispatchingCompletions = true;
        int count = 0;
        try {
            while (head != snapshotTail) {
                int index = head & kmask;
                long cqeOffset = index * 16L;
                long userData = cqesSegment.get(
                    ValueLayout.JAVA_LONG, cqeOffset);
                int res = cqesSegment.get(
                    ValueLayout.JAVA_INT, cqeOffset + 8);
                int flags = cqesSegment.get(
                    ValueLayout.JAVA_INT, cqeOffset + 12);

                head++;
                count++;
                INT_HANDLE.setRelease(kheadSegment, 0L, head);
                dispatchCompletion(userData, res, flags);
            }
        } finally {
            dispatchingCompletions = false;
        }
        return count;
    }

    private void dispatchCompletion(long userData, int res, int flags) {
        if (userData == -99L) {
            wakeupPending.set(0);
            submitEvfdRead();
            return;
        }
        if (userData == -88L) {
            return;
        }

        int taskId = (int) userData;
        if (taskId < 0 || taskId >= tasks.length) {
            return;
        }
        UringTask task = tasks[taskId];
        if (task.userData != userData) {
            return;
        }

        CompletionHandler completionHandler = task.completionHandler;
        if (completionHandler != null) {
            if (task.vectorSlot >= 0) {
                handleAsyncVectorSendCompletion(
                    task, res, flags, completionHandler);
                return;
            }
            if (task.egressId >= 0) {
                handleAsyncSendCompletion(
                    task, res, flags, completionHandler);
                return;
            }
            boolean terminal =
                (flags & Opcodes.IORING_CQE_F_MORE) == 0;
            if (terminal) {
                task.completionHandler = null;
                releaseTaskId(taskId);
            }
            completionHandler.onCompletion(res, flags, terminal);
            return;
        }

        // Only synchronous operations publish completion state through the
        // task before waking their parked virtual thread. Async handlers take
        // result and flags directly and never acquire task.thread.
        task.result = res;
        task.flags = flags;
        Thread vt = (Thread) TASK_THREAD_HANDLE.getAcquire(task);
        if (vt != null
                && (flags & Opcodes.IORING_CQE_F_MORE) == 0) {
            TASK_THREAD_HANDLE.setRelease(task, null);
            LockSupport.unpark(vt);
        } else if (vt == null
                && (flags & Opcodes.IORING_CQE_F_MORE) == 0) {
            releaseTaskId(taskId);
        }
    }

    private boolean sqePending = false;

    void submitPendingOperations() {
        if (!sqePending && !ring.hasPendingSubmissions()) {
            return;
        }
        int result;
        try {
            result = ring.submit();
        } finally {
            sqePending = ring.hasPendingSubmissions();
        }
        schedulerSubmits++;
        if (result < 0) {
            throw new IllegalStateException(
                "Submitting pending io_uring operations failed: " + result);
        }
    }

    private void submitForSqeSpace() {
        int result;
        try {
            result = ring.submit();
        } finally {
            sqePending = ring.hasPendingSubmissions();
        }
        schedulerSubmits++;
        checkEnterResult(result, "making room in the io_uring submission queue");
    }

    private MemorySegment reserveSqe() {
        MemorySegment sqe = ring.getSqe();
        if (!sqe.equals(MemorySegment.NULL)) {
            return sqe;
        }
        submitForSqeSpace();
        sqe = ring.getSqe();
        if (sqe.equals(MemorySegment.NULL)) {
            throw new IllegalStateException(
                "io_uring submission queue remained full after submit");
        }
        return sqe;
    }

    private void handleAsyncSendCompletion(UringTask task, int res, int flags, CompletionHandler completionHandler) {
        if ((flags & Opcodes.IORING_CQE_F_MORE) != 0) {
            completionHandler.onCompletion(res, flags, false);
            return;
        }

        boolean retry = res == -11
            || (res > 0 && res < task.len);

        if (retry) {
            if (res > 0) {
                task.addr += res;
                task.len -= res;
            }
            if (resubmitTaskAsync(task)) {
                return;
            }
            res = -5;
        }

        int egressId = task.egressId;
        task.egressId = -1;
        task.completionHandler = null;
        if (egressId >= 0) {
            releaseEgressBuffer(egressId);
        }
        releaseTaskId(task.id);
        completionHandler.onCompletion(res, flags, true);
    }

    private void handleAsyncVectorSendCompletion(
        UringTask task,
        int res,
        int flags,
        CompletionHandler completionHandler
    ) {
        if ((flags & Opcodes.IORING_CQE_F_MORE) != 0) {
            completionHandler.onCompletion(res, flags, false);
            return;
        }

        boolean retry = res == -11
            || (res > 0 && res < task.vectorRemaining);

        if (retry) {
            if (res > 0) {
                if (!advanceVector(task, res)) {
                    res = -5;
                    retry = false;
                }
            }
            if (retry && resubmitTaskAsync(task)) {
                return;
            }
            res = -5;
        } else if (res > task.vectorRemaining) {
            res = -5;
        }

        int vectorSlot = task.vectorSlot;
        task.vectorSlot = -1;
        task.completionHandler = null;
        if (vectorSlot >= 0) {
            freeVectorSlots.offer(vectorSlot);
        }
        releaseTaskId(task.id);
        completionHandler.onCompletion(res, flags, true);
    }

    private boolean advanceVector(UringTask task, int bytes) {
        long slotOffset = (long) task.vectorSlot * VECTOR_SLOT_SIZE;
        MemorySegment iovecs = vectorScratch.asSlice(
            slotOffset + VECTOR_IOV_OFFSET,
            MAX_SEND_VECTORS * IOV_SIZE
        );
        int nextIndex = advanceIovecs(iovecs, task.vectorIndex, task.vectorCount, bytes);
        if (nextIndex < 0) {
            return false;
        }

        task.vectorIndex = nextIndex;
        task.vectorRemaining -= bytes;
        long iovAddress = iovecs.address() + (long) nextIndex * IOV_SIZE;
        vectorScratch.set(ValueLayout.JAVA_LONG, slotOffset + MSGHDR_IOV_OFFSET, iovAddress);
        vectorScratch.set(
            ValueLayout.JAVA_LONG,
            slotOffset + MSGHDR_IOV_COUNT_OFFSET,
            task.vectorCount - nextIndex
        );
        return true;
    }

    static int advanceIovecs(MemorySegment iovecs, int first, int count, int bytes) {
        if (bytes < 0 || first < 0 || count < first) {
            return -1;
        }

        int index = first;
        int remaining = bytes;
        while (remaining != 0 && index < count) {
            long offset = (long) index * IOV_SIZE;
            long length = iovecs.get(ValueLayout.JAVA_LONG, offset + 8);
            if (length <= 0) {
                return -1;
            }
            if (remaining < length) {
                long address = iovecs.get(ValueLayout.JAVA_LONG, offset);
                iovecs.set(ValueLayout.JAVA_LONG, offset, address + remaining);
                iovecs.set(ValueLayout.JAVA_LONG, offset + 8, length - remaining);
                remaining = 0;
            } else {
                remaining -= (int) length;
                index++;
            }
        }
        return remaining == 0 ? index : -1;
    }

    private boolean resubmitTaskAsync(UringTask task) {
        try {
            MemorySegment sqeRaw = ring.getSqe();
            if (sqeRaw.equals(MemorySegment.NULL)) {
                submitForSqeSpace();
                sqeRaw = ring.getSqe();
                if (sqeRaw.equals(MemorySegment.NULL)) {
                    return false;
                }
            }

            MemorySegment sqe = sqeRaw;
            sqe.fill((byte) 0);

            sqe.set(ValueLayout.JAVA_BYTE, 0, task.opcode);
            sqe.set(ValueLayout.JAVA_BYTE, 1, task.opFlags);
            sqe.set(ValueLayout.JAVA_INT, 4, task.fd);
            sqe.set(ValueLayout.JAVA_LONG, 8, task.off);
            sqe.set(ValueLayout.JAVA_LONG, 16, task.addr);
            sqe.set(ValueLayout.JAVA_INT, 24, task.len);
            sqe.set(ValueLayout.JAVA_INT, 28, task.unionFlags);
            sqe.set(ValueLayout.JAVA_LONG, 32, task.userData);
            sqe.set(ValueLayout.JAVA_SHORT, 40, task.bufGroup);
            sqePending = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean submitOpAsync(byte opcode, byte flags, int fd, long addr, int len, long off, int unionFlags,
                                  short bufGroup, int egressId, int rawFd, CompletionHandler completionHandler) {
        int taskId = acquireTaskId();
        if (taskId < 0) {
            return false;
        }

        UringTask task = tasks[taskId];
        prepareTask(task);
        TASK_THREAD_HANDLE.setRelease(task, null);
        task.completionHandler = completionHandler;
        task.result = -11;
        task.flags = 0;
        task.egressId = egressId;
        task.opcode = opcode;
        task.opFlags = flags;
        task.fd = fd;
        task.rawFd = rawFd;
        task.addr = addr;
        task.len = len;
        task.off = off;
        task.unionFlags = unionFlags;
        task.bufGroup = bufGroup;

        try {
            MemorySegment sqeRaw = ring.getSqe();
            if (sqeRaw.equals(MemorySegment.NULL)) {
                submitForSqeSpace();
                sqeRaw = ring.getSqe();
                if (sqeRaw.equals(MemorySegment.NULL)) {
                    task.completionHandler = null;
                    task.egressId = -1;
                    releaseTaskId(taskId);
                    return false;
                }
            }

            MemorySegment sqe = sqeRaw;
            sqe.fill((byte) 0);

            sqe.set(ValueLayout.JAVA_BYTE, 0, opcode);
            sqe.set(ValueLayout.JAVA_BYTE, 1, flags);
            sqe.set(ValueLayout.JAVA_INT, 4, fd);
            sqe.set(ValueLayout.JAVA_LONG, 8, off);
            sqe.set(ValueLayout.JAVA_LONG, 16, addr);
            sqe.set(ValueLayout.JAVA_INT, 24, len);
            sqe.set(ValueLayout.JAVA_INT, 28, unionFlags);
            sqe.set(ValueLayout.JAVA_LONG, 32, task.userData);
            sqe.set(ValueLayout.JAVA_SHORT, 40, bufGroup);
            sqePending = true;
            return true;
        } catch (Throwable t) {
            task.completionHandler = null;
            task.egressId = -1;
            releaseTaskId(taskId);
            return false;
        }
    }

    private static void prepareTask(UringTask task) {
        int generation = task.generation + 1;
        if (generation == 0) {
            generation = 1;
        }
        task.generation = generation;
        task.userData = ((long) generation << 32) | (task.id & 0xffff_ffffL);
        task.completionHandler = null;
        task.egressId = -1;
        task.vectorSlot = -1;
    }

    private int acquireTaskId() {
        int taskId = freeIds.poll();
        if (TASK_POOL_STATS_ENABLED) {
            if (taskId < 0) {
                taskPoolExhaustions++;
            } else {
                int current = ++activeTasks;
                if (current > peakActiveTasks) {
                    peakActiveTasks = current;
                }
            }
        }
        return taskId;
    }

    private void releaseTaskId(int taskId) {
        if (TASK_POOL_STATS_ENABLED) {
            activeTasks--;
        }
        freeIds.offer(taskId);
    }

    public boolean writeAsync(int fd, MemorySegment buf, int len, int fixedSlot, int egressId,
                              CompletionHandler completionHandler) {
        byte opFlags = (fixedSlot >= 0) ? Opcodes.IOSQE_FIXED_FILE : (byte) 0;
        int targetFd = (fixedSlot >= 0) ? fixedSlot : fd;
        return submitOpAsync(Opcodes.IORING_OP_SEND, opFlags, targetFd, buf.address(), len, 0L, 0x4000,
            (short) 0, egressId, fd, completionHandler);
    }

    boolean writeVectorAsync(
        int fd,
        long[] addresses,
        int[] lengths,
        int count,
        int fixedSlot,
        CompletionHandler completionHandler
    ) {
        if (count < 2 || count > MAX_SEND_VECTORS) {
            throw new IllegalArgumentException("Invalid vector count: " + count);
        }

        int vectorSlot = freeVectorSlots.poll();
        if (vectorSlot < 0) {
            return false;
        }
        int taskId = acquireTaskId();
        if (taskId < 0) {
            freeVectorSlots.offer(vectorSlot);
            return false;
        }

        long slotOffset = (long) vectorSlot * VECTOR_SLOT_SIZE;
        MemorySegment header = vectorScratch.asSlice(slotOffset, VECTOR_IOV_OFFSET);
        header.fill((byte) 0);
        long iovAddress = vectorScratch.address() + slotOffset + VECTOR_IOV_OFFSET;
        int totalLength = 0;
        for (int i = 0; i < count; i++) {
            int length = lengths[i];
            if (length <= 0) {
                releaseTaskId(taskId);
                freeVectorSlots.offer(vectorSlot);
                throw new IllegalArgumentException("Invalid vector length: " + length);
            }
            long iovOffset = slotOffset + VECTOR_IOV_OFFSET + (long) i * IOV_SIZE;
            vectorScratch.set(
                ValueLayout.JAVA_LONG,
                iovOffset,
                addresses[i]
            );
            vectorScratch.set(ValueLayout.JAVA_LONG, iovOffset + 8, length);
            totalLength += length;
        }
        vectorScratch.set(ValueLayout.JAVA_LONG, slotOffset + MSGHDR_IOV_OFFSET, iovAddress);
        vectorScratch.set(ValueLayout.JAVA_LONG, slotOffset + MSGHDR_IOV_COUNT_OFFSET, count);

        UringTask task = tasks[taskId];
        prepareTask(task);
        TASK_THREAD_HANDLE.setRelease(task, null);
        task.completionHandler = completionHandler;
        task.result = -11;
        task.flags = 0;
        task.vectorSlot = vectorSlot;
        task.vectorIndex = 0;
        task.vectorCount = count;
        task.vectorRemaining = totalLength;
        task.opcode = Opcodes.IORING_OP_SENDMSG;
        task.opFlags = fixedSlot >= 0 ? Opcodes.IOSQE_FIXED_FILE : (byte) 0;
        task.fd = fixedSlot >= 0 ? fixedSlot : fd;
        task.rawFd = fd;
        task.addr = vectorScratch.address() + slotOffset;
        task.len = 1;
        task.off = 0L;
        task.unionFlags = 0x4000;
        task.bufGroup = 0;

        if (resubmitTaskAsync(task)) {
            return true;
        }

        task.completionHandler = null;
        task.vectorSlot = -1;
        releaseTaskId(taskId);
        freeVectorSlots.offer(vectorSlot);
        return false;
    }

    public boolean writeAsyncZc(int fd, MemorySegment buf, int len, int fixedSlot, int egressId,
                                CompletionHandler completionHandler) {
        // SEND_ZC adds a notification CQE, but the ordered writer tracks one
        // completion per buffer. Use SEND so its lifetime remains unambiguous.
        return writeAsync(fd, buf, len, fixedSlot, egressId, completionHandler);
    }

    private int submitOp(byte opcode, byte flags, int fd, long addr, int len, long off, int unionFlags, short bufGroup) {
        int taskId = acquireTaskId();
        if (taskId < 0) {
            throw new RuntimeException("io_uring task pool exhausted");
        }

        UringTask task = tasks[taskId];
        prepareTask(task);
        TASK_THREAD_HANDLE.setRelease(task, Thread.currentThread());
        task.result = -11;
        task.flags = 0;

        try {
            MemorySegment sqeRaw = ring.getSqe();
            if (sqeRaw.equals(MemorySegment.NULL)) {
                submitForSqeSpace();
                sqeRaw = ring.getSqe();
                if (sqeRaw.equals(MemorySegment.NULL)) {
                    throw new RuntimeException("SQE queue completely full!");
                }
            }

            MemorySegment sqe = sqeRaw;
            sqe.fill((byte) 0);

            sqe.set(ValueLayout.JAVA_BYTE, 0, opcode);
            sqe.set(ValueLayout.JAVA_BYTE, 1, flags);
            sqe.set(ValueLayout.JAVA_INT, 4, fd);
            sqe.set(ValueLayout.JAVA_LONG, 8, off);
            sqe.set(ValueLayout.JAVA_LONG, 16, addr);
            sqe.set(ValueLayout.JAVA_INT, 24, len);
            sqe.set(ValueLayout.JAVA_INT, 28, unionFlags);
            sqe.set(ValueLayout.JAVA_LONG, 32, task.userData);
            sqe.set(ValueLayout.JAVA_SHORT, 40, bufGroup);
            sqePending = true;
        } catch (Throwable t) {
            task.result = -5;
            TASK_THREAD_HANDLE.setRelease(task, null);
            releaseTaskId(taskId);
            return -5;
        }

        awaitCompletion(task);
        int result = task.result;
        releaseTaskId(taskId);
        return result;
    }

    /**
     * Asynchronously installs a raw descriptor into a kernel-selected fixed
     * slot. The calling virtual thread parks until the FILES_UPDATE CQE, so
     * registration never pins the fused carrier in io_uring_register.
     */
    public int registerFixedFd(int clientFd) {
        if (!useFixedFiles) {
            throw new IllegalStateException(
                "Fixed-file registration was not initialized");
        }

        int taskId = acquireTaskId();
        if (taskId < 0) {
            throw new IllegalStateException("io_uring task pool exhausted");
        }
        UringTask task = tasks[taskId];
        prepareTask(task);
        TASK_THREAD_HANDLE.setRelease(task, Thread.currentThread());
        task.result = -11;
        task.flags = 0;

        long valueOffset = (long) taskId * 4;
        fileUpdateValues.set(ValueLayout.JAVA_INT, valueOffset, clientFd);
        try {
            MemorySegment sqe = reserveSqe();
            prepareFilesUpdateSqe(
                sqe,
                fileUpdateValues.address() + valueOffset,
                Opcodes.IORING_FILE_INDEX_ALLOC,
                task.userData
            );
            sqePending = true;
        } catch (Throwable failure) {
            TASK_THREAD_HANDLE.setRelease(task, null);
            releaseTaskId(taskId);
            throw new IllegalStateException(
                "Submitting asynchronous fixed-file allocation failed",
                failure);
        }

        awaitCompletion(task);
        int result = task.result;
        int slot = result == 1
            ? fileUpdateValues.get(ValueLayout.JAVA_INT, valueOffset)
            : -1;
        releaseTaskId(taskId);

        if (result == -23) { // ENFILE: the registered table is full.
            fixedFileCapacityMisses++;
            return -1;
        }
        if (result != 1 || slot < 0 || slot >= MAX_FIXED_FILES) {
            throw new IllegalStateException(
                "IORING_OP_FILES_UPDATE allocation failed: result="
                    + result + ", slot=" + slot);
        }
        registeredFds.set(
            ValueLayout.JAVA_INT, (long) slot * 4, clientFd);
        fixedFileAcquired();
        return slot;
    }

    int registerFixedFdAsyncExplicit(int clientFd) {
        if (!useFixedFiles) {
            throw new IllegalStateException(
                "Fixed-file registration was not initialized");
        }
        int slot = freeFixedSlots.poll();
        if (slot < 0) {
            fixedFileCapacityMisses++;
            return -1;
        }
        int taskId = acquireTaskId();
        if (taskId < 0) {
            freeFixedSlots.offer(slot);
            throw new IllegalStateException("io_uring task pool exhausted");
        }
        UringTask task = tasks[taskId];
        prepareTask(task);
        TASK_THREAD_HANDLE.setRelease(task, Thread.currentThread());
        task.result = -11;
        task.flags = 0;
        long valueOffset = (long) taskId * 4;
        fileUpdateValues.set(ValueLayout.JAVA_INT, valueOffset, clientFd);
        try {
            MemorySegment sqe = reserveSqe();
            prepareFilesUpdateSqe(
                sqe,
                fileUpdateValues.address() + valueOffset,
                slot,
                task.userData
            );
            sqePending = true;
        } catch (Throwable failure) {
            TASK_THREAD_HANDLE.setRelease(task, null);
            releaseTaskId(taskId);
            freeFixedSlots.offer(slot);
            throw new IllegalStateException(
                "Submitting asynchronous fixed-file update failed", failure);
        }
        awaitCompletion(task);
        int result = task.result;
        releaseTaskId(taskId);
        if (result != 1) {
            freeFixedSlots.offer(slot);
            throw new IllegalStateException(
                "IORING_OP_FILES_UPDATE failed for slot " + slot
                    + " with error " + result);
        }
        registeredFds.set(
            ValueLayout.JAVA_INT, (long) slot * 4, clientFd);
        fixedFileAcquired();
        return slot;
    }

    /** Benchmark-only baseline retaining the old synchronous update path. */
    int registerFixedFdLegacy(int clientFd) {
        if (!useFixedFiles) {
            throw new IllegalStateException(
                "Fixed-file registration was not initialized");
        }
        int slot = freeFixedSlots.poll();
        if (slot < 0) {
            fixedFileCapacityMisses++;
            return -1;
        }
        registeredFds.set(ValueLayout.JAVA_INT, (long) slot * 4, clientFd);
        MemorySegment slice = registeredFds.asSlice((long) slot * 4, 4);
        int result;
        try {
            result = ring.updateFiles(slot, slice, 1);
        } catch (Throwable t) {
            throw new IllegalStateException(
                "Updating fixed-file slot " + slot + " failed", t);
        }
        if (result != 1) {
            registeredFds.set(
                ValueLayout.JAVA_INT, (long) slot * 4, -1);
            freeFixedSlots.offer(slot);
            throw new IllegalStateException(
                "IORING_REGISTER_FILES_UPDATE failed for slot " + slot
                    + " with error " + result);
        }
        fixedFileAcquired();
        return slot;
    }

    public void unregisterFixedFd(int slot, int clientFd) {
        int fixedResult = 0;
        try {
            if (slot >= 0 && useFixedFiles) {
                fixedResult = closeDirect(slot);
            }
        } finally {
            if (clientFd >= 0) {
                closeFd(clientFd);
            }
        }
        if (!directCloseSucceeded(fixedResult)) {
            throw new IllegalStateException(
                "Direct close of fixed-file slot " + slot
                    + " failed with error " + fixedResult);
        }
    }

    void unregisterFixedFdLegacy(int slot, int clientFd) {
        boolean released = false;
        try {
            if (slot >= 0 && useFixedFiles) {
                try {
                    registeredFds.set(
                        ValueLayout.JAVA_INT, (long) slot * 4, -1);
                    int ret = ring.updateFiles(slot, emptyFdSegment, 1);
                    if (ret == 1) {
                        fixedFileReleased(slot);
                        released = true;
                    } else {
                        throw new IllegalStateException(
                            "Legacy fixed-file release failed for slot "
                                + slot + " with error " + ret);
                    }
                } finally {
                    if (released) {
                        freeFixedSlots.offer(slot);
                    }
                }
            }
        } finally {
            if (clientFd >= 0) {
                closeFd(clientFd);
            }
        }
    }

    void unregisterFixedFdAsyncExplicit(int slot, int clientFd) {
        boolean released = false;
        try {
            unregisterFixedFd(slot, clientFd);
            released = true;
        } finally {
            if (slot >= 0 && released) {
                freeFixedSlots.offer(slot);
            }
        }
    }

    int closeDirect(int slot) {
        if (slot < 0 || slot >= MAX_FIXED_FILES) {
            throw new IllegalArgumentException(
                "Invalid fixed-file slot: " + slot);
        }
        int taskId = acquireTaskId();
        if (taskId < 0) {
            throw new IllegalStateException("io_uring task pool exhausted");
        }
        UringTask task = tasks[taskId];
        prepareTask(task);
        TASK_THREAD_HANDLE.setRelease(task, Thread.currentThread());
        task.result = -11;
        task.flags = 0;
        try {
            MemorySegment sqe = reserveSqe();
            prepareDirectCloseSqe(sqe, slot, task.userData);
            sqePending = true;
        } catch (Throwable failure) {
            TASK_THREAD_HANDLE.setRelease(task, null);
            releaseTaskId(taskId);
            return -5;
        }
        awaitCompletion(task);
        int result = task.result;
        releaseTaskId(taskId);
        if (directCloseSucceeded(result)) {
            fixedFileReleased(slot);
        }
        return result;
    }

    boolean closeDirectAsync(
            int slot, CompletionHandler completionHandler) {
        if (slot < 0 || slot >= MAX_FIXED_FILES) {
            return false;
        }
        int taskId = acquireTaskId();
        if (taskId < 0) {
            return false;
        }
        UringTask task = tasks[taskId];
        prepareTask(task);
        TASK_THREAD_HANDLE.setRelease(task, null);
        task.result = -11;
        task.flags = 0;
        task.completionHandler = (result, flags, terminal) -> {
            if (terminal && directCloseSucceeded(result)) {
                fixedFileReleased(slot);
            }
            completionHandler.onCompletion(result, flags, terminal);
        };
        try {
            MemorySegment sqe = reserveSqe();
            prepareDirectCloseSqe(sqe, slot, task.userData);
            sqePending = true;
            return true;
        } catch (Throwable failure) {
            task.completionHandler = null;
            releaseTaskId(taskId);
            return false;
        }
    }

    int shutdownFixed(int slot, int how) {
        if (slot < 0 || slot >= MAX_FIXED_FILES) {
            return -9;
        }
        return submitOp(
            Opcodes.IORING_OP_SHUTDOWN,
            Opcodes.IOSQE_FIXED_FILE,
            slot,
            0L,
            how,
            0L,
            0,
            (short) 0
        );
    }

    void fixedFileAccepted(int slot) {
        if (slot < 0 || slot >= MAX_FIXED_FILES) {
            throw new IllegalArgumentException(
                "Invalid direct-accept fixed slot: " + slot);
        }
        fixedFileAcquired();
    }

    void whenFixedFileAvailable(Runnable waiter) {
        fixedFileCapacityMisses++;
        if (activeFixedFiles < MAX_FIXED_FILES) {
            waiter.run();
        } else {
            fixedFileWaiters.addLast(waiter);
        }
    }

    private void fixedFileAcquired() {
        int active = ++activeFixedFiles;
        if (active > peakActiveFixedFiles) {
            peakActiveFixedFiles = active;
        }
    }

    static boolean directCloseSucceeded(int result) {
        return result == 0;
    }

    private void fixedFileReleased(int slot) {
        registeredFds.set(
            ValueLayout.JAVA_INT, (long) slot * 4, -1);
        if (activeFixedFiles > 0) {
            activeFixedFiles--;
        }
        Runnable waiter = fixedFileWaiters.pollFirst();
        if (waiter != null) {
            waiter.run();
        }
    }

    static void prepareFilesUpdateSqe(
            MemorySegment sqe, long valuesAddress,
            int offset, long userData) {
        requireSqe(sqe);
        sqe.fill((byte) 0);
        sqe.set(ValueLayout.JAVA_BYTE, 0, Opcodes.IORING_OP_FILES_UPDATE);
        sqe.set(ValueLayout.JAVA_INT, 4, -1);
        sqe.set(ValueLayout.JAVA_LONG, 8, (long) offset);
        sqe.set(ValueLayout.JAVA_LONG, 16, valuesAddress);
        sqe.set(ValueLayout.JAVA_INT, 24, 1);
        sqe.set(ValueLayout.JAVA_LONG, 32, userData);
    }

    static void prepareDirectAcceptSqe(
            MemorySegment sqe, int listenerFd, long userData) {
        requireSqe(sqe);
        sqe.fill((byte) 0);
        sqe.set(ValueLayout.JAVA_BYTE, 0, Opcodes.IORING_OP_ACCEPT);
        sqe.set(
            ValueLayout.JAVA_SHORT, 2, Opcodes.IORING_ACCEPT_MULTISHOT);
        sqe.set(ValueLayout.JAVA_INT, 4, listenerFd);
        sqe.set(ValueLayout.JAVA_LONG, 32, userData);
        sqe.set(
            ValueLayout.JAVA_INT, 44, Opcodes.IORING_FILE_INDEX_ALLOC);
    }

    static void prepareDirectCloseSqe(
            MemorySegment sqe, int slot, long userData) {
        requireSqe(sqe);
        if (slot < 0 || slot == Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Invalid fixed-file slot: " + slot);
        }
        sqe.fill((byte) 0);
        sqe.set(ValueLayout.JAVA_BYTE, 0, Opcodes.IORING_OP_CLOSE);
        sqe.set(ValueLayout.JAVA_INT, 4, 0);
        sqe.set(ValueLayout.JAVA_LONG, 32, userData);
        sqe.set(ValueLayout.JAVA_INT, 44, slot + 1);
    }

    private static void requireSqe(MemorySegment sqe) {
        if (sqe.byteSize() < 64) {
            throw new IllegalArgumentException(
                "An io_uring SQE requires 64 bytes");
        }
    }

    public RecvResult recvSelectedBuffer(int fd, int len, short bgid, int fixedSlot) {
        byte opFlags = (fixedSlot >= 0) ? (byte) (Opcodes.IOSQE_BUFFER_SELECT | Opcodes.IOSQE_FIXED_FILE) : Opcodes.IOSQE_BUFFER_SELECT;
        int targetFd = (fixedSlot >= 0) ? fixedSlot : fd;

        int taskId = acquireTaskId();
        if (taskId < 0) {
            throw new RuntimeException("io_uring task pool exhausted");
        }

        UringTask task = tasks[taskId];
        prepareTask(task);
        TASK_THREAD_HANDLE.setRelease(task, Thread.currentThread());
        task.result = -11; 
        task.flags = 0;

        try {
            MemorySegment sqeRaw = ring.getSqe();
            if (sqeRaw.equals(MemorySegment.NULL)) {
                submitForSqeSpace();
                sqeRaw = ring.getSqe();
                if (sqeRaw.equals(MemorySegment.NULL)) {
                    throw new RuntimeException("SQE queue completely full!");
                }
            }

            MemorySegment sqe = sqeRaw;
            sqe.fill((byte) 0);

            sqe.set(ValueLayout.JAVA_BYTE, 0, Opcodes.IORING_OP_RECV);
            sqe.set(ValueLayout.JAVA_BYTE, 1, opFlags);
            sqe.set(ValueLayout.JAVA_INT, 4, targetFd);
            sqe.set(ValueLayout.JAVA_LONG, 8, 0L);
            sqe.set(ValueLayout.JAVA_LONG, 16, 0L);
            sqe.set(ValueLayout.JAVA_INT, 24, len);
            sqe.set(ValueLayout.JAVA_INT, 28, 0);
            sqe.set(ValueLayout.JAVA_LONG, 32, task.userData);
            sqe.set(ValueLayout.JAVA_SHORT, 40, bgid);
            sqePending = true;
        } catch (Throwable t) {
            task.result = -5;
            TASK_THREAD_HANDLE.setRelease(task, null);
            releaseTaskId(taskId);
            return new RecvResult(-5, -1);
        }

        awaitCompletion(task);
        int res = task.result;
        int flags = task.flags;
        releaseTaskId(taskId);

        int bid = ((flags & Opcodes.IORING_CQE_F_BUFFER) != 0) ? ((flags >> Opcodes.IORING_CQE_BUFFER_SHIFT) & 0xFFFF) : -1;
        return new RecvResult(res, bid);
    }

    /**
     * Starts a provided-buffer multishot receive and returns its user_data
     * token, or {@code -1} if the SQE could not be submitted.
     */
    public long recvMultishot(int fd, short bgid, int fixedSlot, CompletionHandler completionHandler) {
        int taskId = acquireTaskId();
        if (taskId < 0) {
            return -1;
        }

        UringTask task = tasks[taskId];
        prepareTask(task);
        TASK_THREAD_HANDLE.setRelease(task, null);
        task.completionHandler = completionHandler;
        task.opcode = Opcodes.IORING_OP_RECV;
        task.opFlags = fixedSlot >= 0
            ? (byte) (Opcodes.IOSQE_BUFFER_SELECT | Opcodes.IOSQE_FIXED_FILE)
            : Opcodes.IOSQE_BUFFER_SELECT;
        task.fd = fixedSlot >= 0 ? fixedSlot : fd;
        task.rawFd = fd;
        task.len = 0;
        task.bufGroup = bgid;

        try {
            MemorySegment sqeRaw = ring.getSqe();
            if (sqeRaw.equals(MemorySegment.NULL)) {
                submitForSqeSpace();
                sqeRaw = ring.getSqe();
                if (sqeRaw.equals(MemorySegment.NULL)) {
                    task.completionHandler = null;
                    releaseTaskId(taskId);
                    return -1;
                }
            }

            MemorySegment sqe = sqeRaw;
            sqe.fill((byte) 0);
            sqe.set(ValueLayout.JAVA_BYTE, 0, Opcodes.IORING_OP_RECV);
            sqe.set(ValueLayout.JAVA_BYTE, 1, task.opFlags);
            sqe.set(ValueLayout.JAVA_SHORT, 2, Opcodes.IORING_RECV_MULTISHOT);
            sqe.set(ValueLayout.JAVA_INT, 4, task.fd);
            sqe.set(ValueLayout.JAVA_INT, 24, 0);
            sqe.set(ValueLayout.JAVA_INT, 28, 0);
            sqe.set(ValueLayout.JAVA_LONG, 32, task.userData);
            sqe.set(ValueLayout.JAVA_SHORT, 40, bgid);
            sqePending = true;
            return task.userData;
        } catch (Throwable t) {
            task.completionHandler = null;
            releaseTaskId(taskId);
            return -1;
        }
    }

    /** Starts a provided-buffer multishot RECVMSG for kTLS record metadata. */
    public long recvMsgMultishot(
            int fd, int fixedSlot, CompletionHandler completionHandler) {
        if (!ktlsReceiveBuffers) {
            return -1;
        }
        int taskId = acquireTaskId();
        if (taskId < 0) {
            return -1;
        }

        UringTask task = tasks[taskId];
        prepareTask(task);
        TASK_THREAD_HANDLE.setRelease(task, null);
        task.completionHandler = completionHandler;
        task.opcode = Opcodes.IORING_OP_RECVMSG;
        task.opFlags = fixedSlot >= 0
            ? (byte) (Opcodes.IOSQE_BUFFER_SELECT | Opcodes.IOSQE_FIXED_FILE)
            : Opcodes.IOSQE_BUFFER_SELECT;
        task.fd = fixedSlot >= 0 ? fixedSlot : fd;
        task.rawFd = fd;
        task.addr = ktlsRecvmsgHeader.address();
        task.len = 1;
        task.bufGroup = KTLS_BUF_GROUP;

        try {
            MemorySegment sqeRaw = ring.getSqe();
            if (sqeRaw.equals(MemorySegment.NULL)) {
                submitForSqeSpace();
                sqeRaw = ring.getSqe();
                if (sqeRaw.equals(MemorySegment.NULL)) {
                    task.completionHandler = null;
                    releaseTaskId(taskId);
                    return -1;
                }
            }

            MemorySegment sqe = sqeRaw;
            sqe.fill((byte) 0);
            sqe.set(ValueLayout.JAVA_BYTE, 0, Opcodes.IORING_OP_RECVMSG);
            sqe.set(ValueLayout.JAVA_BYTE, 1, task.opFlags);
            sqe.set(
                ValueLayout.JAVA_SHORT, 2, Opcodes.IORING_RECV_MULTISHOT);
            sqe.set(ValueLayout.JAVA_INT, 4, task.fd);
            sqe.set(ValueLayout.JAVA_LONG, 16, task.addr);
            sqe.set(ValueLayout.JAVA_INT, 24, 1);
            sqe.set(ValueLayout.JAVA_INT, 28, 0);
            sqe.set(ValueLayout.JAVA_LONG, 32, task.userData);
            sqe.set(ValueLayout.JAVA_SHORT, 40, KTLS_BUF_GROUP);
            sqePending = true;
            return task.userData;
        } catch (Throwable t) {
            task.completionHandler = null;
            releaseTaskId(taskId);
            return -1;
        }
    }

    public boolean cancelAsync(long targetUserData, CompletionHandler completionHandler) {
        return submitOpAsync(
            Opcodes.IORING_OP_ASYNC_CANCEL,
            (byte) 0,
            -1,
            targetUserData,
            0,
            0L,
            0,
            (short) 0,
            -1,
            -1,
            completionHandler
        );
    }

    public RecvResult recvSelectedBuffer(int fd, int len, short bgid) {
        return recvSelectedBuffer(fd, len, bgid, -1);
    }

    public int recvTargeted(int fd, MemorySegment buf, int len, int fixedSlot) {
        byte opFlags = (fixedSlot >= 0) ? Opcodes.IOSQE_FIXED_FILE : (byte) 0;
        int targetFd = (fixedSlot >= 0) ? fixedSlot : fd;
        return submitOp(Opcodes.IORING_OP_RECV, opFlags, targetFd, buf.address(), len, 0L, 0, (short) 0);
    }

    public int recvTargeted(int fd, MemorySegment buf, int len) {
        return recvTargeted(fd, buf, len, -1);
    }

    /**
     * Parks the current virtual thread until a socket becomes readable or
     * writable. This is primarily used to resume nonblocking native protocol
     * engines such as OpenSSL after WANT_READ/WANT_WRITE.
     */
    public int awaitSocketReady(int fd, int fixedSlot, int pollEvents) {
        byte opFlags = fixedSlot >= 0
            ? Opcodes.IOSQE_FIXED_FILE
            : (byte) 0;
        int targetFd = fixedSlot >= 0 ? fixedSlot : fd;
        return submitOp(
            Opcodes.IORING_OP_POLL_ADD,
            opFlags,
            targetFd,
            0L,
            0,
            0L,
            pollEvents,
            (short) 0
        );
    }

    public int provideBuffers(MemorySegment addr, int len, int nbufs, short bgid, int bid) {
        return submitOp(Opcodes.IORING_OP_PROVIDE_BUFFERS, (byte) 0, nbufs, addr.address(), len, (long) bid, 0, bgid);
    }

    public MemorySegment getBufferSegment(int bufferId) {
        if (bufferId < 0 || bufferId >= numBuffers) {
            throw new IllegalArgumentException("Invalid bufferId: " + bufferId);
        }
        return ringBufferSegments[bufferId];
    }

    InboundChunk leaseInboundChunk(int bufferId, int length) {
        if (bufferId < 0 || bufferId >= numBuffers) {
            throw new IllegalArgumentException("Invalid bufferId: " + bufferId);
        }
        return inboundChunks[bufferId].lease(length);
    }

    MemorySegment getKtlsBufferSegment(int bufferId) {
        if (!ktlsReceiveBuffers || bufferId < 0 || bufferId >= numBuffers) {
            throw new IllegalArgumentException(
                "Invalid kTLS bufferId: " + bufferId);
        }
        return ktlsRingBufferSegments[bufferId];
    }

    public int acquireEgressBuffer() {
        int bufferId = freeEgressIds.poll();
        if (Http2ResourceStats.ENABLED) {
            if (bufferId >= 0) {
                Http2ResourceStats.egressBufferAcquired();
            } else {
                Http2ResourceStats.egressBufferMissed();
            }
        }
        return bufferId;
    }

    public void releaseEgressBuffer(int bufferId) {
        if (bufferId >= 0) {
            freeEgressIds.offer(bufferId);
            if (Http2ResourceStats.ENABLED) {
                Http2ResourceStats.egressBufferReleased();
            }
        }
    }

    public MemorySegment getEgressBufferSegment(int bufferId) {
        if (bufferId < 0 || bufferId >= numEgressBuffers) {
            throw new IllegalArgumentException("Invalid egress bufferId: " + bufferId);
        }
        return egressBufferSegments[bufferId];
    }

    /**
     * Returns a buffer obtained through the primitive
     * {@link #recvSelectedBuffer(int, int, short)} API. Object leases obtained
     * by the HTTP receive path must return through {@link InboundChunk#close()}.
     */
    public void returnBuffer(int bufferId) {
        if (bufferId < 0 || bufferId >= numBuffers) {
            throw new IllegalArgumentException("Invalid bufferId: " + bufferId);
        }
        appendReturnedBuffer(bufferId);
        publishReturnedBuffer();
    }

    private void publishReturnedBuffer() {
        SHORT_HANDLE.setRelease(bufRingSegment, 14L, pbufTail);

        Runnable waiter = inboundBufferWaiters.pollFirst();
        if (waiter != null) {
            waiter.run();
        }
    }

    void returnInboundChunk(InboundChunk chunk) {
        int bufferId = validatePooledInboundChunk(chunk);
        appendReturnedBuffer(bufferId);
        chunk.markProvided();
        publishReturnedBuffer();
    }

    private void appendReturnedBuffer(int bufferId) {
        long bufAddr = ringBuffers.address() + (long) bufferId * BUFFER_SIZE;
        int tail = pbufTail & 0xFFFF;
        int index = tail & pbufMask;
        long offset = (long) index * 16;

        bufRingSegment.set(ValueLayout.JAVA_LONG, offset + 0, bufAddr);
        bufRingSegment.set(ValueLayout.JAVA_INT, offset + 8, BUFFER_SIZE);
        bufRingSegment.set(ValueLayout.JAVA_SHORT, offset + 12, (short) bufferId);
        bufRingSegment.set(ValueLayout.JAVA_SHORT, offset + 14, (short) 0);

        pbufTail = (short) (tail + 1);
    }

    void returnKtlsBuffer(int bufferId) {
        if (!ktlsReceiveBuffers || bufferId < 0 || bufferId >= numBuffers) {
            throw new IllegalArgumentException(
                "Invalid kTLS bufferId: " + bufferId);
        }
        appendReturnedKtlsBuffer(bufferId);
        SHORT_HANDLE.setRelease(
            ktlsBufRingSegment, 14L, ktlsPbufTail);

        Runnable waiter = ktlsBufferWaiters.pollFirst();
        if (waiter != null) {
            waiter.run();
        }
    }

    private void appendReturnedKtlsBuffer(int bufferId) {
        long bufferAddress = ktlsRingBuffers.address()
            + (long) bufferId * KTLS_BUFFER_SIZE;
        int tail = ktlsPbufTail & 0xffff;
        int index = tail & pbufMask;
        long offset = (long) index * 16;

        ktlsBufRingSegment.set(
            ValueLayout.JAVA_LONG, offset, bufferAddress);
        ktlsBufRingSegment.set(
            ValueLayout.JAVA_INT, offset + 8, KTLS_BUFFER_SIZE);
        ktlsBufRingSegment.set(
            ValueLayout.JAVA_SHORT, offset + 12, (short) bufferId);
        ktlsBufRingSegment.set(
            ValueLayout.JAVA_SHORT, offset + 14, (short) 0);

        ktlsPbufTail = (short) (tail + 1);
    }

    void releaseInboundChunk(InboundChunk chunk) {
        boolean ownerLocal = inCarrierDomain();
        short bufferGroup = chunk.bufferGroup();
        int bufferId = chunk.bufferId();
        if (bufferGroup == BUF_GROUP) {
            validatePooledInboundChunk(chunk);
        } else if (bufferGroup != KTLS_BUF_GROUP) {
            throw new IllegalArgumentException(
                "Unknown inbound buffer group: " + bufferGroup);
        }
        if (ownerLocal) {
            chunk.beginOwnerReturn();
            if (bufferGroup == KTLS_BUF_GROUP) {
                if (returnedKtlsBufferCount == returnedKtlsBufferIds.length) {
                    flushReturnedBuffers();
                }
                returnedKtlsBufferIds[returnedKtlsBufferCount++] = bufferId;
            } else {
                if (returnedInboundBufferCount
                        == returnedInboundBufferIds.length) {
                    flushReturnedBuffers();
                }
                returnedInboundBufferIds[returnedInboundBufferCount++] =
                    bufferId;
            }
            return;
        }
        chunk.beginExternalReturn();
        execute(chunk);
    }

    private int validatePooledInboundChunk(InboundChunk chunk) {
        int bufferId = chunk.bufferId();
        if (bufferId < 0 || bufferId >= inboundChunks.length
                || inboundChunks[bufferId] != chunk) {
            throw new IllegalArgumentException(
                "Inbound chunk is not leased from this event loop");
        }
        return bufferId;
    }

    private void flushReturnedBuffers() {
        int inboundCount = returnedInboundBufferCount;
        if (inboundCount != 0) {
            returnedInboundBufferCount = 0;
            for (int i = 0; i < inboundCount; i++) {
                appendReturnedBuffer(returnedInboundBufferIds[i]);
            }
            for (int i = 0; i < inboundCount; i++) {
                inboundChunks[returnedInboundBufferIds[i]].markProvided();
            }
            SHORT_HANDLE.setRelease(bufRingSegment, 14L, pbufTail);
            for (int i = 0; i < inboundCount; i++) {
                Runnable waiter = inboundBufferWaiters.pollFirst();
                if (waiter == null) {
                    break;
                }
                waiter.run();
            }
        }

        int ktlsCount = returnedKtlsBufferCount;
        if (ktlsCount != 0) {
            returnedKtlsBufferCount = 0;
            for (int i = 0; i < ktlsCount; i++) {
                appendReturnedKtlsBuffer(returnedKtlsBufferIds[i]);
            }
            SHORT_HANDLE.setRelease(
                ktlsBufRingSegment, 14L, ktlsPbufTail);
            for (int i = 0; i < ktlsCount; i++) {
                Runnable waiter = ktlsBufferWaiters.pollFirst();
                if (waiter == null) {
                    break;
                }
                waiter.run();
            }
        }
    }

    int inboundChunkPoolSize() {
        return inboundChunks.length;
    }

    int outstandingInboundChunkCount() {
        int outstanding = 0;
        for (InboundChunk chunk : inboundChunks) {
            if (!chunk.isProvided()) {
                outstanding++;
            }
        }
        return outstanding;
    }

    void whenInboundBufferAvailable(Runnable waiter) {
        inboundBufferWaiters.addLast(waiter);
    }

    void whenKtlsBufferAvailable(Runnable waiter) {
        ktlsBufferWaiters.addLast(waiter);
    }

    public int accept(int serverFd, MemorySegment clientAddr, MemorySegment clientAddrLen) {
        return submitOp(Opcodes.IORING_OP_ACCEPT, (byte) 0, serverFd, clientAddr.address(), 0, clientAddrLen.address(), 0, (short) 0);
    }

    public long acceptMultishot(int serverFd, CompletionHandler completionHandler) {
        return acceptMultishot(serverFd, false, completionHandler);
    }

    public long acceptMultishotDirect(
            int serverFd, CompletionHandler completionHandler) {
        return acceptMultishot(serverFd, true, completionHandler);
    }

    private long acceptMultishot(
            int serverFd, boolean direct,
            CompletionHandler completionHandler) {
        int taskId = acquireTaskId();
        if (taskId < 0) {
            return -1;
        }

        UringTask task = tasks[taskId];
        prepareTask(task);
        TASK_THREAD_HANDLE.setRelease(task, null);
        task.completionHandler = completionHandler;
        task.opcode = Opcodes.IORING_OP_ACCEPT;
        task.fd = serverFd;

        try {
            MemorySegment sqe = reserveSqe();
            if (direct) {
                prepareDirectAcceptSqe(sqe, serverFd, task.userData);
            } else {
                sqe.fill((byte) 0);
                sqe.set(ValueLayout.JAVA_BYTE, 0, Opcodes.IORING_OP_ACCEPT);
                sqe.set(
                    ValueLayout.JAVA_SHORT, 2,
                    Opcodes.IORING_ACCEPT_MULTISHOT);
                sqe.set(ValueLayout.JAVA_INT, 4, serverFd);
                sqe.set(ValueLayout.JAVA_LONG, 32, task.userData);
            }
            sqePending = true;
            return task.userData;
        } catch (Throwable t) {
            task.completionHandler = null;
            releaseTaskId(taskId);
            return -1;
        }
    }

    public boolean cancelFdAsync(
        int fd,
        CompletionHandler completionHandler
    ) {
        return submitOpAsync(
            Opcodes.IORING_OP_ASYNC_CANCEL,
            (byte) 0,
            fd,
            0L,
            0,
            0L,
            Opcodes.IORING_ASYNC_CANCEL_ALL
                | Opcodes.IORING_ASYNC_CANCEL_FD,
            (short) 0,
            -1,
            -1,
            completionHandler
        );
    }

    public int read(int fd, MemorySegment buf, int len) {
        return submitOp(Opcodes.IORING_OP_READ, (byte) 0, fd, buf.address(), len, 0L, 0, (short) 0);
    }

    public int write(int fd, MemorySegment buf, int len) {
        return submitOp(Opcodes.IORING_OP_WRITE, (byte) 0, fd, buf.address(), len, 0L, 0, (short) 0);
    }

    public int writeFully(int fd, MemorySegment buf, int len, int fixedSlot) {
        byte opFlags = (fixedSlot >= 0) ? Opcodes.IOSQE_FIXED_FILE : (byte) 0;
        int targetFd = (fixedSlot >= 0) ? fixedSlot : fd;
        int total = 0;
        while (total < len) {
            int written = submitOp(Opcodes.IORING_OP_SEND, opFlags, targetFd, buf.address() + total, len - total, 0L, 0x4000, (short) 0);
            if (written == -11) {
                continue;
            }
            if (written <= 0) {
                return written;
            }
            total += written;
        }
        return total;
    }

    public int writeFully(int fd, MemorySegment buf, int len) {
        return writeFully(fd, buf, len, -1);
    }

    public int closeFd(int fd) {
        return submitOp(Opcodes.IORING_OP_CLOSE, (byte) 0, fd, 0L, 0, 0L, 0, (short) 0);
    }

    public int nop() {
        return submitOp(Opcodes.IORING_OP_NOP, (byte) 0, -1, 0L, 0, 0L, 0, (short) 0);
    }

    public Thread startVirtualThread(Runnable task) {
        Thread vt = virtualThreadFactory.newThread(task);
        vt.start();
        return vt;
    }

    Thread newVirtualThread(java.util.concurrent.Executor scheduler, Runnable task, String name) {
        try {
            Class<?> builderClass = Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder");
            java.lang.reflect.Constructor<?> ctor =
                builderClass.getDeclaredConstructor(java.util.concurrent.Executor.class);
            ctor.setAccessible(true);
            Thread.Builder.OfVirtual builder = (Thread.Builder.OfVirtual) ctor.newInstance(scheduler);
            return builder.name(name).unstarted(task);
        } catch (Throwable t) {
            throw new RuntimeException(
                "Missing JVM argument: --add-opens java.base/java.lang=ALL-UNNAMED",
                t
            );
        }
    }

    public java.util.concurrent.ThreadFactory getVirtualThreadFactory() {
        return virtualThreadFactory;
    }

    boolean inCarrierDomain() {
        return carrierDomain.containsCurrentThread();
    }

    long schedulerEpoch() {
        return schedulerEpoch;
    }

    ExchangeExecutor exchangeExecutor() {
        return exchangeExecutor;
    }

    int exchangeWorkerCount() {
        return exchangeExecutor.workerCount();
    }

    static int configuredMaxHttp2ParkedSenders() {
        return Math.max(
            1,
            Integer.getInteger(
                "cardigan.http2.max.parked.senders.per.loop",
                1_024
            )
        );
    }

    static int configuredTaskCapacity(int entries) {
        // One long-lived receive and one possible send per fixed-file slot,
        // plus a complete SQ's worth of cancellation and control operations.
        int requested = Integer.getInteger(
            "cardigan.max.tasks",
            2 * MAX_FIXED_FILES + entries
        );
        if (requested <= 0) {
            throw new IllegalArgumentException(
                "cardigan.max.tasks must be positive");
        }
        return requested;
    }

    static void validateSchedulerConfiguration() {
        for (String property : REMOVED_SCHEDULER_PROPERTIES) {
            if (System.getProperty(property) != null) {
                throw new IllegalArgumentException(
                    property + " was removed; Cardigan's topological "
                        + "scheduler is always enabled");
            }
        }
    }

    record TaskPoolStats(
        int capacity,
        int active,
        int peak,
        long exhaustions
    ) {}

    record SchedulerStats(
        long epochs,
        long cqes,
        long completionTasks,
        long protocolTasks,
        long handlerContinuations,
        long handlerRanges,
        long handlerRangeBoundaries,
        long egressTasks,
        long externalTasks,
        long taskWorkEnters,
        long submits,
        long waits,
        long cqOverflows
    ) {}

    record FixedFileStats(
        int capacity,
        int active,
        int peak,
        long capacityMisses,
        int admissionWaiters
    ) {}

    TaskPoolStats taskPoolStats() {
        return new TaskPoolStats(
            tasks.length,
            activeTasks,
            peakActiveTasks,
            taskPoolExhaustions
        );
    }

    SchedulerStats schedulerStats() {
        return new SchedulerStats(
            schedulerEpochs,
            schedulerCqes,
            schedulerCompletionTasks,
            schedulerProtocolTasks,
            schedulerHandlerTasks,
            schedulerHandlerRanges,
            schedulerHandlerRangeBoundaries,
            schedulerEgressTasks,
            schedulerExternalTasks,
            schedulerTaskWorkEnters,
            schedulerSubmits,
            schedulerWaits,
            ring == null ? 0 : ring.cqOverflowCount()
        );
    }

    FixedFileStats fixedFileStats() {
        return new FixedFileStats(
            MAX_FIXED_FILES,
            activeFixedFiles,
            peakActiveFixedFiles,
            fixedFileCapacityMisses,
            fixedFileWaiters.size()
        );
    }

    boolean tryAcquireHttp2ParkedSender() {
        int current = http2ParkedSenders.get();
        while (current < maxHttp2ParkedSenders) {
            if (http2ParkedSenders.compareAndSet(current, current + 1)) {
                return true;
            }
            current = http2ParkedSenders.get();
        }
        return false;
    }

    void releaseHttp2ParkedSender() {
        http2ParkedSenders.decrementAndGet();
    }

    int http2ParkedSenderCount() {
        return http2ParkedSenders.get();
    }

    private static void awaitCompletion(UringTask task) {
        boolean interrupted = false;
        while (TASK_THREAD_HANDLE.getAcquire(task) != null) {
            LockSupport.park(task);
            if (TASK_THREAD_HANDLE.getAcquire(task) != null && Thread.interrupted()) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void triggerWakeup() {
        while (!writeWakeupEvent()) {
            int failures = wakeupWriteFailures.incrementAndGet();
            // Do not leave the coalescing latch asserted unless an event was
            // actually delivered. Pair the clear with a fresh readiness
            // check so the task whose wakeup failed cannot be abandoned.
            wakeupPending.set(0);
            VarHandle.fullFence();

            if (!isSleeping || readyTasks.isEmpty()) {
                return;
            }
            if (!wakeupPending.compareAndSet(0, 1)) {
                // Another producer owns the retry.
                return;
            }
            if (failures >= 2) {
                wakeupPending.set(0);
                throw failWakeupChannel();
            }
        }
        wakeupWriteFailures.set(0);
    }

    private RejectedExecutionException failWakeupChannel() {
        IllegalStateException failure = new IllegalStateException(
            "eventfd wakeup channel failed for CPU " + cpuId);
        wakeupFailure = failure;
        System.err.println(failure.getMessage());
        // A permanent eventfd failure leaves no normal way to interrupt the
        // blocking enter. Mark the loop failed first, then make a best-effort
        // attempt to break the carrier out of the syscall.
        loopThread.interrupt();
        return new RejectedExecutionException(
            "Event loop wakeup channel failed", failure);
    }

    private boolean writeWakeupEvent() {
        while (true) {
            try {
                long written = (long) Libc.write.invokeExact(
                    evfd, wakeupBuf, 8L);
                if (written == 8L) {
                    return true;
                }
                if (written < 0 && Libc.errno() == EINTR) {
                    continue;
                }
                return false;
            } catch (Throwable failure) {
                return false;
            }
        }
    }

    @Override
    public void execute(Runnable command) {
        if (inCarrierDomain()) {
            if (command instanceof HandlerContinuation) {
                handlerReadyTasks.addLast(command);
            } else if (command instanceof EgressTask) {
                egressReadyTasks.addLast(command);
            } else if (dispatchingCompletions) {
                completionReadyTasks.addLast(command);
            } else {
                protocolReadyTasks.addLast(command);
            }
            return;
        }

        enqueueExternal(command);
    }

    void executeHandler(HandlerContinuation command) {
        if (inCarrierDomain()) {
            handlerReadyTasks.addLast(command);
            return;
        }
        enqueueExternal(command);
    }

    void executeEgress(EgressTask command) {
        if (inCarrierDomain()) {
            egressReadyTasks.addLast(command);
            return;
        }
        enqueueExternal(command);
    }

    private void enqueueExternal(Runnable command) {
        Throwable failure = wakeupFailure;
        if (closed || failure != null) {
            throw new RejectedExecutionException(
                "Event loop is not accepting work", failure);
        }
        if (!readyTasks.offer(command)) {
            throw new RejectedExecutionException("Event loop ready queue full");
        }

        VarHandle.fullFence();

        if (isSleeping && !inCarrierDomain()
            && wakeupPending.compareAndSet(0, 1)) {
            triggerWakeup();
        }
    }

    @Override
    public synchronized void close() {
        if (resourcesClosed) return;
        if (!closed) {
            exchangeExecutor.close();
            boolean workersStopped =
                exchangeExecutor.awaitTermination(2_000);
            if (!workersStopped) {
                throw new IllegalStateException(
                    "Exchange workers for CPU " + cpuId
                        + " did not terminate; retaining their live carrier, "
                        + "io_uring, and native memory");
            }
            closed = true;
            writeWakeupEvent();
        }

        try {
            loopThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (loopThread.isAlive()) {
            StackTraceElement[] carrierStack = loopThread.getStackTrace();
            String carrierLocation = carrierStack.length == 0
                ? "unknown"
                : carrierStack[0].toString();
            throw new IllegalStateException(
                "Event-loop carrier for CPU " + cpuId
                    + " did not terminate; retaining its live io_uring "
                    + "and native memory (state=" + loopThread.getState()
                    + ", at=" + carrierLocation + ")");
        }

        if (TASK_POOL_STATS_ENABLED) {
            TaskPoolStats stats = taskPoolStats();
            System.out.println(
                "Uring task pool CPU " + cpuId
                    + ": capacity=" + stats.capacity()
                    + ", peak=" + stats.peak()
                    + ", active-at-close=" + stats.active()
                    + ", exhaustions=" + stats.exhaustions());
        }
        if (SCHEDULER_STATS_ENABLED) {
            SchedulerStats stats = schedulerStats();
            System.out.println(
                "Uring scheduler CPU " + cpuId
                    + ": epochs=" + stats.epochs()
                    + ", cqes=" + stats.cqes()
                    + ", completion-tasks=" + stats.completionTasks()
                    + ", protocol-tasks=" + stats.protocolTasks()
                    + ", handler-continuations="
                    + stats.handlerContinuations()
                    + ", handler-ranges=" + stats.handlerRanges()
                    + ", handler-range-boundaries="
                    + stats.handlerRangeBoundaries()
                    + ", egress-tasks=" + stats.egressTasks()
                    + ", external-tasks=" + stats.externalTasks()
                    + ", task-work-enters=" + stats.taskWorkEnters()
                    + ", submits=" + stats.submits()
                    + ", waits=" + stats.waits()
                    + ", cq-overflows=" + stats.cqOverflows());
        }
        if (FIXED_FILE_STATS_ENABLED) {
            FixedFileStats stats = fixedFileStats();
            System.out.println(
                "Fixed-file table CPU " + cpuId
                    + ": capacity=" + stats.capacity()
                    + ", peak=" + stats.peak()
                    + ", active-at-close=" + stats.active()
                    + ", capacity-misses=" + stats.capacityMisses()
                    + ", admission-waiters=" + stats.admissionWaiters());
        }

        try {
            int unused = (int) Libc.close.invokeExact(evfd);
        } catch (Throwable t) {
            // Continue releasing ring resources after a descriptor-close failure.
        }

        try {
            try (Arena localArena = Arena.ofConfined()) {
                MemorySegment reg = localArena.allocate(48);
                reg.fill((byte) 0);
                reg.set(ValueLayout.JAVA_SHORT, 12, BUF_GROUP);

                int unused = ring.register(
                    IORING_UNREGISTER_PBUF_RING, reg, 1);
                if (ktlsReceiveBuffers) {
                    reg.fill((byte) 0);
                    reg.set(ValueLayout.JAVA_SHORT, 12, KTLS_BUF_GROUP);
                    int ignored = ring.register(
                        IORING_UNREGISTER_PBUF_RING, reg, 1);
                }
            }
        } catch (Throwable t) {
            // Closing the ring releases any registrations left behind.
        }

        try {
            ring.close();
        } catch (Throwable t) {
            System.err.println("Error during io_uring_queue_exit: " + t.getMessage());
        }

        arena.close();
        resourcesClosed = true;
        System.out.println("Closed UringEventLoop for CPU " + cpuId);
    }

    public int getCpuId() {
        return cpuId;
    }

}
