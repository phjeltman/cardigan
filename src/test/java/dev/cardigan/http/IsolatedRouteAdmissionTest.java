// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsolatedRouteAdmissionTest {

    @Test
    void admissionNeverExceedsConfiguredProcessLimit() {
        IsolatedRouteStats before = IsolatedRouteStats.snapshot();
        int permits = before.limit() - before.active();
        int acquired = 0;
        try {
            for (; acquired < permits; acquired++) {
                assertTrue(IsolatedRouteExecutor.tryAcquireTask());
            }

            assertFalse(IsolatedRouteExecutor.tryAcquireTask());
            IsolatedRouteStats full = IsolatedRouteStats.snapshot();
            assertEquals(full.limit(), full.active());
            assertEquals(
                before.admitted() + permits,
                full.admitted()
            );
            assertEquals(before.rejected() + 1, full.rejected());
        } finally {
            for (int i = 0; i < acquired; i++) {
                IsolatedRouteExecutor.releaseTask();
            }
        }

        assertEquals(before.active(), IsolatedRouteStats.snapshot().active());
    }

    @Test
    void overloadResponseIsReusableServiceUnavailable() {
        assertEquals(503, Response.serviceUnavailable().statusCode());
        assertSame(
            Response.serviceUnavailable(),
            Response.serviceUnavailable()
        );
    }
}
