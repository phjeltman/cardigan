// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class FixedFileLifecycleIntegrationTest {
    @Test
    void everyLifecycleModeServesAndClosesAPlaintextConnection()
            throws Exception {
        String previous = System.getProperty("cardigan.fixed.files.mode");
        String[] modes = {
            "legacy", "async-explicit", "async-alloc", "direct"
        };
        try {
            for (int index = 0; index < modes.length; index++) {
                String mode = modes[index];
                int port = 8160 + index;
                System.setProperty("cardigan.fixed.files.mode", mode);
                try (CardiganServer server = TestServers.example(port, 1)) {
                    server.start();
                    try (Socket socket = new Socket("127.0.0.1", port)) {
                        socket.setSoTimeout(2_000);
                        socket.getOutputStream().write((
                            "GET /users/" + index + " HTTP/1.1\r\n"
                                + "Host: localhost\r\n"
                                + "Connection: close\r\n\r\n"
                        ).getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                        String response = new String(
                            socket.getInputStream().readAllBytes(),
                            StandardCharsets.US_ASCII);
                        assertTrue(
                            response.contains(
                                "User details for ID: " + index),
                            "mode " + mode + " failed to serve a request");
                    }
                }
            }
        } finally {
            if (previous == null) {
                System.clearProperty("cardigan.fixed.files.mode");
            } else {
                System.setProperty("cardigan.fixed.files.mode", previous);
            }
        }
    }
}
