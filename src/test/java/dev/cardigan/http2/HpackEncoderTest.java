// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http2;

import dev.cardigan.http.ResponseHeaders;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HpackEncoderTest {
    @Test
    void writesStatelessResponseBlock() {
        byte[] encoded = new byte[256];
        int length = HpackEncoder.writeResponseHeaders(
            MemorySegment.ofArray(encoded), 0, 200, "text/plain", 42);

        byte[] decoded = new byte[256];
        HpackFields fields = new HpackFields(8);
        int decodedLength = new HpackDecoder(0, 1024).decode(
            MemorySegment.ofArray(encoded), 0, length,
            MemorySegment.ofArray(decoded), fields);

        assertEquals(3, fields.count());
        assertEquals(":status", text(decoded, fields.nameOffset(0), fields.nameLength(0)));
        assertEquals("200", text(decoded, fields.valueOffset(0), fields.valueLength(0)));
        assertEquals("content-type", text(decoded, fields.nameOffset(1), fields.nameLength(1)));
        assertEquals("text/plain", text(decoded, fields.valueOffset(1), fields.valueLength(1)));
        assertEquals("content-length", text(decoded, fields.nameOffset(2), fields.nameLength(2)));
        assertEquals("42", text(decoded, fields.valueOffset(2), fields.valueLength(2)));
        assertEquals(48, decodedLength);
    }

    @Test
    void writesLiteralStatusWhenStaticTableHasNoExactValue() {
        byte[] encoded = new byte[128];
        int length = HpackEncoder.writeResponseHeaders(
            MemorySegment.ofArray(encoded), 0, 431, "text/plain", 0);

        byte[] decoded = new byte[128];
        HpackFields fields = new HpackFields(8);
        new HpackDecoder(0, 1024).decode(
            MemorySegment.ofArray(encoded), 0, length,
            MemorySegment.ofArray(decoded), fields);

        assertEquals("431", text(decoded, fields.valueOffset(0), fields.valueLength(0)));
    }

    @Test
    void omitsContentLengthForUnknownStreamingResponse() {
        byte[] encoded = new byte[128];
        int length = HpackEncoder.writeResponseHeaders(
            MemorySegment.ofArray(encoded), 0,
            200, "application/octet-stream", -1);

        byte[] decoded = new byte[128];
        HpackFields fields = new HpackFields(8);
        new HpackDecoder(0, 1024).decode(
            MemorySegment.ofArray(encoded), 0, length,
            MemorySegment.ofArray(decoded), fields);

        assertEquals(2, fields.count());
        assertEquals(":status", text(
            decoded, fields.nameOffset(0), fields.nameLength(0)));
        assertEquals("content-type", text(
            decoded, fields.nameOffset(1), fields.nameLength(1)));
        assertEquals("application/octet-stream", text(
            decoded, fields.valueOffset(1), fields.valueLength(1)));
    }

    @Test
    void writesCustomResponseFieldsWithoutDynamicState() {
        byte[] encoded = new byte[256];
        ResponseHeaders headers = ResponseHeaders.builder()
            .add("Cache-Control", "public, max-age=60")
            .add("x-cardigan", "yes")
            .build();
        int length = HpackEncoder.writeResponseHeaders(
            MemorySegment.ofArray(encoded), 0,
            200, "text/plain", 3, headers);

        byte[] decoded = new byte[256];
        HpackFields fields = new HpackFields(8);
        new HpackDecoder(0, 1024).decode(
            MemorySegment.ofArray(encoded), 0, length,
            MemorySegment.ofArray(decoded), fields);

        assertEquals(5, fields.count());
        assertEquals("cache-control", text(
            decoded, fields.nameOffset(3), fields.nameLength(3)));
        assertEquals("public, max-age=60", text(
            decoded, fields.valueOffset(3), fields.valueLength(3)));
        assertEquals("x-cardigan", text(
            decoded, fields.nameOffset(4), fields.nameLength(4)));
        assertEquals("yes", text(
            decoded, fields.valueOffset(4), fields.valueLength(4)));
    }

    private static String text(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }
}
