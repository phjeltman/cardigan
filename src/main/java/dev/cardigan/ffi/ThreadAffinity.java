// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.ffi;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntToLongFunction;

/**
 * Preserves the process CPU allowance before Cardigan pins event-loop
 * threads. Linux threads inherit the affinity mask of the thread that creates
 * them, so auxiliary carrier threads must explicitly restore this mask.
 */
public final class ThreadAffinity {
    private static final long CPU_SET_SIZE = 128;
    private static final Arena PROCESS_ARENA = Arena.ofShared();
    private static final MemorySegment PROCESS_CPU_MASK =
        PROCESS_ARENA.allocate(CPU_SET_SIZE);
    private static final boolean PROCESS_CPU_MASK_CAPTURED =
        captureCurrent(PROCESS_CPU_MASK) == 0;

    private ThreadAffinity() {
    }

    /** Forces the initial CPU mask to be captured on the calling thread. */
    public static void initialize() {
        if (!PROCESS_CPU_MASK_CAPTURED) {
            System.err.println(
                "Warning: failed to capture Cardigan process CPU affinity");
        }
    }

    /** Restores the CPU mask captured when this class was initialized. */
    public static int restoreProcessAffinity() {
        if (!PROCESS_CPU_MASK_CAPTURED) {
            return -1;
        }
        return setCurrent(PROCESS_CPU_MASK);
    }

    /** Restricts the calling native thread to a Linux CPU-list expression. */
    public static int setCurrentCpus(String cpuList) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mask = arena.allocate(CPU_SET_SIZE);
            fillCpuList(cpuList, mask);
            return setCurrent(mask);
        }
    }

    /** Validates a CPU list against the process mask without changing affinity. */
    public static void validateCpuList(String cpuList) {
        try (Arena arena = Arena.ofConfined()) {
            fillCpuList(cpuList, arena.allocate(CPU_SET_SIZE));
        }
    }

    /** Pins the calling native thread to one CPU. */
    public static int pinCurrentThread(int cpu) {
        if (cpu < 0 || cpu >= CPU_SET_SIZE * 8) {
            return -1;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mask = arena.allocate(CPU_SET_SIZE);
            mask.fill((byte) 0);
            long wordOffset = (long) (cpu >>> 6) * Long.BYTES;
            long word = mask.get(ValueLayout.JAVA_LONG, wordOffset);
            mask.set(
                ValueLayout.JAVA_LONG,
                wordOffset,
                word | (1L << (cpu & 63))
            );
            return setCurrent(mask);
        }
    }

    /** Returns a copy of the calling native thread's CPU mask. */
    public static byte[] currentMask() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mask = arena.allocate(CPU_SET_SIZE);
            int result = captureCurrent(mask);
            if (result != 0) {
                throw new IllegalStateException(
                    "pthread_getaffinity_np failed: " + result);
            }
            return mask.toArray(ValueLayout.JAVA_BYTE);
        }
    }

    /** Returns a copy of the mask captured before event-loop pinning. */
    public static byte[] processMask() {
        if (!PROCESS_CPU_MASK_CAPTURED) {
            throw new IllegalStateException(
                "Cardigan process CPU affinity was not captured");
        }
        return PROCESS_CPU_MASK.toArray(ValueLayout.JAVA_BYTE);
    }

    /**
     * Returns every Linux CPU in the mask captured before event-loop pinning.
     * CPU IDs retain their ascending kernel order, including holes in a
     * container or taskset cpuset.
     */
    public static int[] processCpus() {
        if (!PROCESS_CPU_MASK_CAPTURED) {
            throw new IllegalStateException(
                "Cardigan process CPU affinity was not captured");
        }
        return cpusInMask(PROCESS_CPU_MASK, Integer.MAX_VALUE);
    }

    /**
     * Selects {@code count} Linux CPUs from the captured process affinity
     * mask. One hardware thread from each physical core is selected before
     * any SMT siblings are used.
     */
    public static int[] processCpus(int count) {
        if (!PROCESS_CPU_MASK_CAPTURED) {
            throw new IllegalStateException(
                "Cardigan process CPU affinity was not captured");
        }
        if (count <= 0) {
            throw new IllegalArgumentException(
                "Event-loop count must be positive: " + count);
        }
        int[] allowed = cpusInMask(PROCESS_CPU_MASK, Integer.MAX_VALUE);
        if (count > allowed.length) {
            throw new IllegalArgumentException(
                "Requested " + count + " event loops, but the process "
                    + "affinity mask permits only " + allowed.length
                    + " CPUs");
        }
        return physicalCoreFirst(allowed, count, ThreadAffinity::coreKey);
    }

    /**
     * Resolves an explicit Linux CPU-list expression within the captured
     * process affinity mask. Explicit CPU order is kernel order and is not
     * changed by the topology-aware default policy.
     */
    public static int[] processCpus(String cpuList) {
        if (!PROCESS_CPU_MASK_CAPTURED) {
            throw new IllegalStateException(
                "Cardigan process CPU affinity was not captured");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mask = arena.allocate(CPU_SET_SIZE);
            fillCpuList(cpuList, mask);
            return cpusInMask(mask, Integer.MAX_VALUE);
        }
    }

    static int[] physicalCoreFirst(
            int[] allowed,
            int count,
            IntToLongFunction coreKey) {
        if (count <= 0 || count > allowed.length) {
            throw new IllegalArgumentException(
                "CPU selection count must be between 1 and "
                    + allowed.length + ": " + count);
        }

        int[] selected = new int[count];
        boolean[] included = new boolean[allowed.length];
        Set<Long> cores = new HashSet<>();
        int output = 0;

        for (int index = 0; index < allowed.length && output < count; index++) {
            int cpu = allowed[index];
            if (cores.add(coreKey.applyAsLong(cpu))) {
                selected[output++] = cpu;
                included[index] = true;
            }
        }
        for (int index = 0; index < allowed.length && output < count; index++) {
            if (!included[index]) {
                selected[output++] = allowed[index];
            }
        }
        return selected;
    }

    static int[] cpusInMask(MemorySegment mask, int limit) {
        int available = 0;
        for (long byteIndex = 0; byteIndex < mask.byteSize(); byteIndex++) {
            available += Integer.bitCount(
                mask.get(ValueLayout.JAVA_BYTE, byteIndex) & 0xff);
        }
        if (available == 0) {
            throw new IllegalStateException(
                "Cardigan process affinity mask contains no CPUs");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException(
                "CPU selection limit must be positive: " + limit);
        }
        if (limit != Integer.MAX_VALUE && limit > available) {
            throw new IllegalArgumentException(
                "Requested " + limit + " event loops, but the process "
                    + "affinity mask permits only " + available + " CPUs");
        }

        int selected = Math.min(available, limit);
        int[] cpus = new int[selected];
        int output = 0;
        for (long byteIndex = 0;
                byteIndex < mask.byteSize() && output < selected;
                byteIndex++) {
            int bits = mask.get(ValueLayout.JAVA_BYTE, byteIndex) & 0xff;
            while (bits != 0 && output < selected) {
                int bit = Integer.numberOfTrailingZeros(bits);
                cpus[output++] = Math.toIntExact(byteIndex * 8 + bit);
                bits &= bits - 1;
            }
        }
        return cpus;
    }

    private static long coreKey(int cpu) {
        Path topology = Path.of(
            "/sys/devices/system/cpu/cpu" + cpu + "/topology");
        try {
            int packageId = Integer.parseInt(
                Files.readString(topology.resolve("physical_package_id"))
                    .trim());
            int coreId = Integer.parseInt(
                Files.readString(topology.resolve("core_id")).trim());
            return ((long) packageId << 32) | (coreId & 0xffff_ffffL);
        } catch (Exception unavailable) {
            // Treat an unreported CPU as its own core. This preserves the
            // ascending logical-CPU fallback on kernels or containers that do
            // not expose topology through sysfs.
            return Long.MIN_VALUE | (cpu & 0x7fff_ffffL);
        }
    }

    private static int captureCurrent(MemorySegment destination) {
        destination.fill((byte) 0);
        try {
            long pthread = (long) Libc.pthread_self.invokeExact();
            return (int) Libc.pthread_getaffinity_np.invokeExact(
                pthread, CPU_SET_SIZE, destination);
        } catch (Throwable error) {
            return -1;
        }
    }

    private static int setCurrent(MemorySegment mask) {
        try {
            long pthread = (long) Libc.pthread_self.invokeExact();
            return (int) Libc.pthread_setaffinity_np.invokeExact(
                pthread, CPU_SET_SIZE, mask);
        } catch (Throwable error) {
            return -1;
        }
    }

    private static void fillCpuList(String cpuList, MemorySegment destination) {
        destination.fill((byte) 0);
        if (cpuList == null || cpuList.isBlank()) {
            throw new IllegalArgumentException("CPU list must not be empty");
        }

        boolean populated = false;
        for (String rawRange : cpuList.split(",")) {
            String range = rawRange.trim();
            if (range.isEmpty()) {
                throw new IllegalArgumentException(
                    "Invalid CPU list: " + cpuList);
            }
            int separator = range.indexOf('-');
            int first;
            int last;
            try {
                if (separator < 0) {
                    first = Integer.parseInt(range);
                    last = first;
                } else {
                    if (separator == 0 || separator == range.length() - 1
                        || range.indexOf('-', separator + 1) >= 0) {
                        throw new NumberFormatException();
                    }
                    first = Integer.parseInt(range.substring(0, separator));
                    last = Integer.parseInt(range.substring(separator + 1));
                }
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(
                    "Invalid CPU range: " + range, error);
            }
            if (first < 0 || last < first || last >= CPU_SET_SIZE * 8) {
                throw new IllegalArgumentException(
                    "CPU range is out of bounds: " + range);
            }
            for (int cpu = first; cpu <= last; cpu++) {
                int byteIndex = cpu >>> 3;
                int bit = 1 << (cpu & 7);
                int allowed = PROCESS_CPU_MASK.get(
                    ValueLayout.JAVA_BYTE, byteIndex) & 0xff;
                if ((allowed & bit) == 0) {
                    throw new IllegalArgumentException(
                        "CPU " + cpu + " is outside the process affinity mask");
                }
                int current = destination.get(
                    ValueLayout.JAVA_BYTE, byteIndex) & 0xff;
                destination.set(
                    ValueLayout.JAVA_BYTE, byteIndex,
                    (byte) (current | bit));
                populated = true;
            }
        }
        if (!populated) {
            throw new IllegalArgumentException("CPU list must not be empty");
        }
    }
}
