// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http2;

import dev.cardigan.http.HttpRequest;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Http2RequestParserTest {
    @Test
    void preparesCompactDecodedRequestWithoutAllocation() {
        byte[] encoded = hex(
            "82048762d416c430d09986418a089d5c0b8170dc780f03"
                + "7a8825b650c3abbc15c153032a2f2a"
        );
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment decoded = arena.allocate(16 * 1024);
            HpackFields fields = new HpackFields(64);
            HpackDecoder decoder =
                new HpackDecoder(4096, (int) decoded.byteSize());
            int decodedLength = decoder.decodeRequest(
                MemorySegment.ofArray(encoded), 0, encoded.length,
                decoded, fields);

            Http2RequestParser parser =
                new Http2RequestParser(10 * 1024 * 1024L);
            parser.bind(decoded);
            HttpRequest request = new HttpRequest();

            assertTrue(decodedLength > 0);
            assertEquals(
                -1, parser.prepare(decoder, fields, decodedLength, request));
            assertEquals(2, request.version());
            assertEquals("GET", request.method().toString());
            assertEquals("/users/423", request.path().toString());
            assertEquals(0, request.headerCount());
            parser.materializeHeaders();
            assertEquals(2, request.headerCount());
            assertEquals("user-agent", request.headerName(0).toString());
            assertEquals("curl/7.81.0", request.headerValue(0).toString());
            assertEquals("accept", request.headerName(1).toString());
            assertEquals("*/*", request.headerValue(1).toString());
        }
    }

    @Test
    void rejectsPseudoHeaderAfterRegularHeader() {
        byte[] encoded = hex("828658086e6f2d636163686584");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment decoded = arena.allocate(1024);
            HpackFields fields = new HpackFields(16);
            HpackDecoder decoder =
                new HpackDecoder(0, (int) decoded.byteSize());
            int decodedLength = decoder.decodeRequest(
                MemorySegment.ofArray(encoded), 0, encoded.length,
                decoded, fields);
            assertTrue(decodedLength >= 0);

            Http2RequestParser parser = new Http2RequestParser(1024);
            parser.bind(decoded);
            assertEquals(
                Http2RequestParser.ERROR,
                parser.prepare(
                    decoder, fields, decodedLength, new HttpRequest()));
        }
    }

    @Test
    void materializesFullyIndexedHeadersOnlyWhenRequested() {
        byte[] prime = hex(
            "82448762d416c430d09986418a089d5c0b8170dc780f03"
                + "7a8825b650c3abbc15c153032a2f2a"
        );
        byte[] indexed = hex("82c186c0bfbe");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment decoded = arena.allocate(16 * 1024);
            HpackDecoder decoder =
                new HpackDecoder(4096, (int) decoded.byteSize());
            HpackFields fields = new HpackFields(64);
            assertTrue(decoder.decodeRequest(
                MemorySegment.ofArray(prime), 0, prime.length,
                decoded, fields) > 0);
            int decodedLength = decoder.decodeRequest(
                MemorySegment.ofArray(indexed), 0, indexed.length,
                decoded, fields);
            assertEquals(0, decodedLength);

            Http2RequestParser parser =
                new Http2RequestParser(10 * 1024 * 1024L);
            parser.bind(decoded);
            HttpRequest request = new HttpRequest();
            assertEquals(
                -1, parser.prepare(decoder, fields, decodedLength, request));
            assertEquals("GET", request.method().toString());
            assertEquals("/users/423", request.path().toString());
            assertEquals(0, request.headerCount());
            assertEquals(13, parser.outputLength());

            parser.materializeHeaders();
            assertEquals(2, request.headerCount());
            assertEquals("user-agent", request.headerName(0).toString());
            assertEquals("curl/7.81.0", request.headerValue(0).toString());
            assertEquals("accept", request.headerName(1).toString());
            assertEquals("*/*", request.headerValue(1).toString());
            assertEquals(43, parser.outputLength());
        }
    }

    @Test
    void defersKnownMethodUntilHandlerMaterialization() {
        byte[] encoded = hex(
            "82048762d416c430d09986");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment source = arena.allocate(encoded.length);
            MemorySegment.copy(
                MemorySegment.ofArray(encoded), 0,
                source, 0, encoded.length);
            MemorySegment decoded = arena.allocate(1024);
            HpackDecoder decoder = new HpackDecoder(0, 1024);
            HpackFields fields = new HpackFields(8);
            int decodedLength = decoder.decodeRequest(
                source, 0, encoded.length, decoded, fields);

            Http2RequestParser parser = new Http2RequestParser(1024);
            parser.bind(decoded);
            HttpRequest request = new HttpRequest();
            assertEquals(
                -1,
                parser.prepare(
                    decoder, fields, decodedLength,
                    false, true, request));
            assertEquals(1, request.picoRequest().methodCode);
            assertEquals(-1, request.picoRequest().methodOffset);
            assertEquals("/users/423", request.path().toString());
            int compactLength = parser.outputLength();

            parser.materializeHeaders();
            assertEquals("GET", request.method().toString());
            assertEquals(compactLength + 3, parser.outputLength());
        }
    }

    private static byte[] hex(String encoded) {
        byte[] bytes = new byte[encoded.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(
                encoded, i * 2, i * 2 + 2, 16);
        }
        return bytes;
    }
}
