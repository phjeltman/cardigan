// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http2;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Http2FramesTest {

    @Test
    void parsesFrameHeaderWithReservedBitMasked() {
        byte[] bytes = {
            0, 0, 5,
            1,
            5,
            (byte) 0x80, 0, 0, 1
        };
        MemorySegment segment = MemorySegment.ofArray(bytes);

        long word = Http2Frames.readHeaderWord(segment, 0);
        assertEquals(5, Http2Frames.payloadLength(word));
        assertEquals(Http2Frames.HEADERS, Http2Frames.type(word));
        assertEquals(Http2Frames.FLAG_END_STREAM | Http2Frames.FLAG_END_HEADERS,
            Http2Frames.flags(word));
        assertEquals(1, Http2Frames.streamId(segment, 0, word));
    }

    @Test
    void roundTripsHeaderBoundariesWithoutAllocatingFrameObjects() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(18);

            Http2Frames.writeHeader(segment, 0, 0, 0, 0, 0);
            assertHeader(segment, 0, 0, 0, 0, 0);

            Http2Frames.writeHeader(segment, 9, Http2Frames.MAX_FRAME_SIZE, 0xff, 0xff,
                Http2Frames.MAX_STREAM_ID);
            assertHeader(segment, 9, Http2Frames.MAX_FRAME_SIZE, 0xff, 0xff,
                Http2Frames.MAX_STREAM_ID);
        }
    }

    @Test
    void detectsPartialHeadersAndPayloadsWithoutOverflow() {
        assertFalse(Http2Frames.hasHeader(0, 8));
        assertTrue(Http2Frames.hasHeader(0, 9));
        assertFalse(Http2Frames.hasFrame(0, 13, 5));
        assertTrue(Http2Frames.hasFrame(0, 14, 5));
        assertFalse(Http2Frames.hasFrame(Long.MAX_VALUE - 4, Long.MAX_VALUE, 0));
        assertFalse(Http2Frames.hasFrame(0, 9, -1));
    }

    @Test
    void writesControlFrames() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(64);
            int offset = 0;
            offset += Http2Frames.writeSettingsAck(segment, offset);
            offset += Http2Frames.writePing(segment, offset, 0x0102_0304_0506_0708L, true);
            offset += Http2Frames.writeWindowUpdate(segment, offset, 3, 65_535);
            offset += Http2Frames.writeRstStream(segment, offset, 3, Http2Frames.CANCEL);

            assertEquals(52, offset);
            long settings = Http2Frames.readHeaderWord(segment, 0);
            assertEquals(Http2Frames.SETTINGS, Http2Frames.type(settings));
            assertEquals(Http2Frames.FLAG_ACK, Http2Frames.flags(settings));

            long ping = Http2Frames.readHeaderWord(segment, 9);
            assertEquals(8, Http2Frames.payloadLength(ping));
            assertEquals(Http2Frames.PING, Http2Frames.type(ping));
            assertEquals(0x0102_0304_0506_0708L,
                segment.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(java.nio.ByteOrder.BIG_ENDIAN), 18));
        }
    }

    @Test
    void rejectsInvalidHeaderAndControlValues() {
        MemorySegment segment = MemorySegment.ofArray(new byte[32]);
        assertThrows(IllegalArgumentException.class,
            () -> Http2Frames.writeHeader(segment, 0, -1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> Http2Frames.writeHeader(segment, 0, 0, 256, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> Http2Frames.writeRstStream(segment, 0, 0, Http2Frames.CANCEL));
        assertThrows(IllegalArgumentException.class,
            () -> Http2Frames.writeWindowUpdate(segment, 0, 0, 0));
    }

    private static void assertHeader(MemorySegment segment, long offset, int length, int type,
                                     int flags, int streamId) {
        long word = Http2Frames.readHeaderWord(segment, offset);
        assertEquals(length, Http2Frames.payloadLength(word));
        assertEquals(type, Http2Frames.type(word));
        assertEquals(flags, Http2Frames.flags(word));
        assertEquals(streamId, Http2Frames.streamId(segment, offset, word));
    }
}
