// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FixedFileSqeLayoutTest {
    @Test
    void encodesKernelAllocatedFilesUpdate() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sqe = dirtySqe(arena);
            UringEventLoop.prepareFilesUpdateSqe(
                sqe, 0x1234_5678L,
                Opcodes.IORING_FILE_INDEX_ALLOC,
                0x0102_0304_0506_0708L);

            assertEquals(Opcodes.IORING_OP_FILES_UPDATE,
                sqe.get(ValueLayout.JAVA_BYTE, 0));
            assertEquals(-1, sqe.get(ValueLayout.JAVA_INT, 4));
            assertEquals(-1L, sqe.get(ValueLayout.JAVA_LONG, 8));
            assertEquals(0x1234_5678L,
                sqe.get(ValueLayout.JAVA_LONG, 16));
            assertEquals(1, sqe.get(ValueLayout.JAVA_INT, 24));
            assertEquals(0x0102_0304_0506_0708L,
                sqe.get(ValueLayout.JAVA_LONG, 32));
            assertZero(sqe, 1, 4);
            assertZero(sqe, 28, 32);
            assertZero(sqe, 40, 64);
        }
    }

    @Test
    void encodesDirectMultishotAccept() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sqe = dirtySqe(arena);
            UringEventLoop.prepareDirectAcceptSqe(
                sqe, 37, 0x1020_3040_5060_7080L);

            assertEquals(Opcodes.IORING_OP_ACCEPT,
                sqe.get(ValueLayout.JAVA_BYTE, 0));
            assertEquals(Opcodes.IORING_ACCEPT_MULTISHOT,
                sqe.get(ValueLayout.JAVA_SHORT, 2));
            assertEquals(37, sqe.get(ValueLayout.JAVA_INT, 4));
            assertEquals(0L, sqe.get(ValueLayout.JAVA_LONG, 8));
            assertEquals(0L, sqe.get(ValueLayout.JAVA_LONG, 16));
            assertEquals(0, sqe.get(ValueLayout.JAVA_INT, 24));
            assertEquals(0, sqe.get(ValueLayout.JAVA_INT, 28));
            assertEquals(0x1020_3040_5060_7080L,
                sqe.get(ValueLayout.JAVA_LONG, 32));
            assertEquals(Opcodes.IORING_FILE_INDEX_ALLOC,
                sqe.get(ValueLayout.JAVA_INT, 44));
            assertZero(sqe, 1, 2);
            assertZero(sqe, 40, 44);
            assertZero(sqe, 48, 64);
        }
    }

    @Test
    void encodesOneBasedDirectCloseSlot() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sqe = dirtySqe(arena);
            UringEventLoop.prepareDirectCloseSqe(
                sqe, 0, 0x1122_3344_5566_7788L);

            assertEquals(Opcodes.IORING_OP_CLOSE,
                sqe.get(ValueLayout.JAVA_BYTE, 0));
            assertEquals(0, sqe.get(ValueLayout.JAVA_INT, 4));
            assertEquals(0x1122_3344_5566_7788L,
                sqe.get(ValueLayout.JAVA_LONG, 32));
            assertEquals(1, sqe.get(ValueLayout.JAVA_INT, 44));
            assertZero(sqe, 1, 4);
            assertZero(sqe, 8, 32);
            assertZero(sqe, 40, 44);
            assertZero(sqe, 48, 64);
        }
    }

    @Test
    void onlyZeroIsASuccessfulDirectClose() {
        assertTrue(UringEventLoop.directCloseSucceeded(0));
        assertFalse(UringEventLoop.directCloseSucceeded(-9));
        assertFalse(UringEventLoop.directCloseSucceeded(-12));
    }

    private static MemorySegment dirtySqe(Arena arena) {
        MemorySegment sqe = arena.allocate(64, 8);
        sqe.fill((byte) 0x5a);
        return sqe;
    }

    private static void assertZero(
            MemorySegment segment, int from, int to) {
        for (int offset = from; offset < to; offset++) {
            assertEquals(0, segment.get(ValueLayout.JAVA_BYTE, offset),
                "nonzero reserved SQE byte at " + offset);
        }
    }
}
