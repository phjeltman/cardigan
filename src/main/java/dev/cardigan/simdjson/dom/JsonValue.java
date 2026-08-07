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

package dev.cardigan.simdjson.dom;

import dev.cardigan.simdjson.SimdJsonError;
import dev.cardigan.simdjson.SimdJsonException;
import dev.cardigan.simdjson.Tape;
import dev.cardigan.simdjson.util.FastNumberParser;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Zero-copy DOM node view backed by MemorySegment and Tape.
 * Supports lazy evaluation and caches materialized values for repeated access.
 */
public final class JsonValue {

    private final MemorySegment segment;
    private final byte[] heapBytes;
    private final int heapOffset;
    private final Tape tape;
    private final int tapeIndex;

    // Materialization cache.
    private Object cachedVal;

    public JsonValue(MemorySegment segment, Tape tape, int tapeIndex) {
        this.segment = segment;
        this.heapBytes = segment.heapBase().map(o -> (byte[]) o).orElse(null);
        this.heapOffset = heapBytes == null
            ? 0 : Math.toIntExact(segment.address());
        this.tape = tape;
        this.tapeIndex = tapeIndex;
    }

    /**
     * Explicitly materializes and caches the value.
     */
    public JsonValue materialize() {
        if (cachedVal == null) {
            JsonType type = getType();
            switch (type) {
                case STRING -> getString();
                case INT64 -> getLong();
                case DOUBLE -> getDouble();
                case BOOLEAN -> getBoolean();
                default -> cachedVal = this;
            }
        }
        return this;
    }

    /**
     * Returns true if this value has been materialized and cached.
     */
    public boolean isMaterialized() {
        return cachedVal != null;
    }

    public JsonType getType() {
        char tag = tape.getTag(tapeIndex);
        return switch (tag) {
            case Tape.TAG_START_OBJECT -> JsonType.OBJECT;
            case Tape.TAG_START_ARRAY -> JsonType.ARRAY;
            case Tape.TAG_STRING -> JsonType.STRING;
            case Tape.TAG_INT64, Tape.TAG_UINT64, Tape.TAG_DOUBLE -> {
                if (cachedVal instanceof Long) yield JsonType.INT64;
                if (cachedVal instanceof Double) yield JsonType.DOUBLE;
                long pos = tape.getPayload(tapeIndex);
                long end = findNumberEnd(pos);
                long base = heapBytes == null ? 0L : heapOffset;
                yield FastNumberParser.isInteger(
                    segment, heapBytes, base + pos, base + end)
                    ? JsonType.INT64 : JsonType.DOUBLE;
            }
            case Tape.TAG_TRUE, Tape.TAG_FALSE -> JsonType.BOOLEAN;
            case Tape.TAG_NULL -> JsonType.NULL;
            case Tape.TAG_ROOT -> new JsonValue(segment, tape, (int) tape.getPayload(tapeIndex)).getType();
            default -> throw new SimdJsonException(SimdJsonError.INCORRECT_TYPE, "Unknown tag: " + tag);
        };
    }

    public String getString() {
        char tag = tape.getTag(tapeIndex);
        if (tag == Tape.TAG_ROOT) {
            return new JsonValue(segment, tape, (int) tape.getPayload(tapeIndex)).getString();
        }
        if (tag != Tape.TAG_STRING) {
            throw new SimdJsonException(SimdJsonError.INCORRECT_TYPE, "Expected STRING, got " + tag);
        }
        if (cachedVal instanceof String s) {
            return s;
        }

        long startPos = tape.getPayload(tapeIndex);
        long p = startPos + 1;
        long max = segment.byteSize();
        boolean hasEscape = false;

        while (p < max) {
            byte b = getByte(p);
            if (b == '\\') {
                hasEscape = true;
                p += 2;
                continue;
            }
            if (b == '"') {
                break;
            }
            p++;
        }

        long strLen = p - (startPos + 1);
        byte[] bytes = new byte[(int) strLen];
        if (heapBytes != null) {
            System.arraycopy(
                heapBytes, heapOffset + (int) (startPos + 1),
                bytes, 0, bytes.length);
        } else {
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, startPos + 1, bytes, 0, bytes.length);
        }

        String strResult = !hasEscape ? new String(bytes, StandardCharsets.UTF_8) : unescape(bytes);
        this.cachedVal = strResult;
        return strResult;
    }

    public long getLong() {
        char tag = tape.getTag(tapeIndex);
        if (tag == Tape.TAG_ROOT) {
            return new JsonValue(segment, tape, (int) tape.getPayload(tapeIndex)).getLong();
        }
        if (cachedVal instanceof Long l) {
            return l;
        }
        if (tag == Tape.TAG_INT64 || tag == Tape.TAG_UINT64 || tag == Tape.TAG_DOUBLE) {
            long pos = tape.getPayload(tapeIndex);
            long end = findNumberEnd(pos);
            long base = heapBytes == null ? 0L : heapOffset;
            long val = FastNumberParser.parseLong(
                segment, heapBytes, base + pos, base + end);
            this.cachedVal = val;
            return val;
        }
        throw new SimdJsonException(SimdJsonError.INCORRECT_TYPE, "Expected number, got " + tag);
    }

    public double getDouble() {
        char tag = tape.getTag(tapeIndex);
        if (tag == Tape.TAG_ROOT) {
            return new JsonValue(segment, tape, (int) tape.getPayload(tapeIndex)).getDouble();
        }
        if (cachedVal instanceof Double d) {
            return d;
        }
        if (cachedVal instanceof Long l) {
            return l.doubleValue();
        }
        if (tag == Tape.TAG_INT64 || tag == Tape.TAG_UINT64 || tag == Tape.TAG_DOUBLE) {
            long pos = tape.getPayload(tapeIndex);
            long end = findNumberEnd(pos);
            long base = heapBytes == null ? 0L : heapOffset;
            double val = FastNumberParser.parseDouble(
                segment, heapBytes, base + pos, base + end);
            this.cachedVal = val;
            return val;
        }
        throw new SimdJsonException(SimdJsonError.INCORRECT_TYPE, "Expected number, got " + tag);
    }

    public boolean getBoolean() {
        char tag = tape.getTag(tapeIndex);
        if (tag == Tape.TAG_ROOT) {
            return new JsonValue(segment, tape, (int) tape.getPayload(tapeIndex)).getBoolean();
        }
        if (cachedVal instanceof Boolean b) {
            return b;
        }
        if (tag == Tape.TAG_TRUE) {
            this.cachedVal = Boolean.TRUE;
            return true;
        }
        if (tag == Tape.TAG_FALSE) {
            this.cachedVal = Boolean.FALSE;
            return false;
        }
        throw new SimdJsonException(SimdJsonError.INCORRECT_TYPE, "Expected BOOLEAN, got " + tag);
    }

    public boolean isNull() {
        char tag = tape.getTag(tapeIndex);
        if (tag == Tape.TAG_ROOT) {
            return new JsonValue(segment, tape, (int) tape.getPayload(tapeIndex)).isNull();
        }
        return tag == Tape.TAG_NULL;
    }

    public JsonObject getObject() {
        char tag = tape.getTag(tapeIndex);
        if (tag == Tape.TAG_ROOT) {
            return new JsonValue(segment, tape, (int) tape.getPayload(tapeIndex)).getObject();
        }
        if (tag != Tape.TAG_START_OBJECT) {
            throw new SimdJsonException(SimdJsonError.INCORRECT_TYPE, "Expected OBJECT, got " + tag);
        }
        return new JsonObject(segment, tape, tapeIndex);
    }

    public JsonArray getArray() {
        char tag = tape.getTag(tapeIndex);
        if (tag == Tape.TAG_ROOT) {
            return new JsonValue(segment, tape, (int) tape.getPayload(tapeIndex)).getArray();
        }
        if (tag != Tape.TAG_START_ARRAY) {
            throw new SimdJsonException(SimdJsonError.INCORRECT_TYPE, "Expected ARRAY, got " + tag);
        }
        return new JsonArray(segment, tape, tapeIndex);
    }

    private long findNumberEnd(long pos) {
        long p = pos;
        long max = segment.byteSize();
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

    private byte getByte(long pos) {
        return heapBytes != null
            ? heapBytes[heapOffset + (int) pos]
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

    public int getTapeIndex() {
        return tapeIndex;
    }

    @Override
    public String toString() {
        return switch (getType()) {
            case OBJECT -> getObject().toString();
            case ARRAY -> getArray().toString();
            case STRING -> "\"" + getString() + "\"";
            case INT64 -> String.valueOf(getLong());
            case DOUBLE -> String.valueOf(getDouble());
            case BOOLEAN -> String.valueOf(getBoolean());
            case NULL -> "null";
        };
    }
}
