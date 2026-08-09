// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SchedulerModeTest {
    @Test
    void acceptsOnlyTheDocumentedSchedulerModes() {
        assertEquals(
            UringEventLoop.SchedulerMode.BUDGETED,
            UringEventLoop.schedulerMode("budgeted"));
        assertEquals(
            UringEventLoop.SchedulerMode.EPOCH,
            UringEventLoop.schedulerMode("  EPOCH "));
        assertThrows(
            IllegalArgumentException.class,
            () -> UringEventLoop.schedulerMode("turns"));
    }
}
