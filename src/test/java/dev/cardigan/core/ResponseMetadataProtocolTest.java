// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.core.Http2TestSupport.Frame;
import dev.cardigan.http.Get;
import dev.cardigan.http.Response;
import dev.cardigan.http.StreamingBody;
import dev.cardigan.http2.HpackDecoder;
import dev.cardigan.http2.HpackFields;
import dev.cardigan.http2.Http2Frames;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static dev.cardigan.core.Http2TestSupport.frame;
import static dev.cardigan.core.Http2TestSupport.readFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class ResponseMetadataProtocolTest {
    private static final int PORT = 8146;
    private CardiganServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = CardiganServer.builder()
            .port(PORT)
            .eventLoops(1)
            .routes(new MetadataController())
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
    void writesHttp1HeadersAndChunkedTrailers() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", PORT)) {
            socket.setSoTimeout(4_000);
            socket.getOutputStream().write((
                "GET /metadata HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            String response = new String(
                socket.getInputStream().readAllBytes(),
                StandardCharsets.ISO_8859_1);
            assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"));
            assertTrue(response.contains("cache-control: no-cache\r\n"));
            assertTrue(response.contains(
                "Transfer-Encoding: chunked\r\n"));
            assertTrue(response.contains("trailer: grpc-status\r\n"));
            assertFalse(response.contains("Content-Length:"));
            assertTrue(response.endsWith(
                "00000005\r\nhello\r\n"
                    + "0\r\ngrpc-status: 0\r\n\r\n"));
        }
    }

    @Test
    void writesHttp2HeadersDataAndTrailingHeaders() throws Exception {
        try (Socket socket = connectHttp2()) {
            OutputStream output = socket.getOutputStream();
            output.write(frame(
                Http2Frames.HEADERS,
                Http2Frames.FLAG_END_HEADERS | Http2Frames.FLAG_END_STREAM,
                1,
                getBlock("/metadata")));
            output.flush();

            Frame headers = readFrame(socket.getInputStream());
            Frame data = readFrame(socket.getInputStream());
            Frame trailers = readFrame(socket.getInputStream());

            assertEquals(Http2Frames.HEADERS, headers.type);
            assertEquals(0, headers.flags & Http2Frames.FLAG_END_STREAM);
            assertField(headers.payload, "cache-control", "no-cache");
            assertEquals(Http2Frames.DATA, data.type);
            assertEquals(0, data.flags & Http2Frames.FLAG_END_STREAM);
            assertEquals(
                "hello", new String(data.payload, StandardCharsets.UTF_8));
            assertEquals(Http2Frames.HEADERS, trailers.type);
            assertTrue((trailers.flags & Http2Frames.FLAG_END_HEADERS) != 0);
            assertTrue((trailers.flags & Http2Frames.FLAG_END_STREAM) != 0);
            assertField(trailers.payload, "grpc-status", "0");
        }
    }

    @Test
    void writesKnownLengthStreamingBodyAndTrailers()
            throws Exception {
        try (Socket socket = connectHttp2()) {
            OutputStream output = socket.getOutputStream();
            output.write(frame(
                Http2Frames.HEADERS,
                Http2Frames.FLAG_END_HEADERS | Http2Frames.FLAG_END_STREAM,
                1,
                getBlock("/known-stream-metadata")));
            output.flush();

            Frame headers = readFrame(socket.getInputStream());
            Frame data = readFrame(socket.getInputStream());
            Frame trailers = readFrame(socket.getInputStream());

            assertEquals(Http2Frames.HEADERS, headers.type);
            assertField(headers.payload, "x-stream", "known");
            assertEquals(Http2Frames.DATA, data.type);
            assertEquals(
                "known-body",
                new String(data.payload, StandardCharsets.UTF_8));
            assertEquals(0, data.flags & Http2Frames.FLAG_END_STREAM);
            assertEquals(Http2Frames.HEADERS, trailers.type);
            assertTrue((trailers.flags & Http2Frames.FLAG_END_HEADERS) != 0);
            assertTrue((trailers.flags & Http2Frames.FLAG_END_STREAM) != 0);
            assertField(trailers.payload, "grpc-status", "0");
        }
    }

    @Test
    void preservesMetadataAcrossUnknownLengthStreaming() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", PORT)) {
            socket.setSoTimeout(4_000);
            socket.getOutputStream().write((
                "GET /stream-metadata HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            String response = new String(
                socket.getInputStream().readAllBytes(),
                StandardCharsets.ISO_8859_1);
            assertTrue(response.contains("x-stream: yes\r\n"));
            assertTrue(response.endsWith(
                "0000000b\r\nstream-body\r\n"
                    + "0\r\ngrpc-status: 0\r\n\r\n"));
        }

        try (Socket socket = connectHttp2()) {
            OutputStream output = socket.getOutputStream();
            output.write(frame(
                Http2Frames.HEADERS,
                Http2Frames.FLAG_END_HEADERS | Http2Frames.FLAG_END_STREAM,
                1,
                getBlock("/stream-metadata")));
            output.flush();

            Frame headers = readFrame(socket.getInputStream());
            Frame data = readFrame(socket.getInputStream());
            Frame trailers = readFrame(socket.getInputStream());
            assertField(headers.payload, "x-stream", "yes");
            assertEquals(
                "stream-body",
                new String(data.payload, StandardCharsets.UTF_8));
            assertEquals(0, data.flags & Http2Frames.FLAG_END_STREAM);
            assertTrue((trailers.flags & Http2Frames.FLAG_END_STREAM) != 0);
            assertField(trailers.payload, "grpc-status", "0");
        }
    }

    private static Socket connectHttp2() throws Exception {
        Socket socket = new Socket("127.0.0.1", PORT);
        socket.setSoTimeout(4_000);
        OutputStream output = socket.getOutputStream();
        output.write(Http2Frames.CLIENT_PREFACE);
        output.write(frame(Http2Frames.SETTINGS, 0, 0, new byte[0]));
        output.flush();

        Frame settings = readFrame(socket.getInputStream());
        Frame ack = readFrame(socket.getInputStream());
        assertEquals(Http2Frames.SETTINGS, settings.type);
        assertEquals(Http2Frames.SETTINGS, ack.type);
        assertEquals(Http2Frames.FLAG_ACK, ack.flags);
        return socket;
    }

    private static byte[] getBlock(String path) throws Exception {
        byte[] pathBytes = path.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.write(0x82); // :method: GET
        block.write(0x86); // :scheme: http
        block.write(0x04); // literal without indexing, indexed :path name
        block.write(pathBytes.length);
        block.write(pathBytes);
        return block.toByteArray();
    }

    private static void assertField(
            byte[] block,
            String expectedName,
            String expectedValue) {
        byte[] decoded = new byte[16 * 1024];
        HpackFields fields = new HpackFields(16);
        int result = new HpackDecoder(0, decoded.length).decode(
            MemorySegment.ofArray(block), 0, block.length,
            MemorySegment.ofArray(decoded), fields);
        assertTrue(result >= 0);
        for (int index = 0; index < fields.count(); index++) {
            String name = new String(
                decoded,
                fields.nameOffset(index),
                fields.nameLength(index),
                StandardCharsets.ISO_8859_1);
            if (name.equals(expectedName)) {
                assertEquals(
                    expectedValue,
                    new String(
                        decoded,
                        fields.valueOffset(index),
                        fields.valueLength(index),
                        StandardCharsets.ISO_8859_1));
                return;
            }
        }
        throw new AssertionError("Missing response field " + expectedName);
    }

    public static final class MetadataController {
        @Get("/metadata")
        public Response metadata() {
            return Response.text("hello")
                .withHeader("cache-control", "no-cache")
                .withTrailer("grpc-status", "0");
        }

        @Get("/stream-metadata")
        public Response streamMetadata() {
            byte[] bytes = "stream-body".getBytes(StandardCharsets.UTF_8);
            int[] offset = {0};
            return Response.stream(
                    "application/octet-stream",
                    StreamingBody.unknownLength(destination -> {
                        if (offset[0] == bytes.length) {
                            return -1;
                        }
                        int length = Math.min(
                            bytes.length - offset[0],
                            Math.toIntExact(destination.byteSize()));
                        MemorySegment.copy(
                            bytes,
                            offset[0],
                            destination,
                            ValueLayout.JAVA_BYTE,
                            0,
                            length);
                        offset[0] += length;
                        return length;
                    }))
                .withHeader("x-stream", "yes")
                .withTrailer("grpc-status", "0");
        }

        @Get("/known-stream-metadata")
        public Response knownStreamMetadata() {
            byte[] bytes = "known-body".getBytes(StandardCharsets.UTF_8);
            int[] offset = {0};
            return Response.stream(
                    "application/octet-stream",
                    StreamingBody.of(bytes.length, destination -> {
                        int remaining = bytes.length - offset[0];
                        if (remaining == 0) {
                            return -1;
                        }
                        int length = Math.min(
                            Math.min(3, remaining),
                            Math.toIntExact(destination.byteSize()));
                        MemorySegment.copy(
                            bytes,
                            offset[0],
                            destination,
                            ValueLayout.JAVA_BYTE,
                            0,
                            length);
                        offset[0] += length;
                        return length;
                    }))
                .withHeader("x-stream", "known")
                .withTrailer("grpc-status", "0");
        }
    }
}
