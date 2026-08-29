// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.json;

import dev.cardigan.http.Utf8Slice;
import dev.cardigan.simdjson.Stage1Indexer;
import dev.cardigan.simdjson.StructuralIndexes;
import dev.cardigan.simdjson.util.FastNumberParser;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class JsonReader {

    private static final ThreadLocal<Stage1Indexer> THREAD_LOCAL_INDEXER = ThreadLocal.withInitial(Stage1Indexer::new);
    private static final ThreadLocal<StructuralIndexes> THREAD_LOCAL_INDEXES =
        ThreadLocal.withInitial(
            () -> StructuralIndexes.operatorsOnly(128));
    private static final ThreadLocal<byte[]> STRING_BUFFER = ThreadLocal.withInitial(() -> new byte[4096]);

    private static String unescapeString(MemorySegment segment, long start, long len) {
        if (len <= 0) return "";
        
        byte[] buf = STRING_BUFFER.get();
        if (len > buf.length) {
            // Oversized strings require a right-sized array.
            byte[] bytes = segment.asSlice(start, len).toArray(ValueLayout.JAVA_BYTE);
            return unescapeStringBytes(bytes, bytes.length);
        }
        
        // Reuse thread-local scratch for bounded strings.
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, start, buf, 0, (int) len);
        
        boolean hasEscape = false;
        for (int i = 0; i < len; i++) {
            if (buf[i] == '\\') {
                hasEscape = true;
                break;
            }
        }
        
        if (!hasEscape) {
            return new String(buf, 0, (int) len, StandardCharsets.UTF_8);
        }
        
        return unescapeStringBytes(buf, (int) len);
    }
    
    private static String unescapeStringBytes(byte[] bytes, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            byte b = bytes[i];
            if (b == '\\' && i + 1 < length) {
                i++;
                byte esc = bytes[i];
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 < length) {
                            String hex = new String(bytes, i + 1, 4, StandardCharsets.US_ASCII);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        }
                    }
                    default -> sb.append((char) esc);
                }
            } else {
                sb.append((char) b);
            }
        }
        return sb.toString();
    }

    private static long skipWhitespace(MemorySegment segment, long index, long end) {
        while (index < end) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, index);
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r') break;
            index++;
        }
        return index;
    }

    private static long skipWhitespaceBack(MemorySegment segment, long start, long end) {
        long p = end;
        while (p >= start) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, p);
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r') break;
            p--;
        }
        return p;
    }

    private static long skipString(MemorySegment segment, long index, long end) {
        index++; // Skip opening '"'
        while (index < end) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, index);
            if (b == '"') return index;
            if (b == '\\') index++;
            index++;
        }
        return index;
    }

    private static long skipValue(MemorySegment segment, long index, long end) {
        byte b = segment.get(ValueLayout.JAVA_BYTE, index);
        if (b == '"') {
            index = skipString(segment, index, end);
            index++; // skip closing '"'
        } else if (b == '{') {
            index++;
            int braces = 1;
            while (index < end && braces > 0) {
                byte c = segment.get(ValueLayout.JAVA_BYTE, index);
                if (c == '"') {
                    index = skipString(segment, index, end);
                } else if (c == '{') {
                    braces++;
                } else if (c == '}') {
                    braces--;
                }
                index++;
            }
        } else if (b == '[') {
            index++;
            int brackets = 1;
            while (index < end && brackets > 0) {
                byte c = segment.get(ValueLayout.JAVA_BYTE, index);
                if (c == '"') {
                    index = skipString(segment, index, end);
                } else if (c == '[') {
                    brackets++;
                } else if (c == ']') {
                    brackets--;
                }
                index++;
            }
        } else {
            while (index < end) {
                byte c = segment.get(ValueLayout.JAVA_BYTE, index);
                if (c == ',' || c == '}' || c == ']' || c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    break;
                }
                index++;
            }
        }
        return index;
    }

    private static int parseDecimal(MemorySegment segment, long offset, long len, int defaultValue) {
        if (len <= 0) return defaultValue;

        long index = offset;
        long end = offset + len;
        boolean negative = false;

        byte first = segment.get(ValueLayout.JAVA_BYTE, index);
        if (first == '-') {
            negative = true;
            index++;
        } else if (first == '+') {
            index++;
        }

        if (index >= end) return defaultValue;

        int val = 0;
        boolean digits = false;
        while (index < end) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, index);
            if (b >= '0' && b <= '9') {
                val = val * 10 + (b - '0');
                digits = true;
            } else {
                break;
            }
            index++;
        }

        if (!digits) return defaultValue;
        return negative ? -val : val;
    }

    @SuppressWarnings("unchecked")
    public static <T extends Record> T parseRecord(MemorySegment segment, long offset, long len, Class<T> recordClass) {
        return dev.cardigan.serdes.Serdes.fromJson(segment, offset, len, recordClass);
    }

    private static long findQuoteBack(MemorySegment segment, long start, long end) {
        long p = end;
        while (p >= start) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, p);
            if (b == '"') return p;
            p--;
        }
        return -1;
    }

    public static long findValue(MemorySegment segment, long startOffset, long length, String key) {
        if (length <= 0) return -1;
        MemorySegment slice = (startOffset == 0 && length == segment.byteSize()) ? segment : segment.asSlice(startOffset, length);
        Stage1Indexer indexer = THREAD_LOCAL_INDEXER.get();
        StructuralIndexes indexes = THREAD_LOCAL_INDEXES.get();
        indexer.index(slice, indexes);

        int n = indexes.size();
        if (n < 2) return -1;

        byte[] heapBytes = slice.heapBase().map(o -> (byte[]) o).orElse(null);
        int prevPunct = indexes.get(0);

        for (int k = 1; k < n; k++) {
            int punctOffset = indexes.get(k);
            byte punct = getByte(slice, heapBytes, punctOffset);

            if (punct == ':') {
                int keyQuoteStart = (int) skipWhitespace(slice, prevPunct + 1, punctOffset);
                if (keyQuoteStart < punctOffset && getByte(slice, heapBytes, keyQuoteStart) == '"') {
                    int keyQuoteEnd = (int) skipWhitespaceBack(slice, keyQuoteStart + 1, punctOffset - 1);
                    if (keyQuoteEnd > keyQuoteStart && getByte(slice, heapBytes, keyQuoteEnd) == '"') {
                        int keyStart = keyQuoteStart + 1;
                        int keyLen = keyQuoteEnd - keyStart;
                        if (equalsString(slice, heapBytes, keyStart, keyLen, key)) {
                            int valStart = (int) skipWhitespace(slice, punctOffset + 1, (int) length);
                            int valEnd = findValEnd(slice, heapBytes, indexes, k, n, (int) length);
                            long absoluteValStart = startOffset + valStart;
                            long valLen = valEnd - valStart;
                            return (absoluteValStart << 32) | (valLen & 0xFFFFFFFFL);
                        }
                    }
                }
            }
            prevPunct = punctOffset;
        }

        return -1;
    }

    public static IndexedObject indexObject(
            MemorySegment segment, long startOffset, long length) {
        if (length <= 0) {
            return new IndexedObject(
                segment, MemorySegment.NULL, startOffset, 0,
                new int[0]);
        }
        MemorySegment slice = startOffset == 0
                && length == segment.byteSize()
            ? segment : segment.asSlice(startOffset, length);
        Stage1Indexer indexer = THREAD_LOCAL_INDEXER.get();
        StructuralIndexes indexes = THREAD_LOCAL_INDEXES.get();
        indexer.index(slice, indexes);
        return new IndexedObject(
            segment, slice, startOffset, Math.toIntExact(length),
            Arrays.copyOf(indexes.indexes(), indexes.size()));
    }

    public static final class IndexedObject {
        private final MemorySegment segment;
        private final MemorySegment slice;
        private final long startOffset;
        private final int length;
        private final int[] indexes;
        private final byte[] heapBytes;
        private final Map<String, Long> fields;

        private IndexedObject(
                MemorySegment segment, MemorySegment slice,
                long startOffset, int length, int[] indexes) {
            this.segment = segment;
            this.slice = slice;
            this.startOffset = startOffset;
            this.length = length;
            this.indexes = indexes;
            this.heapBytes = slice == MemorySegment.NULL
                ? null
                : slice.heapBase().map(base -> (byte[]) base).orElse(null);
            this.fields = indexFields();
        }

        public long findValue(String key) {
            return fields.getOrDefault(key, -1L);
        }

        private Map<String, Long> indexFields() {
            Map<String, Long> indexedFields = new HashMap<>();
            int count = indexes.length;
            if (count < 2) {
                return indexedFields;
            }
            int previous = indexes[0];
            for (int index = 1; index < count; index++) {
                int punctuationOffset = indexes[index];
                byte punctuation = getByte(
                    slice, heapBytes, punctuationOffset);
                if (punctuation == ':') {
                    int quoteStart = (int) skipWhitespace(
                        slice, previous + 1, punctuationOffset);
                    if (quoteStart < punctuationOffset
                            && getByte(slice, heapBytes, quoteStart) == '"') {
                        int quoteEnd = (int) skipWhitespaceBack(
                            slice, quoteStart + 1, punctuationOffset - 1);
                        if (quoteEnd > quoteStart
                                && getByte(slice, heapBytes, quoteEnd) == '"') {
                            int keyStart = quoteStart + 1;
                            int keyLength = quoteEnd - keyStart;
                            String key = new String(
                                slice.asSlice(keyStart, keyLength)
                                    .toArray(ValueLayout.JAVA_BYTE),
                                StandardCharsets.UTF_8);
                            int valueStart = (int) skipWhitespace(
                                slice, punctuationOffset + 1, length);
                            int valueEnd = findValueEnd(index, count);
                            long absoluteStart = startOffset + valueStart;
                            long packed = (absoluteStart << 32)
                                | ((valueEnd - valueStart) & 0xffff_ffffL);
                            indexedFields.putIfAbsent(key, packed);
                        }
                    }
                }
                previous = punctuationOffset;
            }
            return indexedFields;
        }

        public Utf8Slice findString(String key) {
            long packed = findValue(key);
            if (packed == -1) {
                return null;
            }
            long offset = packed >>> 32;
            long valueLength = packed & 0xffff_ffffL;
            if (valueLength >= 2
                    && segment.get(ValueLayout.JAVA_BYTE, offset) == '"'
                    && segment.get(
                        ValueLayout.JAVA_BYTE,
                        offset + valueLength - 1) == '"') {
                return new Utf8Slice(
                    segment, offset + 1, valueLength - 2);
            }
            return null;
        }

        public int findInt(String key, int defaultValue) {
            long packed = findValue(key);
            if (packed == -1) {
                return defaultValue;
            }
            long offset = packed >>> 32;
            long valueLength = packed & 0xffff_ffffL;
            return (int) FastNumberParser.parseLong(
                segment, null, offset, offset + valueLength);
        }

        public long findLong(String key, long defaultValue) {
            long packed = findValue(key);
            if (packed == -1) {
                return defaultValue;
            }
            long offset = packed >>> 32;
            long valueLength = packed & 0xffff_ffffL;
            return FastNumberParser.parseLong(
                segment, null, offset, offset + valueLength);
        }

        public double findDouble(String key, double defaultValue) {
            long packed = findValue(key);
            if (packed == -1) {
                return defaultValue;
            }
            long offset = packed >>> 32;
            long valueLength = packed & 0xffff_ffffL;
            return FastNumberParser.parseDouble(
                segment, null, (int) offset,
                (int) (offset + valueLength));
        }

        public boolean findBoolean(String key, boolean defaultValue) {
            long packed = findValue(key);
            if (packed == -1) {
                return defaultValue;
            }
            long offset = packed >>> 32;
            long valueLength = packed & 0xffff_ffffL;
            if (equalsString(
                    segment, null, offset, valueLength, "true")) {
                return true;
            }
            if (equalsString(
                    segment, null, offset, valueLength, "false")) {
                return false;
            }
            return defaultValue;
        }

        private int findValueEnd(int colonIndex, int totalIndexes) {
            if (colonIndex + 1 >= totalIndexes) {
                return length;
            }
            int nextPosition = indexes[colonIndex + 1];
            int valueStart = (int) skipWhitespace(
                slice, indexes[colonIndex] + 1, nextPosition);
            byte first = getByte(slice, heapBytes, valueStart);
            if (first == '{' || first == '[') {
                int depth = 0;
                int endIndex = colonIndex + 1;
                while (endIndex < totalIndexes) {
                    int endPosition = indexes[endIndex];
                    byte current = getByte(
                        slice, heapBytes, endPosition);
                    if (current == '{' || current == '[') {
                        depth++;
                    } else if (current == '}' || current == ']') {
                        depth--;
                    }
                    endIndex++;
                    if (depth == 0) {
                        break;
                    }
                }
                return indexes[endIndex - 1] + 1;
            }
            if (first == '"') {
                return nextPosition > 0
                        && getByte(
                            slice, heapBytes, nextPosition - 1) == '"'
                    ? nextPosition
                    : (int) skipWhitespaceBack(
                        slice, valueStart, nextPosition - 1) + 1;
            }
            return nextPosition;
        }
    }

    private static int findValEnd(MemorySegment slice, byte[] heapBytes, StructuralIndexes indexes, int colonIdx, int totalIndexes, int maxLen) {
        if (colonIdx + 1 < totalIndexes) {
            int nextPos = indexes.get(colonIdx + 1);
            int valStart = (int) skipWhitespace(slice, indexes.get(colonIdx) + 1, nextPos);
            byte firstByte = getByte(slice, heapBytes, valStart);
            if (firstByte == '{' || firstByte == '[') {
                int depth = 0;
                int endIdx = colonIdx + 1;
                while (endIdx < totalIndexes) {
                    int endPos = indexes.get(endIdx);
                    byte c = getByte(slice, heapBytes, endPos);
                    if (c == '{' || c == '[') depth++;
                    else if (c == '}' || c == ']') depth--;
                    endIdx++;
                    if (depth == 0) break;
                }
                return (endIdx <= totalIndexes) ? indexes.get(endIdx - 1) + 1 : maxLen;
            } else if (firstByte == '"') {
                return (nextPos > 0 && getByte(slice, heapBytes, nextPos - 1) == '"') ? nextPos : (int) skipWhitespaceBack(slice, valStart, nextPos - 1) + 1;
            } else {
                return nextPos;
            }
        }
        return maxLen;
    }

    private static byte getByte(MemorySegment slice, byte[] heapBytes, long pos) {
        return heapBytes != null ? heapBytes[(int) pos] : slice.get(ValueLayout.JAVA_BYTE, pos);
    }

    public static Utf8Slice findString(MemorySegment segment, long startOffset, long length, String key) {
        long packed = findValue(segment, startOffset, length, key);
        if (packed == -1) return null;
        long offset = packed >>> 32;
        long len = packed & 0xFFFFFFFFL;
        if (len >= 2 && segment.get(ValueLayout.JAVA_BYTE, offset) == '"' && segment.get(ValueLayout.JAVA_BYTE, offset + len - 1) == '"') {
            return new Utf8Slice(segment, offset + 1, len - 2);
        }
        return null;
    }

    public static int findInt(MemorySegment segment, long startOffset, long length, String key, int defaultValue) {
        long packed = findValue(segment, startOffset, length, key);
        if (packed == -1) return defaultValue;
        long offset = packed >>> 32;
        long len = packed & 0xFFFFFFFFL;
        return (int) FastNumberParser.parseLong(segment, null, offset, offset + len);
    }

    public static long findLong(MemorySegment segment, long startOffset, long length, String key, long defaultValue) {
        long packed = findValue(segment, startOffset, length, key);
        if (packed == -1) return defaultValue;
        long offset = packed >>> 32;
        long len = packed & 0xFFFFFFFFL;
        return FastNumberParser.parseLong(segment, null, offset, offset + len);
    }

    public static double findDouble(MemorySegment segment, long startOffset, long length, String key, double defaultValue) {
        long packed = findValue(segment, startOffset, length, key);
        if (packed == -1) return defaultValue;
        long offset = packed >>> 32;
        long len = packed & 0xFFFFFFFFL;
        return FastNumberParser.parseDouble(segment, null, (int) offset, (int) (offset + len));
    }

    public static boolean findBoolean(MemorySegment segment, long startOffset, long length, String key, boolean defaultValue) {
        long packed = findValue(segment, startOffset, length, key);
        if (packed == -1) return defaultValue;
        long offset = packed >>> 32;
        long len = packed & 0xFFFFFFFFL;
        if (equalsString(segment, null, offset, len, "true")) return true;
        if (equalsString(segment, null, offset, len, "false")) return false;
        return defaultValue;
    }

    private static boolean equalsString(MemorySegment segment, byte[] heapBytes, long offset, long len, String str) {
        if (str.length() != len) return false;
        for (int i = 0; i < len; i++) {
            byte b = heapBytes != null ? heapBytes[(int) (offset + i)] : segment.get(ValueLayout.JAVA_BYTE, offset + i);
            if (b != (byte) str.charAt(i)) return false;
        }
        return true;
    }
}
