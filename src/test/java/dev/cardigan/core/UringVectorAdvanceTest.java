// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

class UringVectorAdvanceTest {
    @Test
    void advancesAcrossWholeVectorsAndIntoTheNextVector() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment iovecs = arena.allocate(3L * 16, 8);
            setIovec(iovecs, 0, 1_000, 10);
            setIovec(iovecs, 1, 2_000, 20);
            setIovec(iovecs, 2, 3_000, 30);

            int index = UringEventLoop.advanceIovecs(iovecs, 0, 3, 35);

            assertEquals(2, index);
            assertEquals(3_005, iovecs.get(ValueLayout.JAVA_LONG, 32));
            assertEquals(25, iovecs.get(ValueLayout.JAVA_LONG, 40));
        }
    }

    @Test
    void leavesTheNextVectorUnmodifiedAtAnExactBoundary() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment iovecs = arena.allocate(2L * 16, 8);
            setIovec(iovecs, 0, 1_000, 10);
            setIovec(iovecs, 1, 2_000, 20);

            int index = UringEventLoop.advanceIovecs(iovecs, 0, 2, 10);

            assertEquals(1, index);
            assertEquals(2_000, iovecs.get(ValueLayout.JAVA_LONG, 16));
            assertEquals(20, iovecs.get(ValueLayout.JAVA_LONG, 24));
        }
    }

    @Test
    void rejectsAdvancementPastTheAvailableVectors() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment iovecs = arena.allocate(16, 8);
            setIovec(iovecs, 0, 1_000, 10);

            assertEquals(-1, UringEventLoop.advanceIovecs(iovecs, 0, 1, 11));
        }
    }

    private static void setIovec(
        MemorySegment iovecs,
        int index,
        long address,
        long length
    ) {
        long offset = (long) index * 16;
        iovecs.set(ValueLayout.JAVA_LONG, offset, address);
        iovecs.set(ValueLayout.JAVA_LONG, offset + 8, length);
    }
}
