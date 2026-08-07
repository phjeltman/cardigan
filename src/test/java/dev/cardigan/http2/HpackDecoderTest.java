// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http2;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HpackDecoderTest {
    private static final String[][] FIRST_REQUEST = {
        {":method", "GET"},
        {":scheme", "http"},
        {":path", "/"},
        {":authority", "www.example.com"}
    };
    private static final String[][] SECOND_REQUEST = {
        {":method", "GET"},
        {":scheme", "http"},
        {":path", "/"},
        {":authority", "www.example.com"},
        {"cache-control", "no-cache"}
    };
    private static final String[][] THIRD_REQUEST = {
        {":method", "GET"},
        {":scheme", "https"},
        {":path", "/index.html"},
        {":authority", "www.example.com"},
        {"custom-key", "custom-value"}
    };

    @Test
    void decodesRfcRequestsWithoutHuffmanAndMaintainsDynamicTable() {
        HpackDecoder decoder = new HpackDecoder(4096, 16 * 1024);
        HpackFields fields = new HpackFields(32);
        byte[] output = new byte[16 * 1024];

        assertBlock(decoder, fields, output,
                    "828684410f7777772e6578616d706c652e636f6d", FIRST_REQUEST);
        assertEquals(1, decoder.dynamicTableEntryCount());
        assertEquals(57, decoder.dynamicTableSize());

        assertBlock(decoder, fields, output,
                    "828684be58086e6f2d6361636865", SECOND_REQUEST);
        assertEquals(2, decoder.dynamicTableEntryCount());
        assertEquals(110, decoder.dynamicTableSize());

        assertBlock(decoder, fields, output,
                    "828785bf400a637573746f6d2d6b65790c637573746f6d2d76616c7565",
                    THIRD_REQUEST);
        assertEquals(3, decoder.dynamicTableEntryCount());
        assertEquals(164, decoder.dynamicTableSize());
    }

    @Test
    void decodesRfcRequestsWithHuffmanAndMaintainsDynamicTable() {
        HpackDecoder decoder = new HpackDecoder(4096, 16 * 1024);
        HpackFields fields = new HpackFields(32);
        byte[] output = new byte[16 * 1024];

        assertBlock(decoder, fields, output,
                    "828684418cf1e3c2e5f23a6ba0ab90f4ff", FIRST_REQUEST);
        assertBlock(decoder, fields, output,
                    "828684be5886a8eb10649cbf", SECOND_REQUEST);
        assertBlock(decoder, fields, output,
                    "828785bf408825a849e95ba97d7f8925a849e95bb8e8b4bf", THIRD_REQUEST);
    }

    @Test
    void appliesDynamicTableSizeUpdatesAndEvictsEntries() {
        HpackDecoder decoder = new HpackDecoder(4096, 16 * 1024);
        HpackFields fields = new HpackFields(8);
        byte[] output = new byte[1024];

        assertBlock(decoder, fields, output,
                    "410f7777772e6578616d706c652e636f6d",
                    new String[][] {{":authority", "www.example.com"}});
        assertEquals(1, decoder.dynamicTableEntryCount());

        assertEquals(0, decode(decoder, fields, output, "20"));
        assertEquals(0, decoder.dynamicTableEntryCount());
        assertEquals(0, decoder.dynamicTableSize());
    }

    @Test
    void preservesIndexedNameIdentityThroughDynamicEntries() {
        HpackDecoder decoder = new HpackDecoder(4096, 16 * 1024);
        HpackFields fields = new HpackFields(32);
        byte[] output = new byte[16 * 1024];

        decode(decoder, fields, output, "83");
        assertEquals(HpackFields.NAME_METHOD, fields.nameIndex(0));

        decode(
            decoder,
            fields,
            output,
            "828684410f7777772e6578616d706c652e636f6d"
        );
        assertEquals(HpackFields.NAME_METHOD, fields.nameIndex(0));
        assertEquals(HpackFields.NAME_SCHEME, fields.nameIndex(1));
        assertEquals(HpackFields.NAME_PATH, fields.nameIndex(2));
        assertEquals(HpackFields.NAME_AUTHORITY, fields.nameIndex(3));

        decode(decoder, fields, output, "828684be58086e6f2d6361636865");
        assertEquals(HpackFields.NAME_AUTHORITY, fields.nameIndex(3));
        assertEquals(24, fields.nameIndex(4));

        decode(
            decoder,
            fields,
            output,
            "828785bf400a637573746f6d2d6b65790c637573746f6d2d76616c7565"
        );
        assertEquals(HpackFields.NAME_LITERAL, fields.nameIndex(4));
    }

    @Test
    void compactRequestDecodeOmitsUnobservedPseudoHeaderBytes() {
        HpackDecoder decoder = new HpackDecoder(4096, 16 * 1024);
        HpackFields fields = new HpackFields(32);
        byte[] output = new byte[16 * 1024];

        int firstLength = decodeRequest(
            decoder,
            fields,
            output,
            "828684410f7777772e6578616d706c652e636f6d"
        );
        assertEquals("GET", fieldText(decoder, fields, output, 0, false));
        assertEquals("/", fieldText(decoder, fields, output, 2, false));
        assertEquals(":authority", fieldText(decoder, fields, output, 3, true));
        assertEquals(
            "www.example.com", fieldText(decoder, fields, output, 3, false));
        org.junit.jupiter.api.Assertions.assertTrue(firstLength < 52);

        int secondLength = decodeRequest(
            decoder,
            fields,
            output,
            "828684be58086e6f2d6361636865"
        );
        assertEquals("GET", fieldText(decoder, fields, output, 0, false));
        assertEquals("/", fieldText(decoder, fields, output, 2, false));
        assertEquals(
            "cache-control", fieldText(decoder, fields, output, 4, true));
        assertEquals(
            "no-cache", fieldText(decoder, fields, output, 4, false));
        org.junit.jupiter.api.Assertions.assertTrue(secondLength < firstLength);
    }

    @Test
    void compactReferencesAreSpilledBeforeDynamicEntryEviction() {
        HpackDecoder decoder = new HpackDecoder(64, 1024);
        HpackFields fields = new HpackFields(8);
        byte[] output = new byte[1024];

        assertEquals(1, decodeRequest(
            decoder, fields, output, "580161"));
        int decodedLength = decodeRequest(
            decoder, fields, output, "be530162");

        assertEquals("cache-control",
            fieldText(decoder, fields, output, 0, true));
        assertEquals("a",
            fieldText(decoder, fields, output, 0, false));
        assertEquals("accept",
            fieldText(decoder, fields, output, 1, true));
        assertEquals("b",
            fieldText(decoder, fields, output, 1, false));
        org.junit.jupiter.api.Assertions.assertTrue(decodedLength > 1);
        assertEquals(HpackFields.SOURCE_OUTPUT, fields.nameSource(0));
        assertEquals(HpackFields.SOURCE_OUTPUT, fields.valueSource(0));
    }

    @Test
    void nativeCompactDecodeSupportsOffsetsHuffmanAndDynamicReferences() {
        byte[] first = hex(
            "828684418cf1e3c2e5f23a6ba0ab90f4ff");
        byte[] second = hex(
            "828684be5886a8eb10649cbf");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment source = arena.allocate(first.length + 4);
            MemorySegment output = arena.allocate(16 * 1024);
            HpackDecoder decoder = new HpackDecoder(4096, 16 * 1024);
            HpackFields fields = new HpackFields(32);
            MemorySegment.copy(
                MemorySegment.ofArray(first), 0,
                source, 2, first.length);

            int firstLength = decoder.decodeRequest(
                source, 2, first.length, output, fields);
            assertTrue(firstLength >= 0);
            assertEquals(
                "www.example.com",
                fieldText(decoder, fields, output, 3, false));

            source = arena.allocate(second.length);
            MemorySegment.copy(
                MemorySegment.ofArray(second), 0,
                source, 0, second.length);
            int secondLength = decoder.decodeRequest(
                source, 0, second.length, output, fields);
            assertTrue(secondLength >= 0);
            assertEquals(
                "www.example.com",
                fieldText(decoder, fields, output, 3, false));
            assertEquals(
                "no-cache",
                fieldText(decoder, fields, output, 4, false));
        }
    }

    @Test
    void rejectsCompressionErrorsAndEnforcesCallerLimits() {
        HpackFields fields = new HpackFields(8);
        byte[] output = new byte[1024];

        HpackDecoder decoder = new HpackDecoder(4096, 16 * 1024);
        assertEquals(HpackDecoder.ERROR_COMPRESSION,
                     decode(decoder, fields, output, "80"));
        assertEquals(HpackDecoder.ERROR_COMPRESSION,
                     decode(decoder, fields, output, "8220"));
        assertEquals(HpackDecoder.ERROR_COMPRESSION,
                     decode(decoder, fields, output, "3fe21f"));

        HpackDecoder limitedHeaders = new HpackDecoder(4096, 41);
        assertEquals(HpackDecoder.ERROR_HEADER_LIST_SIZE,
                     decode(limitedHeaders, fields, output, "82"));

        HpackDecoder limitedOutput = new HpackDecoder(4096, 16 * 1024);
        assertEquals(HpackDecoder.ERROR_OUTPUT_SIZE,
                     decode(limitedOutput, fields, new byte[2], "82"));
    }

    @Test
    void decodesNghttp2CurlRequestBlock() {
        HpackDecoder decoder = new HpackDecoder(4096, 16 * 1024);
        HpackFields fields = new HpackFields(32);
        byte[] output = new byte[16 * 1024];

        assertBlock(
            decoder,
            fields,
            output,
            "82048762d416c430d09986418a089d5c0b8170dc780f037a8825b650c3abbc15c153032a2f2a",
            new String[][] {
                {":method", "GET"},
                {":path", "/users/423"},
                {":scheme", "http"},
                {":authority", "127.0.0.1:8080"},
                {"user-agent", "curl/7.81.0"},
                {"accept", "*/*"}
            }
        );
    }

    private static void assertBlock(HpackDecoder decoder, HpackFields fields, byte[] output,
                                    String encoded, String[][] expected) {
        int length = decode(decoder, fields, output, encoded);
        org.junit.jupiter.api.Assertions.assertTrue(length >= 0, "decode result: " + length);
        assertEquals(expected.length, fields.count());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], text(output, fields.nameOffset(i), fields.nameLength(i)));
            assertEquals(expected[i][1], text(output, fields.valueOffset(i), fields.valueLength(i)));
        }
        int expectedLength = 0;
        for (String[] field : expected) {
            expectedLength += field[0].length() + field[1].length();
        }
        assertEquals(expectedLength, length);
    }

    private static int decode(HpackDecoder decoder, HpackFields fields, byte[] output,
                              String encoded) {
        byte[] input = hex(encoded);
        return decoder.decode(MemorySegment.ofArray(input), 0, input.length,
                              MemorySegment.ofArray(output), fields);
    }

    private static int decodeRequest(HpackDecoder decoder, HpackFields fields,
                                     byte[] output, String encoded) {
        byte[] input = hex(encoded);
        return decoder.decodeRequest(
            MemorySegment.ofArray(input),
            0,
            input.length,
            MemorySegment.ofArray(output),
            fields
        );
    }

    private static String text(byte[] output, int offset, int length) {
        return new String(output, offset, length, StandardCharsets.US_ASCII);
    }

    private static String fieldText(
            HpackDecoder decoder, HpackFields fields, byte[] output,
            int fieldIndex, boolean name) {
        return fieldText(
            decoder, fields, MemorySegment.ofArray(output),
            fieldIndex, name);
    }

    private static String fieldText(
            HpackDecoder decoder, HpackFields fields, MemorySegment output,
            int fieldIndex, boolean name) {
        int length = name
            ? fields.nameLength(fieldIndex)
            : fields.valueLength(fieldIndex);
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = name
                ? decoder.fieldNameByte(
                    fields, fieldIndex, i, output)
                : decoder.fieldValueByte(
                    fields, fieldIndex, i, output);
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value, i * 2, i * 2 + 2, 16);
        }
        return result;
    }
}
