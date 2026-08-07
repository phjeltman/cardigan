// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http2;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HpackHuffmanTest {
    @Test
    void decodesRfcExample() {
        MemorySegment encoded = MemorySegment.ofArray(
            hex("f1e3c2e5f23a6ba0ab90f4ff"));
        byte[] decoded = new byte[64];

        int length = HpackHuffman.decode(
            encoded, 0, (int) encoded.byteSize(), MemorySegment.ofArray(decoded), 0, decoded.length);

        assertEquals("www.example.com",
                     new String(decoded, 0, length, StandardCharsets.US_ASCII));
    }

    @Test
    void rejectsInvalidPadding() {
        MemorySegment encoded = MemorySegment.ofArray(hex("18"));
        byte[] decoded = new byte[8];

        assertEquals(-1, HpackHuffman.decode(
            encoded, 0, 1, MemorySegment.ofArray(decoded), 0, decoded.length));
    }

    @Test
    void detectsOutputOverflow() {
        MemorySegment encoded = MemorySegment.ofArray(
            hex("f1e3c2e5f23a6ba0ab90f4ff"));
        byte[] decoded = new byte[2];

        assertEquals(-2, HpackHuffman.decode(
            encoded, 0, (int) encoded.byteSize(), MemorySegment.ofArray(decoded), 0, decoded.length));
    }

    @Test
    void decodesNghttp2UserAgent() {
        MemorySegment encoded = MemorySegment.ofArray(hex("25b650c3abbc15c1"));
        byte[] decoded = new byte[32];

        int length = HpackHuffman.decode(
            encoded, 0, (int) encoded.byteSize(), MemorySegment.ofArray(decoded), 0, decoded.length);

        assertEquals("curl/7.81.0",
            new String(decoded, 0, length, StandardCharsets.US_ASCII));
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value, i * 2, i * 2 + 2, 16);
        }
        return result;
    }
}
