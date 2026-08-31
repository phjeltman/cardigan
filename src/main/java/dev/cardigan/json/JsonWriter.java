// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.json;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import dev.cardigan.http.Utf8Slice;

public class JsonWriter {

    private static final short[] DIGIT_PAIRS = new short[100];
    static {
        for (int i = 0; i < 100; i++) {
            int tens = i / 10;
            int ones = i % 10;
            byte b1 = (byte) ('0' + tens);
            byte b2 = (byte) ('0' + ones);
            DIGIT_PAIRS[i] = (short) ((b2 << 8) | (b1 & 0xFF));
        }
    }

    private static final byte[] HEX_DIGITS = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    public static int writeRecord(MemorySegment segment, long offset, Record record) {
        RecordCache.RecordMetadata metadata = RecordCache.getMetadata(record.getClass());
        long currentOffset = offset;

        FieldWriter[] writers = metadata.fieldWriters;
        byte[][] keyBytesArray = metadata.preEncodedKeyBytes;
        int n = writers.length;

        if (n == 0) {
            segment.set(ValueLayout.JAVA_BYTE, currentOffset++, (byte) '{');
            segment.set(ValueLayout.JAVA_BYTE, currentOffset++, (byte) '}');
            return (int) (currentOffset - offset);
        }

        for (int i = 0; i < n; i++) {
            byte[] keyBytes = keyBytesArray[i];
            int keyLen = keyBytes.length;
            MemorySegment.copy(
                keyBytes, 0, segment, ValueLayout.JAVA_BYTE,
                currentOffset, keyLen);
            currentOffset += keyLen;
            try {
                currentOffset = writers[i].write(segment, currentOffset, record);
            } catch (Throwable e) {
                throw new RuntimeException("Failed to write field: " + metadata.componentNames[i], e);
            }
        }

        segment.set(ValueLayout.JAVA_BYTE, currentOffset++, (byte) '}');
        return (int) (currentOffset - offset);
    }

    public static int encodedSize(Object value) {
        if (value == null) {
            return 4;
        }
        if (value instanceof String string) {
            return encodedStringSize(string);
        }
        if (value instanceof Utf8Slice slice) {
            return Math.addExact(Math.toIntExact(slice.length()), 2);
        }
        if (value instanceof Integer integer) {
            return encodedIntSize(integer);
        }
        if (value instanceof Short number) {
            return encodedIntSize(number.intValue());
        }
        if (value instanceof Byte number) {
            return encodedIntSize(number.intValue());
        }
        if (value instanceof Long number) {
            return encodedLongSize(number);
        }
        if (value instanceof Number number) {
            return number.toString().length();
        }
        if (value instanceof Boolean bool) {
            return bool ? 4 : 5;
        }
        if (value instanceof Record record) {
            return encodedRecordSize(record);
        }
        throw new IllegalArgumentException(
            "Unsupported JSON serialization type: " + value.getClass());
    }

    public static int encodedRecordSize(Record record) {
        if (record == null) {
            return 4;
        }
        RecordCache.RecordMetadata metadata =
            RecordCache.getMetadata(record.getClass());
        int fields = metadata.preEncodedKeyBytes.length;
        if (fields == 0) {
            return 2;
        }
        int size = 0;
        for (int index = 0; index < fields; index++) {
            size = Math.addExact(
                size, metadata.preEncodedKeyBytes[index].length);
            try {
                Object value = metadata.accessorHandles[index].invoke(record);
                size = Math.addExact(size, encodedSize(value));
            } catch (Throwable failure) {
                throw new RuntimeException(
                    "Failed to size field: "
                        + metadata.componentNames[index],
                    failure);
            }
        }
        return Math.addExact(size, 1);
    }

    public static int encodedStringSize(String string) {
        if (string == null) {
            return 4;
        }
        int size = 2;
        for (int index = 0; index < string.length(); index++) {
            char current = string.charAt(index);
            if (current < 0x20) {
                size = Math.addExact(size,
                    current == '\b' || current == '\f'
                        || current == '\n' || current == '\r'
                        || current == '\t' ? 2 : 6);
            } else if (current < 0x80) {
                size = Math.addExact(size,
                    current == '"' || current == '\\' ? 2 : 1);
            } else if (current < 0x800) {
                size = Math.addExact(size, 2);
            } else if (Character.isSurrogate(current)) {
                if (index + 1 < string.length()) {
                    size = Math.addExact(size, 4);
                    index++;
                } else {
                    size = Math.addExact(size, 1);
                }
            } else {
                size = Math.addExact(size, 3);
            }
        }
        return size;
    }

    private static int encodedIntSize(int value) {
        if (value == Integer.MIN_VALUE) {
            return 11;
        }
        int size = value < 0 ? 1 : 0;
        int magnitude = value < 0 ? -value : value;
        do {
            size++;
            magnitude /= 10;
        } while (magnitude != 0);
        return size;
    }

    private static int encodedLongSize(long value) {
        if (value == Long.MIN_VALUE) {
            return 20;
        }
        int size = value < 0 ? 1 : 0;
        long magnitude = value < 0 ? -value : value;
        do {
            size++;
            magnitude /= 10;
        } while (magnitude != 0);
        return size;
    }

    public static long writeUtf8String(MemorySegment segment, long offset, String str) {
        if (str == null) {
            segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset, 0x6c6c756e); // "null"
            return offset + 4;
        }
        segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '"');
        int len = str.length();
        for (int i = 0; i < len; i++) {
            char c = str.charAt(i);
            if (c < 0x20) {
                offset = writeControlChar(segment, offset, c);
            } else if (c < 0x80) {
                if (c == '"' || c == '\\') {
                    segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '\\');
                }
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) c);
            } else {
                return writeUtf8Multibyte(segment, offset, str, i);
            }
        }
        segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '"');
        return offset;
    }

    private static long writeControlChar(MemorySegment segment, long offset, char c) {
        switch (c) {
            case '\b' -> {
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '\\');
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) 'b');
            }
            case '\f' -> {
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '\\');
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) 'f');
            }
            case '\n' -> {
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '\\');
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) 'n');
            }
            case '\r' -> {
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '\\');
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) 'r');
            }
            case '\t' -> {
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '\\');
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) 't');
            }
            default -> {
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '\\');
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) 'u');
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '0');
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '0');
                segment.set(ValueLayout.JAVA_BYTE, offset++, HEX_DIGITS[(c >> 4) & 0xF]);
                segment.set(ValueLayout.JAVA_BYTE, offset++, HEX_DIGITS[c & 0xF]);
            }
        }
        return offset;
    }

    private static long writeUtf8Multibyte(MemorySegment segment, long offset, String str, int startIdx) {
        int len = str.length();
        for (int i = startIdx; i < len; i++) {
            char c = str.charAt(i);
            if (c < 0x20) {
                offset = writeControlChar(segment, offset, c);
            } else if (c < 0x80) {
                if (c == '"' || c == '\\') {
                    segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '\\');
                }
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) c);
            } else if (c < 0x800) {
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) (0xc0 | (c >> 6)));
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) (0x80 | (c & 0x3f)));
            } else if (Character.isSurrogate(c)) {
                if (i + 1 < len) {
                    int codePoint = Character.toCodePoint(c, str.charAt(i + 1));
                    i++;
                    segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) (0xf0 | (codePoint >> 18)));
                    segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) (0x80 | ((codePoint >> 12) & 0x3f)));
                    segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) (0x80 | ((codePoint >> 6) & 0x3f)));
                    segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) (0x80 | (codePoint & 0x3f)));
                } else {
                    segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '?');
                }
            } else {
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) (0xe0 | (c >> 12)));
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) (0x80 | ((c >> 6) & 0x3f)));
                segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) (0x80 | (c & 0x3f)));
            }
        }
        segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '"');
        return offset;
    }

    public static long writeDouble(MemorySegment segment, long offset, double val) {
        String s = Double.toString(val);
        int len = s.length();
        for (int i = 0; i < len; i++) {
            segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) s.charAt(i));
        }
        return offset;
    }

    public static long writeSlice(MemorySegment segment, long offset, Utf8Slice slice) {
        if (slice == null) {
            segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset, 0x6c6c756e); // "null"
            return offset + 4;
        }
        segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '"');
        MemorySegment.copy(slice.segment(), slice.offset(), segment, offset, slice.length());
        offset += slice.length();
        segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '"');
        return offset;
    }

    public static long writeInt(MemorySegment segment, long offset, int q) {
        if (q < 0) {
            if (q == Integer.MIN_VALUE) {
                byte[] lit = {'-','2','1','4','7','4','8','3','6','4','8'};
                for (byte b : lit) segment.set(ValueLayout.JAVA_BYTE, offset++, b);
                return offset;
            }
            segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '-');
            q = -q;
        }

        if (q < 10) {
            segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) ('0' + q));
            return offset;
        }
        if (q < 100) {
            short pair = DIGIT_PAIRS[q];
            segment.set(ValueLayout.JAVA_SHORT_UNALIGNED, offset, pair);
            return offset + 2;
        }
        if (q < 1000) {
            int top = q / 100;
            int rem = q % 100;
            segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) ('0' + top));
            short pair = DIGIT_PAIRS[rem];
            segment.set(ValueLayout.JAVA_SHORT_UNALIGNED, offset, pair);
            return offset + 2;
        }
        if (q < 10000) {
            int top = q / 100;
            int rem = q % 100;
            short pair1 = DIGIT_PAIRS[top];
            short pair2 = DIGIT_PAIRS[rem];
            segment.set(ValueLayout.JAVA_SHORT_UNALIGNED, offset, pair1);
            segment.set(ValueLayout.JAVA_SHORT_UNALIGNED, offset + 2, pair2);
            return offset + 4;
        }
        return writeLongGeneral(segment, offset, q);
    }

    public static long writeLong(MemorySegment segment, long offset, long value) {
        if (value < 0) {
            if (value == Long.MIN_VALUE) {
                byte[] literal = {'-','9','2','2','3','3','7','2','0','3','6','8','5','4','7','7','5','8','0','8'};
                for (byte b : literal) segment.set(ValueLayout.JAVA_BYTE, offset++, b);
                return offset;
            }
            segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '-');
            value = -value;
        }
        if (value <= Integer.MAX_VALUE) {
            return writeInt(segment, offset, (int) value);
        }
        return writeLongGeneral(segment, offset, value);
    }

    private static long writeLongGeneral(MemorySegment segment, long offset, long value) {
        if (value == 0) {
            segment.set(ValueLayout.JAVA_BYTE, offset++, (byte) '0');
            return offset;
        }
        long temp = value;
        int len = 0;
        while (temp > 0) {
            len++;
            temp /= 10;
        }
        long nextOffset = offset + len;
        long ptr = nextOffset - 1;
        while (value >= 100) {
            int rem = (int) (value % 100);
            value /= 100;
            short pair = DIGIT_PAIRS[rem];
            byte b1 = (byte) (pair & 0xFF);
            byte b2 = (byte) ((pair >> 8) & 0xFF);
            segment.set(ValueLayout.JAVA_BYTE, ptr--, b2);
            segment.set(ValueLayout.JAVA_BYTE, ptr--, b1);
        }
        if (value >= 10) {
            short pair = DIGIT_PAIRS[(int) value];
            byte b1 = (byte) (pair & 0xFF);
            byte b2 = (byte) ((pair >> 8) & 0xFF);
            segment.set(ValueLayout.JAVA_BYTE, ptr--, b2);
            segment.set(ValueLayout.JAVA_BYTE, ptr--, b1);
        } else {
            segment.set(ValueLayout.JAVA_BYTE, ptr--, (byte) ('0' + value));
        }
        return nextOffset;
    }

    public static long writeBoolean(MemorySegment segment, long offset, boolean val) {
        if (val) {
            segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset, 0x65757274); // "true"
            return offset + 4;
        } else {
            segment.set(ValueLayout.JAVA_BYTE, offset, (byte) 'f');
            segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset + 1, 0x65736c61); // "alse"
            return offset + 5;
        }
    }

    public static long writeValue(MemorySegment segment, long offset, Object val) {
        if (val == null) {
            segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset, 0x6c6c756e); // "null"
            return offset + 4;
        }
        if (val instanceof String s) {
            return writeUtf8String(segment, offset, s);
        } else if (val instanceof Utf8Slice slice) {
            return writeSlice(segment, offset, slice);
        } else if (val instanceof Number n) {
            if (n instanceof Integer || n instanceof Short || n instanceof Byte) {
                return writeInt(segment, offset, n.intValue());
            } else if (n instanceof Long) {
                return writeLong(segment, offset, n.longValue());
            } else {
                String s = n.toString();
                int len = s.length();
                for (int i = 0; i < len; i++) {
                    segment.set(ValueLayout.JAVA_BYTE, offset + i, (byte) s.charAt(i));
                }
                return offset + len;
            }
        } else if (val instanceof Boolean b) {
            return writeBoolean(segment, offset, b);
        } else if (val instanceof Record rec) {
            return offset + writeRecord(segment, offset, rec);
        } else {
            throw new IllegalArgumentException("Unsupported JSON serialization type: " + val.getClass());
        }
    }
}
