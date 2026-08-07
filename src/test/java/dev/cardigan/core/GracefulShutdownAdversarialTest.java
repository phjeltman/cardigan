// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http2.Http2Frames;
import dev.cardigan.tls.TlsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class GracefulShutdownAdversarialTest {
    private static final String GRACE_PROPERTY =
        "cardigan.shutdown.grace.millis";
    private static final String FORCE_PROPERTY =
        "cardigan.shutdown.force.millis";
    private static final String SLEEP_PROPERTY =
        "cardigan.benchmark.sleepMillis";

    @AfterEach
    void clearProperties() {
        System.clearProperty(GRACE_PROPERTY);
        System.clearProperty(FORCE_PROPERTY);
        System.clearProperty(SLEEP_PROPERTY);
    }

    @Test
    void drainsMixedConnectionsWhilePeersDisconnectAndConnect() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            System.setProperty(GRACE_PROPERTY, "250");
            System.setProperty(FORCE_PROPERTY, "1000");
            System.setProperty(SLEEP_PROPERTY, "100");
            CardiganServer server = TestServers.example(8125, 1);
            List<Socket> held = new ArrayList<>();
            AtomicBoolean churn = new AtomicBoolean(true);
            List<Thread> churners = new ArrayList<>();
            try {
                server.start();
                Thread.sleep(100);

                for (int i = 0; i < 6; i++) {
                    held.add(connect(8125));
                }
                for (int i = 0; i < 6; i++) {
                    Socket socket = connect(8125);
                    socket.getOutputStream().write(
                        "GET /users/1 HTTP/1.1\r\nHost: local"
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    held.add(socket);
                }
                for (int i = 0; i < 4; i++) {
                    Socket socket = connect(8125);
                    sendHttp2Get(
                        socket.getOutputStream(), 1,
                        "/some/response/large");
                    held.add(socket);
                }
                for (int i = 0; i < 2; i++) {
                    Socket socket = connect(8125);
                    sendPartialHttp2Upload(socket.getOutputStream(), 1);
                    held.add(socket);
                }
                for (int i = 0; i < 4; i++) {
                    Socket socket = connect(8125);
                    socket.getOutputStream().write(
                        ("GET /sleepy HTTP/1.1\r\n"
                            + "Host: localhost\r\n"
                            + "Connection: keep-alive\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    held.add(socket);
                }
                awaitConnectionCount(server, held.size());

                for (int i = 0; i < 4; i++) {
                    int id = i;
                    churners.add(Thread.ofVirtual().start(() -> {
                        while (churn.get()) {
                            try (Socket socket = connect(8125)) {
                                if ((id & 1) == 0) {
                                    socket.getOutputStream().write(
                                        "GET /users/2 HTTP/1.1\r\n"
                                            .getBytes(
                                                StandardCharsets.US_ASCII));
                                    socket.getOutputStream().flush();
                                }
                            } catch (IOException failure) {
                                if (server.isDraining()) {
                                    return;
                                }
                                Thread.onSpinWait();
                            }
                        }
                    }));
                }

                Thread closer = Thread.ofPlatform().start(server::close);
                awaitDraining(server);
                for (int i = 0; i < held.size(); i += 2) {
                    held.get(i).close();
                }
                closer.join(3_000);
                churn.set(false);
                joinAll(churners, 1_000);

                assertFalse(closer.isAlive());
                assertAllStopped(churners);
                assertEquals(0, server.activeConnectionCount());
                assertEquals(0, Http2StreamingAdmission.reservedBytes(),
                    "shutdown leaked HTTP/2 streaming-body admission");
                assertTrue(server.forcedConnectionCount() > 0,
                    "flow-controlled HTTP/2 streams should reach the deadline");
            } finally {
                churn.set(false);
                closeAll(held);
                joinAll(churners, 1_000);
                server.close();
            }
        });
    }

    @Test
    void repeatedlyRacesAcceptAgainstClose() {
        int cycles = Integer.getInteger(
            "cardigan.shutdown.soak.cycles", 20);
        long timeoutSeconds = Math.max(15L, 5L + cycles / 2L);
        assertTimeoutPreemptively(Duration.ofSeconds(timeoutSeconds), () -> {
            long initialFdCount = openFdCount();
            System.setProperty(GRACE_PROPERTY, "500");
            System.setProperty(FORCE_PROPERTY, "1000");

            for (int cycle = 0; cycle < cycles; cycle++) {
                CardiganServer server = TestServers.example(8126, 1);
                AtomicBoolean connecting = new AtomicBoolean(true);
                AtomicInteger accepted = new AtomicInteger();
                CountDownLatch firstAccepts = new CountDownLatch(1);
                List<Thread> connectors = new ArrayList<>();
                try {
                    server.start();
                    for (int i = 0; i < 2; i++) {
                        connectors.add(Thread.ofVirtual().start(() -> {
                            while (connecting.get()) {
                                try (Socket socket = connect(8126)) {
                                    int count = accepted.incrementAndGet();
                                    socket.getOutputStream().write(
                                        "GET /users/3 HTTP/1.1\r\nHost:"
                                            .getBytes(
                                                StandardCharsets.US_ASCII));
                                    socket.getOutputStream().flush();
                                    if (count >= 4) {
                                        firstAccepts.countDown();
                                    }
                                } catch (IOException failure) {
                                    if (server.isDraining()) {
                                        return;
                                    }
                                    Thread.onSpinWait();
                                }
                            }
                        }));
                    }
                    assertTrue(firstAccepts.await(2, TimeUnit.SECONDS));

                    Thread closer = Thread.ofPlatform().start(server::close);
                    closer.join(3_000);
                    connecting.set(false);
                    joinAll(connectors, 2_000);

                    assertFalse(closer.isAlive(), "cycle " + cycle);
                    assertAllStopped(connectors);
                    assertEquals(0, server.activeConnectionCount(),
                        "cycle " + cycle);
                } finally {
                    connecting.set(false);
                    joinAll(connectors, 2_000);
                    server.close();
                }
            }
            awaitFdConvergence(initialFdCount + 16);
        });
    }

    @Test
    void incompleteTlsHandshakesCannotHoldShutdownOpen() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            System.setProperty(GRACE_PROPERTY, "500");
            System.setProperty(FORCE_PROPERTY, "1000");
            Path certificate = Path.of(
                GracefulShutdownAdversarialTest.class.getResource(
                    "/tls/localhost-cert.pem").toURI());
            Path privateKey = Path.of(
                GracefulShutdownAdversarialTest.class.getResource(
                    "/tls/localhost-key.pem").toURI());
            CardiganServer server = TestServers.example(
                8127, 1, new TlsConfig(certificate, privateKey));
            List<Socket> clients = new ArrayList<>();
            try {
                server.start();
                Thread.sleep(100);
                for (int i = 0; i < 16; i++) {
                    Socket socket = connect(8127);
                    if ((i & 1) != 0) {
                        socket.getOutputStream().write(
                            new byte[] {0x16, 0x03});
                        socket.getOutputStream().flush();
                    }
                    clients.add(socket);
                }
                awaitConnectionCount(server, clients.size());

                Thread closer = Thread.ofPlatform().start(server::close);
                closer.join(3_000);

                assertFalse(closer.isAlive());
                assertEquals(0, server.activeConnectionCount());
                assertEquals(0, server.forcedConnectionCount());
            } finally {
                closeAll(clients);
                server.close();
            }
        });
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(
                new InetSocketAddress("127.0.0.1", port), 500);
            socket.setSoTimeout(1_000);
            return socket;
        } catch (IOException failure) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw failure;
        }
    }

    private static void sendHttp2Get(
            OutputStream output, int streamId, String path) throws IOException {
        output.write(Http2Frames.CLIENT_PREFACE);
        output.write(frame(Http2Frames.SETTINGS, 0, 0, new byte[0]));

        byte[] pathBytes = path.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.write(0x82);
        block.write(0x86);
        block.write(0x04);
        block.write(pathBytes.length);
        block.write(pathBytes);
        output.write(frame(
            Http2Frames.HEADERS,
            Http2Frames.FLAG_END_HEADERS | Http2Frames.FLAG_END_STREAM,
            streamId,
            block.toByteArray()));
        output.flush();
    }

    private static void sendPartialHttp2Upload(
            OutputStream output, int streamId) throws IOException {
        output.write(Http2Frames.CLIENT_PREFACE);
        output.write(frame(Http2Frames.SETTINGS, 0, 0, new byte[0]));

        byte[] path = "/stream/upload".getBytes(StandardCharsets.US_ASCII);
        byte[] length = "1048576".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.write(0x83); // :method: POST
        block.write(0x86); // :scheme: http
        block.write(0x04); // literal :path
        block.write(path.length);
        block.write(path);
        block.write(0x0f); // content-length static name index 28
        block.write(0x0d);
        block.write(length.length);
        block.write(length);
        output.write(frame(
            Http2Frames.HEADERS,
            Http2Frames.FLAG_END_HEADERS,
            streamId,
            block.toByteArray()));
        output.write(frame(
            Http2Frames.DATA, 0, streamId, new byte[1024]));
        output.flush();
    }

    private static byte[] frame(
            int type, int flags, int streamId, byte[] payload) {
        byte[] frame = new byte[Http2Frames.HEADER_SIZE + payload.length];
        MemorySegment segment = MemorySegment.ofArray(frame);
        Http2Frames.writeHeader(
            segment, 0, payload.length, type, flags, streamId);
        MemorySegment.copy(
            MemorySegment.ofArray(payload), 0,
            segment, Http2Frames.HEADER_SIZE, payload.length);
        return frame;
    }

    private static void awaitConnectionCount(
            CardiganServer server, int expected) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (server.activeConnectionCount() < expected
                && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertTrue(server.activeConnectionCount() >= expected,
            "expected at least " + expected + " live connections, saw "
                + server.activeConnectionCount());
    }

    private static void awaitDraining(CardiganServer server)
            throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!server.isDraining() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(server.isDraining());
    }

    private static void closeAll(List<Socket> sockets) {
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void joinAll(List<Thread> threads, long timeoutMillis)
            throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(timeoutMillis);
        }
    }

    private static void assertAllStopped(List<Thread> threads) {
        for (Thread thread : threads) {
            assertFalse(thread.isAlive(),
                "adversarial client driver did not terminate");
        }
    }

    private static long openFdCount() throws IOException {
        try (var descriptors = Files.list(Path.of("/proc/self/fd"))) {
            return descriptors.count();
        }
    }

    private static void awaitFdConvergence(long maximum) throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;
        long count;
        while ((count = openFdCount()) > maximum
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(count <= maximum,
            "open file descriptors did not converge: " + count
                + " > " + maximum + "; " + fdSummary());
    }

    private static Map<String, Long> fdSummary() throws IOException {
        try (var descriptors = Files.list(Path.of("/proc/self/fd"))) {
            return descriptors
                .map(path -> {
                    try {
                        return Files.readSymbolicLink(path).toString();
                    } catch (IOException failure) {
                        return "<closed>";
                    }
                })
                .collect(Collectors.groupingBy(
                    target -> target.startsWith("socket:[")
                        ? "socket"
                        : target,
                    Collectors.counting()));
        }
    }
}
