// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EncodedBodyTest {
    @Test
    void writesExactlyTheDeclaredLength() {
        byte[] expected = {3, 1, 4, 1, 5};
        EncodedBody body = EncodedBody.of(expected.length, destination -> {
            MemorySegment.copy(
                expected, 0,
                destination, ValueLayout.JAVA_BYTE, 0, expected.length);
            return expected.length;
        });

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment destination = arena.allocate(expected.length);
            body.write(destination);
            assertEquals(expected.length, body.length());
            assertArrayEquals(
                expected, destination.toArray(ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    void rejectsInvalidLengthsAndEncoderResults() {
        assertThrows(
            IllegalArgumentException.class,
            () -> EncodedBody.of(-1, destination -> 0));

        EncodedBody body = EncodedBody.of(4, destination -> 3);
        try (Arena arena = Arena.ofConfined()) {
            assertThrows(
                IllegalArgumentException.class,
                () -> body.write(arena.allocate(3)));
            assertThrows(
                IllegalStateException.class,
                () -> body.write(arena.allocate(4)));
        }
    }
}
