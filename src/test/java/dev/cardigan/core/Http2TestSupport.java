// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http2.Http2Frames;
import java.io.InputStream;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Shared wire helpers for live HTTP/2 integration tests. */
final class Http2TestSupport {
    private Http2TestSupport() {
    }

    static byte[] frame(
            int type, int flags, int streamId, byte[] payload) {
        byte[] bytes = new byte[Http2Frames.HEADER_SIZE + payload.length];
        MemorySegment segment = MemorySegment.ofArray(bytes);
        Http2Frames.writeHeader(
            segment, 0, payload.length, type, flags, streamId);
        MemorySegment.copy(
            MemorySegment.ofArray(payload), 0,
            segment, Http2Frames.HEADER_SIZE, payload.length);
        return bytes;
    }

    static Frame readFrame(InputStream input) throws Exception {
        Frame frame = readFrameOrEof(input);
        if (frame == null) {
            throw new AssertionError("EOF before HTTP/2 frame");
        }
        return frame;
    }

    static Frame readFrameOrEof(InputStream input) throws Exception {
        byte[] header = input.readNBytes(Http2Frames.HEADER_SIZE);
        if (header.length == 0) {
            return null;
        }
        assertEquals(Http2Frames.HEADER_SIZE, header.length);
        MemorySegment segment = MemorySegment.ofArray(header);
        long word = Http2Frames.readHeaderWord(segment, 0);
        int length = Http2Frames.payloadLength(word);
        byte[] payload = input.readNBytes(length);
        assertEquals(length, payload.length);
        return new Frame(
            Http2Frames.type(word),
            Http2Frames.flags(word),
            Http2Frames.streamId(segment, 0, word),
            payload
        );
    }

    static final class Frame {
        final int type;
        final int flags;
        final int streamId;
        final byte[] payload;

        Frame(int type, int flags, int streamId, byte[] payload) {
            this.type = type;
            this.flags = flags;
            this.streamId = streamId;
            this.payload = payload;
        }

        int type() {
            return type;
        }

        int flags() {
            return flags;
        }

        int streamId() {
            return streamId;
        }

        byte[] payload() {
            return payload;
        }
    }
}
