// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.example.ExampleController;
import dev.cardigan.http.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BenchmarkEndpointTest {

    @Test
    void sleepyAndHeavyWorkAreConfigurable() {
        ExampleController controller =
            new ExampleController(0, 1_000);

        Response sleepy = controller.getSleepy();
        assertEquals("Slept like a baby for 0ms!", sleepy.body());

        Response heavy = controller.heavyTask();
        assertEquals(
            "Heavy lifting complete! Iterations: 1000, checksum: "
                + ExampleController.heavyChecksum(1_000),
            heavy.body()
        );
        assertNotEquals(
            ExampleController.heavyChecksum(999),
            ExampleController.heavyChecksum(1_000)
        );
    }
}
