/*
 * Copyright 2023-2025 simdjson-java contributors
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

package dev.cardigan.simdjson;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

import java.util.Arrays;

import static jdk.incubator.vector.VectorOperators.EQ;
import static jdk.incubator.vector.VectorOperators.LSHL;
import static jdk.incubator.vector.VectorOperators.LSHR;
import static jdk.incubator.vector.VectorOperators.NE;
import static jdk.incubator.vector.VectorOperators.UGE;
import static jdk.incubator.vector.VectorOperators.UGT;

/** Compact lookup4 UTF-8 validator for heap-array Stage 1 input. */
final class HeapUtf8Validator {

    private static final VectorSpecies<Byte> BYTE_SPECIES =
        ByteVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Integer> INT_SPECIES =
        IntVector.SPECIES_PREFERRED;

    private static final byte TOO_SHORT = 1;
    private static final byte TOO_LONG = 1 << 1;
    private static final byte OVERLONG_3BYTE = 1 << 2;
    private static final byte TOO_LARGE = 1 << 3;
    private static final byte SURROGATE = 1 << 4;
    private static final byte OVERLONG_2BYTE = 1 << 5;
    private static final byte TOO_LARGE_1000 = 1 << 6;
    private static final byte OVERLONG_4BYTE = 1 << 6;
    private static final byte TWO_CONTINUATIONS = (byte) (1 << 7);
    private static final byte MAX_2_LEADING_BYTE = (byte) 0xdf;
    private static final byte MAX_3_LEADING_BYTE = (byte) 0xef;
    private static final int TWO_BYTES_SIZE = Byte.SIZE * 2;
    private static final int THREE_BYTES_SIZE = Byte.SIZE * 3;
    private static final byte LOW_NIBBLE_MASK = 0x0f;
    private static final byte ALL_ASCII_MASK = (byte) 0x80;

    private static final ByteVector BYTE_1_HIGH_LOOKUP =
        createByte1HighLookup();
    private static final ByteVector BYTE_1_LOW_LOOKUP =
        createByte1LowLookup();
    private static final ByteVector BYTE_2_HIGH_LOOKUP =
        createByte2HighLookup();
    private static final ByteVector INCOMPLETE_CHECK =
        createIncompleteCheck();
    private static final VectorShuffle<Integer> FOUR_BYTES_FORWARD_SHIFT =
        VectorShuffle.iota(
            INT_SPECIES, INT_SPECIES.elementSize() - 1, 1, true);

    private HeapUtf8Validator() {}

    static boolean validate(
            byte[] buffer, int base, int length,
            StructuralIndexes indexes) {
        long previousIncomplete = 0;
        long errors = 0;
        int previousFourUtf8Bytes = 0;
        int loopBound = BYTE_SPECIES.loopBound(length);
        int offset = 0;

        for (; offset < loopBound;
                offset += BYTE_SPECIES.vectorByteSize()) {
            ByteVector chunk = ByteVector.fromArray(
                BYTE_SPECIES, buffer, base + offset);
            long backslashes = chunk.eq((byte) '\\').toLong();
            if (backslashes != 0) {
                indexes.addBackslashBits(offset, backslashes);
            }
            if (chunk.and(ALL_ASCII_MASK).compare(EQ, 0).allTrue()) {
                errors |= previousIncomplete;
                previousFourUtf8Bytes = 0;
            } else {
                IntVector chunkAsInts = chunk.reinterpretAsInts();
                previousIncomplete = chunk.compare(
                    UGE, INCOMPLETE_CHECK).toLong();
                errors |= checkVector(
                    chunk, chunkAsInts, previousFourUtf8Bytes);
                previousFourUtf8Bytes =
                    chunkAsInts.lane(INT_SPECIES.length() - 1);
            }
        }

        if (offset < length) {
            VectorMask<Byte> remaining =
                BYTE_SPECIES.indexInRange(offset, length);
            ByteVector chunk = ByteVector.fromArray(
                BYTE_SPECIES, buffer, base + offset, remaining);
            long backslashes = chunk.eq((byte) '\\').toLong();
            if (backslashes != 0) {
                indexes.addBackslashBits(offset, backslashes);
            }
            if (!chunk.and(ALL_ASCII_MASK)
                    .compare(EQ, 0).allTrue()) {
                IntVector chunkAsInts = chunk.reinterpretAsInts();
                previousIncomplete = chunk.compare(
                    UGE, INCOMPLETE_CHECK).toLong();
                errors |= checkVector(
                    chunk, chunkAsInts, previousFourUtf8Bytes);
            }
        }
        return (errors | previousIncomplete) == 0;
    }

    private static long checkVector(
            ByteVector chunk, IntVector chunkAsInts,
            int previousFourUtf8Bytes) {
        IntVector chunkWithPreviousFourBytes = chunkAsInts
            .rearrange(FOUR_BYTES_FORWARD_SHIFT)
            .withLane(0, previousFourUtf8Bytes);
        ByteVector previousOneByte = chunkAsInts
            .lanewise(LSHL, Byte.SIZE)
            .or(chunkWithPreviousFourBytes
                .lanewise(LSHR, THREE_BYTES_SIZE))
            .reinterpretAsBytes();
        ByteVector byte2HighNibbles = chunkAsInts
            .lanewise(LSHR, 4).reinterpretAsBytes()
            .and(LOW_NIBBLE_MASK);
        ByteVector byte1HighNibbles = previousOneByte
            .reinterpretAsInts().lanewise(LSHR, 4)
            .reinterpretAsBytes().and(LOW_NIBBLE_MASK);
        ByteVector byte1LowNibbles =
            previousOneByte.and(LOW_NIBBLE_MASK);
        ByteVector firstCheck = byte1HighNibbles
            .selectFrom(BYTE_1_HIGH_LOOKUP)
            .and(byte1LowNibbles.selectFrom(BYTE_1_LOW_LOOKUP))
            .and(byte2HighNibbles.selectFrom(BYTE_2_HIGH_LOOKUP));

        ByteVector previousTwoBytes = chunkAsInts
            .lanewise(LSHL, TWO_BYTES_SIZE)
            .or(chunkWithPreviousFourBytes
                .lanewise(LSHR, TWO_BYTES_SIZE))
            .reinterpretAsBytes();
        VectorMask<Byte> is3ByteLead = previousTwoBytes
            .compare(UGT, MAX_2_LEADING_BYTE);
        ByteVector previousThreeBytes = chunkAsInts
            .lanewise(LSHL, THREE_BYTES_SIZE)
            .or(chunkWithPreviousFourBytes
                .lanewise(LSHR, Byte.SIZE))
            .reinterpretAsBytes();
        VectorMask<Byte> is4ByteLead = previousThreeBytes
            .compare(UGT, MAX_3_LEADING_BYTE);
        ByteVector secondCheck = firstCheck.add(
            (byte) 0x80, is3ByteLead.or(is4ByteLead));
        return secondCheck.compare(NE, 0).toLong();
    }

    private static ByteVector createIncompleteCheck() {
        byte[] values = new byte[BYTE_SPECIES.vectorByteSize()];
        Arrays.fill(values, (byte) 0xff);
        values[values.length - 3] = (byte) 0xf0;
        values[values.length - 2] = (byte) 0xe0;
        values[values.length - 1] = (byte) 0xc0;
        return ByteVector.fromArray(BYTE_SPECIES, values, 0);
    }

    private static ByteVector createByte1HighLookup() {
        return lookup(new byte[]{
            TOO_LONG, TOO_LONG, TOO_LONG, TOO_LONG,
            TOO_LONG, TOO_LONG, TOO_LONG, TOO_LONG,
            TWO_CONTINUATIONS, TWO_CONTINUATIONS,
            TWO_CONTINUATIONS, TWO_CONTINUATIONS,
            TOO_SHORT | OVERLONG_2BYTE, TOO_SHORT,
            TOO_SHORT | OVERLONG_3BYTE | SURROGATE,
            TOO_SHORT | TOO_LARGE | TOO_LARGE_1000
                | OVERLONG_4BYTE
        });
    }

    private static ByteVector createByte1LowLookup() {
        byte carry = TOO_SHORT | TOO_LONG | TWO_CONTINUATIONS;
        return lookup(new byte[]{
            (byte) (carry | OVERLONG_2BYTE | OVERLONG_3BYTE
                | OVERLONG_4BYTE),
            (byte) (carry | OVERLONG_2BYTE),
            carry, carry, (byte) (carry | TOO_LARGE),
            (byte) (carry | TOO_LARGE | TOO_LARGE_1000),
            (byte) (carry | TOO_LARGE | TOO_LARGE_1000),
            (byte) (carry | TOO_LARGE | TOO_LARGE_1000),
            (byte) (carry | TOO_LARGE | TOO_LARGE_1000),
            (byte) (carry | TOO_LARGE | TOO_LARGE_1000),
            (byte) (carry | TOO_LARGE | TOO_LARGE_1000),
            (byte) (carry | TOO_LARGE | TOO_LARGE_1000),
            (byte) (carry | TOO_LARGE | TOO_LARGE_1000),
            (byte) (carry | TOO_LARGE | TOO_LARGE_1000 | SURROGATE),
            (byte) (carry | TOO_LARGE | TOO_LARGE_1000),
            (byte) (carry | TOO_LARGE | TOO_LARGE_1000)
        });
    }

    private static ByteVector createByte2HighLookup() {
        byte continuation = TOO_LONG | TWO_CONTINUATIONS
            | OVERLONG_2BYTE;
        return lookup(new byte[]{
            TOO_SHORT, TOO_SHORT, TOO_SHORT, TOO_SHORT,
            TOO_SHORT, TOO_SHORT, TOO_SHORT, TOO_SHORT,
            (byte) (continuation | OVERLONG_3BYTE
                | OVERLONG_4BYTE | TOO_LARGE_1000),
            (byte) (continuation | OVERLONG_3BYTE | TOO_LARGE),
            (byte) (continuation | SURROGATE | TOO_LARGE),
            (byte) (continuation | SURROGATE | TOO_LARGE),
            TOO_SHORT, TOO_SHORT, TOO_SHORT, TOO_SHORT
        });
    }

    private static ByteVector lookup(byte[] values) {
        byte[] aligned = new byte[BYTE_SPECIES.vectorByteSize()];
        for (int i = 0; i < aligned.length; i++) {
            aligned[i] = values[i & 0x0f];
        }
        return ByteVector.fromArray(BYTE_SPECIES, aligned, 0);
    }
}
