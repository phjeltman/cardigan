// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@Tag("integration")
public class ConcurrentPipelinedExchangeTest {

    private static final int PORT = 8100;
    private static final int REQUEST_COUNT = 8;

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
    void safePipelinedHandlersExecuteConcurrentlyButRespondInOrder() {
        assertTimeoutPreemptively(Duration.ofSeconds(6), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(5_000);

                StringBuilder requests = new StringBuilder();
                for (int i = 0; i < REQUEST_COUNT; i++) {
                    String path = i == 0 || i + 1 == REQUEST_COUNT ? "/sleepy" : "/users/" + i;
                    requests.append("GET ").append(path).append(" HTTP/1.1\r\n")
                        .append("Host: localhost\r\n")
                        .append("Connection: ")
                        .append(i + 1 == REQUEST_COUNT ? "close" : "keep-alive")
                        .append("\r\n\r\n");
                }

                long started = System.nanoTime();
                socket.getOutputStream().write(requests.toString().getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                String responses = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

                assertTrue(elapsedMillis < 4_000,
                    "pipelined sleeps executed serially in " + elapsedMillis + "ms");
                assertEquals(2, count(responses, "Slept like a baby for 2000ms!"));
                assertEquals(REQUEST_COUNT, count(responses, "HTTP/1.1 200 OK"));

                int cursor = responses.indexOf("Slept like a baby for 2000ms!");
                for (int i = 1; i < REQUEST_COUNT - 1; i++) {
                    String body = "User details for ID: " + i + " parsed directly off-heap!";
                    int position = responses.indexOf(body, cursor);
                    assertTrue(position > cursor, "response for /users/" + i + " was reordered");
                    cursor = position;
                }
                assertTrue(responses.lastIndexOf("Slept like a baby for 2000ms!") > cursor,
                    "final /sleepy response was reordered");
            }
        });
    }

    private static int count(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
