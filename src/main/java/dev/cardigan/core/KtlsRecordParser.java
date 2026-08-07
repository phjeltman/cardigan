// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Decodes the packed result produced by multishot {@code RECVMSG}. */
final class KtlsRecordParser {
    static final int OUT_SIZE = 16;
    static final int CONTROL_RESERVE = 48;
    static final int PAYLOAD_OFFSET = OUT_SIZE + CONTROL_RESERVE;

    static final int CHANGE_CIPHER_SPEC = 20;
    static final int ALERT = 21;
    static final int HANDSHAKE = 22;
    static final int APPLICATION_DATA = 23;

    static final long INVALID = -1L;

    private static final int SOL_TLS = 282;
    private static final int TLS_GET_RECORD_TYPE = 2;
    private static final int MSG_CTRUNC = 0x08;
    private static final int MSG_TRUNC = 0x20;
    private static final int CMSG_HEADER_SIZE = 16;
    private static final int CMSG_LENGTH = CMSG_HEADER_SIZE + 1;
    private static final int CMSG_SPACE = 24;

    private KtlsRecordParser() {
    }

    /**
     * Returns the record type in the high word and the usable payload length
     * in the low word. No allocation is performed on the completion path.
     */
    static long decode(MemorySegment buffer, int result) {
        if (result < PAYLOAD_OFFSET || result > buffer.byteSize()) {
            return INVALID;
        }

        int controlLength = buffer.get(ValueLayout.JAVA_INT, 4);
        int payloadLength = buffer.get(ValueLayout.JAVA_INT, 8);
        int flags = buffer.get(ValueLayout.JAVA_INT, 12);
        int copiedPayload = result - PAYLOAD_OFFSET;
        if (controlLength < 0 || controlLength > CONTROL_RESERVE
                || payloadLength < 0 || payloadLength != copiedPayload
                || (flags & (MSG_CTRUNC | MSG_TRUNC)) != 0) {
            return INVALID;
        }

        int recordType = APPLICATION_DATA;
        if (controlLength != 0) {
            if (controlLength < CMSG_LENGTH) {
                return INVALID;
            }
            long cmsgLength = buffer.get(ValueLayout.JAVA_LONG, OUT_SIZE);
            int cmsgLevel = buffer.get(
                ValueLayout.JAVA_INT, OUT_SIZE + 8L);
            int cmsgType = buffer.get(
                ValueLayout.JAVA_INT, OUT_SIZE + 12L);
            if (cmsgLength != CMSG_LENGTH
                    || controlLength != CMSG_SPACE
                    || cmsgLevel != SOL_TLS
                    || cmsgType != TLS_GET_RECORD_TYPE) {
                return INVALID;
            }
            recordType = buffer.get(
                ValueLayout.JAVA_BYTE, OUT_SIZE + CMSG_HEADER_SIZE) & 0xff;
        }

        return ((long) recordType << 32) | (payloadLength & 0xffff_ffffL);
    }

    static int recordType(long decoded) {
        return (int) (decoded >>> 32);
    }

    static int payloadLength(long decoded) {
        return (int) decoded;
    }
}
