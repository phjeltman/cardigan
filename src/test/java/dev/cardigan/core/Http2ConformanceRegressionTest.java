// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.core.Http2TestSupport.Frame;
import dev.cardigan.http2.Http2Frames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static dev.cardigan.core.Http2TestSupport.frame;
import static dev.cardigan.core.Http2TestSupport.readFrame;

@Tag("integration")
class Http2ConformanceRegressionTest {
    private static final int PORT = 8106;

    private CardiganServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = TestServers.example(
            PORT, 1, null, ProtocolMode.HTTP2_ONLY);
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
    void http2OnlyModeRejectsInvalidPreface() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(2_000);
                socket.getOutputStream().write(
                    "INVALID CONNECTION PREFACE\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                Frame goAway = readFrame(socket.getInputStream());
                assertEquals(Http2Frames.GOAWAY, goAway.type);
                assertEquals(Http2Frames.PROTOCOL_ERROR,
                    intValue(goAway.payload, 4));
                assertEquals(-1, socket.getInputStream().read());
            }
        });
    }

    @Test
    void rejectsIdleResetAndSelfDependentPriority() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                byte[] reset = new byte[4];
                socket.getOutputStream().write(frame(
                    Http2Frames.RST_STREAM, 0, 1, reset));
                socket.getOutputStream().flush();

                Frame goAway = readFrame(socket.getInputStream());
                assertEquals(Http2Frames.GOAWAY, goAway.type);
                assertEquals(Http2Frames.PROTOCOL_ERROR,
                    intValue(goAway.payload, 4));
            }

            try (Socket socket = connect()) {
                byte[] priority = new byte[5];
                priority[3] = 1;
                socket.getOutputStream().write(frame(
                    Http2Frames.PRIORITY, 0, 1, priority));
                socket.getOutputStream().flush();

                Frame reset = readFrame(socket.getInputStream());
                assertEquals(Http2Frames.RST_STREAM, reset.type);
                assertEquals(1, reset.streamId);
                assertEquals(Http2Frames.PROTOCOL_ERROR,
                    intValue(reset.payload, 0));
            }
        });
    }

    @Test
    void remainsResponsiveAfterPeerGoAwayWithUnknownError() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                OutputStream output = socket.getOutputStream();
                byte[] goAway = new byte[8];
                goAway[4] = 0x12;
                goAway[5] = 0x34;
                goAway[6] = 0x56;
                goAway[7] = 0x78;
                byte[] opaque = "cardigan".getBytes(StandardCharsets.US_ASCII);
                output.write(frame(Http2Frames.GOAWAY, 0, 0, goAway));
                output.write(frame(Http2Frames.PING, 0, 0, opaque));
                output.flush();

                Frame ping = readFrame(socket.getInputStream());
                assertEquals(Http2Frames.PING, ping.type);
                assertEquals(Http2Frames.FLAG_ACK, ping.flags);
                assertEquals("cardigan",
                    new String(ping.payload, StandardCharsets.US_ASCII));
            }
        });
    }

    @Test
    void acceptsTrailersAndClassifiesMessageFramingErrors() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            byte[] body = "{\"name\":\"H2\",\"id\":7,\"active\":true}"
                .getBytes(StandardCharsets.UTF_8);
            try (Socket socket = connect()) {
                OutputStream output = socket.getOutputStream();
                output.write(postHeaders(1, body.length));
                output.write(frame(Http2Frames.DATA, 0, 1, body));
                output.write(frame(
                    Http2Frames.HEADERS,
                    Http2Frames.FLAG_END_HEADERS | Http2Frames.FLAG_END_STREAM,
                    1,
                    literalHeader("x-check", "ok")
                ));
                output.flush();

                Frame responseHeaders = readFrame(socket.getInputStream());
                assertEquals(Http2Frames.HEADERS, responseHeaders.type);
                assertEquals(1, responseHeaders.streamId);
            }

            try (Socket socket = connect()) {
                OutputStream output = socket.getOutputStream();
                output.write(postHeaders(1, 1));
                output.write(frame(
                    Http2Frames.DATA,
                    Http2Frames.FLAG_END_STREAM,
                    1,
                    new byte[2]
                ));
                output.flush();

                Frame reset = readFrame(socket.getInputStream());
                assertEquals(Http2Frames.RST_STREAM, reset.type);
                assertEquals(Http2Frames.PROTOCOL_ERROR,
                    intValue(reset.payload, 0));
            }

            try (Socket socket = connect()) {
                OutputStream output = socket.getOutputStream();
                output.write(postHeaders(1, 0));
                output.write(frame(
                    Http2Frames.DATA,
                    Http2Frames.FLAG_PADDED | Http2Frames.FLAG_END_STREAM,
                    1,
                    new byte[]{2, 0}
                ));
                output.flush();

                Frame goAway = readFrame(socket.getInputStream());
                assertEquals(Http2Frames.GOAWAY, goAway.type);
                assertEquals(Http2Frames.PROTOCOL_ERROR,
                    intValue(goAway.payload, 4));
            }
        });
    }

    private static Socket connect() throws Exception {
        Socket socket = new Socket("127.0.0.1", PORT);
        socket.setSoTimeout(2_000);
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

    private static byte[] postHeaders(int streamId, int contentLength)
            throws Exception {
        byte[] path = "/users".getBytes(StandardCharsets.US_ASCII);
        byte[] length = Integer.toString(contentLength)
            .getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.write(0x83); // :method: POST
        block.write(0x86); // :scheme: http
        block.write(0x04); // literal without indexing, indexed :path name
        block.write(path.length);
        block.write(path);
        block.write(0x0f); // literal without indexing, content-length index 28
        block.write(0x0d);
        block.write(length.length);
        block.write(length);
        return frame(
            Http2Frames.HEADERS,
            Http2Frames.FLAG_END_HEADERS,
            streamId,
            block.toByteArray()
        );
    }

    private static byte[] literalHeader(String name, String value)
            throws Exception {
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.write(0x00);
        block.write(nameBytes.length);
        block.write(nameBytes);
        block.write(valueBytes.length);
        block.write(valueBytes);
        return block.toByteArray();
    }

    private static int intValue(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
            | ((bytes[offset + 1] & 0xff) << 16)
            | ((bytes[offset + 2] & 0xff) << 8)
            | (bytes[offset + 3] & 0xff);
    }

}
