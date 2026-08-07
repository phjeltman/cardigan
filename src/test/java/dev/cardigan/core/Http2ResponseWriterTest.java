// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class Http2ResponseWriterTest {
    @Test
    void exposesCompactAsciiBodiesWithoutEncoding() {
        assertAscii("");
        assertAscii("1234567");
        assertAscii("12345678");
        assertAscii("User details for ID: 423 parsed directly off-heap!");
    }

    @Test
    void rejectsLatin1AndUtf16BodiesThatRequireUtf8Encoding() {
        assertNull(Http2ResponseWriter.asciiBytes("café"));
        assertNull(Http2ResponseWriter.asciiBytes("snowman \u2603"));
    }

    private static void assertAscii(String value) {
        assertArrayEquals(
            value.getBytes(StandardCharsets.US_ASCII),
            Http2ResponseWriter.asciiBytes(value));
    }
}
