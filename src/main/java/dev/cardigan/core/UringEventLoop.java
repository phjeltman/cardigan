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
    private static final boolean TASK_POOL_STATS_ENABLED =
        Boolean.getBoolean("cardigan.uring.task.stats");
    private static final boolean LOCAL_READY_QUEUE_ENABLED =
        Boolean.parseBoolean(System.getProperty(
            "cardigan.scheduler.localReady", "true"));
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

    /**
     * Scheduler submissions made while the owning platform thread dispatches
     * CQEs do not need the CAS, fence, or eventfd protocol used by external
     * producers. Other loop-thread submissions are batched through readyTasks.
     */
    private final ArrayDeque<Runnable> localReadyTasks =
        new ArrayDeque<>(1024);
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
        private final Object[] buffer;
        private final int mask;
        private final java.util.concurrent.atomic.AtomicLong head = new java.util.concurrent.atomic.AtomicLong(0);
        private final java.util.concurrent.atomic.AtomicLong tail = new java.util.concurrent.atomic.AtomicLong(0);

        @SuppressWarnings("unchecked")
        public MpscArrayQueue(int capacity) {
            int cap = 1;
            while (cap < capacity) {
                cap <<= 1;
            }
            this.buffer = new Object[cap];
            this.mask = cap - 1;
        }

        public boolean offer(T value) {
            if (value == null) throw new NullPointerException();
            while (true) {
                long t = tail.get();
                long h = head.get();
                if (t - h >= buffer.length) {
                    return false;
                }
                if (tail.compareAndSet(t, t + 1)) {
                    int index = (int) (t & mask);
                    ARRAY_HANDLE.setRelease(buffer, index, value);
                    return true;
                }
            }
        }

        @SuppressWarnings("unchecked")
        public T poll() {
            long h = head.get();
            if (h == tail.get()) {
                return null;
            }
            int index = (int) (h & mask);
            Object value;
            // A producer reserves its tail position before publishing the slot.
            while ((value = ARRAY_HANDLE.getAcquire(buffer, index)) == null) {
                Thread.onSpinWait();
            }
            ARRAY_HANDLE.setRelease(buffer, index, null);
            head.lazySet(h + 1);
            return (T) value;
        }

        public boolean isEmpty() {
            return head.get() == tail.get();
        }
    }
    
    private final int numBuffers;
    private final int pbufMask;
    private final MemorySegment bufRingSegment;
    private final MemorySegment ringBuffers;
    private final MemorySegment[] ringBufferSegments;
    private final ArrayDeque<Runnable> inboundBufferWaiters = new ArrayDeque<>();
    private short pbufTail = 0;
    private final boolean ktlsReceiveBuffers;
    private final MemorySegment ktlsBufRingSegment;
    private final MemorySegment ktlsRingBuffers;
    private final MemorySegment[] ktlsRingBufferSegments;
    private final MemorySegment ktlsRecvmsgHeader;
    private final ArrayDeque<Runnable> ktlsBufferWaiters = new ArrayDeque<>();
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
    
    public static final int MAX_FIXED_FILES = 8192;
    private final MemorySegment registeredFds;
    private final MemorySegment emptyFdSegment;
    private final IntIdPool freeFixedSlots;
    private boolean useFixedFiles;

    private volatile boolean closed = false;
    private volatile boolean isSleeping = false;

    public UringEventLoop(int cpuId, int entries) {
        this(cpuId, entries, Math.max(entries, 512), false);
    }

    public UringEventLoop(int cpuId, int entries, int numBuffers) {
        this(cpuId, entries, numBuffers, false);
    }

    public UringEventLoop(
            int cpuId, int entries, int numBuffers,
            boolean ktlsReceiveBuffers) {
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
        this.ringBuffers.fill((byte) 0);
        this.ringBufferSegments = new MemorySegment[this.numBuffers];
        for (int i = 0; i < this.numBuffers; i++) {
            ringBufferSegments[i] = ringBuffers.asSlice(
                (long) i * BUFFER_SIZE, BUFFER_SIZE);
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
            this.ktlsRecvmsgHeader.fill((byte) 0);
            this.ktlsRecvmsgHeader.set(
                ValueLayout.JAVA_LONG, MSGHDR_CONTROL_LENGTH_OFFSET,
                (long) KtlsRecordParser.CONTROL_RESERVE);
        } else {
            this.ktlsRingBuffers = MemorySegment.NULL;
            this.ktlsRingBufferSegments = null;
            this.ktlsBufRingSegment = MemorySegment.NULL;
            this.ktlsRecvmsgHeader = MemorySegment.NULL;
        }

        this.numEgressBuffers = Math.max(this.numBuffers, 4096);
        this.egressBufferRing = arena.allocate((long) this.numEgressBuffers * EGRESS_FRAME_SIZE);
        this.egressBufferRing.fill((byte) 0);
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
            .start(() -> runLoop(entries, initLatch, initError));

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

            int flags = Opcodes.IORING_SETUP_SINGLE_ISSUER | Opcodes.IORING_SETUP_DEFER_TASKRUN;
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

        while (!closed) {
            try {
                if (LOCAL_READY_QUEUE_ENABLED) {
                    Runnable localTask;
                    while ((localTask = localReadyTasks.pollFirst()) != null) {
                        try {
                            localTask.run();
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    }
                }

                Runnable vtTask;
                while ((vtTask = readyTasks.poll()) != null) {
                    try {
                        vtTask.run();
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }

                if (sqePending) {
                    submitPendingOperations();
                }

                if (hasCompletions()) {
                    reapCompletions();
                }

                // Pair sleeping publication with execute()'s fence before
                // rechecking all work queues and entering the kernel wait.
                isSleeping = true;
                VarHandle.fullFence();

                if ((LOCAL_READY_QUEUE_ENABLED && !localReadyTasks.isEmpty())
                    || !readyTasks.isEmpty() || hasCompletions()) {
                    isSleeping = false;
                    continue;
                }

                try {
                    int ret = ring.submitAndWait(1);
                    if (ret >= 0) {
                        reapCompletions();
                    }
                } finally {
                    isSleeping = false;
                }
            } catch (Throwable t) {
                if (closed) break;
                System.err.println("Error in event loop for CPU " + cpuId + ": " + t.getMessage());
            }
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
            int unusedSubmit = ring.submit();
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

        int submitResult = ring.submit();
        if (submitResult < 0) {
            throw new IllegalStateException(
                "Submitting the eventfd wakeup read failed: "
                    + submitResult);
        }
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

    private void reapCompletions() {
        if (!LOCAL_READY_QUEUE_ENABLED) {
            reapCompletionBatch();
            return;
        }
        dispatchingCompletions = true;
        try {
            reapCompletionBatch();
        } finally {
            dispatchingCompletions = false;
        }
    }

    private void reapCompletionBatch() {
        int head = (int) INT_HANDLE.getAcquire(kheadSegment, 0L);
        int tail = (int) INT_HANDLE.getAcquire(ktailSegment, 0L);
        int mask = kmask;

        int count = 0;
        while (head != tail) {
            int index = head & mask;
            long cqeOffset = index * 16L;

            long userData = cqesSegment.get(ValueLayout.JAVA_LONG, cqeOffset + 0);
            int res = cqesSegment.get(ValueLayout.JAVA_INT, cqeOffset + 8);
            int flags = cqesSegment.get(ValueLayout.JAVA_INT, cqeOffset + 12);

            if (userData == -99L) {
                submitEvfdRead();
            } else if (userData != -88L) {
                int taskId = (int) userData;
                if (taskId >= 0 && taskId < tasks.length) {
                    UringTask task = tasks[taskId];
                    if (task.userData == userData) {
                        task.result = res;
                        task.flags = flags;
                        CompletionHandler completionHandler = task.completionHandler;
                        Thread vt = (Thread) TASK_THREAD_HANDLE.getAcquire(task);

                        if (completionHandler != null && task.vectorSlot >= 0) {
                            handleAsyncVectorSendCompletion(task, res, flags, completionHandler);
                        } else if (completionHandler != null && task.egressId >= 0) {
                            handleAsyncSendCompletion(task, res, flags, completionHandler);
                        } else if (completionHandler != null) {
                            boolean terminal = (flags & Opcodes.IORING_CQE_F_MORE) == 0;
                            if (terminal) {
                                task.completionHandler = null;
                                releaseTaskId(taskId);
                            }
                            completionHandler.onCompletion(res, flags, terminal);
                        } else if (vt != null && (flags & Opcodes.IORING_CQE_F_MORE) == 0) {
                            TASK_THREAD_HANDLE.setRelease(task, null);
                            LockSupport.unpark(vt);
                        } else if (vt == null && (flags & Opcodes.IORING_CQE_F_MORE) == 0) {
                            releaseTaskId(taskId);
                        }
                    }
                }
            }

            head++;
            count++;
        }

        if (count > 0) {
            INT_HANDLE.setRelease(kheadSegment, 0L, head);
        }
    }

    private boolean sqePending = false;

    void submitPendingOperations() {
        if (!sqePending) {
            return;
        }
        int result = ring.submit();
        sqePending = false;
        if (result < 0) {
            throw new IllegalStateException(
                "Submitting pending io_uring operations failed: " + result);
        }
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
                int unused = ring.submit();
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
                int unused = ring.submit();
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
                int unused = ring.submit();
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

    public int registerFixedFd(int clientFd) {
        if (!useFixedFiles) {
            throw new IllegalStateException(
                "Fixed-file registration was not initialized");
        }
        int slot = freeFixedSlots.poll();
        if (slot < 0) return -1;
        registeredFds.set(ValueLayout.JAVA_INT, (long) slot * 4, clientFd);
        MemorySegment slice = registeredFds.asSlice((long) slot * 4, 4);
        int result;
        try {
            result = ring.updateFiles(slot, slice, 1);
        } catch (Throwable t) {
            throw new IllegalStateException(
                "Updating fixed-file slot " + slot + " failed", t);
        }
        if (result < 0) {
            registeredFds.set(
                ValueLayout.JAVA_INT, (long) slot * 4, -1);
            freeFixedSlots.offer(slot);
            throw new IllegalStateException(
                "IORING_REGISTER_FILES_UPDATE failed for slot " + slot
                    + " with error " + result);
        }
        return slot;
    }

    public void unregisterFixedFd(int slot, int clientFd) {
        if (slot >= 0 && useFixedFiles) {
            try {
                registeredFds.set(ValueLayout.JAVA_INT, (long) slot * 4, -1);
                int ret = ring.updateFiles(slot, emptyFdSegment, 1);
            } catch (Throwable t) {
            } finally {
                freeFixedSlots.offer(slot);
            }
        }
        closeFd(clientFd);
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
                int unused = ring.submit();
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
                int unused = ring.submit();
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
                int unused = ring.submit();
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

    public void returnBuffer(int bufferId) {
        if (bufferId < 0 || bufferId >= numBuffers) {
            throw new IllegalArgumentException("Invalid bufferId: " + bufferId);
        }
        long bufAddr = ringBuffers.address() + (long) bufferId * BUFFER_SIZE;
        int tail = pbufTail & 0xFFFF;
        int index = tail & pbufMask;
        long offset = (long) index * 16;

        bufRingSegment.set(ValueLayout.JAVA_LONG, offset + 0, bufAddr);
        bufRingSegment.set(ValueLayout.JAVA_INT, offset + 8, BUFFER_SIZE);
        bufRingSegment.set(ValueLayout.JAVA_SHORT, offset + 12, (short) bufferId);
        bufRingSegment.set(ValueLayout.JAVA_SHORT, offset + 14, (short) 0);

        pbufTail = (short) (tail + 1);
        SHORT_HANDLE.setRelease(bufRingSegment, 14L, pbufTail);

        Runnable waiter = inboundBufferWaiters.pollFirst();
        if (waiter != null) {
            waiter.run();
        }
    }

    void returnKtlsBuffer(int bufferId) {
        if (!ktlsReceiveBuffers || bufferId < 0 || bufferId >= numBuffers) {
            throw new IllegalArgumentException(
                "Invalid kTLS bufferId: " + bufferId);
        }
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
        SHORT_HANDLE.setRelease(
            ktlsBufRingSegment, 14L, ktlsPbufTail);

        Runnable waiter = ktlsBufferWaiters.pollFirst();
        if (waiter != null) {
            waiter.run();
        }
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
            MemorySegment sqeRaw = ring.getSqe();
            if (sqeRaw.equals(MemorySegment.NULL)) {
                int unused = ring.submit();
                sqeRaw = ring.getSqe();
                if (sqeRaw.equals(MemorySegment.NULL)) {
                    task.completionHandler = null;
                    releaseTaskId(taskId);
                    return -1;
                }
            }

            MemorySegment sqe = sqeRaw;
            sqe.fill((byte) 0);
            sqe.set(ValueLayout.JAVA_BYTE, 0, Opcodes.IORING_OP_ACCEPT);
            sqe.set(ValueLayout.JAVA_SHORT, 2, Opcodes.IORING_ACCEPT_MULTISHOT);
            sqe.set(ValueLayout.JAVA_INT, 4, serverFd);
            sqe.set(ValueLayout.JAVA_LONG, 32, task.userData);
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

    record TaskPoolStats(
        int capacity,
        int active,
        int peak,
        long exhaustions
    ) {}

    TaskPoolStats taskPoolStats() {
        return new TaskPoolStats(
            tasks.length,
            activeTasks,
            peakActiveTasks,
            taskPoolExhaustions
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
        try {
            long unused = (long) Libc.write.invokeExact(evfd, wakeupBuf, 8L);
        } catch (Throwable t) {
            // The submitted task remains queued if the best-effort wakeup fails.
        }
    }

    @Override
    public void execute(Runnable command) {
        if (LOCAL_READY_QUEUE_ENABLED && Thread.currentThread() == loopThread
            && dispatchingCompletions) {
            localReadyTasks.addLast(command);
            return;
        }

        if (!readyTasks.offer(command)) {
            throw new RejectedExecutionException("Event loop ready queue full");
        }

        VarHandle.fullFence();

        if (isSleeping && Thread.currentThread() != loopThread) {
            triggerWakeup();
        }
    }

    @Override
    public void close() {
        if (closed) return;
        exchangeExecutor.close();
        closed = true;

        try {
            long unused = (long) Libc.write.invokeExact(evfd, wakeupBuf, 8L);
        } catch (Throwable t) {
            // Continue teardown if the wakeup descriptor is unavailable.
        }

        try {
            loopThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        System.out.println("Closed UringEventLoop for CPU " + cpuId);
    }

    public int getCpuId() {
        return cpuId;
    }

}
