// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class InboundChunkPoolIntegrationTest {
    private static final int PORT = 8147;
    private static final Field EVENT_LOOPS = field("eventLoops");

    @Test
    void repeatedPlaintextReceivesReturnEveryLeaseToFixedPool() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            CardiganServer server = TestServers.example(PORT, 1);
            try {
                server.start();
                Thread.sleep(100);
                UringEventLoop loop = eventLoop(server);
                assertEquals(
                    CardiganServer.configuredIngressBuffersPerLoop(),
                    loop.inboundChunkPoolSize());

                try (Socket socket = new Socket("127.0.0.1", PORT)) {
                    socket.setSoTimeout(2_000);
                    BufferedInputStream input =
                        new BufferedInputStream(socket.getInputStream());
                    for (int request = 0; request < 64; request++) {
                        boolean last = request == 63;
                        socket.getOutputStream().write((
                            "GET /users/" + request + " HTTP/1.1\r\n"
                                + "Host: localhost\r\n"
                                + "Connection: "
                                + (last ? "close" : "keep-alive")
                                + "\r\n\r\n"
                        ).getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                        assertTrue(readResponse(input).contains(
                            "User details for ID: " + request));
                    }
                }

                long deadline = System.nanoTime()
                    + Duration.ofSeconds(2).toNanos();
                while (loop.outstandingInboundChunkCount() != 0
                        && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(0, loop.outstandingInboundChunkCount());
                assertEquals(
                    CardiganServer.configuredIngressBuffersPerLoop(),
                    loop.inboundChunkPoolSize());
            } finally {
                server.close();
            }
        });
    }

    private static String readResponse(BufferedInputStream input)
            throws Exception {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int value = input.read();
            if (value < 0) {
                throw new IllegalStateException(
                    "Connection closed before response headers");
            }
            response.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> matched;
            };
        }
        String headers = response.toString(StandardCharsets.US_ASCII);
        int marker = headers.indexOf("Content-Length: ");
        int end = headers.indexOf("\r\n", marker);
        int length = Integer.parseInt(
            headers.substring(marker + "Content-Length: ".length(), end));
        response.write(input.readNBytes(length));
        return response.toString(StandardCharsets.US_ASCII);
    }

    @SuppressWarnings("unchecked")
    private static UringEventLoop eventLoop(CardiganServer server)
            throws IllegalAccessException {
        return ((List<UringEventLoop>) EVENT_LOOPS.get(server)).getFirst();
    }

    private static Field field(String name) {
        try {
            Field field = CardiganServer.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
