// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.httparena;

import dev.cardigan.http.EncodedBody;
import org.postgresql.client.core.PgResultStream;
import org.postgresql.client.core.Row;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Query rows materialized as their final JSON representation. */
final class HttpArenaDatabaseResult {
    private static final byte[] ROOT_PREFIX = ascii("{\"items\":[");
    private static final byte[] ID = ascii("{\"id\":");
    private static final byte[] NAME = ascii(",\"name\":");
    private static final byte[] CATEGORY = ascii(",\"category\":");
    private static final byte[] PRICE = ascii(",\"price\":");
    private static final byte[] QUANTITY = ascii(",\"quantity\":");
    private static final byte[] ACTIVE = ascii(",\"active\":");
    private static final byte[] TAGS = ascii(",\"tags\":");
    private static final byte[] RATING_SCORE =
        ascii(",\"rating\":{\"score\":");
    private static final byte[] RATING_COUNT = ascii(",\"count\":");
    private static final byte[] ITEM_SUFFIX = ascii("}}");
    private static final byte[] ROOT_COUNT = ascii("],\"count\":");
    private static final byte[] TRUE = ascii("true");
    private static final byte[] FALSE = ascii("false");
    private static final byte[] EMPTY_JSON =
        ascii("{\"items\":[],\"count\":0}");
    private static final HttpArenaDatabaseResult EMPTY =
        new HttpArenaDatabaseResult(EMPTY_JSON, EMPTY_JSON.length, 0);

    private final byte[] encoded;
    private final int encodedLength;
    private final int count;

    private HttpArenaDatabaseResult(
            byte[] encoded, int encodedLength, int count) {
        this.encoded = encoded;
        this.encodedLength = encodedLength;
        this.count = count;
    }

    static HttpArenaDatabaseResult read(PgResultStream rows) {
        if (!rows.next()) {
            return EMPTY;
        }
        JsonBuffer output = new JsonBuffer(4096);
        output.append(ROOT_PREFIX);
        int count = 0;
        do {
            if (count != 0) {
                output.append((byte) ',');
            }
            append(output, rows.currentRow());
            count++;
        } while (rows.next());
        return finish(output, count);
    }

    static HttpArenaDatabaseResult of(List<ItemData> items) {
        if (items.isEmpty()) {
            return EMPTY;
        }
        JsonBuffer output = new JsonBuffer(4096);
        output.append(ROOT_PREFIX);
        for (int index = 0; index < items.size(); index++) {
            if (index != 0) {
                output.append((byte) ',');
            }
            append(output, items.get(index));
        }
        return finish(output, items.size());
    }

    static HttpArenaDatabaseResult empty() {
        return EMPTY;
    }

    int count() {
        return count;
    }

    EncodedBody encodedBody() {
        return EncodedBody.of(encodedLength, this::encode);
    }

    private int encode(MemorySegment output) {
        MemorySegment.copy(
            encoded, 0, output, ValueLayout.JAVA_BYTE,
            0, encodedLength);
        return encodedLength;
    }

    private static HttpArenaDatabaseResult finish(
            JsonBuffer output, int count) {
        if (count == 0) {
            return EMPTY;
        }
        output.append(ROOT_COUNT);
        output.appendInt(count);
        output.append((byte) '}');
        return new HttpArenaDatabaseResult(
            output.bytes(), output.length(), count);
    }

    private static void append(JsonBuffer output, Row row) {
        output.append(ID);
        output.appendInt(row.getIntPrimitive(1));
        output.append(NAME);
        output.appendJsonString(required(row.getString(2), "name"));
        output.append(CATEGORY);
        output.appendJsonString(required(row.getString(3), "category"));
        output.append(PRICE);
        output.appendInt(row.getIntPrimitive(4));
        output.append(QUANTITY);
        output.appendInt(row.getIntPrimitive(5));
        output.append(ACTIVE);
        output.append(row.getBooleanPrimitive(6) ? TRUE : FALSE);
        output.append(TAGS);
        output.appendUtf8(required(row.getString(7), "tags"));
        output.append(RATING_SCORE);
        output.appendInt(row.getIntPrimitive(8));
        output.append(RATING_COUNT);
        output.appendInt(row.getIntPrimitive(9));
        output.append(ITEM_SUFFIX);
    }

    private static void append(JsonBuffer output, ItemData item) {
        output.append(ID);
        output.appendInt(item.id());
        output.append(NAME);
        output.appendJsonString(item.name());
        output.append(CATEGORY);
        output.appendJsonString(item.category());
        output.append(PRICE);
        output.appendInt(item.price());
        output.append(QUANTITY);
        output.appendInt(item.quantity());
        output.append(ACTIVE);
        output.append(item.active() ? TRUE : FALSE);
        output.append(TAGS);
        output.appendUtf8(item.tagsJson());
        output.append(RATING_SCORE);
        output.appendInt(item.ratingScore());
        output.append(RATING_COUNT);
        output.appendInt(item.ratingCount());
        output.append(ITEM_SUFFIX);
    }

    private static String required(String value, String column) {
        return Objects.requireNonNull(value, column + " must not be null");
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    record ItemData(
        int id,
        String name,
        String category,
        int price,
        int quantity,
        boolean active,
        String tagsJson,
        int ratingScore,
        int ratingCount
    ) {
        ItemData {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(tagsJson, "tagsJson");
        }
    }

    private static final class JsonBuffer {
        private static final byte[] HEX = ascii("0123456789abcdef");

        private byte[] bytes;
        private int length;

        private JsonBuffer(int initialCapacity) {
            bytes = new byte[initialCapacity];
        }

        private byte[] bytes() {
            return bytes;
        }

        private int length() {
            return length;
        }

        private void append(byte value) {
            ensureCapacity(1);
            bytes[length++] = value;
        }

        private void append(byte[] value) {
            ensureCapacity(value.length);
            System.arraycopy(value, 0, bytes, length, value.length);
            length += value.length;
        }

        private void appendInt(int value) {
            int encodedLength = digits(value);
            ensureCapacity(encodedLength);
            int start = length;
            int cursor = start + encodedLength;
            int remaining = value;
            do {
                int digit = remaining % 10;
                bytes[--cursor] = (byte) ('0' + Math.abs(digit));
                remaining /= 10;
            } while (remaining != 0);
            if (value < 0) {
                bytes[start] = '-';
            }
            length += encodedLength;
        }

        private void appendJsonString(String value) {
            append((byte) '"');
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                switch (current) {
                    case '"' -> appendAsciiEscape('"');
                    case '\\' -> appendAsciiEscape('\\');
                    case '\b' -> appendAsciiEscape('b');
                    case '\f' -> appendAsciiEscape('f');
                    case '\n' -> appendAsciiEscape('n');
                    case '\r' -> appendAsciiEscape('r');
                    case '\t' -> appendAsciiEscape('t');
                    default -> {
                        if (current < 0x20) {
                            ensureCapacity(6);
                            bytes[length++] = '\\';
                            bytes[length++] = 'u';
                            bytes[length++] = '0';
                            bytes[length++] = '0';
                            bytes[length++] = HEX[current >>> 4];
                            bytes[length++] = HEX[current & 0xf];
                        } else if (Character.isHighSurrogate(current)
                                && index + 1 < value.length()
                                && Character.isLowSurrogate(
                                    value.charAt(index + 1))) {
                            appendCodePoint(Character.toCodePoint(
                                current, value.charAt(++index)));
                        } else if (Character.isSurrogate(current)) {
                            append((byte) '?');
                        } else {
                            appendCodePoint(current);
                        }
                    }
                }
            }
            append((byte) '"');
        }

        private void appendUtf8(String value) {
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (Character.isHighSurrogate(current)
                        && index + 1 < value.length()
                        && Character.isLowSurrogate(
                            value.charAt(index + 1))) {
                    appendCodePoint(Character.toCodePoint(
                        current, value.charAt(++index)));
                } else if (Character.isSurrogate(current)) {
                    append((byte) '?');
                } else {
                    appendCodePoint(current);
                }
            }
        }

        private void appendAsciiEscape(char value) {
            ensureCapacity(2);
            bytes[length++] = '\\';
            bytes[length++] = (byte) value;
        }

        private void appendCodePoint(int value) {
            if (value <= 0x7f) {
                append((byte) value);
            } else if (value <= 0x7ff) {
                ensureCapacity(2);
                bytes[length++] = (byte) (0xc0 | value >>> 6);
                bytes[length++] = (byte) (0x80 | value & 0x3f);
            } else if (value <= 0xffff) {
                ensureCapacity(3);
                bytes[length++] = (byte) (0xe0 | value >>> 12);
                bytes[length++] = (byte) (0x80 | value >>> 6 & 0x3f);
                bytes[length++] = (byte) (0x80 | value & 0x3f);
            } else {
                ensureCapacity(4);
                bytes[length++] = (byte) (0xf0 | value >>> 18);
                bytes[length++] = (byte) (0x80 | value >>> 12 & 0x3f);
                bytes[length++] = (byte) (0x80 | value >>> 6 & 0x3f);
                bytes[length++] = (byte) (0x80 | value & 0x3f);
            }
        }

        private void ensureCapacity(int additional) {
            int required;
            try {
                required = Math.addExact(length, additional);
            } catch (ArithmeticException error) {
                throw new IllegalStateException(
                    "Database response is too large", error);
            }
            if (required <= bytes.length) {
                return;
            }
            int expanded = bytes.length;
            while (expanded < required) {
                int next = expanded << 1;
                if (next <= expanded) {
                    expanded = required;
                    break;
                }
                expanded = next;
            }
            bytes = Arrays.copyOf(bytes, expanded);
        }

        private static int digits(int value) {
            int result = value < 0 ? 2 : 1;
            while (value <= -10 || value >= 10) {
                value /= 10;
                result++;
            }
            return result;
        }
    }
}
