// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.core.Http2TestSupport.Frame;
import dev.cardigan.http.Get;
import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.Response;
import dev.cardigan.http.Utf8Slice;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static dev.cardigan.core.Http2TestSupport.frame;
import static dev.cardigan.core.Http2TestSupport.readFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
final class RequestHeaderAccessIntegrationTest {
    private static final int HTTP1_HEADER_COUNT = 64;
    private static final int HTTP2_HEADER_COUNT = 58;

    private CardiganServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        port = availablePort();
        server = CardiganServer.builder()
            .port(port)
            .eventLoops(1)
            .routes(new HeaderController())
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
    void pipelinedHttp1RequestsIndexHeadersWithoutLeakingPriorState() {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            String requests = http1Request(1, "first", "keep-alive")
                + http1Request(2, "second", "close");
            String responses;
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5_000);
                socket.getOutputStream().write(
                    requests.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                responses = new String(
                    socket.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            }

            assertEquals(2, occurrences(responses, "HTTP/1.1 200 OK"));
            assertTrue(responses.contains(
                expected(1, "first", HTTP1_HEADER_COUNT)));
            assertTrue(responses.contains(
                expected(2, "second", HTTP1_HEADER_COUNT)));
        });
    }

    @Test
    void multiplexedHttp2RequestsExposeIndexedRegularHeaders() {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            try (Socket socket = connectHttp2()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                output.write(frame(
                    Http2Frames.HEADERS,
                    Http2Frames.FLAG_END_HEADERS
                        | Http2Frames.FLAG_END_STREAM,
                    1,
                    http2RequestBlock(1, "first")));
                output.write(frame(
                    Http2Frames.HEADERS,
                    Http2Frames.FLAG_END_HEADERS
                        | Http2Frames.FLAG_END_STREAM,
                    3,
                    http2RequestBlock(3, "third")));
                output.flush();

                Map<Integer, ByteArrayOutputStream> bodies = new HashMap<>();
                int ended = 0;
                while (ended != 2) {
                    Frame response = readFrame(input);
                    if (response.type == Http2Frames.DATA) {
                        bodies.computeIfAbsent(
                            response.streamId,
                            ignored -> new ByteArrayOutputStream()
                        ).write(response.payload);
                    }
                    if ((response.flags & Http2Frames.FLAG_END_STREAM) != 0) {
                        ended++;
                    }
                }

                assertEquals(
                    expected(1, "first", HTTP2_HEADER_COUNT),
                    bodies.get(1).toString(StandardCharsets.UTF_8));
                assertEquals(
                    expected(3, "third", HTTP2_HEADER_COUNT),
                    bodies.get(3).toString(StandardCharsets.UTF_8));
            }
        });
    }

    private static String http1Request(
            int id, String prefix, String connection) {
        StringBuilder request = new StringBuilder(2_048);
        request.append("GET /headers/").append(id)
            .append(" HTTP/1.1\r\nHost: localhost\r\n");
        appendProbeHeaders(request, prefix);
        for (int index = 0; index < 52; index++) {
            request.append("X-Filler-").append(index)
                .append(": filler-").append(index).append("\r\n");
        }
        request.append("X-Duplicate: ").append(prefix).append("-first\r\n")
            .append("x-duplicate: ").append(prefix).append("-second\r\n")
            .append("Connection: ").append(connection).append("\r\n\r\n");
        return request.toString();
    }

    private static byte[] http2RequestBlock(int id, String prefix) {
        ByteArrayOutputStream block = new ByteArrayOutputStream(2_048);
        block.write(0x82); // :method: GET
        block.write(0x86); // :scheme: http
        writeIndexedNameLiteral(block, 4, "/headers/" + id); // :path
        for (int index = 0; index < 8; index++) {
            writeLiteral(
                block, "x-probe-" + index, prefix + '-' + index);
        }
        for (int index = 0; index < 48; index++) {
            writeLiteral(
                block, "x-filler-" + index, "filler-" + index);
        }
        writeLiteral(block, "x-duplicate", prefix + "-first");
        writeLiteral(block, "x-duplicate", prefix + "-second");
        return block.toByteArray();
    }

    private static void appendProbeHeaders(
            StringBuilder request, String prefix) {
        for (int index = 0; index < 8; index++) {
            request.append(index % 2 == 0 ? "X-Probe-" : "x-pROBE-")
                .append(index).append(": ")
                .append(prefix).append('-').append(index).append("\r\n");
        }
    }

    private static void writeLiteral(
            ByteArrayOutputStream block, String name, String value) {
        block.write(0); // literal without indexing, new name
        writeAscii(block, name);
        writeAscii(block, value);
    }

    private static void writeIndexedNameLiteral(
            ByteArrayOutputStream block, int nameIndex, String value) {
        block.write(nameIndex);
        writeAscii(block, value);
    }

    private static void writeAscii(
            ByteArrayOutputStream block, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length >= 127) {
            throw new IllegalArgumentException("test literal is too long");
        }
        block.write(bytes.length);
        block.writeBytes(bytes);
    }

    private Socket connectHttp2() throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(5_000);
        OutputStream output = socket.getOutputStream();
        output.write(Http2Frames.CLIENT_PREFACE);
        output.write(frame(Http2Frames.SETTINGS, 0, 0, new byte[0]));
        output.flush();

        assertEquals(Http2Frames.SETTINGS,
            readFrame(socket.getInputStream()).type);
        Frame acknowledgement = readFrame(socket.getInputStream());
        assertEquals(Http2Frames.SETTINGS, acknowledgement.type);
        assertEquals(Http2Frames.FLAG_ACK, acknowledgement.flags);
        return socket;
    }

    private static String expected(int id, String prefix, int count) {
        StringBuilder value = new StringBuilder(160);
        value.append("id=").append(id).append(";probes=");
        for (int index = 0; index < 8; index++) {
            if (index != 0) {
                value.append(',');
            }
            value.append(prefix).append('-').append(index);
        }
        return value.append(";duplicate=").append(prefix).append("-first")
            .append(";missing=true;count=").append(count)
            .toString();
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

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    public static final class HeaderController {
        @Get("/headers/{id}")
        public Response headers(long id, HttpRequest request) {
            StringBuilder value = new StringBuilder(160);
            value.append("id=").append(id).append(";probes=");
            for (int index = 0; index < 8; index++) {
                if (index != 0) {
                    value.append(',');
                }
                Utf8Slice probe = request.getHeader("X-PROBE-" + index);
                value.append(probe == null ? "missing" : probe.toString());
            }
            Utf8Slice duplicate = request.getHeader("X-DUPLICATE");
            return Response.text(value.append(";duplicate=")
                .append(duplicate == null ? "missing" : duplicate.toString())
                .append(";missing=")
                .append(request.getHeader("x-does-not-exist") == null)
                .append(";count=").append(request.headerCount())
                .toString());
        }
    }
}
