/*
 * Copyright 2018-2025 The simdjson authors
 * Copyright 2026 dev.cardigan Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.cardigan.simdjson.ondemand;

import dev.cardigan.simdjson.SimdJsonError;
import dev.cardigan.simdjson.SimdJsonException;
import dev.cardigan.simdjson.StructuralIndexes;
import dev.cardigan.simdjson.dom.JsonType;
import dev.cardigan.simdjson.util.FastNumberParser;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Lazy on-demand JSON value view.
 */
public final class Value {

    private final MemorySegment segment;
    private final byte[] heapBytes;
    private final int heapOffset;
    private final StructuralIndexes indexes;
    private final int indexIdx;
    private final int valueOffset;
    private ObjectVal objectView;

    public Value(MemorySegment segment, byte[] heapBytes, StructuralIndexes indexes, int indexIdx, int valueOffset) {
        this.segment = segment;
        this.heapBytes = heapBytes;
        this.heapOffset = heapBytes == null
            ? 0 : Math.toIntExact(segment.address());
        this.indexes = indexes;
        this.indexIdx = indexIdx;
        this.valueOffset = valueOffset;
    }

    public MemorySegment segment() {
        return segment;
    }

    public int getRawOffset() {
        return skipWhitespace(valueOffset);
    }

    public int getRawLength() {
        int pos = skipWhitespace(valueOffset);
        byte b = getByte(pos);
        if (b == '"') {
            if (indexes != null && indexIdx >= 0 && indexIdx + 1 < indexes.size()) {
                int nextStruct = indexes.get(indexIdx + 1);
                int p = nextStruct - 1;
                while (p > pos && isWhitespace(getByte(p))) {
                    p--;
                }
                if (p > pos && getByte(p) == '"') {
                    return (p + 1) - pos;
                }
            }
            int endQuote = findQuoteFallback(pos + 1);
            return (endQuote + 1) - pos;
        } else if (b == '{' || b == '[') {
            int depth = 1;
            int p = pos + 1;
            int max = Math.toIntExact(segment.byteSize());
            while (p < max && depth > 0) {
                byte c = getByte(p);
                if (c == '"') {
                    p = findQuoteFallback(p + 1);
                } else if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                }
                p++;
            }
            return p - pos;
        } else if (b == 't') {
            return 4;
        } else if (b == 'f') {
            return 5;
        } else if (b == 'n') {
            return 4;
        } else {
            return findNumberEnd(pos) - pos;
        }
    }

    public JsonType getType() {
        int pos = skipWhitespace(valueOffset);
        byte b = getByte(pos);
        return switch (b) {
            case '{' -> JsonType.OBJECT;
            case '[' -> JsonType.ARRAY;
            case '"' -> JsonType.STRING;
            case 't', 'f' -> JsonType.BOOLEAN;
            case 'n' -> JsonType.NULL;
            case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' ->
                FastNumberParser.isInteger(
                    segment, heapBytes,
                    (long) heapOffset + pos,
                    (long) heapOffset + findNumberEnd(pos))
                    ? JsonType.INT64 : JsonType.DOUBLE;
            default -> throw new SimdJsonException(SimdJsonError.INCORRECT_TYPE, "Unexpected token: " + (char) b);
        };
    }

    public String getString() {
        int pos = skipWhitespace(valueOffset);
        byte b = getByte(pos);
        if (b != '"') {
            throw new SimdJsonException(SimdJsonError.INCORRECT_TYPE, "Expected String start quote, got: " + (char) b);
        }
        int startPos = pos + 1;
        int endPos = -1;

        if (indexes != null && indexIdx >= 0 && indexIdx + 1 < indexes.size()) {
            int nextStruct = indexes.get(indexIdx + 1);
            int p = nextStruct - 1;
            while (p > startPos && isWhitespace(getByte(p))) {
                p--;
            }
            if (p > startPos && getByte(p) == '"') {
                endPos = p;
            }
        }

        if (endPos == -1) {
            endPos = findQuoteFallback(startPos);
        }

        int strLen = endPos - startPos;
        if (heapBytes != null) {
            boolean hasEscape = false;
            for (int i = 0; i < strLen; i++) {
                if (heapBytes[heapOffset + startPos + i] == '\\') {
                    hasEscape = true;
                    break;
                }
            }
            if (!hasEscape) {
                return new String(
                    heapBytes, heapOffset + startPos,
                    strLen, StandardCharsets.UTF_8);
            } else {
                byte[] bytes = new byte[strLen];
                System.arraycopy(
                    heapBytes, heapOffset + startPos,
                    bytes, 0, strLen);
                return unescape(bytes);
            }
        } else {
            boolean hasEscape = false;
            for (int i = 0; i < strLen; i++) {
                if (segment.get(ValueLayout.JAVA_BYTE, (long) startPos + i) == '\\') {
                    hasEscape = true;
                    break;
                }
            }
            byte[] bytes = new byte[strLen];
            MemorySegment.ofArray(bytes).copyFrom(segment.asSlice(startPos, strLen));
            if (!hasEscape) {
                return new String(bytes, StandardCharsets.UTF_8);
            } else {
                return unescape(bytes);
            }
        }
    }

    private boolean isWhitespace(byte c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private int findQuoteFallback(int startPos) {
        int p = startPos;
        int max = Math.toIntExact(segment.byteSize());
        while (p < max) {
            byte byteVal = getByte(p);
            if (byteVal == '\\') {
                p += 2;
                continue;
            }
            if (byteVal == '"') {
                return p;
            }
            p++;
        }
        return p;
    }

    public long getLong() {
        int pos = skipWhitespace(valueOffset);
        int end = findNumberEnd(pos);
        return FastNumberParser.parseLong(
            segment, heapBytes,
            (long) heapOffset + pos, (long) heapOffset + end);
    }

    public double getDouble() {
        int pos = skipWhitespace(valueOffset);
        int end = findNumberEnd(pos);
        return FastNumberParser.parseDouble(
            segment, heapBytes,
            (long) heapOffset + pos, (long) heapOffset + end);
    }

    public boolean getBoolean() {
        int pos = skipWhitespace(valueOffset);
        byte b = getByte(pos);
        if (b == 't') return true;
        if (b == 'f') return false;
        throw new SimdJsonException(SimdJsonError.INCORRECT_TYPE, "Expected boolean, got: " + (char) b);
    }

    public boolean isNull() {
        int pos = skipWhitespace(valueOffset);
        return getByte(pos) == 'n';
    }

    public ObjectVal getObject() {
        ObjectVal cached = objectView;
        if (cached != null) {
            return cached;
        }
        int pos = skipWhitespace(valueOffset);
        if (getByte(pos) != '{') {
            throw new SimdJsonException(SimdJsonError.INCORRECT_TYPE, "Expected Object '{'");
        }
        int braceIdx = indexIdx;
        int max = indexes.size();
        while (braceIdx < max && indexes.get(braceIdx) != pos) {
            braceIdx++;
        }
        ObjectVal object = new ObjectVal(
            segment, heapBytes, indexes, braceIdx);
        objectView = object;
        return object;
    }

    public ArrayVal getArray() {
        int pos = skipWhitespace(valueOffset);
        if (getByte(pos) != '[') {
            throw new SimdJsonException(SimdJsonError.INCORRECT_TYPE, "Expected Array '['");
        }
        int bracketIdx = indexIdx;
        int max = indexes.size();
        while (bracketIdx < max && indexes.get(bracketIdx) != pos) {
            bracketIdx++;
        }
        return new ArrayVal(segment, heapBytes, indexes, bracketIdx);
    }

    public Value get(String fieldKey) {
        return getObject().get(fieldKey);
    }

    private int skipWhitespace(int start) {
        int p = start;
        int max = (int) segment.byteSize();
        while (p < max) {
            byte b = getByte(p);
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r') {
                break;
            }
            p++;
        }
        return p;
    }

    private int findNumberEnd(int start) {
        int p = start;
        int max = (int) segment.byteSize();
        while (p < max) {
            byte b = getByte(p);
            if ((b >= '0' && b <= '9') || b == '-' || b == '+' || b == '.' || b == 'e' || b == 'E') {
                p++;
            } else {
                break;
            }
        }
        return p;
    }

    private byte getByte(int pos) {
        return heapBytes != null
            ? heapBytes[heapOffset + pos]
            : segment.get(ValueLayout.JAVA_BYTE, pos);
    }

    private static String unescape(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length);
        int chunkStart = 0;
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            if (b == '\\' && i + 1 < bytes.length) {
                if (chunkStart < i) {
                    sb.append(new String(
                        bytes, chunkStart, i - chunkStart,
                        StandardCharsets.UTF_8));
                }
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
                        if (i + 4 < bytes.length) {
                            String hex = new String(bytes, i + 1, 4, StandardCharsets.US_ASCII);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        }
                    }
                    default -> sb.append((char) esc);
                }
                chunkStart = i + 1;
            }
        }
        if (chunkStart < bytes.length) {
            sb.append(new String(
                bytes, chunkStart, bytes.length - chunkStart,
                StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        int len = getRawLength();
        if (heapBytes != null) {
            return new String(
                heapBytes, heapOffset + valueOffset, len,
                StandardCharsets.UTF_8);
        } else {
            return new String(segment.asSlice(valueOffset, len).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
        }
    }
}
