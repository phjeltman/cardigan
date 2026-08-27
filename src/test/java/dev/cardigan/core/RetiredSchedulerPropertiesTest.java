// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class RetiredSchedulerPropertiesTest {
    private static final List<String> RETIRED_PROPERTIES = List.of(
        "cardigan.scheduler.mode",
        "cardigan.scheduler.localReady",
        "cardigan.scheduler.boundedTurns",
        "cardigan.scheduler.cqesPerTurn",
        "cardigan.scheduler.completionsPerTurn",
        "cardigan.scheduler.protocolTasksPerTurn",
        "cardigan.scheduler.handlerContinuationsPerTurn",
        "cardigan.scheduler.egressTasksPerTurn",
        "cardigan.scheduler.externalTasksPerTurn",
        "cardigan.scheduler.protocolQuantumMicros",
        "cardigan.scheduler.protocolCheckpointInterval"
    );

    @Test
    void rejectsEveryRetiredSchedulerProperty() {
        Map<String, String> previousValues = new HashMap<>();
        for (String property : RETIRED_PROPERTIES) {
            previousValues.put(property, System.getProperty(property));
            System.clearProperty(property);
        }

        try {
            assertDoesNotThrow(
                UringEventLoop::validateSchedulerConfiguration);
            for (String property : RETIRED_PROPERTIES) {
                System.setProperty(property, "retired");
                assertThrows(
                    IllegalArgumentException.class,
                    UringEventLoop::validateSchedulerConfiguration,
                    property + " was silently accepted");
                System.clearProperty(property);
            }
        } finally {
            for (String property : RETIRED_PROPERTIES) {
                String previous = previousValues.get(property);
                if (previous == null) {
                    System.clearProperty(property);
                } else {
                    System.setProperty(property, previous);
                }
            }
        }
    }
}
