// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.httparena;

import dev.cardigan.http.EncodedBody;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpArenaDatabaseResultTest {
    @Test
    void encodesTheCanonicalNestedShapeDirectly() {
        HttpArenaDatabaseResult result = HttpArenaDatabaseResult.of(List.of(
            new HttpArenaDatabaseResult.ItemData(
                42, "Alpha \"Widget\"", "café", 30, 5, true,
                "[\"fast\", \"new\"]", 42, 127),
            new HttpArenaDatabaseResult.ItemData(
                -7, "line\nfeed", "other", -1, 0, false,
                "[]", 0, -12)
        ));

        assertEquals(2, result.count());
        assertEquals(
            "{\"items\":["
                + "{\"id\":42,\"name\":\"Alpha \\\"Widget\\\"\","
                + "\"category\":\"café\",\"price\":30,\"quantity\":5,"
                + "\"active\":true,\"tags\":[\"fast\", \"new\"],"
                + "\"rating\":{\"score\":42,\"count\":127}},"
                + "{\"id\":-7,\"name\":\"line\\nfeed\","
                + "\"category\":\"other\",\"price\":-1,\"quantity\":0,"
                + "\"active\":false,\"tags\":[],"
                + "\"rating\":{\"score\":0,\"count\":-12}}],\"count\":2}",
            encode(result));
    }

    @Test
    void encodesTheRequiredEmptyFallback() {
        assertEquals(
            "{\"items\":[],\"count\":0}",
            encode(HttpArenaDatabaseResult.empty()));
    }

    @Test
    void escapesEveryJsonControlAndMalformedSurrogates() {
        HttpArenaDatabaseResult result = HttpArenaDatabaseResult.of(List.of(
            new HttpArenaDatabaseResult.ItemData(
                Integer.MIN_VALUE,
                "\u0000\b\f\n\r\t\\\"\ud800",
                "emoji 😀", Integer.MAX_VALUE, -1, true,
                "[\"café\",\"😀\"]", -2, Integer.MIN_VALUE)
        ));

        assertEquals(
            "{\"items\":[{\"id\":-2147483648,"
                + "\"name\":\"\\u0000\\b\\f\\n\\r\\t\\\\\\\"?\","
                + "\"category\":\"emoji 😀\",\"price\":2147483647,"
                + "\"quantity\":-1,\"active\":true,"
                + "\"tags\":[\"café\",\"😀\"],"
                + "\"rating\":{\"score\":-2,\"count\":-2147483648}}],"
                + "\"count\":1}",
            encode(result));
    }

    @Test
    void growsPastTheInitialBufferWithoutChangingUtf8() {
        String name = "café 😀 ".repeat(800);
        HttpArenaDatabaseResult result = HttpArenaDatabaseResult.of(List.of(
            new HttpArenaDatabaseResult.ItemData(
                1, name, "large", 2, 3, false,
                "[]", 4, 5)
        ));

        assertEquals(
            "{\"items\":[{\"id\":1,\"name\":\"" + name
                + "\",\"category\":\"large\",\"price\":2,"
                + "\"quantity\":3,\"active\":false,\"tags\":[],"
                + "\"rating\":{\"score\":4,\"count\":5}}],\"count\":1}",
            encode(result));
    }

    private static String encode(HttpArenaDatabaseResult result) {
        EncodedBody body = result.encodedBody();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment destination = arena.allocate(body.length());
            body.write(destination);
            return new String(
                destination.toArray(ValueLayout.JAVA_BYTE),
                StandardCharsets.UTF_8);
        }
    }
}
