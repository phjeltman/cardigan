// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("integration")
final class SchedulerExternalQueueHttpIntegrationTest {
    private static final int CLIENTS = 64;

    private CardiganServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void concurrentParkedHandlersResumeOverHttp() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            int port = availablePort();
            server = CardiganServer.builder()
                .port(port)
                .eventLoops(1)
                .routes(new TestController(10))
                .build();
            server.start();

            CountDownLatch ready = new CountDownLatch(CLIENTS);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger completed = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread[] clients = new Thread[CLIENTS];

            for (int index = 0; index < CLIENTS; index++) {
                clients[index] = Thread.ofPlatform().daemon(true).start(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        try (Socket socket = new Socket("127.0.0.1", port)) {
                            socket.setSoTimeout(5_000);
                            socket.getOutputStream().write((
                                "GET /sleepy HTTP/1.1\r\n"
                                    + "Host: localhost\r\n"
                                    + "Connection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().flush();
                            String response = new String(
                                socket.getInputStream().readAllBytes(),
                                StandardCharsets.UTF_8);
                            if (!response.contains("HTTP/1.1 200 OK")
                                    || !response.endsWith(
                                        "Slept like a baby for 10ms!")) {
                                throw new AssertionError(
                                    "unexpected HTTP response: " + response);
                            }
                            completed.incrementAndGet();
                        }
                    } catch (Throwable thrown) {
                        failure.compareAndSet(null, thrown);
                    }
                });
            }

            boolean allClientsReady = ready.await(2, TimeUnit.SECONDS);
            start.countDown();
            assertTrue(allClientsReady);
            for (Thread client : clients) {
                client.join(7_000);
                assertFalse(client.isAlive(), "HTTP client did not finish");
            }

            Throwable clientFailure = failure.get();
            if (clientFailure != null) {
                fail("HTTP client failed", clientFailure);
            }
            assertEquals(CLIENTS, completed.get());
        });
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
