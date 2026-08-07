// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@Tag("integration")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
public class PipelinedResponseOrderingTest {

    private static final int PORT = 8098;
    private static final int REQUEST_COUNT = 4_000;

    private CardiganServer server;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("cardigan.socket.send.buffer", "4096");
        server = TestServers.example(PORT, 1);
        server.start();
        Thread.sleep(100);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        System.clearProperty("cardigan.socket.send.buffer");
    }

    @Test
    void pipelinedResponsesRemainOrderedWhenTheClientAppliesBackpressure() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            try (Socket socket = new Socket()) {
                socket.setReceiveBufferSize(32 * 1_024);
                socket.connect(new InetSocketAddress("127.0.0.1", PORT));
                socket.setSoTimeout(10_000);

                StringBuilder requests = new StringBuilder(REQUEST_COUNT * 80);
                for (int i = 0; i < REQUEST_COUNT; i++) {
                    requests.append("GET /users/").append(i).append(" HTTP/1.1\r\n")
                        .append("Host: localhost\r\n")
                        .append("Connection: ")
                        .append(i + 1 == REQUEST_COUNT ? "close" : "keep-alive")
                        .append("\r\n\r\n");
                }

                socket.getOutputStream().write(requests.toString().getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                // Let the server fill the socket send buffer so that the
                // ordered writer exercises EAGAIN/partial-send continuation.
                Thread.sleep(250);

                String responses = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                int cursor = 0;
                for (int i = 0; i < REQUEST_COUNT; i++) {
                    String body = "User details for ID: " + i + " parsed directly off-heap!";
                    int position = responses.indexOf(body, cursor);
                    assertTrue(position >= cursor, "Missing or reordered response body for request " + i);
                    cursor = position + body.length();
                }
            }
        });
    }
}
