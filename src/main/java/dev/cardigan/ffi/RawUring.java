// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.ffi;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/**
 * Minimal io_uring userspace implementation over the stable Linux UAPI.
 *
 * <p>The kernel returns every mmap offset used below in {@code io_uring_params}.
 * Cardigan therefore has no dependency on the private layout of liburing's
 * {@code struct io_uring}, nor on generated C layout probes.</p>
 */
public final class RawUring implements AutoCloseable {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();
    private static final VarHandle INT_HANDLE = ValueLayout.JAVA_INT.varHandle();

    // Linux assigned these numbers in the architecture-independent range.
    private static final long SYS_IO_URING_SETUP = 425;
    private static final long SYS_IO_URING_ENTER = 426;
    private static final long SYS_IO_URING_REGISTER = 427;

    private static final long IORING_OFF_SQ_RING = 0;
    private static final long IORING_OFF_CQ_RING = 0x0800_0000L;
    private static final long IORING_OFF_SQES = 0x1000_0000L;

    private static final int IORING_ENTER_GETEVENTS = 1;
    private static final int IORING_FEAT_SINGLE_MMAP = 1;
    private static final int IORING_SQ_CQ_OVERFLOW = 1 << 1;
    private static final int IORING_SQ_TASKRUN = 1 << 2;
    private static final int IORING_SETUP_SUBMIT_ALL = 1 << 7;
    private static final int IORING_SETUP_TASKRUN_FLAG = 1 << 9;
    private static final int IORING_SETUP_SINGLE_ISSUER = 1 << 12;
    private static final int IORING_SETUP_DEFER_TASKRUN = 1 << 13;
    private static final int IORING_REGISTER_FILES = 2;
    private static final int IORING_REGISTER_FILES_UPDATE = 6;

    private static final int PROT_READ = 1;
    private static final int PROT_WRITE = 2;
    private static final int MAP_SHARED = 1;
    private static final int MAP_POPULATE = 0x8000;
    private static final long MAP_FAILED = -1L;

    private static final long PARAMS_SIZE = 120;
    private static final long SQ_OFFSETS = 40;
    private static final long CQ_OFFSETS = 80;
    private static final int SQE_SIZE = 64;
    private static final int CQE_SIZE = 16;

    private static final MethodHandle SYSCALL_SETUP;
    private static final MethodHandle SYSCALL_ENTER;
    private static final MethodHandle SYSCALL_REGISTER;
    private static final MethodHandle MMAP;
    private static final MethodHandle MUNMAP;
    private static final MethodHandle CLOSE;
    private static final MethodHandle ERRNO_LOCATION;

    static {
        try {
            MemorySegment syscall = symbol("syscall");
            SYSCALL_SETUP = LINKER.downcallHandle(
                syscall,
                FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS),
                Linker.Option.firstVariadicArg(1));
            SYSCALL_ENTER = LINKER.downcallHandle(
                syscall,
                FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG),
                Linker.Option.firstVariadicArg(1));
            SYSCALL_REGISTER = LINKER.downcallHandle(
                syscall,
                FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT),
                Linker.Option.firstVariadicArg(1));
            MMAP = LINKER.downcallHandle(
                symbol("mmap"),
                FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG));
            MUNMAP = LINKER.downcallHandle(
                symbol("munmap"),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG));
            CLOSE = LINKER.downcallHandle(
                symbol("close"),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            ERRNO_LOCATION = LINKER.downcallHandle(
                symbol("__errno_location"),
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        } catch (RuntimeException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final int fd;
    private final boolean deferTaskRun;
    private final MemorySegment sqRing;
    private final long sqRingSize;
    private final MemorySegment cqRing;
    private final long cqRingSize;
    private final boolean singleMmap;
    private final MemorySegment sqes;
    private final MemorySegment[] sqeSegments;
    private final long sqesSize;

    private final MemorySegment sqHead;
    private final MemorySegment sqTail;
    private final MemorySegment sqFlags;
    private final MemorySegment sqArray;
    private final int sqMask;
    private final int sqEntries;
    private final MemorySegment cqHead;
    private final MemorySegment cqTail;
    private final MemorySegment cqOverflow;
    private final MemorySegment cqes;
    private final int cqMask;
    private final MemorySegment fileUpdate;

    private int sqeHead;
    private int sqeTail;
    private boolean closed;

    public RawUring(Arena arena, int requestedEntries, int flags) {
        MemorySegment params = arena.allocate(PARAMS_SIZE, 8);

        int initializedFd = -1;
        MemorySegment initializedSqRing = MemorySegment.NULL;
        MemorySegment initializedCqRing = MemorySegment.NULL;
        MemorySegment initializedSqes = MemorySegment.NULL;
        long initializedSqRingSize = 0;
        long initializedCqRingSize = 0;
        long initializedSqesSize = 0;
        boolean initializedSingleMmap = false;
        try {
            long setupResult = setup(requestedEntries, params, flags);
            if (setupResult == -1) {
                throw setupFailure(flags, errno());
            }
            initializedFd = (int) setupResult;

            int kernelSqEntries = params.get(ValueLayout.JAVA_INT, 0);
            int kernelCqEntries = params.get(ValueLayout.JAVA_INT, 4);
            int features = params.get(ValueLayout.JAVA_INT, 20);

            long sqArrayOffset = u32(params, SQ_OFFSETS + 24);
            long cqesOffset = u32(params, CQ_OFFSETS + 20);
            initializedSqRingSize = Math.addExact(
                sqArrayOffset, Math.multiplyExact((long) kernelSqEntries, 4));
            initializedCqRingSize = Math.addExact(
                cqesOffset, Math.multiplyExact((long) kernelCqEntries, CQE_SIZE));
            initializedSingleMmap =
                (features & IORING_FEAT_SINGLE_MMAP) != 0;

            if (initializedSingleMmap) {
                initializedSqRingSize = Math.max(
                    initializedSqRingSize, initializedCqRingSize);
                initializedCqRingSize = initializedSqRingSize;
                initializedSqRing = mmap(
                    initializedFd, IORING_OFF_SQ_RING,
                    initializedSqRingSize, "SQ/CQ ring");
                initializedCqRing = initializedSqRing;
            } else {
                initializedSqRing = mmap(
                    initializedFd, IORING_OFF_SQ_RING,
                    initializedSqRingSize, "SQ ring");
                initializedCqRing = mmap(
                    initializedFd, IORING_OFF_CQ_RING,
                    initializedCqRingSize, "CQ ring");
            }

            initializedSqesSize = Math.multiplyExact(
                (long) kernelSqEntries, SQE_SIZE);
            initializedSqes = mmap(
                initializedFd, IORING_OFF_SQES,
                initializedSqesSize, "SQEs");

            this.fd = initializedFd;
            this.deferTaskRun =
                (flags & IORING_SETUP_DEFER_TASKRUN) != 0;
            this.sqRing = initializedSqRing;
            this.sqRingSize = initializedSqRingSize;
            this.cqRing = initializedCqRing;
            this.cqRingSize = initializedCqRingSize;
            this.singleMmap = initializedSingleMmap;
            this.sqes = initializedSqes;
            this.sqesSize = initializedSqesSize;

            long sqHeadOffset = u32(params, SQ_OFFSETS);
            long sqTailOffset = u32(params, SQ_OFFSETS + 4);
            long sqMaskOffset = u32(params, SQ_OFFSETS + 8);
            long sqEntriesOffset = u32(params, SQ_OFFSETS + 12);
            long sqFlagsOffset = u32(params, SQ_OFFSETS + 16);
            this.sqHead = slice(initializedSqRing, sqHeadOffset, 4, "SQ head");
            this.sqTail = slice(initializedSqRing, sqTailOffset, 4, "SQ tail");
            this.sqFlags = slice(
                initializedSqRing, sqFlagsOffset, 4, "SQ flags");
            this.sqMask = slice(initializedSqRing, sqMaskOffset, 4, "SQ mask")
                .get(ValueLayout.JAVA_INT, 0);
            this.sqEntries = slice(
                initializedSqRing, sqEntriesOffset, 4, "SQ entries")
                .get(ValueLayout.JAVA_INT, 0);
            this.sqeSegments = new MemorySegment[this.sqEntries];
            for (int i = 0; i < this.sqEntries; i++) {
                sqeSegments[i] = initializedSqes.asSlice(
                    (long) i * SQE_SIZE, SQE_SIZE);
            }
            this.sqArray = slice(
                initializedSqRing, sqArrayOffset,
                Math.multiplyExact((long) this.sqEntries, 4), "SQ array");

            long cqHeadOffset = u32(params, CQ_OFFSETS);
            long cqTailOffset = u32(params, CQ_OFFSETS + 4);
            long cqMaskOffset = u32(params, CQ_OFFSETS + 8);
            long cqOverflowOffset = u32(params, CQ_OFFSETS + 16);
            int kernelCqMask = slice(
                initializedCqRing, cqMaskOffset, 4, "CQ mask")
                .get(ValueLayout.JAVA_INT, 0);
            this.cqHead = slice(
                initializedCqRing, cqHeadOffset, 4, "CQ head");
            this.cqTail = slice(
                initializedCqRing, cqTailOffset, 4, "CQ tail");
            this.cqOverflow = slice(
                initializedCqRing, cqOverflowOffset, 4, "CQ overflow");
            this.cqMask = kernelCqMask;
            this.cqes = slice(
                initializedCqRing, cqesOffset,
                Math.multiplyExact((long) (kernelCqMask + 1), CQE_SIZE),
                "CQEs");
            this.fileUpdate = arena.allocate(16, 8);

            for (int i = 0; i < this.sqEntries; i++) {
                this.sqArray.set(ValueLayout.JAVA_INT, (long) i * 4, i);
            }
        } catch (Throwable t) {
            unmapQuietly(initializedSqes, initializedSqesSize);
            if (initializedSingleMmap) {
                unmapQuietly(initializedSqRing, initializedSqRingSize);
            } else {
                unmapQuietly(initializedCqRing, initializedCqRingSize);
                unmapQuietly(initializedSqRing, initializedSqRingSize);
            }
            closeQuietly(initializedFd);
            if (t instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException("Failed to initialize io_uring", t);
        }
    }

    public int fd() {
        return fd;
    }

    public MemorySegment cqHead() {
        return cqHead;
    }

    public MemorySegment cqTail() {
        return cqTail;
    }

    public MemorySegment cqes() {
        return cqes;
    }

    public int cqMask() {
        return cqMask;
    }

    /** Returns SQEs published to the shared ring but not yet consumed. */
    public int pendingSubmissionCount() {
        int head = (int) INT_HANDLE.getAcquire(sqHead, 0L);
        int tail = (int) INT_HANDLE.getAcquire(sqTail, 0L);
        return pendingSubmissionCount(tail, head);
    }

    public boolean hasPendingSubmissions() {
        return pendingSubmissionCount() != 0;
    }

    /** Returns locally prepared SQEs that have not reached the shared SQ yet. */
    public int unflushedSubmissionCount() {
        return sqeTail - sqeHead;
    }

    /** Returns whether deferred completion task work needs a kernel entry. */
    public boolean taskWorkPending() {
        int flags = (int) INT_HANDLE.getAcquire(sqFlags, 0L);
        return taskWorkPending(flags);
    }

    /** Returns whether CQEs are waiting in the kernel overflow list. */
    public boolean overflowPending() {
        int flags = (int) INT_HANDLE.getAcquire(sqFlags, 0L);
        return overflowPending(flags);
    }

    /** Returns the unsigned cumulative CQ overflow counter. */
    public long cqOverflowCount() {
        int count = (int) INT_HANDLE.getAcquire(cqOverflow, 0L);
        return cqOverflowCount(count);
    }

    public MemorySegment getSqe() {
        int head = (int) INT_HANDLE.getAcquire(sqHead, 0L);
        int next = sqeTail + 1;
        if (next - head > sqEntries) {
            return MemorySegment.NULL;
        }
        MemorySegment sqe = sqeSegments[sqeTail & sqMask];
        sqeTail = next;
        return sqe;
    }

    public int submit() {
        return enter(0);
    }

    public int submitAndWait(int minimumCompletions) {
        if (minimumCompletions < 0) {
            throw new IllegalArgumentException(
                "minimumCompletions must be non-negative");
        }
        return enter(minimumCompletions);
    }

    /**
     * Enters the kernel with {@code IORING_ENTER_GETEVENTS}, even when no SQEs
     * are pending. This runs deferred task work and flushes CQ overflow state
     * without waiting for a minimum number of completions.
     */
    public int enterGetEvents() {
        return enter(0, true);
    }

    public int registerFiles(MemorySegment files, int count) {
        return register(IORING_REGISTER_FILES, files, count);
    }

    public int updateFiles(int offset, MemorySegment files, int count) {
        fileUpdate.set(ValueLayout.JAVA_INT, 0, offset);
        fileUpdate.set(ValueLayout.JAVA_INT, 4, 0);
        fileUpdate.set(ValueLayout.JAVA_LONG, 8, files.address());
        return register(IORING_REGISTER_FILES_UPDATE, fileUpdate, count);
    }

    public int register(int opcode, MemorySegment argument, int count) {
        try {
            long result = (long) SYSCALL_REGISTER.invokeExact(
                SYS_IO_URING_REGISTER, fd, opcode, argument, count);
            return syscallResult(result);
        } catch (Throwable t) {
            throw new RuntimeException("io_uring_register failed", t);
        }
    }

    private int enter(int minimumCompletions) {
        return enter(minimumCompletions, false);
    }

    private int enter(int minimumCompletions, boolean forceGetEvents) {
        int toSubmit = flushSq();
        if (skipEnter(toSubmit, minimumCompletions, forceGetEvents)) {
            return 0;
        }
        int enterFlags = enterFlags(
            deferTaskRun, minimumCompletions, forceGetEvents);
        try {
            long result = (long) SYSCALL_ENTER.invokeExact(
                SYS_IO_URING_ENTER,
                fd,
                toSubmit,
                minimumCompletions,
                enterFlags,
                MemorySegment.NULL,
                0L);
            return syscallResult(result);
        } catch (Throwable t) {
            throw new RuntimeException("io_uring_enter failed", t);
        }
    }

    static boolean taskWorkPending(int sqFlags) {
        return (sqFlags & IORING_SQ_TASKRUN) != 0;
    }

    static boolean overflowPending(int sqFlags) {
        return (sqFlags & IORING_SQ_CQ_OVERFLOW) != 0;
    }

    static long cqOverflowCount(int rawCount) {
        return Integer.toUnsignedLong(rawCount);
    }

    static int pendingSubmissionCount(int tail, int head) {
        return tail - head;
    }

    static boolean skipEnter(
            int submitted, int minimumCompletions, boolean forceGetEvents) {
        return submitted == 0 && minimumCompletions == 0 && !forceGetEvents;
    }

    static int enterFlags(
            boolean deferTaskRun, int minimumCompletions,
            boolean forceGetEvents) {
        return deferTaskRun || minimumCompletions != 0 || forceGetEvents
            ? IORING_ENTER_GETEVENTS
            : 0;
    }

    private int flushSq() {
        int unflushed = sqeTail - sqeHead;
        if (unflushed != 0) {
            int tail = (int) INT_HANDLE.getAcquire(sqTail, 0L);
            // The submission array is initialized as an identity map. Since
            // this single-producer ring allocates SQEs in tail order it never
            // changes.
            sqeHead = sqeTail;
            INT_HANDLE.setRelease(sqTail, 0L, tail + unflushed);
        }

        // Use the shared SQ state, not merely the number flushed above. If a
        // prior io_uring_enter consumed fewer SQEs than requested (including
        // an interrupted enter), khead remains behind ktail and the next
        // enter retries every unconsumed entry.
        return pendingSubmissionCount();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        unmapQuietly(sqes, sqesSize);
        if (singleMmap) {
            unmapQuietly(sqRing, sqRingSize);
        } else {
            unmapQuietly(cqRing, cqRingSize);
            unmapQuietly(sqRing, sqRingSize);
        }
        closeQuietly(fd);
    }

    private static MemorySegment mmap(
            int fd, long offset, long size, String region) throws Throwable {
        MemorySegment result = (MemorySegment) MMAP.invokeExact(
            MemorySegment.NULL,
            size,
            PROT_READ | PROT_WRITE,
            MAP_SHARED | MAP_POPULATE,
            fd,
            offset);
        if (result.address() == MAP_FAILED) {
            throw failure("mmap " + region);
        }
        return result.reinterpret(size);
    }

    private static long setup(
            int requestedEntries, MemorySegment params, int flags)
            throws Throwable {
        params.fill((byte) 0);
        params.set(ValueLayout.JAVA_INT, 8, flags);
        return (long) SYSCALL_SETUP.invokeExact(
            SYS_IO_URING_SETUP, requestedEntries, params);
    }

    private static MemorySegment slice(
            MemorySegment segment, long offset, long size, String field) {
        try {
            return segment.asSlice(offset, size);
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalStateException(
                "Kernel returned invalid " + field + " offset " + offset, e);
        }
    }

    private static long u32(MemorySegment segment, long offset) {
        return Integer.toUnsignedLong(segment.get(ValueLayout.JAVA_INT, offset));
    }

    private static int syscallResult(long result) {
        if (result == -1) {
            return -errno();
        }
        if (result > Integer.MAX_VALUE) {
            throw new IllegalStateException("Unexpected syscall result: " + result);
        }
        return (int) result;
    }

    private static RuntimeException failure(String operation) {
        int errno = errno();
        return new IllegalStateException(
            operation + " failed with errno " + errno);
    }

    static IllegalStateException setupFailure(int flags, int errno) {
        int required = IORING_SETUP_SINGLE_ISSUER
            | IORING_SETUP_SUBMIT_ALL
            | IORING_SETUP_DEFER_TASKRUN
            | IORING_SETUP_TASKRUN_FLAG;
        if (errno == 22 && (flags & required) == required) {
            return new UnsupportedKernelException(
                "Cardigan requires Linux 6.1 or newer with "
                    + "IORING_SETUP_SINGLE_ISSUER, "
                    + "IORING_SETUP_SUBMIT_ALL, and "
                    + "IORING_SETUP_DEFER_TASKRUN with "
                    + "IORING_SETUP_TASKRUN_FLAG. Upgrade the kernel "
                    + "instead of running Cardigan with a degraded "
                    + "io_uring execution model.");
        }
        return new IllegalStateException(
            "io_uring_setup failed with errno " + errno);
    }

    private static int errno() {
        try {
            MemorySegment address = (MemorySegment) ERRNO_LOCATION.invokeExact();
            return address.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to read errno", t);
        }
    }

    private static MemorySegment symbol(String name) {
        return LIBC.find(name).orElseThrow(
            () -> new UnsatisfiedLinkError("Missing libc symbol: " + name));
    }

    private static void unmapQuietly(MemorySegment segment, long size) {
        if (segment.equals(MemorySegment.NULL) || size == 0) {
            return;
        }
        try {
            int ignored = (int) MUNMAP.invokeExact(segment, size);
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(int fd) {
        if (fd < 0) {
            return;
        }
        try {
            int ignored = (int) CLOSE.invokeExact(fd);
        } catch (Throwable ignored) {
        }
    }
}
