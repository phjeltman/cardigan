// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.serdes;

import dev.cardigan.ffi.RawSegment;
import dev.cardigan.http.Utf8Slice;
import dev.cardigan.simdjson.StructuralIndexes;
import dev.cardigan.simdjson.util.FastNumberParser;

import dev.cardigan.json.RecordCache;
import dev.cardigan.json.RecordCache.RecordMetadata;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * On-Demand SIMD Format Deserializer Driver.
 * Traverses JSON tokens lazily directly over MemorySegment without AST or Map allocations.
 */
public final class SimdJsonDeserializer {

    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    private static final int SPECIES_LEN = SPECIES.length();

    private static final java.lang.invoke.VarHandle BYTE_ARRAY_LONG_UNALIGNED = 
        java.lang.invoke.MethodHandles.byteArrayViewVarHandle(long[].class, java.nio.ByteOrder.LITTLE_ENDIAN);

    private MemorySegment segment;
    private byte[] heapBytes;
    private int heapOffset;
    private StructuralIndexes indexes;
    private long offset;
    private long maxOffset;
    private boolean mayContainEscapes = true;

    public SimdJsonDeserializer() {
    }

    public SimdJsonDeserializer(MemorySegment segment, StructuralIndexes indexes) {
        reset(segment, indexes);
    }

    public void reset(MemorySegment segment, StructuralIndexes indexes) {
        reset(segment, 0, segment.byteSize(), indexes);
    }

    public void reset(MemorySegment segment, long offset, long length, StructuralIndexes indexes) {
        reset(segment, offset, length, indexes, true);
    }

    public void reset(MemorySegment segment, long offset, long length,
                      StructuralIndexes indexes,
                      boolean mayContainEscapes) {
        this.segment = (offset == 0 && length == segment.byteSize()) ? segment : segment.asSlice(offset, length);
        this.indexes = indexes;
        this.offset = 0;
        this.maxOffset = length;
        this.mayContainEscapes = mayContainEscapes;
        Object base = segment.heapBase().orElse(null);
        if (base instanceof byte[] bytes) {
            this.heapBytes = bytes;
            this.heapOffset = Math.toIntExact(this.segment.address());
        } else {
            this.heapBytes = null;
            this.heapOffset = 0;
        }
    }

    public MemorySegment getSegment() {
        return segment;
    }

    public long getOffset() {
        return offset;
    }

    public boolean isNull() {
        skipWhitespace();
        if (offset + 4 <= maxOffset) {
            byte b0 = getByte(offset);
            byte b1 = getByte(offset + 1);
            byte b2 = getByte(offset + 2);
            byte b3 = getByte(offset + 3);
            if (b0 == 'n' && b1 == 'u' && b2 == 'l' && b3 == 'l') {
                offset += 4;
                return true;
            }
        }
        return false;
    }

    public boolean readBoolean() {
        skipWhitespace();
        if (offset < maxOffset) {
            byte b = getByte(offset);
            if (b == 't') {
                offset += 4;
                return true;
            } else if (b == 'f') {
                offset += 5;
                return false;
            }
        }
        throw new IllegalStateException("Expected boolean at offset " + offset);
    }

    public int readInt() {
        skipWhitespace();
        long end = findNumberEnd(offset);
        long base = heapBytes == null ? 0L : heapOffset;
        int result = FastNumberParser.parseInt(
            segment, heapBytes, base + offset, base + end);
        this.offset = end;
        return result;
    }

    public long readLong() {
        skipWhitespace();
        long end = findNumberEnd(offset);
        long base = heapBytes == null ? 0L : heapOffset;
        long res = FastNumberParser.parseLong(
            segment, heapBytes, base + offset, base + end);
        this.offset = end;
        return res;
    }

    public float readFloat() {
        return (float) readDouble();
    }

    public double readDouble() {
        skipWhitespace();
        long end = findNumberEnd(offset);
        long base = heapBytes == null ? 0L : heapOffset;
        double res = FastNumberParser.parseDouble(
            segment, heapBytes, base + offset, base + end);
        this.offset = end;
        return res;
    }

    private long findNumberEnd(long start) {
        long p = start;
        while (p < maxOffset) {
            byte b = getByte(p);
            if ((b >= '0' && b <= '9') || b == '.' || b == 'e' || b == 'E' || b == '+' || b == '-') {
                p++;
            } else {
                break;
            }
        }
        return p;
    }

    public Utf8Slice readStringSlice() {
        skipWhitespace();
        if (offset >= maxOffset || getByte(offset) != '"') {
            throw new IllegalStateException("Expected string quote at offset " + offset);
        }
        long start = offset + 1;
        long p = start;
        while (p < maxOffset) {
            byte b = getByte(p);
            if (b == '\\') {
                p += 2;
                continue;
            }
            if (b == '"') {
                break;
            }
            p++;
        }
        long len = p - start;
        offset = p + 1; // skip closing quote
        return new Utf8Slice(segment, start, len);
    }

    public String readString() {
        Utf8Slice slice = readStringSlice();
        if (slice == null) return null;
        return unescape(slice);
    }

    public <T> T visitStruct(StructVisitor<T> visitor) {
        int n = indexes.size();
        if (n < 2) return visitor.build();

        boolean isNative = indexes.isNative();
        int[] idxArr = isNative ? null : indexes.indexes();
        int prevPunct = isNative ? indexes.get(0) : idxArr[0];
        final byte[] localBytes = this.heapBytes;
        final int baseOff = this.heapOffset;

        if (localBytes != null) {
            for (int k = 1; k < n; k++) {
                int punctOffset = isNative ? indexes.get(k) : idxArr[k];
                byte punct = localBytes[baseOff + punctOffset];

                if (punct == ':') {
                    int keyQuoteStart = skipWhitespaceHeap(localBytes, baseOff, prevPunct + 1);
                    if (keyQuoteStart < punctOffset && localBytes[baseOff + keyQuoteStart] == '"') {
                        int keyQuoteEnd = skipWhitespaceBackHeap(localBytes, baseOff, keyQuoteStart + 1, punctOffset - 1);
                        if (keyQuoteEnd > keyQuoteStart && localBytes[baseOff + keyQuoteEnd] == '"') {
                            int keyStart = keyQuoteStart + 1;
                            int keyLen = keyQuoteEnd - keyStart;
                            this.offset = skipWhitespaceHeap(localBytes, baseOff, punctOffset + 1);
                            visitor.visitField(keyStart, keyLen, this);
                        }
                    }
                }
                prevPunct = punctOffset;
            }
        } else {
            for (int k = 1; k < n; k++) {
                int punctOffset = isNative ? indexes.get(k) : idxArr[k];
                byte punct = getByte(punctOffset);

                if (punct == ':') {
                    int keyQuoteStart = skipWhitespace(prevPunct + 1);
                    if (keyQuoteStart < punctOffset && getByte(keyQuoteStart) == '"') {
                        int keyQuoteEnd = skipWhitespaceBack(keyQuoteStart + 1, punctOffset - 1);
                        if (keyQuoteEnd > keyQuoteStart && getByte(keyQuoteEnd) == '"') {
                            int keyStart = keyQuoteStart + 1;
                            int keyLen = keyQuoteEnd - keyStart;
                            this.offset = skipWhitespace(punctOffset + 1);
                            visitor.visitField(keyStart, keyLen, this);
                        }
                    }
                }
                prevPunct = punctOffset;
            }
        }

        return visitor.build();
    }

    public <T> T visitSeq(SeqVisitor<T> visitor) {
        int n = indexes.size();
        if (n < 2) return visitor.build();

        boolean isNative = indexes.isNative();
        int[] idxArr = isNative ? null : indexes.indexes();
        int index = 0;
        int prevPunct = isNative ? indexes.get(0) : idxArr[0];
        final byte[] localBytes = this.heapBytes;
        final int baseOff = this.heapOffset;

        if (localBytes != null) {
            for (int k = 1; k < n; k++) {
                int punctOffset = isNative ? indexes.get(k) : idxArr[k];
                byte punct = localBytes[baseOff + punctOffset];

                if (punct == ',' || punct == ']') {
                    int elemStart = skipWhitespaceHeap(localBytes, baseOff, prevPunct + 1);
                    if (elemStart < punctOffset) {
                        this.offset = elemStart;
                        visitor.visitElement(index++, this);
                    }
                    prevPunct = punctOffset;
                }
            }
        } else {
            for (int k = 1; k < n; k++) {
                int punctOffset = isNative ? indexes.get(k) : idxArr[k];
                byte punct = getByte(punctOffset);

                if (punct == ',' || punct == ']') {
                    int elemStart = skipWhitespace(prevPunct + 1);
                    if (elemStart < punctOffset) {
                        this.offset = elemStart;
                        visitor.visitElement(index++, this);
                    }
                    prevPunct = punctOffset;
                }
            }
        }

        return visitor.build();
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> T readRecord(Class<T> recordClass) {
        RecordMetadata metadata = RecordCache.getMetadata(recordClass);
        return readRecord(metadata);
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> T readRecord(RecordMetadata metadata) {
        final byte[] localBytes = this.heapBytes;
        T direct = tryDirectPositionalRecord(metadata, this.segment, localBytes, this.heapOffset);
        if (direct != null) {
            return direct;
        }
        if (localBytes != null) {
            return readRecordHeap(metadata, localBytes, this.heapOffset);
        } else {
            return readRecordSegment(metadata, this.segment);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Record> T tryDirectPositionalRecord(RecordMetadata metadata, MemorySegment seg, byte[] localBytes, int baseOff) {
        int numFields = metadata.componentNames.length;
        if (numFields == 0 || numFields > 8) return null;

        int numStructs = indexes.size();
        if (numStructs != numFields * 2 + 1) return null;

        boolean isNative = indexes.isNative();
        int[] idxArr = isNative ? null : indexes.indexes();

        for (int i = 0; i < numFields; i++) {
            int colonIdx = 1 + 2 * i;
            int colonPos = isNative ? indexes.get(colonIdx) : idxArr[colonIdx];
            byte c = (localBytes != null) ? localBytes[baseOff + colonPos] : seg.get(ValueLayout.JAVA_BYTE, colonPos);
            if (c != ':') return null;

            int prevPos = isNative ? indexes.get(2 * i) : idxArr[2 * i];
            int keyStart = prevPos + 1;
            if (localBytes != null) {
                while (keyStart < colonPos && (localBytes[baseOff + keyStart] & 0xFF) <= ' ') keyStart++;
            } else {
                while (keyStart < colonPos && (seg.get(ValueLayout.JAVA_BYTE, keyStart) & 0xFF) <= ' ') keyStart++;
            }
            if (keyStart >= colonPos) return null;

            byte bStart = (localBytes != null) ? localBytes[baseOff + keyStart] : seg.get(ValueLayout.JAVA_BYTE, keyStart);
            if (bStart != '"') return null;

            int keyEnd = colonPos - 1;
            if (localBytes != null) {
                while (keyEnd > keyStart && (localBytes[baseOff + keyEnd] & 0xFF) <= ' ') keyEnd--;
            } else {
                while (keyEnd > keyStart && (seg.get(ValueLayout.JAVA_BYTE, keyEnd) & 0xFF) <= ' ') keyEnd--;
            }
            if (keyEnd <= keyStart) return null;

            byte bEnd = (localBytes != null) ? localBytes[baseOff + keyEnd] : seg.get(ValueLayout.JAVA_BYTE, keyEnd);
            if (bEnd != '"') return null;

            int keyLen = keyEnd - (keyStart + 1);
            if (keyLen != metadata.componentLengths[i]) return null;

            long word;
            int kStart = keyStart + 1;
            if (localBytes != null) {
                if (baseOff + kStart + 8 <= localBytes.length) {
                    word = (long) BYTE_ARRAY_LONG_UNALIGNED.get(localBytes, baseOff + kStart);
                } else {
                    word = 0;
                    int limit = Math.min(keyLen, 8);
                    for (int j = 0; j < limit; j++) {
                        word |= ((long) (localBytes[baseOff + kStart + j] & 0xFF)) << (j * 8);
                    }
                }
            } else {
                long addr = seg.address() + kStart;
                word = RawSegment.getLong(addr, 0);
            }

            if ((word & metadata.componentMasks[i]) != metadata.componentLongs[i]) {
                return null;
            }
        }

        Object a0 = null, a1 = null, a2 = null, a3 = null, a4 = null, a5 = null, a6 = null, a7 = null;

        for (int i = 0; i < numFields; i++) {
            int colonIdx = 1 + 2 * i;
            int colonPos = isNative ? indexes.get(colonIdx) : idxArr[colonIdx];
            int nextPos = isNative ? indexes.get(2 * i + 2) : idxArr[2 * i + 2];

            int valStart = colonPos + 1;
            if (localBytes != null) {
                while (valStart < nextPos && (localBytes[baseOff + valStart] & 0xFF) <= ' ') valStart++;
            } else {
                while (valStart < nextPos && (seg.get(ValueLayout.JAVA_BYTE, valStart) & 0xFF) <= ' ') valStart++;
            }

            int valEnd = nextPos - 1;
            if (localBytes != null) {
                while (valEnd >= valStart && (localBytes[baseOff + valEnd] & 0xFF) <= ' ') valEnd--;
            } else {
                while (valEnd >= valStart && (seg.get(ValueLayout.JAVA_BYTE, valEnd) & 0xFF) <= ' ') valEnd--;
            }
            valEnd++;

            int valLen = valEnd - valStart;
            byte firstByte = (localBytes != null) ? localBytes[baseOff + valStart] : seg.get(ValueLayout.JAVA_BYTE, valStart);

            Object parsedVal = null;
            if (valLen > 0) {
                switch (metadata.componentTypeCodes[i]) {
                    case RecordMetadata.TYPE_STRING -> {
                        if (firstByte == '"') {
                            parsedVal = unescapeSegment(seg, valStart + 1, valLen - 2);
                        }
                    }
                    case RecordMetadata.TYPE_UTF8_SLICE -> {
                        if (firstByte == '"') {
                            parsedVal = new Utf8Slice(seg, valStart + 1, valLen - 2);
                        } else {
                            parsedVal = new Utf8Slice(seg, valStart, valLen);
                        }
                    }
                    case RecordMetadata.TYPE_INT -> parsedVal = FastNumberParser.parseInt(seg, localBytes, (localBytes != null ? baseOff : 0) + valStart, (localBytes != null ? baseOff : 0) + valEnd);
                    case RecordMetadata.TYPE_LONG -> parsedVal = FastNumberParser.parseLong(seg, localBytes, (localBytes != null ? baseOff : 0) + valStart, (localBytes != null ? baseOff : 0) + valEnd);
                    case RecordMetadata.TYPE_FLOAT -> parsedVal = (float) FastNumberParser.parseDouble(seg, localBytes, (localBytes != null ? baseOff : 0) + valStart, (localBytes != null ? baseOff : 0) + valEnd);
                    case RecordMetadata.TYPE_DOUBLE -> parsedVal = FastNumberParser.parseDouble(seg, localBytes, (localBytes != null ? baseOff : 0) + valStart, (localBytes != null ? baseOff : 0) + valEnd);
                    case RecordMetadata.TYPE_BOOLEAN -> parsedVal = (firstByte == 't');
                    case RecordMetadata.TYPE_RECORD -> parsedVal = Serdes.fromJson(seg, valStart, valLen, (Class<? extends Record>) metadata.componentTypes[i]);
                }
            }

            if (parsedVal == null) parsedVal = metadata.defaultArgs[i];

            switch (i) {
                case 0 -> a0 = parsedVal;
                case 1 -> a1 = parsedVal;
                case 2 -> a2 = parsedVal;
                case 3 -> a3 = parsedVal;
                case 4 -> a4 = parsedVal;
                case 5 -> a5 = parsedVal;
                case 6 -> a6 = parsedVal;
                case 7 -> a7 = parsedVal;
            }
        }

        try {
            return switch (numFields) {
                case 1 -> (T) (Record) metadata.constructorHandle.invoke(a0);
                case 2 -> (T) (Record) metadata.constructorHandle.invoke(a0, a1);
                case 3 -> (T) (Record) metadata.constructorHandle.invoke(a0, a1, a2);
                case 4 -> (T) (Record) metadata.constructorHandle.invoke(a0, a1, a2, a3);
                case 5 -> (T) (Record) metadata.constructorHandle.invoke(a0, a1, a2, a3, a4);
                case 6 -> (T) (Record) metadata.constructorHandle.invoke(a0, a1, a2, a3, a4, a5);
                case 7 -> (T) (Record) metadata.constructorHandle.invoke(a0, a1, a2, a3, a4, a5, a6);
                case 8 -> (T) (Record) metadata.constructorHandle.invoke(a0, a1, a2, a3, a4, a5, a6, a7);
                default -> null;
            };
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Record> T readRecordHeap(RecordMetadata metadata, byte[] localBytes, int baseOff) {
        Object[] args = metadata.defaultArgs.clone();

        int n = indexes.size();
        if (n < 2) return null;

        boolean isNative = indexes.isNative();
        int[] idxArr = isNative ? null : indexes.indexes();

        int prevPunct = isNative ? indexes.get(0) : idxArr[0];
        int fieldIdx = 0;

        for (int k = 1; k < n; k++) {
            int punctOffset = isNative ? indexes.get(k) : idxArr[k];
            byte punct = localBytes[baseOff + punctOffset];

            if (punct == ':') {
                int keyQuoteStart = (localBytes[baseOff + prevPunct + 1] == '"') ? (prevPunct + 1) : skipWhitespaceHeap(localBytes, baseOff, prevPunct + 1);
                byte bStart = localBytes[baseOff + keyQuoteStart];
                if (keyQuoteStart < punctOffset && bStart == '"') {
                    int keyQuoteEnd = (localBytes[baseOff + punctOffset - 1] == '"') ? (punctOffset - 1) : skipWhitespaceBackHeap(localBytes, baseOff, keyQuoteStart + 1, punctOffset - 1);
                    byte bEnd = localBytes[baseOff + keyQuoteEnd];
                    if (keyQuoteEnd > keyQuoteStart && bEnd == '"') {
                        int keyStart = keyQuoteStart + 1;
                        int keyLen = keyQuoteEnd - keyStart;
                        int compIndex = metadata.matchKeyHeap(localBytes, baseOff + keyStart, keyLen, fieldIdx++);

                        if (compIndex != -1) {
                            int valStart = ((localBytes[baseOff + punctOffset + 1] & 0xFF) > ' ') ? (punctOffset + 1) : skipWhitespaceHeap(localBytes, baseOff, punctOffset + 1);
                            byte firstByte = localBytes[baseOff + valStart];
                            int valEnd;
                            if (firstByte == '"') {
                                int nextPunct = (k + 1 < n) ? (isNative ? indexes.get(k + 1) : idxArr[k + 1]) : (int) maxOffset;
                                valEnd = (nextPunct > 0 && localBytes[baseOff + nextPunct - 1] == '"') ? nextPunct : skipWhitespaceBackHeap(localBytes, baseOff, valStart, nextPunct - 1) + 1;
                            } else if (firstByte == '{' || firstByte == '[') {
                                int depth = 0;
                                int endIdx = k + 1;
                                while (endIdx < n) {
                                    int endPos = isNative ? indexes.get(endIdx) : idxArr[endIdx];
                                    byte c = localBytes[baseOff + endPos];
                                    if (c == '{' || c == '[') depth++;
                                    else if (c == '}' || c == ']') depth--;
                                    endIdx++;
                                    if (depth == 0) break;
                                }
                                valEnd = (endIdx <= n) ? (isNative ? indexes.get(endIdx - 1) : idxArr[endIdx - 1]) + 1 : (int) maxOffset;
                            } else {
                                valEnd = (k + 1 < n) ? (isNative ? indexes.get(k + 1) : idxArr[k + 1]) : (int) maxOffset;
                            }
                            int valLen = valEnd - valStart;

                            if (valLen > 0) {
                                final byte[] typeCodes = metadata.componentTypeCodes;
                                if (compIndex >= 0 && compIndex < typeCodes.length) {
                                    switch (typeCodes[compIndex]) {
                                        case RecordMetadata.TYPE_STRING -> {
                                            if (firstByte == '"') {
                                                args[compIndex] = unescapeHeap(localBytes, baseOff + valStart + 1, valLen - 2);
                                            }
                                        }
                                        case RecordMetadata.TYPE_UTF8_SLICE -> {
                                            if (firstByte == '"') {
                                                args[compIndex] = new Utf8Slice(segment, valStart + 1, valLen - 2);
                                            } else {
                                                args[compIndex] = new Utf8Slice(segment, valStart, valLen);
                                            }
                                        }
                                        case RecordMetadata.TYPE_INT -> args[compIndex] = FastNumberParser.parseInt(segment, localBytes, baseOff + valStart, baseOff + valEnd);
                                        case RecordMetadata.TYPE_LONG -> args[compIndex] = FastNumberParser.parseLong(segment, localBytes, baseOff + valStart, baseOff + valEnd);
                                        case RecordMetadata.TYPE_FLOAT -> args[compIndex] = (float) FastNumberParser.parseDouble(segment, localBytes, baseOff + valStart, baseOff + valEnd);
                                        case RecordMetadata.TYPE_DOUBLE -> args[compIndex] = FastNumberParser.parseDouble(segment, localBytes, baseOff + valStart, baseOff + valEnd);
                                        case RecordMetadata.TYPE_BOOLEAN -> args[compIndex] = (firstByte == 't');
                                        case RecordMetadata.TYPE_RECORD -> args[compIndex] = Serdes.fromJson(segment, valStart, valLen, (Class<? extends Record>) metadata.componentTypes[compIndex]);
                                    }
                                }
                                while (k + 1 < n && (isNative ? indexes.get(k + 1) : idxArr[k + 1]) < valEnd) {
                                    k++;
                                }
                            }
                        }
                    }
                }
            }
            prevPunct = punctOffset;
        }

        try {
            return (T) (Record) metadata.constructorSpreader.invokeExact(args);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to instantiate record: " + metadata.constructor.getDeclaringClass().getName(), e);
        }
    }

    private int skipWhitespaceHeap(byte[] localBytes, int baseOff, int start) {
        int max = (int) maxOffset;
        int p = start;
        while (p + SPECIES_LEN <= max) {
            ByteVector v = ByteVector.fromArray(SPECIES, localBytes, baseOff + p);
            VectorMask<Byte> nonSpaceMask = v.compare(VectorOperators.GT, (byte) 0x20);
            if (nonSpaceMask.anyTrue()) {
                return p + nonSpaceMask.firstTrue();
            }
            p += SPECIES_LEN;
        }
        while (p < max) {
            if ((localBytes[baseOff + p] & 0xFF) > ' ') return p;
            p++;
        }
        return p;
    }

    private int skipWhitespaceBackHeap(byte[] localBytes, int baseOff, int start, int end) {
        int p = end;
        while (p - SPECIES_LEN + 1 >= start) {
            int loadOffset = p - SPECIES_LEN + 1;
            ByteVector v = ByteVector.fromArray(SPECIES, localBytes, baseOff + loadOffset);
            VectorMask<Byte> nonSpaceMask = v.compare(VectorOperators.GT, (byte) 0x20);
            if (nonSpaceMask.anyTrue()) {
                long bits = nonSpaceMask.toLong();
                int lastBitPos = 63 - Long.numberOfLeadingZeros(bits);
                return loadOffset + lastBitPos;
            }
            p -= SPECIES_LEN;
        }
        while (p >= start) {
            if ((localBytes[baseOff + p] & 0xFF) > ' ') return p;
            p--;
        }
        return p;
    }

    private String unescapeHeap(byte[] heapBytes, int start, int len) {
        if (len <= 0) return "";

        boolean hasEscape = mayContainEscapes;
        int end = start + len;
        if (hasEscape) {
            hasEscape = false;
            for (int i = start; i < end; i++) {
                if (heapBytes[i] == '\\') {
                    hasEscape = true;
                    break;
                }
            }
        }

        if (!hasEscape) {
            return new String(heapBytes, start, len, StandardCharsets.UTF_8);
        }

        return decodeEscapedUtf8(heapBytes, start, len);
    }

    private static String decodeEscapedUtf8(
            byte[] bytes, int start, int len) {
        int end = start + len;
        StringBuilder sb = new StringBuilder(len);
        int chunkStart = start;
        for (int i = start; i < end; i++) {
            byte b = bytes[i];
            if (b == '\\' && i + 1 < end) {
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
                        if (i + 4 < end) {
                            String hex = new String(
                                bytes, i + 1, 4,
                                StandardCharsets.US_ASCII);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        }
                    }
                    default -> sb.append((char) esc);
                }
                chunkStart = i + 1;
            }
        }
        if (chunkStart < end) {
            sb.append(new String(
                bytes, chunkStart, end - chunkStart,
                StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private String unescapeSegment(MemorySegment seg, long start, int len) {
        if (len <= 0) return "";
        if (heapBytes != null) {
            return unescapeHeap(heapBytes, heapOffset + (int) start, len);
        }
        boolean hasEscape = mayContainEscapes;
        long ptr = seg.address() + start;
        if (hasEscape) {
            hasEscape = false;
            for (int i = 0; i < len; i++) {
                if (RawSegment.getByte(ptr, i) == '\\') {
                    hasEscape = true;
                    break;
                }
            }
        }
        byte[] bytes = new byte[len];
        MemorySegment.copy(
            seg,
            ValueLayout.JAVA_BYTE,
            start,
            bytes,
            0,
            len
        );
        if (!hasEscape) {
            return new String(bytes, StandardCharsets.UTF_8);
        } else {
            return unescapeHeap(bytes, 0, len);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Record> T readRecordSegment(RecordMetadata metadata, MemorySegment seg) {
        Object[] args = metadata.defaultArgs.clone();

        int n = indexes.size();
        if (n < 2) return null;

        boolean isNative = indexes.isNative();
        int[] idxArr = isNative ? null : indexes.indexes();

        int prevPunct = isNative ? indexes.get(0) : idxArr[0];
        int fieldIdx = 0;

        for (int k = 1; k < n; k++) {
            int punctOffset = isNative ? indexes.get(k) : idxArr[k];
            byte punct = getByte(punctOffset);

            if (punct == ':') {
                int keyQuoteStart = skipWhitespace(null, prevPunct + 1);
                byte bStart = getByte(keyQuoteStart);
                if (keyQuoteStart < punctOffset && bStart == '"') {
                    int keyQuoteEnd = skipWhitespaceBack(null, keyQuoteStart + 1, punctOffset - 1);
                    byte bEnd = getByte(keyQuoteEnd);
                    if (keyQuoteEnd > keyQuoteStart && bEnd == '"') {
                        int keyStart = keyQuoteStart + 1;
                        int keyLen = keyQuoteEnd - keyStart;
                        int compIndex = metadata.matchKey(seg, null, keyStart, keyLen, fieldIdx++);

                        if (compIndex != -1) {
                            int valStart = skipWhitespace(null, punctOffset + 1);
                            byte firstByte = getByte(valStart);
                            int valEnd;
                            if (firstByte == '"') {
                                int nextPunct = (k + 1 < n) ? (isNative ? indexes.get(k + 1) : idxArr[k + 1]) : (int) maxOffset;
                                valEnd = skipWhitespaceBack(null, valStart, nextPunct - 1) + 1;
                            } else if (firstByte == '{' || firstByte == '[') {
                                int depth = 0;
                                int endIdx = k + 1;
                                while (endIdx < n) {
                                    int endPos = isNative ? indexes.get(endIdx) : idxArr[endIdx];
                                    byte c = getByte(endPos);
                                    if (c == '{' || c == '[') depth++;
                                    else if (c == '}' || c == ']') depth--;
                                    endIdx++;
                                    if (depth == 0) break;
                                }
                                valEnd = (endIdx <= n) ? (isNative ? indexes.get(endIdx - 1) : idxArr[endIdx - 1]) + 1 : (int) maxOffset;
                            } else {
                                valEnd = (k + 1 < n) ? (isNative ? indexes.get(k + 1) : idxArr[k + 1]) : (int) maxOffset;
                            }
                            int valLen = valEnd - valStart;

                            if (valLen > 0) {
                                switch (metadata.componentTypeCodes[compIndex]) {
                                    case RecordMetadata.TYPE_STRING -> {
                                        if (firstByte == '"') {
                                            args[compIndex] = unescapeSegment(seg, valStart + 1, valLen - 2);
                                        }
                                    }
                                    case RecordMetadata.TYPE_UTF8_SLICE -> {
                                        if (firstByte == '"') {
                                            args[compIndex] = new Utf8Slice(seg, valStart + 1, valLen - 2);
                                        } else {
                                            args[compIndex] = new Utf8Slice(seg, valStart, valLen);
                                        }
                                    }
                                    case RecordMetadata.TYPE_INT -> args[compIndex] = FastNumberParser.parseInt(seg, null, valStart, valEnd);
                                    case RecordMetadata.TYPE_LONG -> args[compIndex] = FastNumberParser.parseLong(seg, null, valStart, valEnd);
                                    case RecordMetadata.TYPE_FLOAT -> args[compIndex] = (float) FastNumberParser.parseDouble(seg, null, valStart, valEnd);
                                    case RecordMetadata.TYPE_DOUBLE -> args[compIndex] = FastNumberParser.parseDouble(seg, null, valStart, valEnd);
                                    case RecordMetadata.TYPE_BOOLEAN -> args[compIndex] = (firstByte == 't');
                                    case RecordMetadata.TYPE_RECORD -> args[compIndex] = Serdes.fromJson(seg, valStart, valLen, (Class<? extends Record>) metadata.componentTypes[compIndex]);
                                }
                                while (k + 1 < n && (isNative ? indexes.get(k + 1) : idxArr[k + 1]) < valEnd) {
                                    k++;
                                }
                            }
                        }
                    }
                }
            }
            prevPunct = punctOffset;
        }

        try {
            return (T) (Record) metadata.constructorSpreader.invokeExact(args);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to instantiate record: " + metadata.constructor.getDeclaringClass().getName(), e);
        }
    }

    private int skipWhitespaceBack(int start, int end) {
        return skipWhitespaceBack(heapBytes, start, end);
    }

    private int skipWhitespaceBack(byte[] localBytes, int start, int end) {
        int p = end;
        if (localBytes != null) {
            while (p - SPECIES_LEN + 1 >= start) {
                int loadOffset = p - SPECIES_LEN + 1;
                ByteVector v = ByteVector.fromArray(SPECIES, localBytes, loadOffset);
                VectorMask<Byte> nonSpaceMask = v.compare(VectorOperators.GT, (byte) 0x20);
                if (nonSpaceMask.anyTrue()) {
                    long bits = nonSpaceMask.toLong();
                    int lastBitPos = 63 - Long.numberOfLeadingZeros(bits);
                    return loadOffset + lastBitPos;
                }
                p -= SPECIES_LEN;
            }
            while (p >= start) {
                if ((localBytes[p] & 0xFF) > ' ') return p;
                p--;
            }
            return p;
        } else {
            while (p - SPECIES_LEN + 1 >= start) {
                long loadOffset = (long) (p - SPECIES_LEN + 1);
                ByteVector v = ByteVector.fromMemorySegment(SPECIES, segment, loadOffset, ByteOrder.nativeOrder());
                VectorMask<Byte> nonSpaceMask = v.compare(VectorOperators.GT, (byte) 0x20);
                if (nonSpaceMask.anyTrue()) {
                    long bits = nonSpaceMask.toLong();
                    int lastBitPos = 63 - Long.numberOfLeadingZeros(bits);
                    return (int) loadOffset + lastBitPos;
                }
                p -= SPECIES_LEN;
            }
            while (p >= start) {
                if ((getByte(p) & 0xFF) > ' ') return p;
                p--;
            }
            return p;
        }
    }

    public void skipWhitespace() {
        this.offset = skipWhitespace(heapBytes, (int) offset);
    }

    private int skipWhitespace(int start) {
        return skipWhitespace(heapBytes, start);
    }

    private int skipWhitespace(byte[] localBytes, int start) {
        int max = (int) maxOffset;
        int p = start;
        if (localBytes != null) {
            while (p + SPECIES_LEN <= max) {
                ByteVector v = ByteVector.fromArray(SPECIES, localBytes, p);
                VectorMask<Byte> nonSpaceMask = v.compare(VectorOperators.GT, (byte) 0x20);
                if (nonSpaceMask.anyTrue()) {
                    return p + nonSpaceMask.firstTrue();
                }
                p += SPECIES_LEN;
            }
            while (p < max) {
                if ((localBytes[p] & 0xFF) > ' ') return p;
                p++;
            }
            return p;
        } else {
            while (p + SPECIES_LEN <= max) {
                ByteVector v = ByteVector.fromMemorySegment(SPECIES, segment, (long) p, ByteOrder.nativeOrder());
                VectorMask<Byte> nonSpaceMask = v.compare(VectorOperators.GT, (byte) 0x20);
                if (nonSpaceMask.anyTrue()) {
                    return p + nonSpaceMask.firstTrue();
                }
                p += SPECIES_LEN;
            }
            while (p < max) {
                if ((getByte(p) & 0xFF) > ' ') return p;
                p++;
            }
            return p;
        }
    }

    public <T> T read(Deserializer<T> deserializer) {
        return deserializer.deserialize(this);
    }

    public void skipValue() {
        skipWhitespace();
        if (offset >= maxOffset) return;

        byte b = getByte(offset);
        if (b == '"') {
            skipString();
        } else if (b == '{') {
            offset++;
            int depth = 1;
            while (offset < maxOffset && depth > 0) {
                byte c = getByte(offset);
                if (c == '"') {
                    skipString();
                } else if (c == '{') {
                    depth++;
                    offset++;
                } else if (c == '}') {
                    depth--;
                    offset++;
                } else {
                    offset++;
                }
            }
        } else if (b == '[') {
            offset++;
            int depth = 1;
            while (offset < maxOffset && depth > 0) {
                byte c = getByte(offset);
                if (c == '"') {
                    skipString();
                } else if (c == '[') {
                    depth++;
                    offset++;
                } else if (c == ']') {
                    depth--;
                    offset++;
                } else {
                    offset++;
                }
            }
        } else {
            while (offset < maxOffset) {
                byte c = getByte(offset);
                if (c == ',' || c == '}' || c == ']' || c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    break;
                }
                offset++;
            }
        }
    }

    public boolean keyEquals(long keyOffset, int keyLen, String targetKey) {
        if (targetKey.length() != keyLen) return false;
        for (int i = 0; i < keyLen; i++) {
            if (getByte(keyOffset + i) != (byte) targetKey.charAt(i)) return false;
        }
        return true;
    }

    public boolean keyEqualsSwar(long keyOffset, int keyLen, long expectedMask, long expectedWord) {
        if (keyLen <= 8) {
            long inputWord = heapBytes != null ?
                    readLongArray(heapBytes, (int) keyOffset) :
                    RawSegment.getLong(segment.address(), keyOffset);
            return (inputWord & expectedMask) == expectedWord;
        }
        return false;
    }

    private static long readLongArray(byte[] b, int off) {
        long val = 0;
        int limit = Math.min(8, b.length - off);
        for (int i = 0; i < limit; i++) {
            val |= ((long) (b[off + i] & 0xFF)) << (i * 8);
        }
        return val;
    }



    private void skipString() {
        offset++; // skip '"'
        while (offset < maxOffset) {
            byte b = getByte(offset);
            if (b == '\\') {
                offset += 2;
                continue;
            }
            if (b == '"') {
                offset++; // skip closing '"'
                break;
            }
            offset++;
        }
    }

    private byte getByte(long pos) {
        return heapBytes != null ? heapBytes[heapOffset + (int) pos] : segment.get(ValueLayout.JAVA_BYTE, pos);
    }

    private String unescape(Utf8Slice slice) {
        int len = (int) slice.length();
        if (len <= 0) return "";

        long start = slice.offset();
        if (heapBytes != null) {
            return unescapeHeap(heapBytes, heapOffset + (int) start, len);
        }

        boolean hasEscape = mayContainEscapes;
        if (hasEscape) {
            hasEscape = false;
            for (int i = 0; i < len; i++) {
                if (getByte(start + i) == '\\') {
                    hasEscape = true;
                    break;
                }
            }
        }

        byte[] bytes = new byte[len];
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, start, bytes, 0, len);

        if (!hasEscape) {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        return decodeEscapedUtf8(bytes, 0, bytes.length);
    }
}
