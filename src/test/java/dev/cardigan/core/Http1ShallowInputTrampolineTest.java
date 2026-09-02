// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.Get;
import dev.cardigan.http.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@Tag("integration")
class Http1ShallowInputTrampolineTest {
    private static final int PORT = 8177;

    private CardiganServer server;
    private ProbeController probe;

    @BeforeEach
    void setUp() throws Exception {
        probe = new ProbeController();
        server = CardiganServer.builder()
            .port(PORT)
            .eventLoops(1)
            .routes(new TestController(800), probe)
            .build();
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
    void steadyKeepAliveReceiveParkOmitsTheParseRouteFrame() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(3_000);
                BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());

                write(socket,
                    "GET /users/1 HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: keep-alive\r\n\r\n");
                assertTrue(readResponse(input).contains("ID: 1"));

                StackTraceElement[] parked = awaitReceivePark(server);
                assertTrue(hasFrame(parked, "handleHttp1"),
                    "the connection owner was not parked in the H1 driver");
                assertFalse(hasFrame(parked, "drainHttp1Session"),
                    "the parse/route frame remained captured at receive park");

                write(socket,
                    "GET /users/2 HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: close\r\n\r\n");
                assertTrue(readResponse(input).contains("ID: 2"));
                assertEquals(-1, input.read());
            }
        });
    }

    @Test
    void fragmentedHeaderAndFixedBodyPreservePipelinedLeftover() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            byte[] json = ("{\"name\":\"Fragmented\",\"id\":42,"
                + "\"active\":true}").getBytes(StandardCharsets.US_ASCII);
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(3_000);
                BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());

                write(socket,
                    "POST /users HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Len");
                Thread.sleep(10);
                write(socket,
                    "gth: " + json.length + "\r\n"
                        + "Connection: keep-alive\r\n\r\n"
                        + new String(json, 0, 7, StandardCharsets.US_ASCII));
                Thread.sleep(10);
                socket.getOutputStream().write(json, 7, json.length - 7);
                socket.getOutputStream().write((
                    "GET /users/42 HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                String post = readResponse(input);
                assertTrue(post.contains("Fragmented"), post);
                String following = readResponse(input);
                assertTrue(following.contains("ID: 42"), following);
                assertEquals(-1, input.read());
            }
        });
    }

    @Test
    void fragmentedChunkedFallbackPreservesFollowingRequest() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(3_000);
                BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());

                write(socket,
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Connection: keep-alive\r\n\r\n"
                        + "4\r\nWi");
                Thread.sleep(10);
                write(socket,
                    "ki\r\n5\r\npedia\r\n0\r\n\r\n"
                        + "GET /users/7 HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: close\r\n\r\n");

                String upload = readResponse(input);
                assertTrue(upload.endsWith("Received 9 bytes"), upload);
                String following = readResponse(input);
                assertTrue(following.contains("ID: 7"), following);
                assertEquals(-1, input.read());
            }
        });
    }

    @Test
    void sleepyFirstPipelineDoesNotStarveFastHandlerAndStaysOrdered() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(3_000);
                BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());

                write(socket,
                    "GET /sleepy HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: keep-alive\r\n\r\n"
                        + "GET /trampoline-fast HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: close\r\n\r\n");

                assertTrue(probe.fastHandled.await(600, TimeUnit.MILLISECONDS),
                    "the fast pipelined handler waited behind /sleepy");
                String sleepy = readResponse(input);
                String fast = readResponse(input);
                assertTrue(sleepy.contains("Slept like a baby"), sleepy);
                assertTrue(fast.endsWith("fast handled"), fast);
                assertEquals(-1, input.read());
            }
        });
    }

    @Test
    void shutdownWakesIdleShallowReceiveDriver() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(3_000);
                BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());
                write(socket,
                    "GET /users/9 HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: keep-alive\r\n\r\n");
                assertTrue(readResponse(input).contains("ID: 9"));
                awaitReceivePark(server);

                Thread closer = Thread.ofPlatform().start(server::close);
                assertEquals(-1, input.read());
                closer.join(3_000);

                assertFalse(closer.isAlive());
                assertEquals(0, server.activeConnectionCount());
                assertEquals(0, server.forcedConnectionCount());
            }
        });
    }

    private static void write(Socket socket, String value) throws Exception {
        socket.getOutputStream().write(value.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static String readResponse(BufferedInputStream input)
        throws Exception {
        String headers = readHeaderBlock(input);
        int marker = headers.indexOf("Content-Length: ");
        int end = headers.indexOf("\r\n", marker);
        int contentLength = Integer.parseInt(
            headers.substring(marker + 16, end));
        byte[] body = input.readNBytes(contentLength);
        assertEquals(contentLength, body.length);
        return headers + new String(body, StandardCharsets.UTF_8);
    }

    private static String readHeaderBlock(BufferedInputStream input)
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
        return response.toString(StandardCharsets.US_ASCII);
    }

    private static StackTraceElement[] awaitReceivePark(CardiganServer server)
        throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        StackTraceElement[] last = new StackTraceElement[0];
        while (System.nanoTime() < deadline) {
            ApplicationRuntime.RuntimeTask owner = connectionOwner(server);
            if (owner != null) {
                last = owner.stackTrace();
                boolean cqeDriver =
                    CardiganServer.http1CqeDriverEnabled();
                if (cqeDriver && hasFrame(last, "awaitOutcome")) {
                    return last;
                }
                if (!cqeDriver
                        && hasFrame(last, "receive")
                        && hasClass(
                            last, MultishotReceiver.class.getName())) {
                    return last;
                }
            }
            Thread.sleep(2);
        }
        throw new AssertionError(
            "connection owner did not park in the configured H1 input driver: "
                + java.util.Arrays.toString(last));
    }

    private static ApplicationRuntime.RuntimeTask connectionOwner(
            CardiganServer server)
        throws Exception {
        Field connectionsField =
            CardiganServer.class.getDeclaredField("activeConnections");
        connectionsField.setAccessible(true);
        Set<?> connections = (Set<?>) connectionsField.get(server);
        Object connection = connections.stream().findFirst().orElse(null);
        if (connection == null) {
            return null;
        }
        Field ownerField = connection.getClass().getDeclaredField("owner");
        ownerField.setAccessible(true);
        return (ApplicationRuntime.RuntimeTask) ownerField.get(connection);
    }

    private static boolean hasFrame(
        StackTraceElement[] stack, String methodName
    ) {
        for (StackTraceElement frame : stack) {
            if (frame.getMethodName().equals(methodName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasClass(
        StackTraceElement[] stack, String className
    ) {
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().equals(className)) {
                return true;
            }
        }
        return false;
    }

    public static final class ProbeController {
        private final CountDownLatch fastHandled = new CountDownLatch(1);

        @Get("/trampoline-fast")
        public Response fast() {
            fastHandled.countDown();
            return Response.text("fast handled");
        }
    }
}
