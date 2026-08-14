// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.Get;
import dev.cardigan.http.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class Http1ByteResponseTest {
    private static final int PORT = 8149;
    private static final byte[] SMALL = {
        0, 1, 2, 3, (byte) 0xfe, (byte) 0xff
    };
    private static final byte[] LARGE = largeBody();

    private CardiganServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = CardiganServer.builder()
            .port(PORT)
            .eventLoops(1)
            .routes(new ByteController())
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
    void preservesSmallLargeAndMetadataBodiesAcrossPipeline()
            throws Exception {
        byte[] response;
        try (Socket socket = new Socket("127.0.0.1", PORT)) {
            socket.setSoTimeout(4_000);
            socket.getOutputStream().write((
                "GET /small HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "GET /large HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "GET /metadata HTTP/1.1\r\nHost: localhost\r\n"
                    + "Connection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            response = socket.getInputStream().readAllBytes();
        }

        int offset = assertResponseAt(response, 0, SMALL, false);
        offset = assertResponseAt(response, offset, LARGE, false);
        offset = assertResponseAt(response, offset, SMALL, true);
        assertEquals(response.length, offset);
    }

    private static int assertResponseAt(
            byte[] response,
            int responseOffset,
            byte[] expected,
            boolean metadata) {
        int bodyOffset = bodyOffset(response, responseOffset);
        String headers = new String(
            response,
            responseOffset,
            bodyOffset - responseOffset,
            StandardCharsets.ISO_8859_1);
        String normalizedHeaders = headers.toLowerCase(Locale.ROOT);
        assertTrue(headers.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(normalizedHeaders.contains(
            "content-type: application/octet-stream\r\n"));
        assertTrue(normalizedHeaders.contains(
            "content-length: " + expected.length + "\r\n"));
        assertEquals(
            metadata,
            normalizedHeaders.contains("x-byte-test: present\r\n"));

        int bodyEnd = bodyOffset + expected.length;
        assertTrue(bodyEnd <= response.length);
        assertArrayEquals(
            expected,
            Arrays.copyOfRange(response, bodyOffset, bodyEnd));
        return bodyEnd;
    }

    private static int bodyOffset(byte[] response, int responseOffset) {
        for (int index = responseOffset;
                index <= response.length - 4;
                index++) {
            if (response[index] == '\r'
                    && response[index + 1] == '\n'
                    && response[index + 2] == '\r'
                    && response[index + 3] == '\n') {
                return index + 4;
            }
        }
        throw new AssertionError("Missing HTTP/1 response headers");
    }

    private static byte[] largeBody() {
        byte[] body = new byte[UringEventLoop.EGRESS_FRAME_SIZE * 2 + 17];
        for (int index = 0; index < body.length; index++) {
            body[index] = (byte) (index * 31);
        }
        return body;
    }

    public static final class ByteController {
        @Get("/small")
        public Response small() {
            return Response.bytes("application/octet-stream", SMALL);
        }

        @Get("/large")
        public Response large() {
            return Response.bytes("application/octet-stream", LARGE);
        }

        @Get("/metadata")
        public Response metadata() {
            return Response.bytes("application/octet-stream", SMALL)
                .withHeader("x-byte-test", "present");
        }
    }
}
