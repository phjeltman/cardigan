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

package dev.cardigan.simdjson;

import dev.cardigan.ffi.RawSegment;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;

/**
 * simdjson-compatible Stage 1 structural indexer.
 *
 * <p>In addition to JSON operators, the normal output contains the opening
 * quote of every string and the first byte of every non-string scalar. UTF-8
 * validation and raw-control detection are performed during the same pass.
 * This is the contract consumed by the C++ simdjson Stage 2 machinery.</p>
 */
public final class Stage1Indexer {

    private static final VectorSpecies<Byte> SPECIES =
        ByteVector.SPECIES_PREFERRED;
    private static final int SPECIES_LEN = SPECIES.length();
    private static final long FULL_SPECIES_BITS = SPECIES_LEN == 64
        ? -1L : (1L << SPECIES_LEN) - 1L;

    private static final long ODD_BITS = 0xaaaa_aaaa_aaaa_aaaaL;

    private boolean hasBackslash;
    private boolean previousIsEscaped;
    private long utf8ContinuationCarry;
    private long utf8E0Carry;
    private long utf8EdCarry;
    private long utf8F0Carry;
    private long utf8F4Carry;
    private boolean utf8Error;
    private long scannedQuoteBits;
    private long scannedBackslashBits;
    private long scannedOperatorBits;
    private long scannedWhitespaceBits;
    private long scannedControlBits;
    private long scannedNonAsciiBits;
    private long pairFirstQuoteBits;
    private long pairFirstBackslashBits;
    private long pairFirstOperatorBits;
    private long pairFirstWhitespaceBits;
    private long pairFirstControlBits;
    private long pairFirstNonAsciiBits;
    private boolean pipelinePreviousInString;
    private boolean pipelinePreviousScalar;
    private boolean pipelineUnescapedControl;
    private long pipelinePreviousStructuralBits;
    private int pipelinePreviousStructuralBase;
    private long scannedVectorQuoteBits;
    private long scannedVectorBackslashBits;
    private long scannedVectorOperatorBits;
    private long scannedVectorWhitespaceBits;
    private long scannedVectorControlBits;
    private long scannedVectorNonAsciiBits;
    private byte[] heapInput;
    private int heapInputOffset;
    private long nativeInputAddress;
    private boolean heapUtf8Prevalidated;

    public Stage1Indexer() {}

    public void reset() {
        hasBackslash = false;
        previousIsEscaped = false;
        utf8ContinuationCarry = 0;
        utf8E0Carry = 0;
        utf8EdCarry = 0;
        utf8F0Carry = 0;
        utf8F4Carry = 0;
        utf8Error = false;
        heapUtf8Prevalidated = false;
    }

    public boolean hasBackslash() {
        return hasBackslash;
    }

    public SimdJsonError index(
            MemorySegment jsonSegment,
            StructuralIndexes outputIndexes) {
        outputIndexes.reset();
        reset();

        long length = jsonSegment.byteSize();
        if (length == 0) {
            return SimdJsonError.EMPTY;
        }
        if (length > Integer.MAX_VALUE) {
            return SimdJsonError.CAPACITY;
        }
        Object heapBase = jsonSegment.heapBase().orElse(null);
        if (heapBase instanceof byte[] bytes) {
            heapInput = bytes;
            heapInputOffset = (int) jsonSegment.address();
            nativeInputAddress = 0;
        } else {
            heapInput = null;
            heapInputOffset = 0;
            nativeInputAddress = jsonSegment.isNative()
                ? jsonSegment.address() : 0;
        }

        long offset = 0;
        boolean previousInString = false;
        boolean previousScalar = false;
        boolean unescapedControl = false;
        // Like C++ simdjson, delay index emission by one block. This gives the
        // processor independent work while classification of the current block
        // is resolving.
        long previousStructuralBits = 0;
        int previousStructuralBase = 0;
        boolean operatorsOnly = outputIndexes.operatorsOnly();

        // Heap arrays can keep vector loads, classification masks and serial
        // quote state in one compilation unit. Besides avoiding mask handoff
        // through fields, this lets C2 hoist all array bounds checks out of
        // the loop. UTF-8 is deliberately checked in a separate compact pass
        // so it does not lengthen the structural loop's live ranges.
        if (heapInput != null && SPECIES_LEN == 32
                && length >= 256 && !operatorsOnly
                && !outputIndexes.isNative()) {
            long fullLength = length & ~63L;
            heapUtf8Prevalidated = true;
            utf8Error = !HeapUtf8Validator.validate(
                heapInput, heapInputOffset, (int) length,
                outputIndexes);
            offset = indexHeapBlocks(
                fullLength, outputIndexes);
            previousInString = pipelinePreviousInString;
            previousScalar = pipelinePreviousScalar;
            unescapedControl = pipelineUnescapedControl;
            previousStructuralBits = pipelinePreviousStructuralBits;
            previousStructuralBase = pipelinePreviousStructuralBase;
        // One isolated pair does not amortize the pipeline call/state handoff;
        // keep small documents on the compact 64-byte loop.
        } else if (SPECIES_LEN == 32 && length >= 256) {
            offset = indexBlockPairs(
                jsonSegment, length, outputIndexes, operatorsOnly);
            previousInString = pipelinePreviousInString;
            previousScalar = pipelinePreviousScalar;
            unescapedControl = pipelineUnescapedControl;
            previousStructuralBits = pipelinePreviousStructuralBits;
            previousStructuralBase = pipelinePreviousStructuralBase;
        }

        while (offset < length) {
            int remaining = (int) Math.min(64L, length - offset);
            long validBits = remaining == 64
                ? -1L : (1L << remaining) - 1L;

            scanBlock(jsonSegment, offset, remaining);
            long quoteBits = scannedQuoteBits & validBits;
            long backslashBits = scannedBackslashBits & validBits;
            long operatorBits = scannedOperatorBits & validBits;
            long whitespaceBits = scannedWhitespaceBits & validBits;
            long controlBits = scannedControlBits & validBits;

            hasBackslash |= backslashBits != 0;
            if (backslashBits != 0 && !heapUtf8Prevalidated) {
                outputIndexes.addBackslashBits(
                    (int) offset, backslashBits);
            }
            long escaped;
            if (backslashBits == 0) {
                escaped = previousIsEscaped ? 1L : 0L;
                previousIsEscaped = false;
            } else {
                long previous = previousIsEscaped ? 1L : 0L;
                long potentialEscape = backslashBits
                    & (previousIsEscaped ? ~1L : -1L);
                long maybeEscaped = potentialEscape << 1;
                long evenSeriesCodesAndOdd =
                    (maybeEscaped | ODD_BITS) - potentialEscape;
                long escapeAndTerminalCode =
                    evenSeriesCodesAndOdd ^ ODD_BITS;
                escaped = escapeAndTerminalCode
                    ^ (potentialEscape | previous);
                previousIsEscaped =
                    (escapeAndTerminalCode & backslashBits) < 0;
            }
            long realQuotes = quoteBits & ~escaped;
            long quotePrefix = realQuotes;
            quotePrefix ^= quotePrefix << 1;
            quotePrefix ^= quotePrefix << 2;
            quotePrefix ^= quotePrefix << 4;
            quotePrefix ^= quotePrefix << 8;
            quotePrefix ^= quotePrefix << 16;
            quotePrefix ^= quotePrefix << 32;
            long inString = quotePrefix
                ^ (previousInString ? -1L : 0L);
            previousInString = inString < 0;

            long stringTail = inString ^ realQuotes;
            unescapedControl |= (controlBits & inString) != 0;

            long scalarBits = ~(operatorBits | whitespaceBits)
                & validBits;
            long nonQuoteScalar = scalarBits & ~quoteBits;
            long followsScalar = (nonQuoteScalar << 1)
                | (previousScalar ? 1L : 0L);
            previousScalar = nonQuoteScalar < 0;
            long scalarStarts = scalarBits & ~followsScalar;
            long structuralBits = operatorsOnly
                ? operatorBits & ~inString
                : (operatorBits | scalarStarts) & ~stringTail;

            outputIndexes.addBits(
                previousStructuralBase, previousStructuralBits);
            previousStructuralBits = structuralBits;
            previousStructuralBase = (int) offset;

            offset += 64;
        }

        outputIndexes.addBits(
            previousStructuralBase, previousStructuralBits);

        if (previousInString) {
            return SimdJsonError.UNCLOSED_STRING;
        }
        if (unescapedControl) {
            return SimdJsonError.UNESCAPED_CHARS;
        }
        if (utf8Error || utf8ContinuationCarry != 0) {
            return SimdJsonError.UTF8_ERROR;
        }
        return SimdJsonError.SUCCESS;
    }

    /**
     * Structural Stage 1 for full heap-array blocks. This method is kept
     * deliberately self-contained: passing six masks through helper fields
     * prevents C2 from hoisting vector bounds checks and costs substantially
     * more than the classification itself.
     */
    private long indexHeapBlocks(
            long length, StructuralIndexes outputIndexes) {
        long previousInString = 0;
        long previousEscaped = 0;
        long previousScalar = 0;
        long previousStructuralBits = 0;
        int previousStructuralBase = 0;
        long unescapedControl = 0;
        boolean foundBackslash = false;
        byte[] input = heapInput;
        int inputBase = heapInputOffset;

        for (int offset = 0; offset < (int) length; offset += 64) {
            int arrayOffset = inputBase + offset;
            ByteVector first = ByteVector.fromArray(
                SPECIES, input, arrayOffset);
            ByteVector second = ByteVector.fromArray(
                SPECIES, input, arrayOffset + 32);

            long quoteBits = first.eq((byte) '"').toLong()
                | (second.eq((byte) '"').toLong() << 32);
            long backslashBits = first.eq((byte) '\\').toLong()
                | (second.eq((byte) '\\').toLong() << 32);
            long controlBits = first.compare(
                    VectorOperators.ULE, (byte) 0x1f).toLong()
                | (second.compare(
                    VectorOperators.ULE, (byte) 0x1f).toLong() << 32);

            ByteVector firstCurlified = first.or((byte) 0x20);
            ByteVector secondCurlified = second.or((byte) 0x20);
            long operatorBits = firstCurlified.eq((byte) '{')
                    .or(firstCurlified.eq((byte) '}'))
                    .or(first.eq((byte) ','))
                    .or(first.eq((byte) ':')).toLong()
                | (secondCurlified.eq((byte) '{')
                    .or(secondCurlified.eq((byte) '}'))
                    .or(second.eq((byte) ','))
                    .or(second.eq((byte) ':')).toLong() << 32);
            long whitespaceBits = first.and((byte) ~0x04)
                    .eq((byte) '\t').or(first.eq((byte) '\n'))
                    .or(first.eq((byte) ' ')).toLong()
                | (second.and((byte) ~0x04).eq((byte) '\t')
                    .or(second.eq((byte) '\n'))
                    .or(second.eq((byte) ' ')).toLong() << 32);

            foundBackslash |= backslashBits != 0;
            long escaped;
            if (backslashBits == 0) {
                escaped = previousEscaped;
                previousEscaped = 0;
            } else {
                long potentialEscape = backslashBits & ~previousEscaped;
                long maybeEscaped = potentialEscape << 1;
                long evenSeriesCodesAndOdd =
                    (maybeEscaped | ODD_BITS) - potentialEscape;
                long escapeAndTerminalCode =
                    evenSeriesCodesAndOdd ^ ODD_BITS;
                escaped = escapeAndTerminalCode
                    ^ (potentialEscape | previousEscaped);
                previousEscaped =
                    (escapeAndTerminalCode & backslashBits) >>> 63;
            }
            long realQuotes = quoteBits & ~escaped;
            long inString = prefixXor(realQuotes)
                ^ previousInString;
            previousInString = inString >> 63;

            long stringTail = inString ^ realQuotes;
            unescapedControl |= controlBits & inString;
            long scalarBits = ~(operatorBits | whitespaceBits);
            long nonQuoteScalar = scalarBits & ~quoteBits;
            long followsScalar = (nonQuoteScalar << 1)
                | previousScalar;
            previousScalar = nonQuoteScalar >>> 63;
            long scalarStarts = scalarBits & ~followsScalar;
            long structuralBits =
                (operatorBits | scalarStarts) & ~stringTail;

            outputIndexes.addBitsHeap(
                previousStructuralBase, previousStructuralBits);
            previousStructuralBits = structuralBits;
            previousStructuralBase = offset;
        }

        hasBackslash = foundBackslash;
        previousIsEscaped = previousEscaped != 0;
        pipelinePreviousInString = previousInString != 0;
        pipelinePreviousScalar = previousScalar != 0;
        pipelineUnescapedControl = unescapedControl != 0;
        pipelinePreviousStructuralBits = previousStructuralBits;
        pipelinePreviousStructuralBase = previousStructuralBase;
        return length;
    }

    private static long prefixXor(long bits) {
        bits ^= bits << 1;
        bits ^= bits << 2;
        bits ^= bits << 4;
        bits ^= bits << 8;
        bits ^= bits << 16;
        bits ^= bits << 32;
        return bits;
    }

    private long indexBlockPairs(
            MemorySegment jsonSegment, long length,
            StructuralIndexes outputIndexes, boolean operatorsOnly) {
        long offset = 0;
        boolean previousInString = false;
        boolean previousScalar = false;
        boolean unescapedControl = false;
        long previousStructuralBits = 0;
        int previousStructuralBase = 0;

        // The C++ implementation deliberately works two blocks at a time:
        // classify both blocks first, then consume the serial quote/backslash
        // carry one block at a time. Keeping the four vector classifications
        // ahead of UTF-8 validation and index emission exposes enough
        // independent work for an out-of-order core to overlap the stages.
        while (offset + 128 <= length) {
            scanBlockPair(jsonSegment, offset);

            long quoteBits = pairFirstQuoteBits;
            long backslashBits = pairFirstBackslashBits;
            long operatorBits = pairFirstOperatorBits;
            long whitespaceBits = pairFirstWhitespaceBits;
            long controlBits = pairFirstControlBits;

            validateFullBlock(
                jsonSegment, offset, pairFirstNonAsciiBits);
            hasBackslash |= backslashBits != 0;
            if (backslashBits != 0) {
                outputIndexes.addBackslashBits(
                    (int) offset, backslashBits);
            }
            long escaped;
            if (backslashBits == 0) {
                escaped = previousIsEscaped ? 1L : 0L;
                previousIsEscaped = false;
            } else {
                long previous = previousIsEscaped ? 1L : 0L;
                long potentialEscape = backslashBits
                    & (previousIsEscaped ? ~1L : -1L);
                long maybeEscaped = potentialEscape << 1;
                long evenSeriesCodesAndOdd =
                    (maybeEscaped | ODD_BITS) - potentialEscape;
                long escapeAndTerminalCode =
                    evenSeriesCodesAndOdd ^ ODD_BITS;
                escaped = escapeAndTerminalCode
                    ^ (potentialEscape | previous);
                previousIsEscaped =
                    (escapeAndTerminalCode & backslashBits) < 0;
            }
            long realQuotes = quoteBits & ~escaped;
            long quotePrefix = realQuotes;
            quotePrefix ^= quotePrefix << 1;
            quotePrefix ^= quotePrefix << 2;
            quotePrefix ^= quotePrefix << 4;
            quotePrefix ^= quotePrefix << 8;
            quotePrefix ^= quotePrefix << 16;
            quotePrefix ^= quotePrefix << 32;
            long inString = quotePrefix
                ^ (previousInString ? -1L : 0L);
            previousInString = inString < 0;

            long stringTail = inString ^ realQuotes;
            unescapedControl |= (controlBits & inString) != 0;
            long scalarBits = ~(operatorBits | whitespaceBits);
            long nonQuoteScalar = scalarBits & ~quoteBits;
            long followsScalar = (nonQuoteScalar << 1)
                | (previousScalar ? 1L : 0L);
            previousScalar = nonQuoteScalar < 0;
            long scalarStarts = scalarBits & ~followsScalar;
            long structuralBits = operatorsOnly
                ? operatorBits & ~inString
                : (operatorBits | scalarStarts) & ~stringTail;

            outputIndexes.addBits(
                previousStructuralBase, previousStructuralBits);
            previousStructuralBits = structuralBits;
            previousStructuralBase = (int) offset;

            quoteBits = scannedQuoteBits;
            backslashBits = scannedBackslashBits;
            operatorBits = scannedOperatorBits;
            whitespaceBits = scannedWhitespaceBits;
            controlBits = scannedControlBits;

            validateFullBlock(
                jsonSegment, offset + 64, scannedNonAsciiBits);
            hasBackslash |= backslashBits != 0;
            if (backslashBits != 0) {
                outputIndexes.addBackslashBits(
                    (int) offset + 64, backslashBits);
            }
            if (backslashBits == 0) {
                escaped = previousIsEscaped ? 1L : 0L;
                previousIsEscaped = false;
            } else {
                long previous = previousIsEscaped ? 1L : 0L;
                long potentialEscape = backslashBits
                    & (previousIsEscaped ? ~1L : -1L);
                long maybeEscaped = potentialEscape << 1;
                long evenSeriesCodesAndOdd =
                    (maybeEscaped | ODD_BITS) - potentialEscape;
                long escapeAndTerminalCode =
                    evenSeriesCodesAndOdd ^ ODD_BITS;
                escaped = escapeAndTerminalCode
                    ^ (potentialEscape | previous);
                previousIsEscaped =
                    (escapeAndTerminalCode & backslashBits) < 0;
            }
            realQuotes = quoteBits & ~escaped;
            quotePrefix = realQuotes;
            quotePrefix ^= quotePrefix << 1;
            quotePrefix ^= quotePrefix << 2;
            quotePrefix ^= quotePrefix << 4;
            quotePrefix ^= quotePrefix << 8;
            quotePrefix ^= quotePrefix << 16;
            quotePrefix ^= quotePrefix << 32;
            inString = quotePrefix
                ^ (previousInString ? -1L : 0L);
            previousInString = inString < 0;

            stringTail = inString ^ realQuotes;
            unescapedControl |= (controlBits & inString) != 0;
            scalarBits = ~(operatorBits | whitespaceBits);
            nonQuoteScalar = scalarBits & ~quoteBits;
            followsScalar = (nonQuoteScalar << 1)
                | (previousScalar ? 1L : 0L);
            previousScalar = nonQuoteScalar < 0;
            scalarStarts = scalarBits & ~followsScalar;
            structuralBits = operatorsOnly
                ? operatorBits & ~inString
                : (operatorBits | scalarStarts) & ~stringTail;

            outputIndexes.addBits(
                previousStructuralBase, previousStructuralBits);
            previousStructuralBits = structuralBits;
            previousStructuralBase = (int) offset + 64;
            offset += 128;
        }

        pipelinePreviousInString = previousInString;
        pipelinePreviousScalar = previousScalar;
        pipelineUnescapedControl = unescapedControl;
        pipelinePreviousStructuralBits = previousStructuralBits;
        pipelinePreviousStructuralBase = previousStructuralBase;
        return offset;
    }

    private void scanBlock(
            MemorySegment segment, long offset, int remaining) {
        long quoteBits = 0;
        long backslashBits = 0;
        long operatorBits = 0;
        long whitespaceBits = 0;
        long controlBits = 0;

        if (SPECIES_LEN == 32) {
            int firstLanes = Math.min(32, remaining);
            int secondLanes = Math.max(0, remaining - 32);
            if (firstLanes == 32) {
                scanFullVector(segment, offset);
            } else {
                scanTailScalar(segment, offset, firstLanes);
            }
            quoteBits = scannedVectorQuoteBits;
            backslashBits = scannedVectorBackslashBits;
            operatorBits = scannedVectorOperatorBits;
            whitespaceBits = scannedVectorWhitespaceBits;
            controlBits = scannedVectorControlBits;
            if (secondLanes != 0) {
                if (secondLanes == 32) {
                    scanFullVector(segment, offset + 32);
                } else {
                    scanTailScalar(
                        segment, offset + 32, secondLanes);
                }
                quoteBits |= scannedVectorQuoteBits << 32;
                backslashBits |= scannedVectorBackslashBits << 32;
                operatorBits |= scannedVectorOperatorBits << 32;
                whitespaceBits |= scannedVectorWhitespaceBits << 32;
                controlBits |= scannedVectorControlBits << 32;
            }
        } else {
            int laneOffset = 0;
            while (laneOffset < 64) {
                int validLanes = Math.min(
                    SPECIES_LEN,
                    Math.max(0, remaining - laneOffset));
                if (validLanes == 0) {
                    break;
                }
                if (validLanes == SPECIES_LEN) {
                    scanFullVector(
                        segment, offset + laneOffset);
                } else {
                    scanTailScalar(
                        segment, offset + laneOffset, validLanes);
                }
                quoteBits |= scannedVectorQuoteBits << laneOffset;
                backslashBits |=
                    scannedVectorBackslashBits << laneOffset;
                operatorBits |= scannedVectorOperatorBits << laneOffset;
                whitespaceBits |=
                    scannedVectorWhitespaceBits << laneOffset;
                controlBits |= scannedVectorControlBits << laneOffset;
                laneOffset += SPECIES_LEN;
            }
        }

        scannedQuoteBits = quoteBits;
        scannedBackslashBits = backslashBits;
        scannedOperatorBits = operatorBits;
        scannedWhitespaceBits = whitespaceBits;
        scannedControlBits = controlBits;
    }

    /**
     * Classify two 64-byte blocks before consuming either block's serial
     * string/escape state. This is the Java equivalent of simdjson's
     * {@code json_structural_indexer::step<128>} pipeline.
     */
    private void scanBlockPair(
            MemorySegment segment, long offset) {
        if (heapInput != null) {
            scanFullBlockArrayUnvalidated(
                heapInput, heapInputOffset + (int) offset);
            pairFirstQuoteBits = scannedQuoteBits;
            pairFirstBackslashBits = scannedBackslashBits;
            pairFirstOperatorBits = scannedOperatorBits;
            pairFirstWhitespaceBits = scannedWhitespaceBits;
            pairFirstControlBits = scannedControlBits;
            pairFirstNonAsciiBits = scannedNonAsciiBits;
            scanFullBlockArrayUnvalidated(
                heapInput, heapInputOffset + (int) offset + 64);
            return;
        }
        if (nativeInputAddress != 0) {
            scanFullBlockNativeUnvalidated(
                nativeInputAddress + offset);
            pairFirstQuoteBits = scannedQuoteBits;
            pairFirstBackslashBits = scannedBackslashBits;
            pairFirstOperatorBits = scannedOperatorBits;
            pairFirstWhitespaceBits = scannedWhitespaceBits;
            pairFirstControlBits = scannedControlBits;
            pairFirstNonAsciiBits = scannedNonAsciiBits;
            scanFullBlockNativeUnvalidated(
                nativeInputAddress + offset + 64);
            return;
        }
        scanFullVectorUnvalidated(segment, offset);
        long firstQuoteBits = scannedVectorQuoteBits;
        long firstBackslashBits = scannedVectorBackslashBits;
        long firstOperatorBits = scannedVectorOperatorBits;
        long firstWhitespaceBits = scannedVectorWhitespaceBits;
        long firstControlBits = scannedVectorControlBits;
        long firstNonAsciiBits = scannedVectorNonAsciiBits;

        scanFullVectorUnvalidated(segment, offset + 32);
        firstQuoteBits |= scannedVectorQuoteBits << 32;
        firstBackslashBits |= scannedVectorBackslashBits << 32;
        firstOperatorBits |= scannedVectorOperatorBits << 32;
        firstWhitespaceBits |= scannedVectorWhitespaceBits << 32;
        firstControlBits |= scannedVectorControlBits << 32;
        firstNonAsciiBits |= scannedVectorNonAsciiBits << 32;

        // Commit block one before loading block two. Besides shortening the
        // live ranges of six masks, this lets C2 keep the two classification
        // groups independent instead of spilling block one around block two.
        pairFirstQuoteBits = firstQuoteBits;
        pairFirstBackslashBits = firstBackslashBits;
        pairFirstOperatorBits = firstOperatorBits;
        pairFirstWhitespaceBits = firstWhitespaceBits;
        pairFirstControlBits = firstControlBits;
        pairFirstNonAsciiBits = firstNonAsciiBits;

        scanFullVectorUnvalidated(segment, offset + 64);
        long secondQuoteBits = scannedVectorQuoteBits;
        long secondBackslashBits = scannedVectorBackslashBits;
        long secondOperatorBits = scannedVectorOperatorBits;
        long secondWhitespaceBits = scannedVectorWhitespaceBits;
        long secondControlBits = scannedVectorControlBits;
        long secondNonAsciiBits = scannedVectorNonAsciiBits;

        scanFullVectorUnvalidated(segment, offset + 96);
        secondQuoteBits |= scannedVectorQuoteBits << 32;
        secondBackslashBits |= scannedVectorBackslashBits << 32;
        secondOperatorBits |= scannedVectorOperatorBits << 32;
        secondWhitespaceBits |= scannedVectorWhitespaceBits << 32;
        secondControlBits |= scannedVectorControlBits << 32;
        secondNonAsciiBits |= scannedVectorNonAsciiBits << 32;

        scannedQuoteBits = secondQuoteBits;
        scannedBackslashBits = secondBackslashBits;
        scannedOperatorBits = secondOperatorBits;
        scannedWhitespaceBits = secondWhitespaceBits;
        scannedControlBits = secondControlBits;
        scannedNonAsciiBits = secondNonAsciiBits;
    }

    private void scanFullBlockArrayUnvalidated(
            byte[] input, int offset) {
        ByteVector vector = ByteVector.fromArray(
            SPECIES, input, offset);
        long quoteBits = vector.eq((byte) '"').toLong();
        long backslashBits = vector.eq((byte) '\\').toLong();
        ByteVector curlified = vector.or((byte) 0x20);
        long operatorBits = curlified.eq((byte) '{')
            .or(curlified.eq((byte) '}'))
            .or(vector.eq((byte) ','))
            .or(vector.eq((byte) ':'))
            .toLong();
        long whitespaceBits = vector.and((byte) ~0x04)
            .eq((byte) '\t')
            .or(vector.eq((byte) '\n'))
            .or(vector.eq((byte) ' '))
            .toLong();
        long controlBits = vector.compare(
            VectorOperators.ULE, (byte) 0x1f).toLong();
        long nonAsciiBits = vector.compare(
            VectorOperators.LT, (byte) 0).toLong();

        vector = ByteVector.fromArray(SPECIES, input, offset + 32);
        quoteBits |= vector.eq((byte) '"').toLong() << 32;
        backslashBits |= vector.eq((byte) '\\').toLong() << 32;
        curlified = vector.or((byte) 0x20);
        operatorBits |= curlified.eq((byte) '{')
            .or(curlified.eq((byte) '}'))
            .or(vector.eq((byte) ','))
            .or(vector.eq((byte) ':'))
            .toLong() << 32;
        whitespaceBits |= vector.and((byte) ~0x04)
            .eq((byte) '\t')
            .or(vector.eq((byte) '\n'))
            .or(vector.eq((byte) ' '))
            .toLong() << 32;
        controlBits |= vector.compare(
            VectorOperators.ULE, (byte) 0x1f).toLong() << 32;
        nonAsciiBits |= vector.compare(
            VectorOperators.LT, (byte) 0).toLong() << 32;

        scannedQuoteBits = quoteBits;
        scannedBackslashBits = backslashBits;
        scannedOperatorBits = operatorBits;
        scannedWhitespaceBits = whitespaceBits;
        scannedControlBits = controlBits;
        scannedNonAsciiBits = nonAsciiBits;
    }

    private void scanFullBlockNativeUnvalidated(long address) {
        ByteVector vector = ByteVector.fromMemorySegment(
            SPECIES, RawSegment.ADDRESS_SPACE,
            address, ByteOrder.nativeOrder());
        long quoteBits = vector.eq((byte) '"').toLong();
        long backslashBits = vector.eq((byte) '\\').toLong();
        ByteVector curlified = vector.or((byte) 0x20);
        long operatorBits = curlified.eq((byte) '{')
            .or(curlified.eq((byte) '}'))
            .or(vector.eq((byte) ','))
            .or(vector.eq((byte) ':'))
            .toLong();
        long whitespaceBits = vector.and((byte) ~0x04)
            .eq((byte) '\t')
            .or(vector.eq((byte) '\n'))
            .or(vector.eq((byte) ' '))
            .toLong();
        long controlBits = vector.compare(
            VectorOperators.ULE, (byte) 0x1f).toLong();
        long nonAsciiBits = vector.compare(
            VectorOperators.LT, (byte) 0).toLong();

        vector = ByteVector.fromMemorySegment(
            SPECIES, RawSegment.ADDRESS_SPACE,
            address + 32, ByteOrder.nativeOrder());
        quoteBits |= vector.eq((byte) '"').toLong() << 32;
        backslashBits |= vector.eq((byte) '\\').toLong() << 32;
        curlified = vector.or((byte) 0x20);
        operatorBits |= curlified.eq((byte) '{')
            .or(curlified.eq((byte) '}'))
            .or(vector.eq((byte) ','))
            .or(vector.eq((byte) ':'))
            .toLong() << 32;
        whitespaceBits |= vector.and((byte) ~0x04)
            .eq((byte) '\t')
            .or(vector.eq((byte) '\n'))
            .or(vector.eq((byte) ' '))
            .toLong() << 32;
        controlBits |= vector.compare(
            VectorOperators.ULE, (byte) 0x1f).toLong() << 32;
        nonAsciiBits |= vector.compare(
            VectorOperators.LT, (byte) 0).toLong() << 32;

        scannedQuoteBits = quoteBits;
        scannedBackslashBits = backslashBits;
        scannedOperatorBits = operatorBits;
        scannedWhitespaceBits = whitespaceBits;
        scannedControlBits = controlBits;
        scannedNonAsciiBits = nonAsciiBits;
    }

    private void scanFullVectorUnvalidated(
            MemorySegment segment, long offset) {
        ByteVector vector = ByteVector.fromMemorySegment(
            SPECIES, segment, offset, ByteOrder.nativeOrder());
        scannedVectorQuoteBits = vector.eq((byte) '"').toLong();
        scannedVectorBackslashBits = vector.eq((byte) '\\').toLong();
        scannedVectorOperatorBits = operatorMask(vector);
        scannedVectorWhitespaceBits = whitespaceMask(vector);
        scannedVectorControlBits = vector.compare(
            VectorOperators.ULE, (byte) 0x1f).toLong();
        scannedVectorNonAsciiBits = vector.compare(
            VectorOperators.LT, (byte) 0).toLong();
    }

    private void validateFullBlock(
            MemorySegment segment, long offset, long nonAsciiBits) {
        long firstNonAscii = nonAsciiBits & FULL_SPECIES_BITS;
        if (firstNonAscii != 0 || utf8ContinuationCarry != 0) {
            validateFullVector(segment, offset, firstNonAscii);
        }
        long secondNonAscii = nonAsciiBits >>> 32;
        if (secondNonAscii != 0 || utf8ContinuationCarry != 0) {
            validateFullVector(segment, offset + 32, secondNonAscii);
        }
    }

    private void validateFullVector(
            MemorySegment segment, long offset, long nonAscii) {
        if (nativeInputAddress != 0) {
            long address = nativeInputAddress + offset;
            validateUtf8(
                RawSegment.ADDRESS_SPACE, address, nonAscii,
                FULL_SPECIES_BITS, SPECIES_LEN);
        } else {
            validateUtf8(
                segment, offset, nonAscii,
                FULL_SPECIES_BITS, SPECIES_LEN);
        }
    }

    private void scanFullVector(
            MemorySegment segment, long offset) {
        if (heapInput != null) {
            scanFullArray(
                segment, offset,
                heapInput, heapInputOffset + (int) offset);
        } else if (nativeInputAddress != 0) {
            scanFullNative(offset);
        } else {
            scanFullSegment(segment, offset);
        }
    }

    private void scanFullNative(long offset) {
        long address = nativeInputAddress + offset;
        ByteVector vector = ByteVector.fromMemorySegment(
            SPECIES, RawSegment.ADDRESS_SPACE, address,
            ByteOrder.nativeOrder());
        scannedVectorQuoteBits =
            vector.eq((byte) '"').toLong();
        scannedVectorBackslashBits =
            vector.eq((byte) '\\').toLong();
        scannedVectorOperatorBits = operatorMask(vector);
        scannedVectorWhitespaceBits = whitespaceMask(vector);
        scannedVectorControlBits = vector.compare(
            VectorOperators.ULE, (byte) 0x1f).toLong();
        long nonAscii = vector.compare(
            VectorOperators.LT, (byte) 0).toLong();
        if (nonAscii != 0 || utf8ContinuationCarry != 0) {
            validateUtf8(
                RawSegment.ADDRESS_SPACE, address, nonAscii,
                FULL_SPECIES_BITS, SPECIES_LEN);
        }
    }

    private void scanFullArray(
            MemorySegment segment, long segmentOffset,
            byte[] input, int offset) {
        ByteVector vector = ByteVector.fromArray(
            SPECIES, input, offset);
        scannedVectorQuoteBits =
            vector.eq((byte) '"').toLong();
        scannedVectorBackslashBits =
            vector.eq((byte) '\\').toLong();
        scannedVectorOperatorBits = operatorMask(vector);
        scannedVectorWhitespaceBits = whitespaceMask(vector);
        scannedVectorControlBits = vector.compare(
            VectorOperators.ULE, (byte) 0x1f).toLong();
        long nonAscii = vector.compare(
            VectorOperators.LT, (byte) 0).toLong();
        if (!heapUtf8Prevalidated
                && (nonAscii != 0 || utf8ContinuationCarry != 0)) {
            validateUtf8(
                segment, segmentOffset, nonAscii,
                FULL_SPECIES_BITS, SPECIES_LEN);
        }
    }

    private void scanFullSegment(
            MemorySegment segment, long offset) {
        ByteVector vector = ByteVector.fromMemorySegment(
            SPECIES, segment, offset, ByteOrder.nativeOrder());
        scannedVectorQuoteBits =
            vector.eq((byte) '"').toLong();
        scannedVectorBackslashBits =
            vector.eq((byte) '\\').toLong();
        scannedVectorOperatorBits = operatorMask(vector);
        scannedVectorWhitespaceBits = whitespaceMask(vector);
        scannedVectorControlBits = vector.compare(
            VectorOperators.ULE, (byte) 0x1f).toLong();
        long nonAscii = vector.compare(
            VectorOperators.LT, (byte) 0).toLong();
        if (nonAscii != 0 || utf8ContinuationCarry != 0) {
            validateUtf8(
                segment, offset, nonAscii,
                FULL_SPECIES_BITS, SPECIES_LEN);
        }
    }

    private void scanTailScalar(
            MemorySegment segment, long offset, int validLanes) {
        long quoteBits = 0;
        long backslashBits = 0;
        long operatorBits = 0;
        long whitespaceBits = 0;
        long controlBits = 0;
        long nonAscii = 0;
        for (int lane = 0; lane < validLanes; lane++) {
            byte value;
            if (heapInput != null) {
                value = heapInput[
                    heapInputOffset + (int) offset + lane];
            } else if (nativeInputAddress != 0) {
                value = (byte) RawSegment.BYTE.get(
                    RawSegment.ADDRESS_SPACE,
                    nativeInputAddress + offset + lane);
            } else {
                value = (byte) RawSegment.BYTE.get(
                    segment, offset + lane);
            }
            long bit = 1L << lane;
            if (value == '"') {
                quoteBits |= bit;
            } else if (value == '\\') {
                backslashBits |= bit;
            }
            if (value == '{' || value == '}'
                    || value == '[' || value == ']'
                    || value == ',' || value == ':') {
                operatorBits |= bit;
            } else if (value == ' ' || value == '\t'
                    || value == '\n' || value == '\r') {
                whitespaceBits |= bit;
            }
            if ((value & 0xff) <= 0x1f) {
                controlBits |= bit;
            }
            if (value < 0) {
                nonAscii |= bit;
            }
        }
        scannedVectorQuoteBits = quoteBits;
        scannedVectorBackslashBits = backslashBits;
        scannedVectorOperatorBits = operatorBits;
        scannedVectorWhitespaceBits = whitespaceBits;
        scannedVectorControlBits = controlBits;
        if (!heapUtf8Prevalidated
                && (nonAscii != 0 || utf8ContinuationCarry != 0)) {
            long validBits = (1L << validLanes) - 1L;
            validateUtf8(
                segment, offset, nonAscii,
                validBits, validLanes);
        }
    }

    private static long operatorMask(ByteVector input) {
        ByteVector curlified = input.or((byte) 0x20);
        return curlified.eq((byte) '{')
            .or(curlified.eq((byte) '}'))
            .or(input.eq((byte) ','))
            .or(input.eq((byte) ':'))
            .toLong();
    }

    private static long whitespaceMask(ByteVector input) {
        return input.and((byte) ~0x04).eq((byte) '\t')
            .or(input.eq((byte) '\n'))
            .or(input.eq((byte) ' '))
            .toLong();
    }

    /**
     * Validate one vector using lookup4-equivalent byte classes and carry
     * semantics. Explicit masks avoid cross-lane table selection in generated
     * Vector API code.
     */
    private void validateUtf8(
            MemorySegment segment, long offset,
            long nonAscii, long validBits,
            int validLanes) {
        if (nonAscii == 0) {
            if (utf8ContinuationCarry != 0) {
                utf8Error = true;
                utf8ContinuationCarry = 0;
                utf8E0Carry = 0;
                utf8EdCarry = 0;
                utf8F0Carry = 0;
                utf8F4Carry = 0;
            }
            return;
        }

        VectorMask<Byte> validMask =
            SPECIES.indexInRange(0, validLanes);
        ByteVector input;
        if (heapInput != null) {
            int arrayOffset = heapInputOffset + (int) offset;
            input = validLanes == SPECIES_LEN
                ? ByteVector.fromArray(
                    SPECIES, heapInput, arrayOffset)
                : ByteVector.fromArray(
                    SPECIES, heapInput, arrayOffset, validMask);
        } else {
            input = validLanes == SPECIES_LEN
                ? ByteVector.fromMemorySegment(
                    SPECIES, segment, offset, ByteOrder.nativeOrder())
                : ByteVector.fromMemorySegment(
                    SPECIES, segment, offset,
                    ByteOrder.nativeOrder(), validMask);
        }

        long continuation = input.compare(
            VectorOperators.LE, (byte) 0xbf).toLong() & validBits;
        long lead2 = input.compare(
                VectorOperators.GE, (byte) 0xc2)
            .and(input.compare(VectorOperators.LE, (byte) 0xdf))
            .toLong() & validBits;
        long lead3 = input.compare(
                VectorOperators.GE, (byte) 0xe0)
            .and(input.compare(VectorOperators.LE, (byte) 0xef))
            .toLong() & validBits;
        long lead4 = input.compare(
                VectorOperators.GE, (byte) 0xf0)
            .and(input.compare(VectorOperators.LE, (byte) 0xf4))
            .toLong() & validBits;

        long allLeads = lead2 | lead3 | lead4;
        long longLeads = lead3 | lead4;
        long expected = utf8ContinuationCarry
            | (allLeads << 1)
            | (longLeads << 2)
            | (lead4 << 3);
        long invalidLead = nonAscii
            & ~(continuation | allLeads);
        if (((continuation ^ expected) & validBits) != 0
                || invalidLead != 0) {
            utf8Error = true;
        }

        long e0 = input.eq((byte) 0xe0).toLong();
        long ed = input.eq((byte) 0xed).toLong();
        long f0 = input.eq((byte) 0xf0).toLong();
        long f4 = input.eq((byte) 0xf4).toLong();
        long e0Second = utf8E0Carry | (e0 << 1);
        long edSecond = utf8EdCarry | (ed << 1);
        long f0Second = utf8F0Carry | (f0 << 1);
        long f4Second = utf8F4Carry | (f4 << 1);
        long belowA0 = continuation & input.compare(
            VectorOperators.LE, (byte) 0x9f).toLong();
        long below90 = continuation & input.compare(
            VectorOperators.LE, (byte) 0x8f).toLong();
        if ((e0Second & belowA0) != 0
                || (edSecond & (continuation ^ belowA0)) != 0
                || (f0Second & below90) != 0
                || (f4Second & (continuation ^ below90)) != 0) {
            utf8Error = true;
        }

        if (validLanes < SPECIES_LEN) {
            if ((expected & ~validBits) != 0) {
                utf8Error = true;
            }
            utf8ContinuationCarry = 0;
            utf8E0Carry = 0;
            utf8EdCarry = 0;
            utf8F0Carry = 0;
            utf8F4Carry = 0;
            return;
        }

        int last = SPECIES_LEN - 1;
        utf8ContinuationCarry = (allLeads >>> last)
            | (longLeads >>> (last - 1))
            | (lead4 >>> (last - 2));
        utf8E0Carry = e0 >>> last;
        utf8EdCarry = ed >>> last;
        utf8F0Carry = f0 >>> last;
        utf8F4Carry = f4 >>> last;
    }
}
