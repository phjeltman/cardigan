// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class UringEventLoopSetupPolicyTest {
    @Test
    void enablesRequiredSubmissionAndTaskWorkFlags() {
        int expected = Opcodes.IORING_SETUP_SINGLE_ISSUER
            | Opcodes.IORING_SETUP_SUBMIT_ALL
            | Opcodes.IORING_SETUP_DEFER_TASKRUN
            | Opcodes.IORING_SETUP_TASKRUN_FLAG;

        assertEquals(expected, UringEventLoop.ringSetupFlags());
    }
}
