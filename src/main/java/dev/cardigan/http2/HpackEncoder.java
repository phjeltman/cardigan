// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http2;

import dev.cardigan.http.ResponseHeaders;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Stateless HPACK encoder for response metadata. It deliberately uses only the
 * static table and non-indexed literals, so concurrent stream completions do
 * not need to coordinate a dynamic encoder table.
 */
public final class HpackEncoder {
    private static final int STATIC_STATUS_200 = 8;
    private static final int STATIC_STATUS_204 = 9;
    private static final int STATIC_STATUS_206 = 10;
    private static final int STATIC_STATUS_304 = 11;
    private static final int STATIC_STATUS_400 = 12;
    private static final int STATIC_STATUS_404 = 13;
    private static final int STATIC_STATUS_500 = 14;
    private static final int STATIC_CONTENT_LENGTH = 28;
    private static final int STATIC_CONTENT_TYPE = 31;
    private static final byte[] RESPONSE_200_TEXT_BYTES =
        response200Prefix("text/plain");
    private static final byte[] RESPONSE_200_JSON_BYTES =
        response200Prefix("application/json");
    private static final MemorySegment RESPONSE_200_TEXT =
        MemorySegment.ofArray(RESPONSE_200_TEXT_BYTES);
    private static final MemorySegment RESPONSE_200_JSON =
        MemorySegment.ofArray(RESPONSE_200_JSON_BYTES);

    private HpackEncoder() {
    }

    public static int writeResponseHeaders(MemorySegment destination, int offset,
                                           int statusCode, String contentType,
                                           int contentLength) {
        int start = offset;
        MemorySegment commonPrefix = null;
        int commonPrefixLength = 0;
        if (statusCode == 200) {
            if ("text/plain".equals(contentType)) {
                commonPrefix = RESPONSE_200_TEXT;
                commonPrefixLength = RESPONSE_200_TEXT_BYTES.length;
            } else if ("application/json".equals(contentType)) {
                commonPrefix = RESPONSE_200_JSON;
                commonPrefixLength = RESPONSE_200_JSON_BYTES.length;
            }
        }
        if (commonPrefix != null && contentLength >= 0) {
            MemorySegment.copy(
                commonPrefix, 0, destination, offset, commonPrefixLength);
            offset += commonPrefixLength;
            offset = writeDecimalString(destination, offset, contentLength);
            return offset - start;
        }

        int statusIndex = switch (statusCode) {
            case 200 -> STATIC_STATUS_200;
            case 204 -> STATIC_STATUS_204;
            case 206 -> STATIC_STATUS_206;
            case 304 -> STATIC_STATUS_304;
            case 400 -> STATIC_STATUS_400;
            case 404 -> STATIC_STATUS_404;
            case 500 -> STATIC_STATUS_500;
            default -> 0;
        };
        if (statusIndex != 0) {
            destination.set(ValueLayout.JAVA_BYTE, offset++, (byte) (0x80 | statusIndex));
        } else {
            offset = writeInteger(destination, offset, STATIC_STATUS_200, 4, 0);
            destination.set(ValueLayout.JAVA_BYTE, offset++, (byte) 3);
            destination.set(ValueLayout.JAVA_BYTE, offset++, (byte) ('0' + statusCode / 100 % 10));
            destination.set(ValueLayout.JAVA_BYTE, offset++, (byte) ('0' + statusCode / 10 % 10));
            destination.set(ValueLayout.JAVA_BYTE, offset++, (byte) ('0' + statusCode % 10));
        }

        if (contentType != null && !contentType.isEmpty()) {
            offset = writeInteger(destination, offset, STATIC_CONTENT_TYPE, 4, 0);
            offset = writeAsciiString(destination, offset, contentType);
        }
        if (contentLength >= 0) {
            offset = writeInteger(
                destination, offset, STATIC_CONTENT_LENGTH, 4, 0);
            offset = writeDecimalString(
                destination, offset, contentLength);
        }
        return offset - start;
    }

    public static int writeResponseHeaders(
            MemorySegment destination,
            int offset,
            int statusCode,
            String contentType,
            int contentLength,
            ResponseHeaders headers) {
        int start = offset;
        offset += writeResponseHeaders(
            destination, offset, statusCode, contentType, contentLength);
        offset += writeFields(destination, offset, headers);
        return offset - start;
    }

    public static int writeFields(
            MemorySegment destination,
            int offset,
            ResponseHeaders fields) {
        int start = offset;
        for (int index = 0; index < fields.size(); index++) {
            // Literal Header Field without Indexing with a new name.
            destination.set(ValueLayout.JAVA_BYTE, offset++, (byte) 0);
            offset = writeAsciiString(
                destination, offset, fields.name(index));
            offset = writeAsciiString(
                destination, offset, fields.value(index));
        }
        return offset - start;
    }

    private static byte[] response200Prefix(String contentType) {
        byte[] value =
            contentType.getBytes(StandardCharsets.US_ASCII);
        byte[] prefix = new byte[1 + 2 + 1 + value.length + 2];
        int offset = 0;
        prefix[offset++] = (byte) (0x80 | STATIC_STATUS_200);
        prefix[offset++] = 0x0f;
        prefix[offset++] =
            (byte) (STATIC_CONTENT_TYPE - 0x0f);
        prefix[offset++] = (byte) value.length;
        System.arraycopy(value, 0, prefix, offset, value.length);
        offset += value.length;
        prefix[offset++] = 0x0f;
        prefix[offset] =
            (byte) (STATIC_CONTENT_LENGTH - 0x0f);
        return prefix;
    }

    static int writeInteger(MemorySegment destination, int offset, int value,
                            int prefixBits, int prefixPattern) {
        int maximumPrefix = (1 << prefixBits) - 1;
        if (value < maximumPrefix) {
            destination.set(ValueLayout.JAVA_BYTE, offset++,
                            (byte) (prefixPattern | value));
            return offset;
        }

        destination.set(ValueLayout.JAVA_BYTE, offset++,
                        (byte) (prefixPattern | maximumPrefix));
        value -= maximumPrefix;
        while (value >= 128) {
            destination.set(ValueLayout.JAVA_BYTE, offset++,
                            (byte) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        destination.set(ValueLayout.JAVA_BYTE, offset++, (byte) value);
        return offset;
    }

    private static int writeAsciiString(MemorySegment destination, int offset, String value) {
        offset = writeInteger(destination, offset, value.length(), 7, 0);
        for (int i = 0; i < value.length(); i++) {
            destination.set(ValueLayout.JAVA_BYTE, offset++, (byte) value.charAt(i));
        }
        return offset;
    }

    private static int writeDecimalString(MemorySegment destination, int offset, int value) {
        int digits = value == 0 ? 1 : decimalDigits(value);
        offset = writeInteger(destination, offset, digits, 7, 0);
        int end = offset + digits;
        int cursor = end;
        do {
            destination.set(ValueLayout.JAVA_BYTE, --cursor, (byte) ('0' + value % 10));
            value /= 10;
        } while (cursor > offset);
        return end;
    }

    private static int decimalDigits(int value) {
        if (value < 10) return 1;
        if (value < 100) return 2;
        if (value < 1_000) return 3;
        if (value < 10_000) return 4;
        if (value < 100_000) return 5;
        if (value < 1_000_000) return 6;
        if (value < 10_000_000) return 7;
        if (value < 100_000_000) return 8;
        if (value < 1_000_000_000) return 9;
        return 10;
    }
}
