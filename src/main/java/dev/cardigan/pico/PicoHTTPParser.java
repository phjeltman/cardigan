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

    private static final ValueLayout.OfInt JAVA_INT_UNALIGNED_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong JAVA_LONG_UNALIGNED_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

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
                if (outNumHeaders != null) outNumHeaders[0] = numHeaders;
                return -2;
            }
            byte currentByte = ms.get(ValueLayout.JAVA_BYTE, base + i);
            if (currentByte == (byte) '\r') {
                i++;
                if (i == limit) {
                    if (outNumHeaders != null) outNumHeaders[0] = numHeaders;
                    return -2;
                }
                if (ms.get(ValueLayout.JAVA_BYTE, base + i) != (byte) '\n') {
                    if (outNumHeaders != null) outNumHeaders[0] = numHeaders;
                    return -1;
                }
                i++;
                break;
            } else if (currentByte == (byte) '\n') {
                i++;
                break;
            }
            if (numHeaders == maxHeaders) {
                if (outNumHeaders != null) outNumHeaders[0] = numHeaders;
                return -1;
            }
            Header h = headers[numHeaders];
            byte headByte = ms.get(ValueLayout.JAVA_BYTE, base + i);
            if (!(numHeaders != 0 && (headByte == (byte) ' ' || headByte == (byte) '\t'))) {
                long colonIndex = parseToken(ms, base, i, limit, (byte) ':');
                if (colonIndex < 0) {
                    if (outNumHeaders != null) outNumHeaders[0] = numHeaders;
                    return colonIndex;
                }
                long nameLen = colonIndex - i;
                if (nameLen == 0) {
                    if (outNumHeaders != null) outNumHeaders[0] = numHeaders;
                    return -1;
                }
                h.nameOffset = i;
                h.nameLen = nameLen;
                i = colonIndex + 1; // skip ':'
                while (true) {
                    if (i == limit) {
                        if (outNumHeaders != null) outNumHeaders[0] = numHeaders;
                        return -2;
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
                if (outNumHeaders != null) outNumHeaders[0] = numHeaders;
                return valRes;
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

            i = nextOffset;
            numHeaders++;
            if (req != null) {
                req.numHeaders = numHeaders;
            }
            if (res != null) {
                res.numHeaders = numHeaders;
            }
            if (outNumHeaders != null) {
                outNumHeaders[0] = numHeaders;
            }
        }
        return i;
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

    public static long decodeChunked(ChunkedDecoder decoder, MemorySegment ms, long offset, long[] bufsz) {
        long src = 0;
        long dst = 0;
        long limit = bufsz[0];
        long ret = -2;
        long base = offset;
        if (ms.isNative()) {
            base += ms.address();
            ms = RawSegment.ADDRESS_SPACE;
        }

        decoder.totalRead += limit;

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
                            ret = limit - src;
                            break;
                        }
                    }
                    decoder.state = CHUNKED_IN_CHUNK_DATA;
                    // fallthru
                case CHUNKED_IN_CHUNK_DATA: {
                    long avail = limit - src;
                    if (Long.compareUnsigned(avail, decoder.bytesLeftInChunk) < 0) {
                        if (dst != src) {
                            MemorySegment.copy(ms, base + src, ms, base + dst, avail);
                        }
                        src += avail;
                        dst += avail;
                        decoder.bytesLeftInChunk -= avail;
                        break;
                    }

                    if (dst != src) {
                        MemorySegment.copy(ms, base + src, ms, base + dst, decoder.bytesLeftInChunk);
                    }
                    src += decoder.bytesLeftInChunk;
                    dst += decoder.bytesLeftInChunk;
                    decoder.bytesLeftInChunk = 0;
                    decoder.state = CHUNKED_IN_CHUNK_DATA_EXPECT_CR;
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
                        ret = limit - src;
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

            if (ret >= 0 || ret == -1 || src == limit) {
                break;
            }
        }

        if (dst != src) {
            MemorySegment.copy(ms, base + src, ms, base + dst, limit - src);
        }
        bufsz[0] = dst;

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
