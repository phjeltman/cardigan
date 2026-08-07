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

package dev.cardigan.simdjson.util;

import dev.cardigan.simdjson.SimdJsonError;
import dev.cardigan.simdjson.SimdJsonException;
import dev.cardigan.ffi.RawSegment;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Number parser operating directly on MemorySegment or heap byte arrays.
 */
public final class FastNumberParser {

    private FastNumberParser() {}

    public static boolean isInteger(MemorySegment segment, byte[] heapBytes, long start, long end) {
        for (long i = start; i < end; i++) {
            byte b = heapBytes != null ? heapBytes[(int) i] : segment.get(ValueLayout.JAVA_BYTE, i);
            if (b == '.' || b == 'e' || b == 'E') {
                return false;
            }
        }
        return true;
    }

    public static long parseLong(MemorySegment segment, byte[] heapBytes, long start, long end) {
        if (start >= end) return 0L;

        long i = start;
        boolean negative = false;

        byte first = heapBytes != null ? heapBytes[(int) i] : RawSegment.getByte(segment.address(), i);
        if (first == '-') {
            negative = true;
            i++;
        } else if (first == '+') {
            i++;
        }

        long accumulationLimit = negative
            ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long multiplyLimit = accumulationLimit / 10;
        long result = 0;
        if (heapBytes != null) {
            int limit = (int) end;
            for (int p = (int) i; p < limit; p++) {
                byte b = heapBytes[p];
                if (b >= '0' && b <= '9') {
                    int digit = b - '0';
                    if (result < multiplyLimit) {
                        throw numberOutOfRange();
                    }
                    result *= 10;
                    if (result < accumulationLimit + digit) {
                        throw numberOutOfRange();
                    }
                    result -= digit;
                } else {
                    break;
                }
            }
        } else {
            long addr = segment.address();
            long p = i;
            while (p < end) {
                byte b = (byte) RawSegment.BYTE.get(
                    RawSegment.ADDRESS_SPACE, addr + p);
                if (b >= '0' && b <= '9') {
                    int digit = b - '0';
                    if (result < multiplyLimit) {
                        throw numberOutOfRange();
                    }
                    result *= 10;
                    if (result < accumulationLimit + digit) {
                        throw numberOutOfRange();
                    }
                    result -= digit;
                } else {
                    break;
                }
                p++;
            }
        }

        return negative ? result : -result;
    }

    public static int parseInt(
            MemorySegment segment, byte[] heapBytes,
            long start, long end) {
        long value = parseLong(segment, heapBytes, start, end);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw numberOutOfRange();
        }
        return (int) value;
    }

    public static double parseDouble(MemorySegment segment, byte[] heapBytes, long start, long end) {
        if (start >= end) return 0.0;

        long i = start;
        boolean negative = false;

        byte first = heapBytes != null ? heapBytes[(int) i] : RawSegment.getByte(segment.address(), i);
        if (first == '-') {
            negative = true;
            i++;
        } else if (first == '+') {
            i++;
        }

        long intPart = 0;
        if (heapBytes != null) {
            int limit = (int) end;
            int p = (int) i;
            int integerDigits = 0;
            while (p < limit) {
                byte b = heapBytes[p];
                if (b >= '0' && b <= '9') {
                    if (++integerDigits > 18) {
                        return parseDoubleFallback(
                            heapBytes, (int) start,
                            (int) (end - start));
                    }
                    intPart = intPart * 10 + (b - '0');
                    p++;
                } else {
                    break;
                }
            }
            if (p < limit && heapBytes[p] == '.') {
                p++;
                long fracPart = 0;
                long div = 1;
                int fractionDigits = 0;
                while (p < limit) {
                    byte b = heapBytes[p];
                    if (b >= '0' && b <= '9') {
                        if (integerDigits + ++fractionDigits > 15) {
                            return parseDoubleFallback(
                                heapBytes, (int) start,
                                (int) (end - start));
                        }
                        fracPart = fracPart * 10 + (b - '0');
                        div *= 10;
                        p++;
                    } else if (b == 'e' || b == 'E') {
                        // Fall back for scientific notation e.g. 1.23e4
                        return parseDoubleFallback(heapBytes, (int) start, (int) (end - start));
                    } else {
                        break;
                    }
                }
                long combined = intPart * div + fracPart;
                double val = (double) combined / (double) div;
                return negative ? -val : val;
            } else if (p < limit && (heapBytes[p] == 'e' || heapBytes[p] == 'E')) {
                return parseDoubleFallback(heapBytes, (int) start, (int) (end - start));
            }
        } else {
            long addr = segment.address();
            long p = i;
            int integerDigits = 0;
            while (p < end) {
                byte b = (byte) RawSegment.BYTE.get(
                    RawSegment.ADDRESS_SPACE, addr + p);
                if (b >= '0' && b <= '9') {
                    if (++integerDigits > 18) {
                        return parseDoubleFallbackSeg(
                            segment, start, end);
                    }
                    intPart = intPart * 10 + (b - '0');
                    p++;
                } else {
                    break;
                }
            }
            if (p < end && (byte) RawSegment.BYTE.get(
                    RawSegment.ADDRESS_SPACE, addr + p) == '.') {
                p++;
                long fracPart = 0;
                long div = 1;
                int fractionDigits = 0;
                while (p < end) {
                    byte b = (byte) RawSegment.BYTE.get(
                        RawSegment.ADDRESS_SPACE, addr + p);
                    if (b >= '0' && b <= '9') {
                        if (integerDigits + ++fractionDigits > 15) {
                            return parseDoubleFallbackSeg(
                                segment, start, end);
                        }
                        fracPart = fracPart * 10 + (b - '0');
                        div *= 10;
                        p++;
                    } else if (b == 'e' || b == 'E') {
                        return parseDoubleFallbackSeg(segment, start, end);
                    } else {
                        break;
                    }
                }
                long combined = intPart * div + fracPart;
                double val = (double) combined / (double) div;
                return negative ? -val : val;
            } else if (p < end && ((byte) RawSegment.BYTE.get(
                    RawSegment.ADDRESS_SPACE, addr + p) == 'e'
                    || (byte) RawSegment.BYTE.get(
                        RawSegment.ADDRESS_SPACE, addr + p) == 'E')) {
                return parseDoubleFallbackSeg(segment, start, end);
            }
        }

        double val = (double) intPart;
        return negative ? -val : val;
    }

    private static double parseDoubleFallback(byte[] heapBytes, int start, int len) {
        String str = new String(heapBytes, start, len, java.nio.charset.StandardCharsets.US_ASCII);
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static double parseDoubleFallbackSeg(MemorySegment segment, long start, long end) {
        int len = (int) (end - start);
        byte[] bytes = new byte[len];
        MemorySegment.ofArray(bytes).copyFrom(segment.asSlice(start, len));
        String str = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static SimdJsonException numberOutOfRange() {
        return new SimdJsonException(
            SimdJsonError.NUMBER_OUT_OF_RANGE);
    }

    private static long getUnalignedLong(byte[] array, int index) {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= ((long) (array[index + i] & 0xFF)) << (i * 8);
        }
        return result;
    }
}
