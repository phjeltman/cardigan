// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.ffi;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadAffinityTest {

    @Test
    void decodesSparseLinuxCpuMaskInKernelOrder() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mask = arena.allocate(16);
            mask.fill((byte) 0);
            mask.set(ValueLayout.JAVA_BYTE, 0, (byte) 0b1000_0101);
            mask.set(ValueLayout.JAVA_BYTE, 8, (byte) 0b0000_0011);
            mask.set(ValueLayout.JAVA_BYTE, 11, (byte) 0b1000_0000);

            assertArrayEquals(
                new int[] {0, 2, 7, 64, 65, 95},
                ThreadAffinity.cpusInMask(mask, Integer.MAX_VALUE)
            );
            assertArrayEquals(
                new int[] {0, 2, 7, 64},
                ThreadAffinity.cpusInMask(mask, 4)
            );
        }
    }

    @Test
    void rejectsOversubscribingAffinityMask() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mask = arena.allocate(2);
            mask.fill((byte) 0);
            mask.set(ValueLayout.JAVA_BYTE, 1, (byte) 0b0000_0101);

            IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ThreadAffinity.cpusInMask(mask, 3)
            );
            assertTrue(error.getMessage().contains("permits only 2 CPUs"));
        }
    }

    @Test
    void selectsDistinctPhysicalCoresBeforeSmtSiblings() {
        int[] allowed = {0, 1, 2, 3, 4, 5, 6, 7};

        assertArrayEquals(
            new int[] {0, 2, 4, 6},
            ThreadAffinity.physicalCoreFirst(
                allowed, 4, cpu -> cpu >>> 1)
        );
        assertArrayEquals(
            new int[] {0, 2, 4, 6, 1, 3},
            ThreadAffinity.physicalCoreFirst(
                allowed, 6, cpu -> cpu >>> 1)
        );
    }

    @Test
    void preservesAllowedCpuOrderAcrossSparseTopology() {
        int[] allowed = {1, 2, 3, 6};

        assertArrayEquals(
            new int[] {1, 2, 6, 3},
            ThreadAffinity.physicalCoreFirst(
                allowed, 4, cpu -> switch (cpu) {
                    case 1 -> 10;
                    case 2, 3 -> 20;
                    case 6 -> 30;
                    default -> throw new AssertionError();
                })
        );
    }

    @Test
    void preservesHttpArenaPhysicalAndSiblingCpuRanges() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mask = arena.allocate(12);
            mask.fill((byte) 0);
            for (long byteIndex = 0; byteIndex < 4; byteIndex++) {
                mask.set(ValueLayout.JAVA_BYTE, byteIndex, (byte) 0xff);
                mask.set(
                    ValueLayout.JAVA_BYTE, byteIndex + 8, (byte) 0xff);
            }

            int[] cpus = ThreadAffinity.cpusInMask(mask, 64);

            assertEquals(64, cpus.length);
            assertEquals(0, cpus[0]);
            assertEquals(31, cpus[31]);
            assertEquals(64, cpus[32]);
            assertEquals(95, cpus[63]);
        }
    }

    @Test
    void processCpuListMatchesCapturedMask() {
        byte[] mask = ThreadAffinity.processMask();
        int[] expected = new int[mask.length * 8];
        int count = 0;
        for (int byteIndex = 0; byteIndex < mask.length; byteIndex++) {
            int bits = mask[byteIndex] & 0xff;
            while (bits != 0) {
                int bit = Integer.numberOfTrailingZeros(bits);
                expected[count++] = byteIndex * 8 + bit;
                bits &= bits - 1;
            }
        }

        assertArrayEquals(
            java.util.Arrays.copyOf(expected, count),
            ThreadAffinity.processCpus()
        );
    }

    @Test
    void explicitCpuListBypassesTopologySelection() {
        int[] allowed = ThreadAffinity.processCpus();
        int first = allowed[0];
        int last = allowed[allowed.length - 1];
        String cpuList = first == last
            ? Integer.toString(first)
            : first + "," + last;

        assertArrayEquals(
            first == last
                ? new int[] {first}
                : new int[] {first, last},
            ThreadAffinity.processCpus(cpuList)
        );
    }
}
