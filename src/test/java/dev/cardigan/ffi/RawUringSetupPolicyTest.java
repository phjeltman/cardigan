// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.ffi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawUringSetupPolicyTest {
    private static final int IORING_SETUP_SINGLE_ISSUER = 1 << 12;
    private static final int IORING_SETUP_DEFER_TASKRUN = 1 << 13;
    private static final int IORING_SETUP_TASKRUN_FLAG = 1 << 9;
    private static final int IORING_SETUP_SUBMIT_ALL = 1 << 7;

    @Test
    void rejectsKernelThatCannotProvideRequiredExecutionModel() {
        IllegalStateException failure = RawUring.setupFailure(
            IORING_SETUP_SINGLE_ISSUER | IORING_SETUP_DEFER_TASKRUN
                | IORING_SETUP_TASKRUN_FLAG | IORING_SETUP_SUBMIT_ALL,
            22);

        assertInstanceOf(UnsupportedKernelException.class, failure);
        assertTrue(failure.getMessage().contains("Linux 6.1 or newer"));
        assertTrue(failure.getMessage().contains(
            "IORING_SETUP_DEFER_TASKRUN"));
        assertTrue(failure.getMessage().contains(
            "IORING_SETUP_TASKRUN_FLAG"));
        assertTrue(failure.getMessage().contains(
            "IORING_SETUP_SUBMIT_ALL"));
        assertTrue(failure.getMessage().contains("Upgrade the kernel"));
    }

    @Test
    void preservesOrdinarySetupFailures() {
        IllegalStateException failure = RawUring.setupFailure(0, 12);

        assertTrue(failure.getMessage().contains("errno 12"));
    }
}
