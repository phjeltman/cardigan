// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.ffi;

import dev.cardigan.core.Opcodes;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
final class RawUringSubmissionIntegrationTest {
    private static final VarHandle INT_HANDLE =
        ValueLayout.JAVA_INT.varHandle();

    @Test
    void repeatedFullRingNopBatchesLeaveNoSubmissionBehind() {
        int flags = requiredFlags();

        try (Arena arena = Arena.ofShared();
                RawUring ring = new RawUring(arena, 8, flags)) {
            for (int round = 0; round < 64; round++) {
                long firstUserData = (long) round * 8 + 1;
                for (int index = 0; index < 8; index++) {
                    MemorySegment sqe = ring.getSqe();
                    assertNotEquals(MemorySegment.NULL, sqe);
                    sqe.fill((byte) 0);
                    sqe.set(ValueLayout.JAVA_BYTE, 0,
                        Opcodes.IORING_OP_NOP);
                    sqe.set(ValueLayout.JAVA_INT, 4, -1);
                    sqe.set(ValueLayout.JAVA_LONG, 32,
                        firstUserData + index);
                }

                assertEquals(8, ring.unflushedSubmissionCount());
                assertEquals(8, ring.submitAndWait(8));
                assertEquals(0, ring.unflushedSubmissionCount());
                assertFalse(ring.hasPendingSubmissions());
                reapNops(ring, firstUserData, 8);
            }
        }
    }

    @Test
    void submitAllContinuesAfterAnInvalidEntry() {
        try (Arena arena = Arena.ofShared();
                RawUring ring = new RawUring(
                    arena, 8, requiredFlags())) {
            prepare(ring, (byte) 0xff, 1);
            prepare(ring, Opcodes.IORING_OP_NOP, 2);
            prepare(ring, Opcodes.IORING_OP_NOP, 3);

            assertEquals(3, ring.submitAndWait(3));
            assertFalse(ring.hasPendingSubmissions());

            int head = (int) INT_HANDLE.getAcquire(ring.cqHead(), 0L);
            int tail = (int) INT_HANDLE.getAcquire(ring.cqTail(), 0L);
            assertEquals(3, tail - head);
            for (int index = 0; index < 3; index++) {
                long cqeOffset =
                    (long) ((head + index) & ring.cqMask()) * 16;
                assertEquals(
                    index + 1,
                    ring.cqes().get(
                        ValueLayout.JAVA_LONG, cqeOffset));
                int result = ring.cqes().get(
                    ValueLayout.JAVA_INT, cqeOffset + 8);
                if (index == 0) {
                    assertTrue(result < 0);
                } else {
                    assertEquals(0, result);
                }
            }
            INT_HANDLE.setRelease(ring.cqHead(), 0L, tail);
        }
    }

    private static int requiredFlags() {
        return Opcodes.IORING_SETUP_SUBMIT_ALL
            | Opcodes.IORING_SETUP_TASKRUN_FLAG
            | Opcodes.IORING_SETUP_SINGLE_ISSUER
            | Opcodes.IORING_SETUP_DEFER_TASKRUN;
    }

    private static void prepare(
            RawUring ring, byte opcode, long userData) {
        MemorySegment sqe = ring.getSqe();
        assertNotEquals(MemorySegment.NULL, sqe);
        sqe.fill((byte) 0);
        sqe.set(ValueLayout.JAVA_BYTE, 0, opcode);
        sqe.set(ValueLayout.JAVA_INT, 4, -1);
        sqe.set(ValueLayout.JAVA_LONG, 32, userData);
    }

    private static void reapNops(
            RawUring ring, long firstUserData, int expected) {
        MemorySegment headSegment = ring.cqHead();
        int head = (int) INT_HANDLE.getAcquire(headSegment, 0L);
        int tail = (int) INT_HANDLE.getAcquire(ring.cqTail(), 0L);
        assertEquals(expected, tail - head);

        boolean[] observed = new boolean[expected];
        for (int offset = 0; offset < expected; offset++) {
            long cqeOffset = (long) ((head + offset) & ring.cqMask()) * 16;
            long userData = ring.cqes().get(
                ValueLayout.JAVA_LONG, cqeOffset);
            int result = ring.cqes().get(
                ValueLayout.JAVA_INT, cqeOffset + 8);
            assertEquals(0, result);

            int index = Math.toIntExact(userData - firstUserData);
            assertTrue(index >= 0 && index < expected);
            assertFalse(observed[index]);
            observed[index] = true;
        }
        INT_HANDLE.setRelease(headSegment, 0L, head + expected);
    }
}
