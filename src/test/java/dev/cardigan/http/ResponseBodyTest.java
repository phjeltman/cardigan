// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResponseBodyTest {
    @Test
    void exposesAlreadyEncodedBytesWithoutCopying() {
        byte[] body = {1, 2, 3};

        Response response = Response.bytes("application/json", body);

        assertSame(body, response.body());
        assertEquals(Response.CT_JSON, response.contentTypeCode());
    }

    @Test
    void rejectsNullByteBody() {
        assertThrows(
            NullPointerException.class,
            () -> Response.bytes("application/octet-stream", null));
    }

    @Test
    void encodesDecimalLongsDirectly() {
        long[] values = {
            0, 9, 10, -1, Long.MAX_VALUE, Long.MIN_VALUE
        };
        try (Arena arena = Arena.ofConfined()) {
            for (long value : values) {
                Response response = Response.text(value)
                    .withHeader("x-value", "decimal");
                String expected = Long.toString(value);
                var output = arena.allocate(response.asciiLongBodyLength());

                response.writeAsciiLongBody(output);

                assertEquals(expected, response.body());
                assertEquals(expected.length(), output.byteSize());
                assertEquals(
                    expected,
                    new String(
                        output.toArray(ValueLayout.JAVA_BYTE),
                        StandardCharsets.US_ASCII));
            }
        }
    }
}
