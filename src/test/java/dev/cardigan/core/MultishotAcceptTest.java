// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@Tag("integration")
public class MultishotAcceptTest {

    private static final int PORT = 8099;

    private CardiganServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = TestServers.example(PORT, 1);
        server.start();
        Thread.sleep(100);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void oneAcceptSubmissionProducesMultipleConnections() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 8; i++) {
                try (Socket socket = new Socket("127.0.0.1", PORT)) {
                    socket.setSoTimeout(2_000);
                    socket.getOutputStream().write((
                        "GET /users/" + i + " HTTP/1.1\r\n"
                            + "Host: localhost\r\n"
                            + "Connection: close\r\n\r\n"
                    ).getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    assertTrue(new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII)
                        .contains("User details for ID: " + i));
                }
            }
        });

        assertTrue(server.isMultishotAcceptObserved(), "accept CQEs never carried IORING_CQE_F_MORE");
    }
}
