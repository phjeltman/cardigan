// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import org.junit.jupiter.api.Test;

import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticBodyTest {
    @Test
    void ownsAnImmutableNativeCopy() {
        byte[] source = "static bytes".getBytes(StandardCharsets.US_ASCII);
        StaticBody body = StaticBody.copyOf(source);
        source[0] = 'X';

        assertEquals(source.length, body.length());
        assertTrue(body.segment().isNative());
        assertTrue(body.segment().isReadOnly());
        assertEquals(
            (byte) 's',
            body.segment().get(ValueLayout.JAVA_BYTE, 0)
        );
    }
}
