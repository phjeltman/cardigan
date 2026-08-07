// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
public class SleepyRouteNonBlockingTest {

    private static final int PORT = 8097;
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
    void sleepyRouteDoesNotBlockAnotherConnectionOnTheSameLoop() throws Exception {
        try (Socket sleepy = new Socket("127.0.0.1", PORT)) {
            sleepy.setSoTimeout(3_000);
            sendGet(sleepy, "/sleepy");

            // Give the sole event-loop carrier time to enter the route's sleep.
            Thread.sleep(250);

            String fastResponse = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> {
                    try (Socket fast = new Socket("127.0.0.1", PORT)) {
                        fast.setSoTimeout(900);
                        sendGet(fast, "/users/1");
                        return readToClose(fast);
                    }
                },
                "Thread.sleep in /sleepy blocked the only io_uring event loop"
            );

            assertTrue(fastResponse.contains("HTTP/1.1 200 OK"));
            assertTrue(fastResponse.contains("User details for ID: 1"));

            String sleepyResponse = readToClose(sleepy);
            assertTrue(sleepyResponse.contains("Slept like a baby for 2000ms!"));
        }
    }

    private static void sendGet(Socket socket, String path) throws Exception {
        String request = "GET " + path + " HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Connection: close\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static String readToClose(Socket socket) throws Exception {
        try (InputStream input = socket.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
