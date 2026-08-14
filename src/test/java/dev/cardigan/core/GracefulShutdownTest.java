// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.core.Http2TestSupport.Frame;
import dev.cardigan.http.IsolatedRouteStats;
import dev.cardigan.http.Post;
import dev.cardigan.http.Response;
import dev.cardigan.http2.Http2Frames;
import dev.cardigan.tls.TlsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static dev.cardigan.core.Http2TestSupport.frame;
import static dev.cardigan.core.Http2TestSupport.readFrame;
import static dev.cardigan.core.Http2TestSupport.readFrameOrEof;

@Tag("integration")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class GracefulShutdownTest {
    private static final String SLEEP_PROPERTY =
        "cardigan.benchmark.sleepMillis";

    @AfterEach
    void clearProperties() {
        System.clearProperty(SLEEP_PROPERTY);
        System.clearProperty("cardigan.shutdown.grace.millis");
        System.clearProperty("cardigan.shutdown.force.millis");
    }

    @Test
    void closesIdleHttp1KeepAliveConnectionWithoutForcing() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            CardiganServer server = startServer(8120);
            try (Socket socket = new Socket("127.0.0.1", 8120)) {
                socket.setSoTimeout(3_000);
                sendHttp1Get(socket, "/users/1", true);
                String response = readHttp1Response(socket.getInputStream());
                assertTrue(response.contains("HTTP/1.1 200 OK"));
                assertTrue(response.contains("Connection: keep-alive"));
                awaitConnectionCount(server, 1);

                Thread closer = Thread.ofPlatform().start(server::close);
                assertEquals(-1, socket.getInputStream().read());
                closer.join(3_000);

                assertFalse(closer.isAlive());
                assertEquals(0, server.activeConnectionCount());
                assertEquals(0, server.forcedConnectionCount());
            } finally {
                server.close();
            }
        });
    }

    @Test
    void finishesAdmittedHttp1ExchangeAndMarksFinalResponseClosed() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            System.setProperty(SLEEP_PROPERTY, "350");
            CardiganServer server = startServer(8121);
            try (Socket socket = new Socket("127.0.0.1", 8121)) {
                socket.setSoTimeout(3_000);
                sendHttp1Get(socket, "/sleepy", true);
                awaitExchangeWorker(server);

                Thread closer = Thread.ofPlatform().start(server::close);
                String response = readHttp1Response(socket.getInputStream());
                assertTrue(response.contains("HTTP/1.1 200 OK"));
                assertTrue(response.contains("Slept like a baby"));
                assertTrue(response.contains("Connection: close"));
                assertEquals(-1, socket.getInputStream().read());
                closer.join(3_000);

                assertFalse(closer.isAlive());
                assertEquals(0, server.activeConnectionCount());
                assertEquals(0, server.forcedConnectionCount());
            } finally {
                server.close();
            }
        });
    }

    @Test
    void cancelsIncompleteHttp1ChunkedUploadDuringDrain() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            System.setProperty("cardigan.shutdown.grace.millis", "500");
            System.setProperty("cardigan.shutdown.force.millis", "1000");
            CardiganServer server = startServer(8128);
            try (Socket socket = new Socket("127.0.0.1", 8128)) {
                socket.setSoTimeout(3_000);
                socket.getOutputStream().write((
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Connection: keep-alive\r\n\r\n"
                        + "100000\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(new byte[1024]);
                socket.getOutputStream().flush();
                awaitConnectionCount(server, 1);

                Thread closer = Thread.ofPlatform().start(server::close);
                assertEquals(-1, socket.getInputStream().read());
                closer.join(3_000);

                assertFalse(closer.isAlive());
                assertEquals(0, server.activeConnectionCount());
                assertEquals(0, server.forcedConnectionCount());
            } finally {
                server.close();
            }
        });
    }

    @Test
    void drainsIncompleteIsolatedStreamingUploadWithoutForcing() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            System.setProperty("cardigan.shutdown.grace.millis", "500");
            System.setProperty("cardigan.shutdown.force.millis", "1000");
            int activeBefore = IsolatedRouteStats.snapshot().active();
            CardiganServer server = startServer(8129);
            try (Socket socket = new Socket("127.0.0.1", 8129)) {
                socket.setSoTimeout(3_000);
                socket.getOutputStream().write((
                    "POST /stream/upload-heavy HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Length: 1048576\r\n"
                        + "Connection: keep-alive\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(new byte[1024]);
                socket.getOutputStream().flush();
                awaitConnectionCount(server, 1);
                awaitIsolatedTasks(activeBefore + 1);

                Thread closer = Thread.ofPlatform().start(server::close);
                assertEquals(-1, socket.getInputStream().read());
                closer.join(3_000);

                assertFalse(closer.isAlive());
                assertEquals(0, server.activeConnectionCount());
                assertEquals(0, server.forcedConnectionCount());
                awaitIsolatedTasks(activeBefore);
            } finally {
                server.close();
            }
        });
    }

    @Test
    void sendsHttp2GoAwayAndFinishesAdmittedStream() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            System.setProperty(SLEEP_PROPERTY, "350");
            CardiganServer server = startServer(8122);
            try (Socket socket = connectHttp2(8122)) {
                socket.setSoTimeout(3_000);
                sendHttp2Get(socket.getOutputStream(), 1, "/sleepy");
                awaitExchangeWorker(server);

                Thread closer = Thread.ofPlatform().start(server::close);
                boolean sawGoAway = false;
                boolean sawHeaders = false;
                boolean sawEndStream = false;
                String responseBody = "";
                Frame frame;
                while ((frame = readFrameOrEof(socket.getInputStream())) != null) {
                    if (frame.type == Http2Frames.GOAWAY) {
                        sawGoAway = true;
                        assertEquals(1, intValue(frame.payload, 0)
                            & Http2Frames.MAX_STREAM_ID);
                        assertEquals(Http2Frames.NO_ERROR,
                            intValue(frame.payload, 4));
                    } else if (frame.streamId == 1
                            && frame.type == Http2Frames.HEADERS) {
                        sawHeaders = true;
                    } else if (frame.streamId == 1
                            && frame.type == Http2Frames.DATA) {
                        responseBody = new String(
                            frame.payload, StandardCharsets.UTF_8);
                        sawEndStream = (frame.flags
                            & Http2Frames.FLAG_END_STREAM) != 0;
                    }
                }
                closer.join(3_000);

                assertTrue(sawGoAway);
                assertTrue(sawHeaders);
                assertTrue(sawEndStream);
                assertTrue(responseBody.contains("Slept like a baby"));
                assertFalse(closer.isAlive());
                assertEquals(0, server.activeConnectionCount());
                assertEquals(0, server.forcedConnectionCount());
            } finally {
                server.close();
            }
        });
    }

    @Test
    void drainsIdleTlsConnectionThroughCloseNotifyPath() {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            CardiganServer server = startTlsServer(8123);

            SSLContext context = trustAllContext();
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory()
                    .createSocket("localhost", 8123)) {
                socket.setSoTimeout(4_000);
                socket.startHandshake();
                sendHttp1Get(socket, "/users/2", true);
                assertTrue(readHttp1Response(socket.getInputStream())
                    .contains("HTTP/1.1 200 OK"));
                awaitConnectionCount(server, 1);

                Thread closer = Thread.ofPlatform().start(server::close);
                assertEquals(-1, socket.getInputStream().read());
                closer.join(4_000);

                assertFalse(closer.isAlive());
                assertEquals(0, server.activeConnectionCount());
                assertEquals(0, server.forcedConnectionCount());
            } finally {
                server.close();
            }
        });
    }

    @Test
    void drainsAdmittedTlsExchangeBeforeCloseNotify() {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            CardiganServer server = startTlsServer(
                8130, new TestController(350));

            SSLContext context = trustAllContext();
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory()
                    .createSocket("localhost", 8130)) {
                socket.setSoTimeout(4_000);
                socket.startHandshake();
                sendHttp1Get(socket, "/sleepy", true);
                awaitExchangeWorker(server);

                Thread closer = Thread.ofPlatform().start(server::close);
                String response = readHttp1Response(socket.getInputStream());
                assertTrue(response.contains("HTTP/1.1 200 OK"));
                assertTrue(response.contains("Slept like a baby"));
                assertTrue(response.contains("Connection: close"));
                assertEquals(-1, socket.getInputStream().read());
                closer.join(4_000);

                assertFalse(closer.isAlive());
                assertEquals(0, server.activeConnectionCount());
                assertEquals(0, server.forcedConnectionCount());
            } finally {
                server.close();
            }
        });
    }

    @Test
    void drainsParkedDirectTlsHandlerBeforeCloseNotify() {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            ParkedPostController controller = new ParkedPostController();
            CardiganServer server = startTlsServer(8131, controller);

            SSLContext context = trustAllContext();
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory()
                    .createSocket("localhost", 8131)) {
                socket.setSoTimeout(4_000);
                socket.startHandshake();
                sendHttp1Post(socket, "/parked-post");
                assertTrue(controller.started.await(2, TimeUnit.SECONDS));

                Thread closer = Thread.ofPlatform().start(server::close);
                String response = readHttp1Response(socket.getInputStream());
                assertTrue(response.contains("HTTP/1.1 200 OK"));
                assertTrue(response.contains("direct handler finished"));
                assertTrue(response.contains("Connection: close"));
                assertEquals(-1, socket.getInputStream().read());
                closer.join(4_000);

                assertFalse(closer.isAlive());
                assertEquals(0, server.activeConnectionCount());
                assertEquals(0, server.forcedConnectionCount());
            } finally {
                server.close();
            }
        });
    }

    @Test
    void forcesFlowControlledHttp2StreamAfterGraceDeadline() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            System.setProperty("cardigan.shutdown.grace.millis", "50");
            System.setProperty("cardigan.shutdown.force.millis", "1000");
            CardiganServer server = startServer(8124);
            try (Socket socket = connectHttp2(8124)) {
                socket.setSoTimeout(3_000);
                sendHttp2Get(
                    socket.getOutputStream(), 1,
                    "/some/response/large");

                Frame headers = readFrame(socket.getInputStream());
                assertEquals(Http2Frames.HEADERS, headers.type);
                int received = 0;
                while (received < 65_535) {
                    Frame data = readFrame(socket.getInputStream());
                    assertEquals(Http2Frames.DATA, data.type);
                    received += data.payload.length;
                }
                assertEquals(65_535, received);

                Thread closer = Thread.ofPlatform().start(server::close);
                closer.join(3_000);

                assertFalse(closer.isAlive());
                assertEquals(0, server.activeConnectionCount());
                assertEquals(1, server.forcedConnectionCount());
            } finally {
                server.close();
            }
        });
    }

    private static CardiganServer startServer(int port) throws Exception {
        CardiganServer server = TestServers.example(port, 1);
        server.start();
        Thread.sleep(100);
        return server;
    }

    private static CardiganServer startTlsServer(int port) throws Exception {
        return startTlsServer(port, new TestController());
    }

    private static CardiganServer startTlsServer(
            int port, Object controller) throws Exception {
        Path certificate = Path.of(GracefulShutdownTest.class.getResource(
            "/tls/localhost-cert.pem").toURI());
        Path privateKey = Path.of(GracefulShutdownTest.class.getResource(
            "/tls/localhost-key.pem").toURI());
        CardiganServer server = CardiganServer.builder()
            .port(port)
            .eventLoops(1)
            .tls(new TlsConfig(certificate, privateKey))
            .routes(controller)
            .build();
        server.start();
        Thread.sleep(100);
        return server;
    }

    private static void sendHttp1Get(
            Socket socket, String path, boolean keepAlive) throws Exception {
        String request = "GET " + path + " HTTP/1.1\r\n"
            + "Host: localhost\r\nConnection: "
            + (keepAlive ? "keep-alive" : "close") + "\r\n\r\n";
        socket.getOutputStream().write(
            request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static void sendHttp1Post(Socket socket, String path)
            throws Exception {
        String request = "POST " + path + " HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Content-Length: 0\r\n"
            + "Connection: keep-alive\r\n\r\n";
        socket.getOutputStream().write(
            request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static String readHttp1Response(InputStream input)
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (matched != 4) {
            int value = input.read();
            if (value < 0) {
                throw new AssertionError("EOF before HTTP response headers");
            }
            bytes.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> matched;
            };
        }
        String headers = bytes.toString(StandardCharsets.US_ASCII);
        int marker = headers.toLowerCase().indexOf("content-length:");
        int start = marker + "content-length:".length();
        int end = headers.indexOf("\r\n", start);
        int contentLength = Integer.parseInt(
            headers.substring(start, end).trim());
        byte[] body = input.readNBytes(contentLength);
        assertEquals(contentLength, body.length);
        return headers + new String(body, StandardCharsets.UTF_8);
    }

    private static Socket connectHttp2(int port) throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(3_000);
        OutputStream output = socket.getOutputStream();
        output.write(Http2Frames.CLIENT_PREFACE);
        output.write(frame(Http2Frames.SETTINGS, 0, 0, new byte[0]));
        output.flush();
        assertEquals(Http2Frames.SETTINGS,
            readFrame(socket.getInputStream()).type);
        Frame ack = readFrame(socket.getInputStream());
        assertEquals(Http2Frames.SETTINGS, ack.type);
        assertEquals(Http2Frames.FLAG_ACK, ack.flags);
        return socket;
    }

    private static void sendHttp2Get(
            OutputStream output, int streamId, String path) throws Exception {
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

    private static int intValue(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
            | ((bytes[offset + 1] & 0xff) << 16)
            | ((bytes[offset + 2] & 0xff) << 8)
            | (bytes[offset + 3] & 0xff);
    }

    private static void awaitConnectionCount(
            CardiganServer server, int expected) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (server.activeConnectionCount() != expected
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, server.activeConnectionCount());
    }

    private static void awaitExchangeWorker(CardiganServer server)
            throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (server.exchangeWorkerCount() == 0
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(server.exchangeWorkerCount() > 0);
    }

    private static void awaitIsolatedTasks(int expected) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (IsolatedRouteStats.snapshot().active() != expected
                && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertEquals(expected, IsolatedRouteStats.snapshot().active());
    }

    public static final class ParkedPostController {
        private final CountDownLatch started = new CountDownLatch(1);

        @Post("/parked-post")
        public Response parkedPost() {
            started.countDown();
            try {
                Thread.sleep(350);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return Response.text("direct handler finished");
        }
    }

    private static SSLContext trustAllContext() throws Exception {
        TrustManager[] trustAll = {new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }

            @Override
            public void checkClientTrusted(
                    X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(
                    X509Certificate[] chain, String authType) {
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustAll, new SecureRandom());
        return context;
    }

}
