// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.core.Http2TestSupport.Frame;
import dev.cardigan.http.Get;
import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.Response;
import dev.cardigan.http2.Http2Frames;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static dev.cardigan.core.Http2TestSupport.frame;
import static dev.cardigan.core.Http2TestSupport.readFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("integration")
final class RequestStorageHttpIntegrationTest {
    private static final int CONCURRENT_CLIENTS = 160;
    private static final int PIPELINE_REQUESTS = 48;

    private CardiganServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        port = availablePort();
        server = CardiganServer.builder()
            .port(port)
            .eventLoops(1)
            .routes(new RequestController())
            .build();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void parkedRequestHandlersReleaseEnoughChunksForLaterClients() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            CountDownLatch ready = new CountDownLatch(CONCURRENT_CLIENTS);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger completed = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread[] clients = new Thread[CONCURRENT_CLIENTS];

            for (int index = 0; index < clients.length; index++) {
                int id = index;
                clients[index] = Thread.ofPlatform().daemon(true).start(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        String response = request(
                            "GET /inspect/" + id + "?q=" + id
                                + " HTTP/1.1\r\n"
                                + "Host: localhost\r\n"
                                + "X-Test: client-" + id + "\r\n"
                                + "Connection: close\r\n\r\n");
                        String expected = expected(id, "client-" + id, id, 0);
                        if (!response.contains("HTTP/1.1 200 OK")
                                || !response.endsWith(expected)) {
                            throw new AssertionError(
                                "unexpected response: " + response);
                        }
                        completed.incrementAndGet();
                    } catch (Throwable thrown) {
                        failure.compareAndSet(null, thrown);
                    }
                });
            }

            boolean allReady = ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            assertTrue(allReady);
            for (Thread client : clients) {
                client.join(7_000);
                assertFalse(client.isAlive(), "HTTP client did not finish");
            }
            Throwable clientFailure = failure.get();
            if (clientFailure != null) {
                fail("HTTP client failed", clientFailure);
            }
            assertEquals(CONCURRENT_CLIENTS, completed.get());
        });
    }

    @Test
    void pipelinedRequestCopiesRemainIndependentWhileHandlersPark() {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            StringBuilder pipeline = new StringBuilder(8_192);
            for (int id = 0; id < PIPELINE_REQUESTS; id++) {
                pipeline.append("GET /inspect/").append(id)
                    .append("?q=").append(id + 1000)
                    .append(" HTTP/1.1\r\nHost: localhost\r\nX-Test: pipe-")
                    .append(id)
                    .append("\r\nConnection: ")
                    .append(id + 1 == PIPELINE_REQUESTS
                        ? "close" : "keep-alive")
                    .append("\r\n\r\n");
            }

            String responses = request(pipeline.toString());
            assertEquals(
                PIPELINE_REQUESTS,
                occurrences(responses, "HTTP/1.1 200 OK"));
            for (int id = 0; id < PIPELINE_REQUESTS; id++) {
                assertTrue(responses.contains(
                    expected(id, "pipe-" + id, id + 1000, 0)),
                    "missing response for pipeline request " + id);
            }
        });
    }

    @Test
    void multiplexedHttp2RequestsKeepHeadersAndBodyUntilHandlersFinish() {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            try (Socket socket = connectHttp2()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                byte[] body = "b".repeat(4_096)
                    .getBytes(StandardCharsets.US_ASCII);

                output.write(frame(
                    Http2Frames.HEADERS,
                    Http2Frames.FLAG_END_HEADERS,
                    1,
                    requestBlock(
                        "/inspect/1?q=11", "body-token", body.length)));
                output.write(frame(
                    Http2Frames.DATA,
                    Http2Frames.FLAG_END_STREAM,
                    1,
                    body));
                output.write(frame(
                    Http2Frames.HEADERS,
                    Http2Frames.FLAG_END_HEADERS
                        | Http2Frames.FLAG_END_STREAM,
                    3,
                    requestBlock(
                        "/inspect/3?q=33", "header-token", -1)));
                output.flush();

                Map<Integer, ByteArrayOutputStream> bodies = new HashMap<>();
                Map<Integer, Boolean> ended = new HashMap<>();
                while (ended.size() != 2) {
                    Frame response = readFrame(input);
                    if (response.type == Http2Frames.DATA) {
                        bodies.computeIfAbsent(
                            response.streamId,
                            ignored -> new ByteArrayOutputStream()
                        ).write(response.payload);
                    }
                    if ((response.flags & Http2Frames.FLAG_END_STREAM) != 0) {
                        ended.put(response.streamId, true);
                    }
                }

                assertEquals(
                    expected(1, "body-token", 11, body.length),
                    bodies.get(1).toString(StandardCharsets.UTF_8));
                assertEquals(
                    expected(3, "header-token", 33, 0),
                    bodies.get(3).toString(StandardCharsets.UTF_8));
            }
        });
    }

    private String request(String encoded) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(
                encoded.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(
                socket.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        }
    }

    private Socket connectHttp2() throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(5_000);
        OutputStream output = socket.getOutputStream();
        output.write(Http2Frames.CLIENT_PREFACE);
        output.write(frame(Http2Frames.SETTINGS, 0, 0, new byte[0]));
        output.flush();

        assertEquals(Http2Frames.SETTINGS, readFrame(
            socket.getInputStream()).type);
        Frame acknowledgement = readFrame(socket.getInputStream());
        assertEquals(Http2Frames.SETTINGS, acknowledgement.type);
        assertEquals(Http2Frames.FLAG_ACK, acknowledgement.flags);
        return socket;
    }

    private static byte[] requestBlock(
            String path, String token, int contentLength) throws Exception {
        byte[] pathBytes = path.getBytes(StandardCharsets.US_ASCII);
        byte[] name = "x-test".getBytes(StandardCharsets.US_ASCII);
        byte[] value = token.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.write(0x82); // :method: GET
        block.write(0x86); // :scheme: http
        block.write(0x04); // literal without indexing, indexed :path name
        block.write(pathBytes.length);
        block.write(pathBytes);
        block.write(0x00); // literal without indexing, new name
        block.write(name.length);
        block.write(name);
        block.write(value.length);
        block.write(value);
        if (contentLength >= 0) {
            byte[] length = Integer.toString(contentLength)
                .getBytes(StandardCharsets.US_ASCII);
            block.write(0x0f); // content-length static name index 28
            block.write(0x0d);
            block.write(length.length);
            block.write(length);
        }
        return block.toByteArray();
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static String expected(
            long id, String token, int query, long bodyLength) {
        return "id=" + id + ";token=" + token + ";query=" + query
            + ";body=" + bodyLength;
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    public static final class RequestController {
        @Get("/inspect/{id}")
        public Response inspect(long id, HttpRequest request) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            String token = request.getHeader("x-test").toString();
            int query = request.queryInt("q", -1);
            return Response.text(expected(
                id, token, query, request.bodyLength()));
        }
    }
}
