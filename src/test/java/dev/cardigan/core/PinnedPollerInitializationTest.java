// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that Cardigan initializes JDK socket polling before restricting an
 * event-loop carrier to one CPU.
 */
@Tag("integration")
final class PinnedPollerInitializationTest {
    @Test
    void virtualPollerProgressesFromAnExchangeWorker()
            throws Exception {
        ProbeResult virtualPoller = runProbe("2");
        assertEquals(0, virtualPoller.exitCode(),
            virtualPoller.output());
        assertTrue(virtualPoller.output().contains(
            "socket probe completed; handler processors=1"));
    }

    @Test
    void systemPollerRemainsAWorkingControl() throws Exception {
        ProbeResult systemPoller = runProbe("1");
        assertEquals(0, systemPoller.exitCode(), systemPoller.output());
        assertTrue(systemPoller.output().contains(
            "socket probe completed; handler processors=1"));
    }

    @Test
    void perCarrierPollerProgressesFromAnExchangeWorker()
            throws Exception {
        ProbeResult perCarrierPoller = runProbe("3");
        assertEquals(0, perCarrierPoller.exitCode(),
            perCarrierPoller.output());
        assertTrue(perCarrierPoller.output().contains(
            "socket probe completed; handler processors=1"));
    }

    private static ProbeResult runProbe(String pollerMode)
            throws Exception {
        Path java = Path.of(
            System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(
            java.toString(),
            "--enable-preview",
            "--enable-native-access=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "--add-modules", "jdk.incubator.vector",
            "-Djdk.pollerMode=" + pollerMode,
            "-Djdk.virtualThreadScheduler.parallelism=1",
            "-Djdk.virtualThreadScheduler.maxPoolSize=1",
            "-cp", System.getProperty("java.class.path"),
            PinnedPollerProbe.class.getName()
        ));
        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();
        if (!process.waitFor(12, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            throw new AssertionError("poller probe did not terminate");
        }
        String output = new String(
            process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        return new ProbeResult(process.exitValue(), output);
    }

    private record ProbeResult(int exitCode, String output) {
    }
}
