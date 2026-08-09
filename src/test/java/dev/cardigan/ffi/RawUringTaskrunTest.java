// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.ffi;

import dev.cardigan.core.Opcodes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawUringTaskrunTest {
    private static final int IORING_SQ_CQ_OVERFLOW = 1 << 1;
    private static final int IORING_SQ_TASKRUN = 1 << 2;

    @Test
    void exposesTaskrunSetupFlagAtItsStableUapiBit() {
        assertEquals(1 << 7, Opcodes.IORING_SETUP_SUBMIT_ALL);
        assertEquals(1 << 9, Opcodes.IORING_SETUP_TASKRUN_FLAG);
    }

    @Test
    void derivesPendingSubmissionsFromSharedHeadAndTail() {
        assertEquals(0, RawUring.pendingSubmissionCount(17, 17));
        assertEquals(5, RawUring.pendingSubmissionCount(17, 12));
        assertEquals(3, RawUring.pendingSubmissionCount(
            Integer.MIN_VALUE + 1, Integer.MAX_VALUE - 1));
    }

    @Test
    void unconsumedSharedEntriesForceAnotherEnter() {
        int pending = RawUring.pendingSubmissionCount(23, 20);
        assertFalse(RawUring.skipEnter(pending, 0, false));
    }

    @Test
    void decodesKernelSqStateFlagsIndependently() {
        assertTrue(RawUring.taskWorkPending(IORING_SQ_TASKRUN));
        assertFalse(RawUring.taskWorkPending(IORING_SQ_CQ_OVERFLOW));
        assertTrue(RawUring.overflowPending(IORING_SQ_CQ_OVERFLOW));
        assertFalse(RawUring.overflowPending(IORING_SQ_TASKRUN));
    }

    @Test
    void exposesUnsignedCqOverflowCounter() {
        assertEquals(0L, RawUring.cqOverflowCount(0));
        assertEquals(0xffff_ffffL, RawUring.cqOverflowCount(-1));
    }

    @Test
    void forcedGetEventsDoesNotSkipAnEmptySubmission() {
        assertTrue(RawUring.skipEnter(0, 0, false));
        assertFalse(RawUring.skipEnter(0, 0, true));
        assertEquals(1, RawUring.enterFlags(false, 0, true));
    }

    @Test
    void ordinaryDeferredSubmissionStillRequestsGetEvents() {
        assertFalse(RawUring.skipEnter(1, 0, false));
        assertEquals(1, RawUring.enterFlags(true, 0, false));
        assertEquals(0, RawUring.enterFlags(false, 0, false));
    }
}
