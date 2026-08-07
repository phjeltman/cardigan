// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KtlsRecordParserTest {
    private static final int PAYLOAD = KtlsRecordParser.PAYLOAD_OFFSET;

    @Test
    void decodesApplicationDataWithoutControlMessage() {
        MemorySegment buffer = buffer(7);

        long decoded = KtlsRecordParser.decode(buffer, PAYLOAD + 7);

        assertEquals(KtlsRecordParser.APPLICATION_DATA,
            KtlsRecordParser.recordType(decoded));
        assertEquals(7, KtlsRecordParser.payloadLength(decoded));
    }

    @Test
    void decodesTlsRecordTypeControlMessage() {
        MemorySegment buffer = buffer(2);
        putRecordType(buffer, KtlsRecordParser.ALERT);

        long decoded = KtlsRecordParser.decode(buffer, PAYLOAD + 2);

        assertEquals(KtlsRecordParser.ALERT,
            KtlsRecordParser.recordType(decoded));
        assertEquals(2, KtlsRecordParser.payloadLength(decoded));
    }

    @Test
    void rejectsTruncatedAndMalformedResults() {
        MemorySegment truncated = buffer(3);
        truncated.set(ValueLayout.JAVA_INT, 12, 0x20);
        assertEquals(KtlsRecordParser.INVALID,
            KtlsRecordParser.decode(truncated, PAYLOAD + 3));

        MemorySegment malformed = buffer(2);
        putRecordType(malformed, KtlsRecordParser.ALERT);
        malformed.set(ValueLayout.JAVA_INT, 28, 99);
        assertEquals(KtlsRecordParser.INVALID,
            KtlsRecordParser.decode(malformed, PAYLOAD + 2));

        assertEquals(KtlsRecordParser.INVALID,
            KtlsRecordParser.decode(buffer(1), PAYLOAD));
    }

    private static MemorySegment buffer(int payloadLength) {
        int byteSize = PAYLOAD + payloadLength;
        MemorySegment buffer = MemorySegment.ofArray(
            new long[(byteSize + Long.BYTES - 1) / Long.BYTES]);
        buffer.set(ValueLayout.JAVA_INT, 8, payloadLength);
        return buffer;
    }

    private static void putRecordType(MemorySegment buffer, int recordType) {
        buffer.set(ValueLayout.JAVA_INT, 4, 24);
        buffer.set(ValueLayout.JAVA_LONG, 16, 17);
        buffer.set(ValueLayout.JAVA_INT, 24, 282);
        buffer.set(ValueLayout.JAVA_INT, 28, 2);
        buffer.set(ValueLayout.JAVA_BYTE, 32, (byte) recordType);
    }
}
