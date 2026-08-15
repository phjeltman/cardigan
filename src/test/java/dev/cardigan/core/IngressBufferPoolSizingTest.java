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
class IngressBufferPoolSizingTest {
    private static final String PROPERTY =
        "cardigan.ingress.buffers.per.loop";
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
    void defaultsTo128BuffersPerLoop() {
        assertEquals(128, CardiganServer.configuredIngressBuffersPerLoop());
    }

    @Test
    void acceptsExplicitPowerOfTwoCapacity() {
        System.setProperty(PROPERTY, "64");
        assertEquals(64, CardiganServer.configuredIngressBuffersPerLoop());
    }

    @Test
    void rejectsCapacityThatProvidedBufferRingCannotRepresent() {
        System.setProperty(PROPERTY, "96");
        assertThrows(
            IllegalArgumentException.class,
            CardiganServer::configuredIngressBuffersPerLoop);
    }
}
