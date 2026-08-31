/*
 * Copyright (c) 2009-2014 Kazuho Oku, Tokuhiro Matsuno, Daisuke Murase,
 *                         Shigeo Mitsunari
 * Copyright (c) 2026 dev.cardigan contributors
 *
 * The software is licensed under either the MIT License (below) or the Perl
 * license.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to
 * deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */

package dev.cardigan.pico;

import dev.cardigan.ffi.RawSegment;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

public class PicoHTTPParser {
    public static final int ERROR_PARSE = -1;
    public static final int ERROR_PARTIAL = -2;
    public static final int ERROR_CHUNKED_PAYLOAD_LIMIT = -3;
    public static final int ERROR_CHUNKED_METADATA_LIMIT = -4;
    public static final int CHUNKED_SPAN_DATA = 0;
    public static final int CHUNKED_OUTPUT_FULL = 0;
    public static final int CHUNKED_COMPLETE = 1;

    private static final ValueLayout.OfInt JAVA_INT_UNALIGNED_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong JAVA_LONG_UNALIGNED_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort JAVA_SHORT_UNALIGNED_LE = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static final VectorSpecies<Byte> RANGE_SPECIES =
        ByteVector.SPECIES_256;
    private static final VectorSpecies<Byte> TOKEN_SPECIES =
        ByteVector.SPECIES_128;
    private static final int RANGE_VECTOR_WIDTH = RANGE_SPECIES.length();
    private static final int TOKEN_VECTOR_WIDTH = TOKEN_SPECIES.length();

    // SIMD Nibble Shuffle Lookup Tables (vpshufb via selectFrom)
    private static final byte[] RT_LOW = { (byte) 0x0b, (byte) 0x01, (byte) 0x03, (byte) 0x01, (byte) 0x01, (byte) 0x01, (byte) 0x01, (byte) 0x01, (byte) 0x03, (byte) 0x03, (byte) 0x05, (byte) 0x35, (byte) 0x17, (byte) 0x35, (byte) 0x05, (byte) 0x27 };
    private static final byte[] RT_HIGH = { (byte) 0x01, (byte) 0x01, (byte) 0x02, (byte) 0x04, (byte) 0x08, (byte) 0x10, (byte) 0x00, (byte) 0x20, (byte) 0x01, (byte) 0x01, (byte) 0x01, (byte) 0x01, (byte) 0x01, (byte) 0x01, (byte) 0x01, (byte) 0x01 };

    private static final ByteVector V_RT_LOW =
        ByteVector.fromArray(TOKEN_SPECIES, RT_LOW, 0);
    private static final ByteVector V_RT_HIGH =
        ByteVector.fromArray(TOKEN_SPECIES, RT_HIGH, 0);

    private static final boolean[] TOKEN_CHAR_MAP = new boolean[256];
    static {
        String mapStr = "\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0"
                      + "\0\1\0\1\1\1\1\1\0\0\1\1\0\1\1\0\1\1\1\1\1\1\1\1\1\1\0\0\0\0\0\0"
                      + "\0\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\0\0\0\1\1"
                      + "\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\1\0\1\0\1\0"
                      + "\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0"
                      + "\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0"
                      + "\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0"
                      + "\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0";
        for (int i = 0; i < 256; i++) {
            TOKEN_CHAR_MAP[i] = mapStr.charAt(i) == 1;
        }
    }

    private static final byte CHUNKED_IN_CHUNK_SIZE = 0;
    private static final byte CHUNKED_IN_CHUNK_EXT = 1;
    private static final byte CHUNKED_IN_CHUNK_HEADER_EXPECT_LF = 2;
    private static final byte CHUNKED_IN_CHUNK_DATA = 3;
    private static final byte CHUNKED_IN_CHUNK_DATA_EXPECT_CR = 4;
    private static final byte CHUNKED_IN_CHUNK_DATA_EXPECT_LF = 5;
    private static final byte CHUNKED_IN_TRAILERS_LINE_HEAD = 6;
    private static final byte CHUNKED_IN_TRAILERS_LINE_MIDDLE = 7;

    // Native callers use ADDRESS_SPACE plus the buffer address as base. This
    // keeps the segment scope and size constant in C2 while parser results
    // remain offsets relative to the caller's original segment. Heap segments
    // use the same routines with a zero base.
    private static long findchar_fast_ranges1(MemorySegment ms, long base,
                                               long offset, long limit) {
        long i = offset;
        long len = limit - offset;
        if (len >= RANGE_VECTOR_WIDTH) {
            long left = len - len % RANGE_VECTOR_WIDTH;
            long end = offset + left;
            for (; i < end; i += RANGE_VECTOR_WIDTH) {
                ByteVector v = ByteVector.fromMemorySegment(
                    RANGE_SPECIES, ms, base + i, ByteOrder.nativeOrder());
                VectorMask<Byte> match = v.compare(
                    VectorOperators.ULT, (byte) 0x20).and(
                        v.compare(VectorOperators.NE, (byte) '\t')).or(
                            v.compare(VectorOperators.EQ, (byte) 0x7f));
                if (match.anyTrue()) {
                    return i + match.firstTrue();
                }
            }
        }
        while (limit - i >= 8) {
            long val = ms.get(JAVA_LONG_UNALIGNED_LE, base + i);
            long maskLE1F = (val - 0x2020202020202020L) & ~val & 0x8080808080808080L;
            long xTab = val ^ 0x0909090909090909L;
            long maskTab = (xTab - 0x0101010101010101L) & ~xTab & 0x8080808080808080L;
            long maskCtrlExceptTab = maskLE1F & ~maskTab;

            long x7F = val ^ 0x7F7F7F7F7F7F7F7FL;
            long mask7F = (x7F - 0x0101010101010101L) & ~x7F & 0x8080808080808080L;
            long match = maskCtrlExceptTab | mask7F;
            if (match != 0) {
                return i + (Long.numberOfTrailingZeros(match) >>> 3);
            }
            i += 8;
        }
        return i;
    }

    private static long findchar_fast_ranges2(MemorySegment ms, long base,
                                               long offset, long limit) {
        long i = offset;
        long len = limit - offset;
        if (len >= RANGE_VECTOR_WIDTH) {
            long left = len - len % RANGE_VECTOR_WIDTH;
            long end = offset + left;
            for (; i < end; i += RANGE_VECTOR_WIDTH) {
                ByteVector v = ByteVector.fromMemorySegment(
                    RANGE_SPECIES, ms, base + i, ByteOrder.nativeOrder());
                VectorMask<Byte> match = v.compare(
                    VectorOperators.ULE, (byte) 0x20).or(
                        v.compare(VectorOperators.EQ, (byte) 0x7f));
                if (match.anyTrue()) {
                    return i + match.firstTrue();
                }
            }
        }
        while (limit - i >= 8) {
            long val = ms.get(JAVA_LONG_UNALIGNED_LE, base + i);
            long maskLE20 = (val - 0x2121212121212121L) & ~val & 0x8080808080808080L;
            long x7F = val ^ 0x7F7F7F7F7F7F7F7FL;
            long mask7F = (x7F - 0x0101010101010101L) & ~x7F & 0x8080808080808080L;
            long match = maskLE20 | mask7F;
            if (match != 0) {
                return i + (Long.numberOfTrailingZeros(match) >>> 3);
            }
            i += 8;
        }
        return i;
    }

    private static long findchar_fast_token(MemorySegment ms, long base,
                                            long offset, long limit) {
        long i = offset;
        long len = limit - offset;
        if (len >= TOKEN_VECTOR_WIDTH) {
            long left = len - len % TOKEN_VECTOR_WIDTH;
            long end = offset + left;
            for (; i < end; i += TOKEN_VECTOR_WIDTH) {
                ByteVector v = ByteVector.fromMemorySegment(
                    TOKEN_SPECIES, ms, base + i, ByteOrder.nativeOrder());
                ByteVector lowIdx = v.and((byte) 0x0F);
                ByteVector highIdx = v.lanewise(VectorOperators.LSHR, 4)
                    .and((byte) 0x0F);

                ByteVector lowMask = lowIdx.selectFrom(V_RT_LOW);
                ByteVector highMask = highIdx.selectFrom(V_RT_HIGH);

                VectorMask<Byte> match = lowMask.and(highMask).compare(VectorOperators.NE, (byte) 0);
                if (match.anyTrue()) {
                    return i + match.firstTrue();
                }
            }
        }
        while (limit - i >= 8) {
            long val = ms.get(JAVA_LONG_UNALIGNED_LE, base + i);
            if (!TOKEN_CHAR_MAP[(int) (val & 0xFF)] ||
                !TOKEN_CHAR_MAP[(int) ((val >>> 8) & 0xFF)] ||
                !TOKEN_CHAR_MAP[(int) ((val >>> 16) & 0xFF)] ||
                !TOKEN_CHAR_MAP[(int) ((val >>> 24) & 0xFF)] ||
                !TOKEN_CHAR_MAP[(int) ((val >>> 32) & 0xFF)] ||
                !TOKEN_CHAR_MAP[(int) ((val >>> 40) & 0xFF)] ||
                !TOKEN_CHAR_MAP[(int) ((val >>> 48) & 0xFF)] ||
                !TOKEN_CHAR_MAP[(int) ((val >>> 56) & 0xFF)]) {
                return i;
            }
            i += 8;
        }
        return i;
    }

    private static boolean isPrintableAscii(byte c) {
        int uc = c & 0xFF;
        return uc >= 32 && uc < 127;
    }

    private static long parseToken(MemorySegment ms, long base, long offset,
                                   long limit, byte nextChar) {
        long i = findchar_fast_token(ms, base, offset, limit);
        while (true) {
            if (i == limit) {
                return -2;
            }
            byte b = ms.get(ValueLayout.JAVA_BYTE, base + i);
            if (b == nextChar) {
                break;
            } else if (!TOKEN_CHAR_MAP[b & 0xFF]) {
                return -1;
            }
            i++;
        }
        return i;
    }

    private static long advanceToken(MemorySegment ms, long base, long offset,
                                     long limit) {
        long i = findchar_fast_ranges2(ms, base, offset, limit);
        while (true) {
            if (i == limit) {
                return -2;
            }
            byte b = ms.get(ValueLayout.JAVA_BYTE, base + i);
            if (b == (byte) ' ') {
                break;
            } else if (!isPrintableAscii(b)) {
                int ub = b & 0xFF;
                if (ub < 0x20 || ub == 0x7f) {
                    return -1;
                }
            }
            i++;
        }
        return i;
    }

    private static long getTokenToEol(MemorySegment ms, long base, long offset,
                                      long limit) {
        long i = findchar_fast_ranges1(ms, base, offset, limit);
        while (true) {
            if (i == limit) {
                return -2;
            }
            byte b = ms.get(ValueLayout.JAVA_BYTE, base + i);
            if (!isPrintableAscii(b)) {
                int ub = b & 0xFF;
                if ((ub < 0x20 && ub != 0x09) || ub == 0x7f) {
                    break;
                }
            }
            i++;
        }
        long tokenLen;
        long nextOffset;
        byte currentByte = ms.get(ValueLayout.JAVA_BYTE, base + i);
        if (currentByte == (byte) '\r') {
            i++;
            if (i == limit) {
                return -2;
            }
            if (ms.get(ValueLayout.JAVA_BYTE, base + i) != (byte) '\n') {
                return -1;
            }
            tokenLen = i - 1 - offset;
            nextOffset = i + 1;
        } else if (currentByte == (byte) '\n') {
            tokenLen = i - offset;
            nextOffset = i + 1;
        } else {
            return -1;
        }
        return ((tokenLen & 0xFFFFFFFFL) << 32) | (nextOffset & 0xFFFFFFFFL);
    }

    private static long parseHttpVersionReq(MemorySegment ms, long base,
                                            long offset, long limit,
                                            Request req) {
        if (limit - offset < 9) {
            return -2;
        }
        long i = offset;
        long prefix = ms.get(JAVA_LONG_UNALIGNED_LE, base + i)
            & 0x00FFFFFFFFFFFFFFL;
        if (prefix != 0x002E312F50545448L) { // "HTTP/1."
            return -1;
        }
        byte ch = ms.get(ValueLayout.JAVA_BYTE, base + i + 7);
        if (ch < (byte) '0' || ch > (byte) '9') {
            return -1;
        }
        req.minorVersion = ch - '0';
        return i + 8;
    }

    private static long parseHttpVersionRes(MemorySegment ms, long base,
                                            long offset, long limit,
                                            Response res) {
        if (limit - offset < 9) {
            return -2;
        }
        long i = offset;
        long prefix = ms.get(JAVA_LONG_UNALIGNED_LE, base + i)
            & 0x00FFFFFFFFFFFFFFL;
        if (prefix != 0x002E312F50545448L) { // "HTTP/1."
            return -1;
        }
        byte ch = ms.get(ValueLayout.JAVA_BYTE, base + i + 7);
        if (ch < (byte) '0' || ch > (byte) '9') {
            return -1;
        }
        res.minorVersion = ch - '0';
        return i + 8;
    }

    private static long isComplete(MemorySegment ms, long base, long offset,
                                   long limit, long lastLen) {
        long i = lastLen < 3 ? offset : offset + lastLen - 3;
        int retCnt = 0;
        while (true) {
            if (i == limit) {
                return -2;
            }
            byte b = ms.get(ValueLayout.JAVA_BYTE, base + i);
            if (b == (byte) '\r') {
                i++;
                if (i == limit) {
                    return -2;
                }
                if (ms.get(ValueLayout.JAVA_BYTE, base + i) != (byte) '\n') {
                    return -1;
                }
                i++;
                retCnt++;
            } else if (b == (byte) '\n') {
                i++;
                retCnt++;
            } else {
                i++;
                retCnt = 0;
            }
            if (retCnt == 2) {
                return i;
            }
        }
    }

    private static long parseHeadersInternal(MemorySegment ms, long base,
                                             long offset, long limit,
                                             Header[] headers, int maxHeaders,
                                             Request req, Response res,
                                             int[] outNumHeaders) {
        long i = offset;
        int numHeaders = 0;
        while (true) {
            if (i == limit) {
                return finishHeaders(
                    req, res, outNumHeaders, numHeaders, -2);
            }
            byte currentByte = ms.get(ValueLayout.JAVA_BYTE, base + i);
            if (currentByte == (byte) '\r') {
                i++;
                if (i == limit) {
                    return finishHeaders(
                        req, res, outNumHeaders, numHeaders, -2);
                }
                if (ms.get(ValueLayout.JAVA_BYTE, base + i) != (byte) '\n') {
                    return finishHeaders(
                        req, res, outNumHeaders, numHeaders, -1);
                }
                i++;
                break;
            } else if (currentByte == (byte) '\n') {
                i++;
                break;
            }
            if (numHeaders == maxHeaders) {
                return finishHeaders(
                    req, res, outNumHeaders, numHeaders, -1);
            }
            Header h = headers[numHeaders];
            byte headByte = ms.get(ValueLayout.JAVA_BYTE, base + i);
            if (!(numHeaders != 0 && (headByte == (byte) ' ' || headByte == (byte) '\t'))) {
                long colonIndex = parseToken(ms, base, i, limit, (byte) ':');
                if (colonIndex < 0) {
                    return finishHeaders(
                        req, res, outNumHeaders, numHeaders, colonIndex);
                }
                long nameLen = colonIndex - i;
                if (nameLen == 0) {
                    return finishHeaders(
                        req, res, outNumHeaders, numHeaders, -1);
                }
                h.nameOffset = i;
                h.nameLen = nameLen;
                i = colonIndex + 1; // skip ':'
                while (true) {
                    if (i == limit) {
                        return finishHeaders(
                            req, res, outNumHeaders, numHeaders, -2);
                    }
                    byte b = ms.get(ValueLayout.JAVA_BYTE, base + i);
                    if (b != (byte) ' ' && b != (byte) '\t') {
                        break;
                    }
                    i++;
                }
            } else {
                h.nameOffset = -1;
                h.nameLen = 0;
            }

            long valRes = getTokenToEol(ms, base, i, limit);
            if (valRes < 0) {
                return finishHeaders(
                    req, res, outNumHeaders, numHeaders, valRes);
            }
            long valLen = valRes >>> 32;
            long nextOffset = valRes & 0xFFFFFFFFL;

            long valEnd = i + valLen;
            while (valEnd > i) {
                byte c = ms.get(
                    ValueLayout.JAVA_BYTE, base + valEnd - 1);
                if (c != (byte) ' ' && c != (byte) '\t') {
                    break;
                }
                valEnd--;
            }
            h.valueOffset = i;
            h.valueLen = valEnd - i;

            if (req != null && h.nameOffset >= 0) {
                classifyFramingHeader(
                    ms, base, h.nameOffset, h.nameLen,
                    numHeaders, req);
            }

            i = nextOffset;
            numHeaders++;
        }
        return finishHeaders(
            req, res, outNumHeaders, numHeaders, i);
    }

    private static long finishHeaders(
            Request req, Response res, int[] outNumHeaders,
            int numHeaders, long result) {
        if (req != null) {
            req.numHeaders = numHeaders;
        } else if (res != null) {
            res.numHeaders = numHeaders;
        } else {
            outNumHeaders[0] = numHeaders;
        }
        return result;
    }

    private static void classifyFramingHeader(
            MemorySegment ms, long base, long offset, long length,
            int headerIndex, Request req) {
        switch ((int) length) {
            case 6 -> {
                if (equalsLowercaseAscii6(
                        ms, base + offset,
                        0x65707865, 0x7463)) { // "expect"
                    recordFramingHeader(
                        req, Request.FRAMING_EXPECT_SHIFT,
                        headerIndex, true);
                }
            }
            case 10 -> {
                if (equalsLowercaseAscii10(
                        ms, base + offset,
                        0x697463656e6e6f63L, 0x6e6f)) { // "connection"
                    recordFramingHeader(
                        req, Request.FRAMING_CONNECTION_SHIFT,
                        headerIndex, false);
                }
            }
            case 14 -> {
                if (equalsLowercaseAscii14(
                        ms, base + offset,
                        0x2d746e65746e6f63L,
                        0x676e656c, 0x6874)) { // "content-length"
                    recordFramingHeader(
                        req, Request.FRAMING_CONTENT_LENGTH_SHIFT,
                        headerIndex, true);
                }
            }
            case 17 -> {
                if (equalsLowercaseAscii17(
                        ms, base + offset,
                        0x726566736e617274L,
                        0x6e69646f636e652dL, 0x67)) { // "transfer-encoding"
                    recordFramingHeader(
                        req, Request.FRAMING_TRANSFER_ENCODING_SHIFT,
                        headerIndex, true);
                }
            }
            default -> {
            }
        }
    }

    private static void recordFramingHeader(
            Request req, int shift, int headerIndex,
            boolean recordDuplicate) {
        long lane = (req.framingHeaders >>> shift) & 0xffffL;
        if ((lane & Request.FRAMING_INDEX_MASK) == 0) {
            req.framingHeaders |= (long) (headerIndex + 1) << shift;
        } else if (recordDuplicate) {
            req.framingHeaders |=
                Request.FRAMING_DUPLICATE_MASK << shift;
        }
    }

    private static boolean equalsLowercaseAscii6(
            MemorySegment ms, long offset, int first, int second) {
        return (ms.get(JAVA_INT_UNALIGNED_LE, offset)
                    | 0x20202020) == first
            && ((ms.get(JAVA_SHORT_UNALIGNED_LE, offset + 4) & 0xffff)
                    | 0x2020) == second;
    }

    private static boolean equalsLowercaseAscii10(
            MemorySegment ms, long offset, long first, int second) {
        return (ms.get(JAVA_LONG_UNALIGNED_LE, offset)
                    | 0x2020202020202020L) == first
            && ((ms.get(JAVA_SHORT_UNALIGNED_LE, offset + 8) & 0xffff)
                    | 0x2020) == second;
    }

    private static boolean equalsLowercaseAscii14(
            MemorySegment ms, long offset,
            long first, int second, int third) {
        return (ms.get(JAVA_LONG_UNALIGNED_LE, offset)
                    | 0x2020202020202020L) == first
            && (ms.get(JAVA_INT_UNALIGNED_LE, offset + 8)
                    | 0x20202020) == second
            && ((ms.get(JAVA_SHORT_UNALIGNED_LE, offset + 12) & 0xffff)
                    | 0x2020) == third;
    }

    private static boolean equalsLowercaseAscii17(
            MemorySegment ms, long offset,
            long first, long second, int third) {
        return (ms.get(JAVA_LONG_UNALIGNED_LE, offset)
                    | 0x2020202020202020L) == first
            && (ms.get(JAVA_LONG_UNALIGNED_LE, offset + 8)
                    | 0x2020202020202020L) == second
            && ((ms.get(ValueLayout.JAVA_BYTE, offset + 16) & 0xff)
                    | 0x20) == third;
    }

    public static long parseRequest(MemorySegment ms, long offset, long limit, Request req, long lastLen) {
        req.reset();
        checkBounds(ms, offset, limit);
        long base = 0;
        if (ms.isNative()) {
            base = ms.address();
            ms = RawSegment.ADDRESS_SPACE;
        }

        if (lastLen != 0) {
            long completeIdx = isComplete(
                ms, base, offset, limit, lastLen);
            if (completeIdx < 0) {
                return completeIdx;
            }
        }

        long i = offset;
        if (i == limit) {
            return -2;
        }
        byte firstByte = ms.get(ValueLayout.JAVA_BYTE, base + i);
        if (firstByte == (byte) '\r') {
            i++;
            if (i == limit) {
                return -2;
            }
            if (ms.get(ValueLayout.JAVA_BYTE, base + i) != (byte) '\n') {
                return -1;
            }
            i++;
        } else if (firstByte == (byte) '\n') {
            i++;
        }

        long spaceIndex = -1;
        if (limit - i >= 4
            && ms.get(JAVA_INT_UNALIGNED_LE, base + i) == 0x20544547) { // "GET "
            req.methodCode = 1;
            req.methodOffset = i;
            req.methodLen = 3;
            spaceIndex = i + 3;
        } else if (limit - i >= 5
            && (ms.get(JAVA_LONG_UNALIGNED_LE, base + i)
                & 0x000000FFFFFFFFFFL) == 0x2054534F50L) { // "POST "
            req.methodCode = 2;
            req.methodOffset = i;
            req.methodLen = 4;
            spaceIndex = i + 4;
        } else {
            req.methodCode = 0;
            spaceIndex = parseToken(ms, base, i, limit, (byte) ' ');
            if (spaceIndex < 0) {
                return spaceIndex;
            }
            req.methodOffset = i;
            req.methodLen = spaceIndex - i;
        }

        i = spaceIndex;
        do {
            i++;
            if (i == limit) {
                return -2;
            }
        } while (ms.get(ValueLayout.JAVA_BYTE, base + i) == (byte) ' ');

        long pathStart = i;
        long pathEnd = advanceToken(ms, base, i, limit);
        if (pathEnd < 0) {
            return pathEnd;
        }
        req.pathOffset = pathStart;
        req.pathLen = pathEnd - pathStart;

        i = pathEnd;
        do {
            i++;
            if (i == limit) {
                return -2;
            }
        } while (ms.get(ValueLayout.JAVA_BYTE, base + i) == (byte) ' ');

        if (req.methodLen == 0 || req.pathLen == 0) {
            return -1;
        }

        long versionEnd = parseHttpVersionReq(ms, base, i, limit, req);
        if (versionEnd < 0) {
            return versionEnd;
        }

        i = versionEnd;
        if (i == limit) {
            return -2;
        }
        byte endByte = ms.get(ValueLayout.JAVA_BYTE, base + i);
        if (endByte == (byte) '\r') {
            i++;
            if (i == limit) {
                return -2;
            }
            if (ms.get(ValueLayout.JAVA_BYTE, base + i) != (byte) '\n') {
                return -1;
            }
            i++;
        } else if (endByte == (byte) '\n') {
            i++;
        } else {
            return -1;
        }

        long headersEnd = parseHeadersInternal(
            ms, base, i, limit, req.headers, req.headers.length,
            req, null, null);
        if (headersEnd < 0) {
            return headersEnd;
        }
        return headersEnd - offset;
    }

    public static long parseResponse(MemorySegment ms, long offset, long limit, Response res, long lastLen) {
        res.reset();
        checkBounds(ms, offset, limit);
        long base = 0;
        if (ms.isNative()) {
            base = ms.address();
            ms = RawSegment.ADDRESS_SPACE;
        }

        if (lastLen != 0) {
            long completeIdx = isComplete(
                ms, base, offset, limit, lastLen);
            if (completeIdx < 0) {
                return completeIdx;
            }
        }

        long i = offset;
        long versionEnd = parseHttpVersionRes(ms, base, i, limit, res);
        if (versionEnd < 0) {
            return versionEnd;
        }
        i = versionEnd;

        if (i == limit) {
            return -2;
        }
        if (ms.get(ValueLayout.JAVA_BYTE, base + i) != (byte) ' ') {
            return -1;
        }
        do {
            i++;
            if (i == limit) {
                return -2;
            }
        } while (ms.get(ValueLayout.JAVA_BYTE, base + i) == (byte) ' ');

        if (limit - i < 4) {
            return -2;
        }

        int statusVal = 0;
        byte d1 = ms.get(ValueLayout.JAVA_BYTE, base + i);
        if (d1 < (byte)'0' || d1 > (byte)'9') {
            i++;
            return -1;
        }
        statusVal += 100 * (d1 - '0');
        i++;

        byte d2 = ms.get(ValueLayout.JAVA_BYTE, base + i);
        if (d2 < (byte)'0' || d2 > (byte)'9') {
            i++;
            return -1;
        }
        statusVal += 10 * (d2 - '0');
        i++;

        byte d3 = ms.get(ValueLayout.JAVA_BYTE, base + i);
        if (d3 < (byte)'0' || d3 > (byte)'9') {
            i++;
            return -1;
        }
        statusVal += d3 - '0';
        i++;

        res.status = statusVal;

        long msgRes = getTokenToEol(ms, base, i, limit);
        if (msgRes < 0) {
            return msgRes;
        }
        long msgLen = msgRes >>> 32;
        long nextOffset = msgRes & 0xFFFFFFFFL;

        long msgStart = i;
        if (msgLen == 0) {
            res.msgOffset = msgStart;
            res.msgLen = 0;
        } else if (ms.get(ValueLayout.JAVA_BYTE, base + msgStart) == (byte) ' ') {
            while (msgLen > 0
                && ms.get(ValueLayout.JAVA_BYTE, base + msgStart) == (byte) ' ') {
                msgStart++;
                msgLen--;
            }
            res.msgOffset = msgStart;
            res.msgLen = msgLen;
        } else {
            return -1;
        }

        i = nextOffset;
        long headersEnd = parseHeadersInternal(
            ms, base, i, limit, res.headers, res.headers.length,
            null, res, null);
        if (headersEnd < 0) {
            return headersEnd;
        }
        return headersEnd - offset;
    }

    public static long parseHeaders(MemorySegment ms, long offset, long limit, Header[] headers, int[] outNumHeaders, long lastLen) {
        checkBounds(ms, offset, limit);
        long base = 0;
        if (ms.isNative()) {
            base = ms.address();
            ms = RawSegment.ADDRESS_SPACE;
        }
        if (lastLen != 0) {
            long completeIdx = isComplete(
                ms, base, offset, limit, lastLen);
            if (completeIdx < 0) {
                return completeIdx;
            }
        }
        outNumHeaders[0] = 0;
        long headersEnd = parseHeadersInternal(
            ms, base, offset, limit, headers, headers.length,
            null, null, outNumHeaders);
        if (headersEnd < 0) {
            return headersEnd;
        }
        return headersEnd - offset;
    }

    private static void checkBounds(MemorySegment segment, long offset,
                                    long limit) {
        if (offset < 0 || limit < offset || limit > segment.byteSize()) {
            throw new IndexOutOfBoundsException(
                "Invalid parser range [" + offset + ", " + limit
                    + ") for segment of " + segment.byteSize() + " bytes");
        }
    }

    private static int decodeHex(byte ch) {
        int c = ch & 0xFF;
        if (c >= '0' && c <= '9') {
            return c - '0';
        } else if (c >= 'A' && c <= 'F') {
            return c - 'A' + 0xa;
        } else if (c >= 'a' && c <= 'f') {
            return c - 'a' + 0xa;
        } else {
            return -1;
        }
    }

    private static long findCrOrLf(MemorySegment ms, long base,
                                   long offset, long limit) {
        long i = offset;
        while (i + Long.BYTES <= limit) {
            long word = ms.get(JAVA_LONG_UNALIGNED_LE, base + i);
            long cr = word ^ 0x0d0d0d0d0d0d0d0dL;
            long lf = word ^ 0x0a0a0a0a0a0a0a0aL;
            long matches = zeroByteMask(cr) | zeroByteMask(lf);
            if (matches != 0) {
                return i + (Long.numberOfTrailingZeros(matches) >>> 3);
            }
            i += Long.BYTES;
        }
        while (i < limit) {
            byte value = ms.get(ValueLayout.JAVA_BYTE, base + i);
            if (value == (byte) '\r' || value == (byte) '\n') {
                break;
            }
            i++;
        }
        return i;
    }

    private static long findLf(MemorySegment ms, long base,
                               long offset, long limit) {
        long i = offset;
        while (i + Long.BYTES <= limit) {
            long word = ms.get(JAVA_LONG_UNALIGNED_LE, base + i);
            long matches = zeroByteMask(
                word ^ 0x0a0a0a0a0a0a0a0aL);
            if (matches != 0) {
                return i + (Long.numberOfTrailingZeros(matches) >>> 3);
            }
            i += Long.BYTES;
        }
        while (i < limit
            && ms.get(ValueLayout.JAVA_BYTE, base + i) != (byte) '\n') {
            i++;
        }
        return i;
    }

    private static long zeroByteMask(long value) {
        return (value - 0x0101010101010101L)
            & ~value & 0x8080808080808080L;
    }

    public static long decodeChunked(
        ChunkedDecoder decoder,
        MemorySegment ms,
        long offset,
        long[] bufsz
    ) {
        return decodeChunkedInternal(
            decoder, ms, offset, bufsz[0], bufsz, null,
            null, 0, 0, 0, 0);
    }

    /**
     * Advances chunk framing without compacting payload bytes. {@code span[0]}
     * receives the number of input bytes consumed and {@code span[1]} the
     * trailing payload bytes within that consumed prefix. A data result always
     * ends at the consumed offset, so its payload begins at
     * {@code offset + span[0] - span[1]}.
     * Returns {@link #CHUNKED_SPAN_DATA}, {@link #CHUNKED_COMPLETE},
     * {@link #ERROR_PARTIAL}, or {@link #ERROR_PARSE}.
     */
    public static int decodeChunkedSpan(
        ChunkedDecoder decoder,
        MemorySegment ms,
        long offset,
        long inputLength,
        long[] span
    ) {
        if (span.length < 2) {
            throw new IllegalArgumentException(
                "Chunked span output requires two elements");
        }
        return (int) decodeChunkedInternal(
            decoder, ms, offset, inputLength, null, span,
            null, 0, 0, 0, 0);
    }

    /**
     * Decodes payload directly from the framed input into {@code destination}.
     * {@code progress[0]} receives consumed input bytes and
     * {@code progress[1]} produced payload bytes. The decoder stops when the
     * input or destination is exhausted, the message completes, or a limit is
     * exceeded. Returns {@link #CHUNKED_OUTPUT_FULL},
     * {@link #CHUNKED_COMPLETE}, {@link #ERROR_PARTIAL}, or one of the
     * chunked error constants.
     */
    public static int decodeChunkedTo(
        ChunkedDecoder decoder,
        MemorySegment ms,
        long offset,
        long inputLength,
        MemorySegment destination,
        long destinationOffset,
        long destinationLength,
        long maximumPayloadLength,
        long maximumMetadataLength,
        long[] progress
    ) {
        if (progress.length < 2) {
            throw new IllegalArgumentException(
                "Chunked progress output requires two elements");
        }
        if (destination == null) {
            throw new IllegalArgumentException(
                "Chunked decode destination is required");
        }
        if (inputLength < 0 || destinationLength < 0
                || maximumPayloadLength < 0
                || maximumMetadataLength < 0) {
            throw new IllegalArgumentException(
                "Chunked decode lengths must not be negative");
        }
        return (int) decodeChunkedInternal(
            decoder, ms, offset, inputLength, null, progress,
            destination, destinationOffset, destinationLength,
            maximumPayloadLength, maximumMetadataLength);
    }

    private static long decodeChunkedInternal(
        ChunkedDecoder decoder,
        MemorySegment ms,
        long offset,
        long inputLength,
        long[] compactedSize,
        long[] progress,
        MemorySegment destination,
        long destinationOffset,
        long destinationLength,
        long maximumPayloadLength,
        long maximumMetadataLength
    ) {
        long src = 0;
        long dst = 0;
        long limit = inputLength;
        long ret = -2;
        long base = offset;
        boolean directMode = destination != null;
        boolean spanMode = progress != null && !directMode;
        boolean progressiveMode = progress != null;
        long destinationBase = destinationOffset;
        if (ms.isNative()) {
            base += ms.address();
            ms = RawSegment.ADDRESS_SPACE;
        }
        if (directMode && destination.isNative()) {
            destinationBase += destination.address();
            destination = RawSegment.ADDRESS_SPACE;
        }

        if (progressiveMode) {
            progress[0] = 0;
            progress[1] = 0;
        } else {
            decoder.totalRead += limit;
        }

        while (true) {
            switch (decoder.state) {
                case CHUNKED_IN_CHUNK_SIZE:
                    for (;; src++) {
                        if (src == limit) {
                            break;
                        }
                        int v = decodeHex(ms.get(ValueLayout.JAVA_BYTE, base + src));
                        if (v == -1) {
                            if (decoder.hexCount == 0) {
                                ret = -1;
                                break;
                            }
                            byte b = ms.get(ValueLayout.JAVA_BYTE, base + src);
                            if (b == (byte) ' ' || b == (byte) '\t' || b == (byte) ';' || b == (byte) '\n' || b == (byte) '\r') {
                                break;
                            }
                            ret = -1;
                            break;
                        }
                        if (decoder.hexCount == 16) {
                            ret = -1;
                            break;
                        }
                        decoder.bytesLeftInChunk = decoder.bytesLeftInChunk * 16 + v;
                        decoder.hexCount++;
                    }
                    if (ret == -1 || src == limit) {
                        break;
                    }
                    decoder.hexCount = 0;
                    decoder.state = CHUNKED_IN_CHUNK_EXT;
                    // fallthru
                case CHUNKED_IN_CHUNK_EXT:
                    while (true) {
                        if (src == limit) {
                            break;
                        }
                        byte b = ms.get(ValueLayout.JAVA_BYTE, base + src);
                        if (b == (byte) '\r') {
                            break;
                        } else if (b == (byte) '\n') {
                            ret = -1;
                            break;
                        }
                        src++;
                        if (limit - src >= Long.BYTES) {
                            src = findCrOrLf(ms, base, src, limit);
                        }
                    }
                    if (ret == -1 || src == limit) {
                        break;
                    }
                    src++;
                    decoder.state = CHUNKED_IN_CHUNK_HEADER_EXPECT_LF;
                    // fallthru
                case CHUNKED_IN_CHUNK_HEADER_EXPECT_LF:
                    if (src == limit) {
                        break;
                    }
                    if (ms.get(ValueLayout.JAVA_BYTE, base + src) != (byte) '\n') {
                        ret = -1;
                        break;
                    }
                    src++;
                    if (decoder.bytesLeftInChunk == 0) {
                        if (decoder.consumeTrailer) {
                            decoder.state = CHUNKED_IN_TRAILERS_LINE_HEAD;
                            break;
                        } else {
                            ret = progressiveMode
                                ? CHUNKED_COMPLETE : limit - src;
                            break;
                        }
                    }
                    decoder.state = CHUNKED_IN_CHUNK_DATA;
                    if (directMode
                            && src - dst > maximumMetadataLength) {
                        ret = ERROR_CHUNKED_METADATA_LIMIT;
                        break;
                    }
                    // fallthru
                case CHUNKED_IN_CHUNK_DATA: {
                    long avail = limit - src;
                    if (directMode) {
                        if (Long.compareUnsigned(
                                decoder.bytesLeftInChunk,
                                maximumPayloadLength - dst) > 0) {
                            ret = ERROR_CHUNKED_PAYLOAD_LIMIT;
                            break;
                        }
                        long count = Math.min(
                            Math.min(avail, decoder.bytesLeftInChunk),
                            destinationLength - dst
                        );
                        if (count != 0) {
                            MemorySegment.copy(
                                ms, base + src,
                                destination, destinationBase + dst,
                                count
                            );
                            src += count;
                            dst += count;
                            decoder.bytesLeftInChunk -= count;
                            if (decoder.bytesLeftInChunk == 0) {
                                decoder.state =
                                    CHUNKED_IN_CHUNK_DATA_EXPECT_CR;
                            }
                        }
                        if (dst == destinationLength) {
                            ret = CHUNKED_OUTPUT_FULL;
                            break;
                        }
                        if (src == limit) {
                            break;
                        }
                        // A non-full destination and remaining input imply
                        // that this chunk ended, so consume its delimiter.
                    } else if (spanMode) {
                        if (avail == 0) {
                            break;
                        }
                        long count;
                        if (Long.compareUnsigned(
                                avail, decoder.bytesLeftInChunk) < 0) {
                            count = avail;
                            decoder.bytesLeftInChunk -= count;
                        } else {
                            count = decoder.bytesLeftInChunk;
                            decoder.bytesLeftInChunk = 0;
                            decoder.state =
                                CHUNKED_IN_CHUNK_DATA_EXPECT_CR;
                        }
                        src += count;
                        progress[0] = src;
                        progress[1] = count;
                        ret = CHUNKED_SPAN_DATA;
                        break;
                    } else {
                        if (Long.compareUnsigned(
                                avail, decoder.bytesLeftInChunk) < 0) {
                            if (dst != src) {
                                MemorySegment.copy(
                                    ms, base + src, ms, base + dst, avail);
                            }
                            src += avail;
                            dst += avail;
                            decoder.bytesLeftInChunk -= avail;
                            break;
                        }

                        if (dst != src) {
                            MemorySegment.copy(
                                ms, base + src, ms, base + dst,
                                decoder.bytesLeftInChunk);
                        }
                        src += decoder.bytesLeftInChunk;
                        dst += decoder.bytesLeftInChunk;
                        decoder.bytesLeftInChunk = 0;
                        decoder.state = CHUNKED_IN_CHUNK_DATA_EXPECT_CR;
                    }
                }
                case CHUNKED_IN_CHUNK_DATA_EXPECT_CR:
                    if (src == limit) {
                        break;
                    }
                    if (ms.get(ValueLayout.JAVA_BYTE, base + src) != (byte) '\r') {
                        ret = -1;
                        break;
                    }
                    src++;
                    decoder.state = CHUNKED_IN_CHUNK_DATA_EXPECT_LF;
                    // fallthru
                case CHUNKED_IN_CHUNK_DATA_EXPECT_LF:
                    if (src == limit) {
                        break;
                    }
                    if (ms.get(ValueLayout.JAVA_BYTE, base + src) != (byte) '\n') {
                        ret = -1;
                        break;
                    }
                    src++;
                    decoder.state = CHUNKED_IN_CHUNK_SIZE;
                    break;
                case CHUNKED_IN_TRAILERS_LINE_HEAD:
                    for (;; src++) {
                        if (src == limit) {
                            break;
                        }
                        if (ms.get(ValueLayout.JAVA_BYTE, base + src) != (byte) '\r') {
                            break;
                        }
                    }
                    if (src == limit) {
                        break;
                    }
                    if (ms.get(ValueLayout.JAVA_BYTE, base + src) == (byte) '\n') {
                        src++;
                        ret = progressiveMode
                            ? CHUNKED_COMPLETE : limit - src;
                        break;
                    }
                    src++;
                    decoder.state = CHUNKED_IN_TRAILERS_LINE_MIDDLE;
                    // fallthru
                case CHUNKED_IN_TRAILERS_LINE_MIDDLE:
                    for (;; src++) {
                        if (src == limit) {
                            break;
                        }
                        src = findLf(ms, base, src, limit);
                        if (src == limit) {
                            break;
                        }
                        if (ms.get(ValueLayout.JAVA_BYTE, base + src) == (byte) '\n') {
                            break;
                        }
                    }
                    if (src == limit) {
                        break;
                    }
                    src++;
                    decoder.state = CHUNKED_IN_TRAILERS_LINE_HEAD;
                    break;
                default:
                    throw new IllegalStateException("decoder is corrupt");
            }

            if (ret != ERROR_PARTIAL || src == limit) {
                break;
            }
        }

        if (directMode && ret != ERROR_PARSE
                && src - dst > maximumMetadataLength) {
            ret = ERROR_CHUNKED_METADATA_LIMIT;
        }

        if (progressiveMode) {
            long produced = directMode ? dst : progress[1];
            progress[0] = src;
            progress[1] = produced;
            decoder.totalRead += src;
            decoder.totalOverhead += src - produced;
            if ((ret == ERROR_PARTIAL || ret == CHUNKED_OUTPUT_FULL)
                    && decoder.totalOverhead >= 100 * 1024
                    && decoder.totalRead - decoder.totalOverhead
                        < decoder.totalRead / 4) {
                ret = ERROR_PARSE;
            }
            return ret;
        }

        if (dst != src) {
            MemorySegment.copy(ms, base + src, ms, base + dst, limit - src);
        }
        compactedSize[0] = dst;

        if (ret == -2) {
            decoder.totalOverhead += limit - dst;
            if (decoder.totalOverhead >= 100 * 1024 && decoder.totalRead - decoder.totalOverhead < decoder.totalRead / 4) {
                ret = -1;
            }
        }
        return ret;
    }

    public static boolean decodeChunkedIsInData(ChunkedDecoder decoder) {
        return decoder.state == CHUNKED_IN_CHUNK_DATA;
    }
}
