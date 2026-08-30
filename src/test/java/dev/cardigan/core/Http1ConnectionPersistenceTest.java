// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.Get;
import dev.cardigan.http.Response;
import dev.cardigan.http.StreamingBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
final class Http1ConnectionPersistenceTest {
    private static final int PORT = 8185;

    private CardiganServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = CardiganServer.builder()
            .port(PORT)
            .eventLoops(1)
            .routes(new PersistenceController())
            .build();
        server.start();
        Thread.sleep(100);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void http11PersistenceUsesTheProtocolDefault() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", PORT)) {
            socket.setSoTimeout(3_000);
            socket.getOutputStream().write((
                "GET /plain HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "GET /number HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "GET /metadata HTTP/1.1\r\nHost: localhost\r\n"
                    + "Connection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            Http1Response first = readResponse(socket.getInputStream());
            Http1Response second = readResponse(socket.getInputStream());
            Http1Response third = readResponse(socket.getInputStream());

            assertEquals("ok", first.body());
            assertFalse(first.headers().contains("connection:"));
            assertEquals(66, first.encodedLength());
            assertEquals(Long.toString(Long.MIN_VALUE), second.body());
            assertFalse(second.headers().contains("connection:"));
            assertEquals("ok", third.body());
            assertTrue(third.headers().contains("connection: close\r\n"));
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    @Test
    void http10PersistenceRetainsTheKeepAliveField() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", PORT)) {
            socket.setSoTimeout(3_000);
            socket.getOutputStream().write((
                "GET /plain HTTP/1.0\r\nHost: localhost\r\n"
                    + "Connection: keep-alive\r\n\r\n"
                    + "GET /metadata HTTP/1.0\r\nHost: localhost\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            Http1Response first = readResponse(socket.getInputStream());
            Http1Response second = readResponse(socket.getInputStream());

            assertTrue(first.headers().contains(
                "connection: keep-alive\r\n"));
            assertTrue(second.headers().contains("connection: close\r\n"));
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    @Test
    void chunkedHttp11PersistenceUsesTheProtocolDefault() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", PORT)) {
            socket.setSoTimeout(3_000);
            socket.getOutputStream().write((
                "GET /chunked HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "GET /metadata HTTP/1.1\r\nHost: localhost\r\n"
                    + "Connection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            Http1Response first = readChunkedResponse(
                socket.getInputStream());
            Http1Response second = readResponse(socket.getInputStream());

            assertEquals("ok", first.body());
            assertTrue(first.headers().contains(
                "transfer-encoding: chunked\r\n"));
            assertFalse(first.headers().contains("connection:"));
            assertEquals("ok", second.body());
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    private static Http1Response readResponse(InputStream input)
            throws IOException {
        String headers = readHeaders(input);
        String marker = "content-length: ";
        int lengthStart = headers.indexOf(marker);
        if (lengthStart < 0) {
            throw new IOException("HTTP response has no Content-Length");
        }
        lengthStart += marker.length();
        int lengthEnd = headers.indexOf("\r\n", lengthStart);
        int contentLength = Integer.parseInt(
            headers.substring(lengthStart, lengthEnd));
        byte[] body = input.readNBytes(contentLength);
        if (body.length != contentLength) {
            throw new IOException("HTTP response body ended early");
        }
        return new Http1Response(
            headers, new String(body, StandardCharsets.UTF_8));
    }

    private static Http1Response readChunkedResponse(InputStream input)
            throws IOException {
        String headers = readHeaders(input);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            int length = Integer.parseInt(readLine(input), 16);
            if (length == 0) {
                if (!readLine(input).isEmpty()) {
                    throw new IOException("Unexpected HTTP trailers");
                }
                break;
            }
            byte[] chunk = input.readNBytes(length);
            if (chunk.length != length
                    || input.read() != '\r' || input.read() != '\n') {
                throw new IOException("Invalid HTTP chunk framing");
            }
            body.write(chunk);
        }
        return new Http1Response(
            headers, body.toString(StandardCharsets.UTF_8));
    }

    private static String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (matched != 4) {
            int current = input.read();
            if (current < 0) {
                throw new IOException("HTTP response ended in its headers");
            }
            bytes.write(current);
            matched = switch (matched) {
                case 0 -> current == '\r' ? 1 : 0;
                case 1 -> current == '\n' ? 2 : current == '\r' ? 1 : 0;
                case 2 -> current == '\r' ? 3 : 0;
                case 3 -> current == '\n' ? 4 : 0;
                default -> matched;
            };
        }

        return bytes.toString(StandardCharsets.ISO_8859_1)
            .toLowerCase(Locale.ROOT);
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int current = input.read();
            if (current < 0) {
                throw new IOException("HTTP response ended in a line");
            }
            if (current == '\r') {
                if (input.read() != '\n') {
                    throw new IOException("Invalid HTTP line ending");
                }
                return line.toString(StandardCharsets.US_ASCII);
            }
            line.write(current);
        }
    }

    private record Http1Response(String headers, String body) {
        private int encodedLength() {
            return headers.length()
                + body.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    public static final class PersistenceController {
        @Get("/plain")
        public Response plain() {
            return Response.text("ok");
        }

        @Get("/metadata")
        public Response metadata() {
            return Response.text("ok").withHeader("x-test", "present");
        }

        @Get("/number")
        public Response number() {
            return Response.text(Long.MIN_VALUE);
        }

        @Get("/chunked")
        public Response chunked() {
            byte[] bytes = "ok".getBytes(StandardCharsets.US_ASCII);
            int[] offset = {0};
            return Response.stream(
                "text/plain",
                StreamingBody.unknownLength(destination -> {
                    if (offset[0] == bytes.length) {
                        return -1;
                    }
                    int length = Math.min(
                        bytes.length - offset[0],
                        Math.toIntExact(destination.byteSize()));
                    MemorySegment.copy(
                        bytes, offset[0], destination,
                        ValueLayout.JAVA_BYTE, 0, length);
                    offset[0] += length;
                    return length;
                }));
        }
    }
}
