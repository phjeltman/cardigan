// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class UringTaskPoolSizingTest {
    private static final String PROPERTY = "cardigan.max.tasks";

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void reservesTwoTasksPerFixedConnectionAndOneSubmissionRing() {
        assertEquals(16_896, UringEventLoop.configuredTaskCapacity(512));
    }

    @Test
    void honorsExactConfiguredCapacity() {
        System.setProperty(PROPERTY, "5000");
        assertEquals(5000, UringEventLoop.configuredTaskCapacity(512));
    }

    @Test
    void idPoolDoesNotManufactureRoundedIds() {
        UringEventLoop.IntIdPool pool = new UringEventLoop.IntIdPool(3);
        assertEquals(2, pool.poll());
        assertEquals(1, pool.poll());
        assertEquals(0, pool.poll());
        assertEquals(-1, pool.poll());

        pool.offer(1);
        assertEquals(1, pool.poll());
        assertEquals(-1, pool.poll());
    }

    @Test
    void rejectsNonPositiveCapacity() {
        System.setProperty(PROPERTY, "0");
        assertThrows(
            IllegalArgumentException.class,
            () -> UringEventLoop.configuredTaskCapacity(512)
        );
    }
}
