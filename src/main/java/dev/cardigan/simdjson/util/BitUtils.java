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

/**
 * High-performance bit manipulation routines for SIMD structural indexing.
 */
public final class BitUtils {

    private static final long ODD_BITS = 0xAAAAAAAAAAAAAAAAL;

    private BitUtils() {}

    /**
     * Computes parallel prefix-XOR over a 64-bit mask.
     * Each set bit toggles the state of subsequent bits.
     *
     * @param mask 64-bit input mask
     * @return 64-bit prefix-XOR mask
     */
    public static long prefixXor(long mask) {
        mask ^= (mask << 1);
        mask ^= (mask << 2);
        mask ^= (mask << 4);
        mask ^= (mask << 8);
        mask ^= (mask << 16);
        mask ^= (mask << 32);
        return mask;
    }

    public static final class EscapeResult {
        public final long escaped;
        public final boolean nextIsEscaped;

        public EscapeResult(long escaped, boolean nextIsEscaped) {
            this.escaped = escaped;
            this.nextIsEscaped = nextIsEscaped;
        }
    }

    /**
     * Computes exact escape mask following simdjson C++ algorithm.
     */
    public static EscapeResult findEscapedChars(long backslashMask, boolean prevIsEscaped) {
        if (backslashMask == 0) {
            return new EscapeResult(prevIsEscaped ? 1L : 0L, false);
        }

        long potentialEscape = backslashMask & (prevIsEscaped ? ~1L : -1L);
        long maybeEscaped = potentialEscape << 1;
        long maybeEscapedAndOdd = maybeEscaped | ODD_BITS;
        long evenSeriesCodesAndOdd = maybeEscapedAndOdd - potentialEscape;
        long escapeAndTerminalCode = evenSeriesCodesAndOdd ^ ODD_BITS;

        long escaped = escapeAndTerminalCode ^ (potentialEscape | (prevIsEscaped ? 1L : 0L));
        long escape = escapeAndTerminalCode & backslashMask;
        boolean nextIsEscaped = (escape & (1L << 63)) != 0;

        return new EscapeResult(escaped, nextIsEscaped);
    }
}
