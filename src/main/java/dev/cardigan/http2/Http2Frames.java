// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http2;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Allocation-free HTTP/2 frame header and control-frame codec.
 */
public final class Http2Frames {
    public static final int HEADER_SIZE = 9;
    public static final int DEFAULT_MAX_FRAME_SIZE = 16_384;
    public static final int MAX_FRAME_SIZE = 0x00ff_ffff;
    public static final int MAX_STREAM_ID = 0x7fff_ffff;

    public static final int DATA = 0x00;
    public static final int HEADERS = 0x01;
    public static final int PRIORITY = 0x02;
    public static final int RST_STREAM = 0x03;
    public static final int SETTINGS = 0x04;
    public static final int PUSH_PROMISE = 0x05;
    public static final int PING = 0x06;
    public static final int GOAWAY = 0x07;
    public static final int WINDOW_UPDATE = 0x08;
    public static final int CONTINUATION = 0x09;

    public static final int FLAG_END_STREAM = 0x01;
    public static final int FLAG_ACK = 0x01;
    public static final int FLAG_END_HEADERS = 0x04;
    public static final int FLAG_PADDED = 0x08;
    public static final int FLAG_PRIORITY = 0x20;

    public static final int SETTINGS_HEADER_TABLE_SIZE = 0x01;
    public static final int SETTINGS_ENABLE_PUSH = 0x02;
    public static final int SETTINGS_MAX_CONCURRENT_STREAMS = 0x03;
    public static final int SETTINGS_INITIAL_WINDOW_SIZE = 0x04;
    public static final int SETTINGS_MAX_FRAME_SIZE = 0x05;
    public static final int SETTINGS_MAX_HEADER_LIST_SIZE = 0x06;

    public static final int NO_ERROR = 0x00;
    public static final int PROTOCOL_ERROR = 0x01;
    public static final int INTERNAL_ERROR = 0x02;
    public static final int FLOW_CONTROL_ERROR = 0x03;
    public static final int SETTINGS_TIMEOUT = 0x04;
    public static final int STREAM_CLOSED = 0x05;
    public static final int FRAME_SIZE_ERROR = 0x06;
    public static final int REFUSED_STREAM = 0x07;
    public static final int CANCEL = 0x08;
    public static final int COMPRESSION_ERROR = 0x09;
    public static final int CONNECT_ERROR = 0x0a;
    public static final int ENHANCE_YOUR_CALM = 0x0b;
    public static final int INADEQUATE_SECURITY = 0x0c;
    public static final int HTTP_1_1_REQUIRED = 0x0d;

    public static final byte[] CLIENT_PREFACE =
        "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static final ValueLayout.OfLong LONG_BE =
        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfInt INT_BE =
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private Http2Frames() {
    }

    public static boolean hasHeader(long offset, long limit) {
        return offset >= 0 && limit >= offset && limit - offset >= HEADER_SIZE;
    }

    public static boolean hasFrame(long offset, long limit, int payloadLength) {
        return payloadLength >= 0
            && hasHeader(offset, limit)
            && (long) payloadLength <= limit - offset - HEADER_SIZE;
    }

    /**
     * Reads bytes 0-7 of a frame header. Callers extract all fields except the
     * low stream-ID byte from this word and read byte 8 only for the stream ID.
     */
    public static long readHeaderWord(MemorySegment source, long offset) {
        return source.get(LONG_BE, offset);
    }

    public static int payloadLength(long headerWord) {
        return (int) ((headerWord >>> 40) & 0x00ff_ffffL);
    }

    public static int type(long headerWord) {
        return (int) ((headerWord >>> 32) & 0xffL);
    }

    public static int flags(long headerWord) {
        return (int) ((headerWord >>> 24) & 0xffL);
    }

    public static int streamId(MemorySegment source, long offset, long headerWord) {
        int high = (int) (headerWord & 0x00ff_ffffL);
        int low = source.get(ValueLayout.JAVA_BYTE, offset + 8) & 0xff;
        return ((high << 8) | low) & MAX_STREAM_ID;
    }

    public static void writeHeader(MemorySegment destination, long offset, int payloadLength,
                                   int type, int flags, int streamId) {
        checkUnsigned("payload length", payloadLength, MAX_FRAME_SIZE);
        checkUnsigned("type", type, 0xff);
        checkUnsigned("flags", flags, 0xff);
        checkUnsigned("stream ID", streamId, MAX_STREAM_ID);

        long word = ((long) payloadLength << 40)
            | ((long) type << 32)
            | ((long) flags << 24)
            | ((streamId >>> 8) & 0x00ff_ffffL);
        destination.set(LONG_BE, offset, word);
        destination.set(ValueLayout.JAVA_BYTE, offset + 8, (byte) streamId);
    }

    public static int writeSettingsAck(MemorySegment destination, long offset) {
        writeHeader(destination, offset, 0, SETTINGS, FLAG_ACK, 0);
        return HEADER_SIZE;
    }

    public static int writeSetting(MemorySegment destination, long offset, int identifier, int value) {
        checkUnsigned("setting identifier", identifier, 0xffff);
        writeHeader(destination, offset, 6, SETTINGS, 0, 0);
        destination.set(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN),
            offset + HEADER_SIZE, (short) identifier);
        destination.set(INT_BE, offset + HEADER_SIZE + 2, value);
        return HEADER_SIZE + 6;
    }

    public static int writePing(MemorySegment destination, long offset, long opaqueData, boolean ack) {
        writeHeader(destination, offset, 8, PING, ack ? FLAG_ACK : 0, 0);
        destination.set(LONG_BE, offset + HEADER_SIZE, opaqueData);
        return HEADER_SIZE + 8;
    }

    public static int writeRstStream(MemorySegment destination, long offset, int streamId, int error) {
        if (streamId == 0) {
            throw new IllegalArgumentException("RST_STREAM requires a non-zero stream ID");
        }
        writeHeader(destination, offset, 4, RST_STREAM, 0, streamId);
        destination.set(INT_BE, offset + HEADER_SIZE, error);
        return HEADER_SIZE + 4;
    }

    public static int writeWindowUpdate(MemorySegment destination, long offset, int streamId,
                                        int increment) {
        if (increment <= 0 || increment > MAX_STREAM_ID) {
            throw new IllegalArgumentException("Invalid flow-control increment: " + increment);
        }
        writeHeader(destination, offset, 4, WINDOW_UPDATE, 0, streamId);
        destination.set(INT_BE, offset + HEADER_SIZE, increment);
        return HEADER_SIZE + 4;
    }

    public static int writeGoAway(MemorySegment destination, long offset, int lastStreamId, int error) {
        writeHeader(destination, offset, 8, GOAWAY, 0, 0);
        destination.set(INT_BE, offset + HEADER_SIZE, lastStreamId & MAX_STREAM_ID);
        destination.set(INT_BE, offset + HEADER_SIZE + 4, error);
        return HEADER_SIZE + 8;
    }

    private static void checkUnsigned(String name, int value, int maximum) {
        if (value < 0 || value > maximum) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
    }
}
