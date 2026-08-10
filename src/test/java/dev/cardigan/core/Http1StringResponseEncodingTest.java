// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.Get;
import dev.cardigan.http.Response;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class Http1StringResponseEncodingTest {
    private static final int PORT = 8148;
    private static final String ASCII = "plain ASCII 123";
    private static final String LATIN1 = "\u0080\u00a9\u00ff";
    private static final String UTF16_NON_BMP =
        "\u6f22\u5b57 \ud83d\ude42";
    private static final String MALFORMED_SURROGATE = "broken \ud800 text";

    private CardiganServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = CardiganServer.builder()
            .port(PORT)
            .eventLoops(1)
            .routes(new StringController())
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
    void writesAsciiCompactStringWithoutChangingBytes() throws Exception {
        assertUtf8Response("/ascii", ASCII);
    }

    @Test
    void encodesLatin1CompactStringAsUtf8() throws Exception {
        assertUtf8Response("/latin1", LATIN1);
    }

    @Test
    void encodesUtf16AndNonBmpStringAsUtf8() throws Exception {
        assertUtf8Response("/utf16", UTF16_NON_BMP);
    }

    @Test
    void matchesUtf8ReplacementSemanticsForMalformedSurrogate()
            throws Exception {
        assertUtf8Response("/malformed", MALFORMED_SURROGATE);
    }

    @Test
    void preservesUtf8BodiesAndLengthsAcrossPipeline() throws Exception {
        byte[] response;
        try (Socket socket = new Socket("127.0.0.1", PORT)) {
            socket.setSoTimeout(4_000);
            socket.getOutputStream().write((
                "GET /ascii HTTP/1.1\r\n"
                    + "Host: localhost\r\n\r\n"
                    + "GET /latin1 HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            response = socket.getInputStream().readAllBytes();
        }

        int secondResponse = assertResponseAt(response, 0, ASCII);
        int end = assertResponseAt(response, secondResponse, LATIN1);
        assertEquals(response.length, end);
    }

    private static void assertUtf8Response(String path, String expected)
            throws Exception {
        byte[] response;
        try (Socket socket = new Socket("127.0.0.1", PORT)) {
            socket.setSoTimeout(4_000);
            socket.getOutputStream().write((
                "GET " + path + " HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            response = socket.getInputStream().readAllBytes();
        }

        int end = assertResponseAt(response, 0, expected);
        assertEquals(response.length, end);
    }

    private static int assertResponseAt(
            byte[] response, int responseOffset, String expected) {
        int bodyOffset = bodyOffset(response, responseOffset);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        String headers = new String(
            response,
            responseOffset,
            bodyOffset - responseOffset,
            StandardCharsets.ISO_8859_1);
        assertTrue(headers.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(headers.contains(
            "Content-Length: " + expectedBytes.length + "\r\n"));
        int bodyEnd = bodyOffset + expectedBytes.length;
        assertTrue(bodyEnd <= response.length);
        assertArrayEquals(
            expectedBytes,
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
        throw new AssertionError("Missing HTTP/1 response header terminator");
    }

    public static final class StringController {
        @Get("/ascii")
        public Response ascii() {
            return Response.text(ASCII);
        }

        @Get("/latin1")
        public Response latin1() {
            return Response.text(LATIN1);
        }

        @Get("/utf16")
        public Response utf16() {
            return Response.text(UTF16_NON_BMP);
        }

        @Get("/malformed")
        public Response malformed() {
            return Response.text(MALFORMED_SURROGATE);
        }
    }
}
