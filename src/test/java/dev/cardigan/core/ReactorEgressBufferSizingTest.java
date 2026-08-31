// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ReactorEgressBufferSizingTest {
    private static final String PROPERTY =
        "cardigan.egress.buffers.per.loop";
    private String previous;

    @BeforeEach
    void clearProperty() {
        previous = System.getProperty(PROPERTY);
        System.clearProperty(PROPERTY);
    }

    @AfterEach
    void restoreProperty() {
        if (previous == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, previous);
        }
    }

    @Test
    void defaultsTo4096BuffersPerLoop() {
        assertEquals(4096,
            UringEventLoop.configuredEgressBuffersPerLoop(128));
    }

    @Test
    void acceptsExplicitPowerOfTwoCapacity() {
        System.setProperty(PROPERTY, "1024");
        assertEquals(1024,
            UringEventLoop.configuredEgressBuffersPerLoop(128));
    }

    @Test
    void retainsEnoughCapacityForTheIngressPool() {
        System.setProperty(PROPERTY, "128");
        assertEquals(256,
            UringEventLoop.configuredEgressBuffersPerLoop(256));
    }

    @Test
    void rejectsCapacityThatCannotBackTheIdPools() {
        System.setProperty(PROPERTY, "64");
        assertThrows(
            IllegalArgumentException.class,
            () -> UringEventLoop.configuredEgressBuffersPerLoop(128));
    }
}
