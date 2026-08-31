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

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Stage 2 DOM tape parser. Stage 1 supplies structural offsets; this stage
 * validates the JSON grammar and scalar tokens while constructing the tape.
 */
public final class Stage2Parser {

    private static final int MAX_DEPTH = 2048;
    private static final VarHandle BYTE_ARRAY_INT_LE =
        MethodHandles.byteArrayViewVarHandle(
            int[].class, ByteOrder.LITTLE_ENDIAN);

    private static final byte DOCUMENT_END = 0;
    private static final byte OBJECT_FIRST_KEY_OR_END = 1;
    private static final byte OBJECT_KEY_AFTER_COMMA = 2;
    private static final byte OBJECT_COLON = 3;
    private static final byte OBJECT_VALUE = 4;
    private static final byte OBJECT_COMPLETE = 5;
    private static final byte ARRAY_FIRST_VALUE_OR_END = 6;
    private static final byte ARRAY_VALUE_AFTER_COMMA = 7;
    private static final byte ARRAY_COMPLETE = 8;
    private static final byte ROOT_VALUE = 9;

    private final int[] stackTapeIndexes = new int[MAX_DEPTH];
    private final byte[] stackStates = new byte[MAX_DEPTH];

    public Stage2Parser() {}

    public SimdJsonError parse(
            MemorySegment jsonSegment, StructuralIndexes indexes,
            Tape tape) {
        tape.reset();
        int depth = 0;
        byte state = 0;

        int jsonLength = Math.toIntExact(jsonSegment.byteSize());
        int numIndexes = indexes.size();
        tape.reserve(numIndexes + 2);

        byte[] heapBytes = jsonSegment.heapBase()
            .map(o -> (byte[]) o).orElse(null);
        long sourceAddress = jsonSegment.address();
        if (numIndexes == 0) {
            return SimdJsonError.EMPTY;
        }
        if (heapBytes != null && !indexes.isNative()
                && !tape.isNative()) {
            return parseHeap(
                heapBytes, Math.toIntExact(sourceAddress), jsonLength,
                indexes.indexes(), numIndexes,
                indexes.backslashIndexes(), indexes.backslashCount(),
                tape);
        }

        tape.addUnchecked(Tape.TAG_ROOT, 1L);

        int prevOffset = -1;
        boolean rootSeen = false;
        boolean rootScalar = false;
        int backslashCursor = 0;
        int backslashCount = indexes.backslashCount();
        int nextBackslash = backslashCount == 0
            ? Integer.MAX_VALUE
            : indexes.backslashAtUnchecked(0);

        for (int i = 0; i < numIndexes; i++) {
            int structOffset = indexes.getUnchecked(i);
            byte structChar = getByte(
                heapBytes, sourceAddress, structOffset);

            switch (structChar) {
                case '{' -> {
                    if (depth == 0) {
                        if (rootSeen) {
                            return SimdJsonError.TAPE_ERROR;
                        }
                        rootSeen = true;
                    } else {
                        byte parentState = acceptContainerValue(state);
                        if (parentState == 0) {
                            return SimdJsonError.TAPE_ERROR;
                        }
                        stackStates[depth - 1] = parentState;
                    }

                    int tapeIndex = tape.size();
                    tape.addUnchecked(Tape.TAG_START_OBJECT, 0L);
                    if (depth >= MAX_DEPTH) {
                        throw new SimdJsonException(
                            SimdJsonError.DEPTH_ERROR);
                    }
                    stackTapeIndexes[depth++] = tapeIndex;
                    state = OBJECT_FIRST_KEY_OR_END;
                }
                case '[' -> {
                    if (depth == 0) {
                        if (rootSeen) {
                            return SimdJsonError.TAPE_ERROR;
                        }
                        rootSeen = true;
                    } else {
                        byte parentState = acceptContainerValue(state);
                        if (parentState == 0) {
                            return SimdJsonError.TAPE_ERROR;
                        }
                        stackStates[depth - 1] = parentState;
                    }

                    int tapeIndex = tape.size();
                    tape.addUnchecked(Tape.TAG_START_ARRAY, 0L);
                    if (depth >= MAX_DEPTH) {
                        throw new SimdJsonException(
                            SimdJsonError.DEPTH_ERROR);
                    }
                    stackTapeIndexes[depth++] = tapeIndex;
                    state = ARRAY_FIRST_VALUE_OR_END;
                }
                case '}' -> {
                    if (depth == 0 || isArray(state)) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    if (state != OBJECT_FIRST_KEY_OR_END
                            && state != OBJECT_COMPLETE) {
                        return SimdJsonError.TAPE_ERROR;
                    }

                    int startTapeIndex = stackTapeIndexes[--depth];
                    int endTapeIndex = tape.size();
                    tape.addUnchecked(
                        Tape.TAG_END_OBJECT, startTapeIndex);
                    tape.setUnchecked(
                        startTapeIndex, Tape.TAG_START_OBJECT,
                        endTapeIndex);
                    if (depth != 0) {
                        state = stackStates[depth - 1];
                    }
                }
                case ']' -> {
                    if (depth == 0 || !isArray(state)) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    if (state != ARRAY_FIRST_VALUE_OR_END
                            && state != ARRAY_COMPLETE) {
                        return SimdJsonError.TAPE_ERROR;
                    }

                    int startTapeIndex = stackTapeIndexes[--depth];
                    int endTapeIndex = tape.size();
                    tape.addUnchecked(
                        Tape.TAG_END_ARRAY, startTapeIndex);
                    tape.setUnchecked(
                        startTapeIndex, Tape.TAG_START_ARRAY,
                        endTapeIndex);
                    if (depth != 0) {
                        state = stackStates[depth - 1];
                    }
                }
                case ':' -> {
                    if (depth == 0 || isArray(state)) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    if (state != OBJECT_COLON) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    state = OBJECT_VALUE;
                }
                case ',' -> {
                    if (depth == 0) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    if (isArray(state)) {
                        if (state != ARRAY_COMPLETE) {
                            return SimdJsonError.TAPE_ERROR;
                        }
                        state = ARRAY_VALUE_AFTER_COMMA;
                    } else {
                        if (state != OBJECT_COMPLETE) {
                            return SimdJsonError.TAPE_ERROR;
                        }
                        state = OBJECT_KEY_AFTER_COMMA;
                    }
                }
                default -> {
                    int nextOffset = i + 1 < numIndexes
                        ? indexes.getUnchecked(i + 1) : jsonLength;
                    while (nextBackslash < structOffset) {
                        nextBackslash = ++backslashCursor < backslashCount
                            ? indexes.backslashAtUnchecked(backslashCursor)
                            : Integer.MAX_VALUE;
                    }
                    boolean tokenMayContainBackslash =
                        nextBackslash < nextOffset;
                    if (depth == 0) {
                        if (rootSeen) {
                            return SimdJsonError.TAPE_ERROR;
                        }
                        SimdJsonError error = parseScalar(
                            heapBytes, sourceAddress,
                            structOffset, nextOffset, tape,
                            tokenMayContainBackslash);
                        if (error != SimdJsonError.SUCCESS) {
                            return error;
                        }
                        rootSeen = true;
                        rootScalar = true;
                    } else if (isArray(state)) {
                        if (state != ARRAY_FIRST_VALUE_OR_END
                                && state != ARRAY_VALUE_AFTER_COMMA) {
                            return SimdJsonError.TAPE_ERROR;
                        }
                        SimdJsonError error = parseScalar(
                            heapBytes, sourceAddress,
                            structOffset, nextOffset, tape,
                            tokenMayContainBackslash);
                        if (error != SimdJsonError.SUCCESS) {
                            return error;
                        }
                        state = ARRAY_COMPLETE;
                    } else if (state == OBJECT_FIRST_KEY_OR_END
                            || state == OBJECT_KEY_AFTER_COMMA) {
                        SimdJsonError error = parseKey(
                            heapBytes, sourceAddress,
                            structOffset, nextOffset, tape,
                            tokenMayContainBackslash);
                        if (error != SimdJsonError.SUCCESS) {
                            return error;
                        }
                        state = OBJECT_COLON;
                    } else if (state == OBJECT_VALUE) {
                        SimdJsonError error = parseScalar(
                            heapBytes, sourceAddress,
                            structOffset, nextOffset, tape,
                            tokenMayContainBackslash);
                        if (error != SimdJsonError.SUCCESS) {
                            return error;
                        }
                        state = OBJECT_COMPLETE;
                    } else {
                        return SimdJsonError.TAPE_ERROR;
                    }
                }
            }

            prevOffset = structOffset;
        }

        if (depth != 0 || !rootSeen) {
            return SimdJsonError.TAPE_ERROR;
        }
        if (!rootScalar && !isWhitespaceOnly(
                    heapBytes, sourceAddress,
                    prevOffset + 1, jsonLength)) {
            return SimdJsonError.TAPE_ERROR;
        }
        return SimdJsonError.SUCCESS;
    }

    /**
     * Heap-specialized Stage 2. The parser state identifies the expected token
     * and selects its punctuation path at each structural index.
     */
    private SimdJsonError parseHeap(
            byte[] input, int inputBase, int jsonLength,
            int[] structuralIndexes, int numIndexes,
            int[] backslashIndexes, int backslashCount,
            Tape tape) {
        long[] tapeWords = tape.heapArray();
        int tapeSize = 1;
        tapeWords[0] = tapeWord(Tape.TAG_ROOT, 1L);

        int cursor = 0;
        int depth = 0;
        byte state = ROOT_VALUE;
        int lastOffset = -1;
        boolean rootScalar = false;
        int backslashCursor = 0;
        int nextBackslash = backslashCount == 0
            ? Integer.MAX_VALUE : backslashIndexes[0];

        while (state != DOCUMENT_END) {
            if (state == OBJECT_FIRST_KEY_OR_END
                    || state == OBJECT_KEY_AFTER_COMMA) {
                if (cursor >= numIndexes) {
                    return SimdJsonError.TAPE_ERROR;
                }
                int offset = structuralIndexes[cursor++];
                lastOffset = offset;
                byte value = input[inputBase + offset];
                if (value == '}') {
                    if (state != OBJECT_FIRST_KEY_OR_END || depth == 0) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    int stackIndex = --depth;
                    int startTapeIndex = stackTapeIndexes[stackIndex];
                    int endTapeIndex = tapeSize;
                    tapeWords[tapeSize++] = tapeWord(
                        Tape.TAG_END_OBJECT, startTapeIndex);
                    tapeWords[startTapeIndex] = tapeWord(
                        Tape.TAG_START_OBJECT, endTapeIndex);
                    state = stackStates[stackIndex];
                } else {
                    if (value != '"') {
                        return isStructural(value)
                            ? SimdJsonError.TAPE_ERROR
                            : SimdJsonError.STRING_ERROR;
                    }
                    int nextOffset = cursor < numIndexes
                        ? structuralIndexes[cursor] : jsonLength;
                    while (nextBackslash < offset) {
                        nextBackslash = ++backslashCursor < backslashCount
                            ? backslashIndexes[backslashCursor]
                            : Integer.MAX_VALUE;
                    }
                    SimdJsonError error = validateHeapString(
                        input, inputBase, offset,
                        trimHeapWhitespace(
                            input, inputBase, offset, nextOffset),
                        nextBackslash < nextOffset);
                    if (error != SimdJsonError.SUCCESS) {
                        return error;
                    }
                    tapeWords[tapeSize++] = tapeWord(
                        Tape.TAG_STRING, offset);
                    state = OBJECT_COLON;
                }
            }

            if (state == OBJECT_COLON) {
                if (cursor >= numIndexes) {
                    return SimdJsonError.TAPE_ERROR;
                }
                int offset = structuralIndexes[cursor++];
                lastOffset = offset;
                if (input[inputBase + offset] != ':') {
                    return SimdJsonError.TAPE_ERROR;
                }
                state = OBJECT_VALUE;
            }

            if (state == ROOT_VALUE || state == OBJECT_VALUE
                    || state == ARRAY_FIRST_VALUE_OR_END
                    || state == ARRAY_VALUE_AFTER_COMMA) {
                if (cursor >= numIndexes) {
                    return SimdJsonError.TAPE_ERROR;
                }
                byte valueState = state;
                byte continuation = valueState == ROOT_VALUE
                    ? DOCUMENT_END
                    : valueState == OBJECT_VALUE
                        ? OBJECT_COMPLETE : ARRAY_COMPLETE;
                int offset = structuralIndexes[cursor++];
                lastOffset = offset;
                byte value = input[inputBase + offset];

                if (value == ']'
                        && valueState == ARRAY_FIRST_VALUE_OR_END) {
                    if (depth == 0) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    int stackIndex = --depth;
                    int startTapeIndex = stackTapeIndexes[stackIndex];
                    int endTapeIndex = tapeSize;
                    tapeWords[tapeSize++] = tapeWord(
                        Tape.TAG_END_ARRAY, startTapeIndex);
                    tapeWords[startTapeIndex] = tapeWord(
                        Tape.TAG_START_ARRAY, endTapeIndex);
                    state = stackStates[stackIndex];
                } else if (value == '{' || value == '[') {
                    if (depth >= MAX_DEPTH) {
                        throw new SimdJsonException(
                            SimdJsonError.DEPTH_ERROR);
                    }
                    byte closing = value == '{' ? (byte) '}' : (byte) ']';
                    if (cursor < numIndexes
                            && input[inputBase
                                + structuralIndexes[cursor]] == closing) {
                        lastOffset = structuralIndexes[cursor++];
                        int startTapeIndex = tapeSize;
                        int endTapeIndex = startTapeIndex + 1;
                        tapeWords[tapeSize++] = tapeWord(
                            value == '{' ? Tape.TAG_START_OBJECT
                                : Tape.TAG_START_ARRAY,
                            endTapeIndex);
                        tapeWords[tapeSize++] = tapeWord(
                            value == '{' ? Tape.TAG_END_OBJECT
                                : Tape.TAG_END_ARRAY,
                            startTapeIndex);
                        state = continuation;
                    } else {
                        int startTapeIndex = tapeSize;
                        tapeWords[tapeSize++] = tapeWord(
                            value == '{' ? Tape.TAG_START_OBJECT
                                : Tape.TAG_START_ARRAY,
                            0L);
                        stackTapeIndexes[depth] = startTapeIndex;
                        stackStates[depth] = continuation;
                        depth++;
                        state = value == '{'
                            ? OBJECT_FIRST_KEY_OR_END
                            : ARRAY_FIRST_VALUE_OR_END;
                    }
                } else {
                    if (isStructural(value)) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    int nextOffset = cursor < numIndexes
                        ? structuralIndexes[cursor] : jsonLength;
                    boolean tokenMayContainBackslash = false;
                    if (value == '"') {
                        while (nextBackslash < offset) {
                            nextBackslash =
                                ++backslashCursor < backslashCount
                                    ? backslashIndexes[backslashCursor]
                                    : Integer.MAX_VALUE;
                        }
                        tokenMayContainBackslash =
                            nextBackslash < nextOffset;
                    }
                    SimdJsonError error = parseHeapScalar(
                        input, inputBase, offset, nextOffset,
                        tapeWords, tapeSize,
                        tokenMayContainBackslash);
                    if (error != SimdJsonError.SUCCESS) {
                        return error;
                    }
                    tapeSize++;
                    rootScalar |= valueState == ROOT_VALUE;
                    state = continuation;
                }
            }

            if (state == OBJECT_COMPLETE) {
                if (cursor >= numIndexes) {
                    return SimdJsonError.TAPE_ERROR;
                }
                int offset = structuralIndexes[cursor++];
                lastOffset = offset;
                byte value = input[inputBase + offset];
                if (value == ',') {
                    state = OBJECT_KEY_AFTER_COMMA;
                } else if (value == '}') {
                    if (depth == 0) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    int stackIndex = --depth;
                    int startTapeIndex = stackTapeIndexes[stackIndex];
                    int endTapeIndex = tapeSize;
                    tapeWords[tapeSize++] = tapeWord(
                        Tape.TAG_END_OBJECT, startTapeIndex);
                    tapeWords[startTapeIndex] = tapeWord(
                        Tape.TAG_START_OBJECT, endTapeIndex);
                    state = stackStates[stackIndex];
                } else {
                    return SimdJsonError.TAPE_ERROR;
                }
            }

            if (state == ARRAY_COMPLETE) {
                if (cursor >= numIndexes) {
                    return SimdJsonError.TAPE_ERROR;
                }
                int offset = structuralIndexes[cursor++];
                lastOffset = offset;
                byte value = input[inputBase + offset];
                if (value == ',') {
                    state = ARRAY_VALUE_AFTER_COMMA;
                } else if (value == ']') {
                    if (depth == 0) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    int stackIndex = --depth;
                    int startTapeIndex = stackTapeIndexes[stackIndex];
                    int endTapeIndex = tapeSize;
                    tapeWords[tapeSize++] = tapeWord(
                        Tape.TAG_END_ARRAY, startTapeIndex);
                    tapeWords[startTapeIndex] = tapeWord(
                        Tape.TAG_START_ARRAY, endTapeIndex);
                    state = stackStates[stackIndex];
                } else {
                    return SimdJsonError.TAPE_ERROR;
                }
            }
        }

        if (depth != 0 || cursor != numIndexes) {
            return SimdJsonError.TAPE_ERROR;
        }
        if (!rootScalar && !isHeapWhitespaceOnly(
                input, inputBase, lastOffset + 1, jsonLength)) {
            return SimdJsonError.TAPE_ERROR;
        }
        tape.commitHeapSize(tapeSize);
        return SimdJsonError.SUCCESS;
    }

    private static SimdJsonError parseHeapScalar(
            byte[] input, int inputBase, int start, int end,
            long[] tapeWords, int tapeIndex,
            boolean mayContainBackslash) {
        int tokenEnd = trimHeapWhitespace(
            input, inputBase, start, end);
        if (start >= tokenEnd) {
            return SimdJsonError.TAPE_ERROR;
        }

        byte first = input[inputBase + start];
        if (first == '"') {
            SimdJsonError error = validateHeapString(
                input, inputBase, start, tokenEnd,
                mayContainBackslash);
            if (error == SimdJsonError.SUCCESS) {
                tapeWords[tapeIndex] = tapeWord(
                    Tape.TAG_STRING, start);
            }
            return error;
        }
        if (first == 't') {
            if (!heapMatches4(
                    input, inputBase, start, tokenEnd,
                    0x65757274)) {
                return SimdJsonError.T_OR_F_ERROR;
            }
            tapeWords[tapeIndex] = tapeWord(Tape.TAG_TRUE, 0L);
            return SimdJsonError.SUCCESS;
        }
        if (first == 'f') {
            if (!heapMatches5(
                    input, inputBase, start, tokenEnd,
                    0x736c6166, 'e')) {
                return SimdJsonError.T_OR_F_ERROR;
            }
            tapeWords[tapeIndex] = tapeWord(Tape.TAG_FALSE, 0L);
            return SimdJsonError.SUCCESS;
        }
        if (first == 'n') {
            if (!heapMatches4(
                    input, inputBase, start, tokenEnd,
                    0x6c6c756e)) {
                return SimdJsonError.N_OR_V_ERROR;
            }
            tapeWords[tapeIndex] = tapeWord(Tape.TAG_NULL, 0L);
            return SimdJsonError.SUCCESS;
        }
        if (first == '-' || (first >= '0' && first <= '9')) {
            if (!isValidHeapNumber(
                    input, inputBase, start, tokenEnd)) {
                return SimdJsonError.NUMBER_ERROR;
            }
            tapeWords[tapeIndex] = tapeWord(
                Tape.TAG_INT64, start);
            return SimdJsonError.SUCCESS;
        }
        return SimdJsonError.TAPE_ERROR;
    }

    private static SimdJsonError validateHeapString(
            byte[] input, int inputBase,
            int start, int end, boolean mayContainBackslash) {
        if (end - start < 2
                || input[inputBase + start] != '"'
                || input[inputBase + end - 1] != '"') {
            return SimdJsonError.STRING_ERROR;
        }
        if (!mayContainBackslash) {
            return SimdJsonError.SUCCESS;
        }

        int index = start + 1;
        int limit = end - 1;
        while (index < limit) {
            if (input[inputBase + index] == '\\') {
                if (++index >= limit) {
                    return SimdJsonError.STRING_ERROR;
                }
                int escape = input[inputBase + index] & 0xff;
                if (escape == 'u') {
                    if (index + 4 >= limit) {
                        return SimdJsonError.STRING_ERROR;
                    }
                    for (int i = 1; i <= 4; i++) {
                        if (!isHex(input[inputBase + index + i])) {
                            return SimdJsonError.STRING_ERROR;
                        }
                    }
                    index += 5;
                    continue;
                }
                if (escape != '"' && escape != '\\' && escape != '/'
                        && escape != 'b' && escape != 'f'
                        && escape != 'n' && escape != 'r'
                        && escape != 't') {
                    return SimdJsonError.STRING_ERROR;
                }
                index++;
                continue;
            }
            index++;
        }
        return SimdJsonError.SUCCESS;
    }

    private static boolean isValidHeapNumber(
            byte[] input, int inputBase, int start, int end) {
        int index = start;
        if (input[inputBase + index] == '-') {
            if (++index == end) {
                return false;
            }
        }

        byte firstDigit = input[inputBase + index];
        if (firstDigit == '0') {
            index++;
            if (index < end) {
                byte next = input[inputBase + index];
                if (next >= '0' && next <= '9') {
                    return false;
                }
            }
        } else if (firstDigit >= '1' && firstDigit <= '9') {
            do {
                index++;
            } while (index < end
                && isDigit(input[inputBase + index]));
        } else {
            return false;
        }

        if (index < end && input[inputBase + index] == '.') {
            index++;
            int fractionStart = index;
            while (index < end
                    && isDigit(input[inputBase + index])) {
                index++;
            }
            if (index == fractionStart) {
                return false;
            }
        }

        if (index < end) {
            byte marker = input[inputBase + index];
            if (marker == 'e' || marker == 'E') {
                index++;
                if (index < end) {
                    byte sign = input[inputBase + index];
                    if (sign == '+' || sign == '-') {
                        index++;
                    }
                }
                int exponentStart = index;
                while (index < end
                        && isDigit(input[inputBase + index])) {
                    index++;
                }
                if (index == exponentStart) {
                    return false;
                }
            }
        }
        return index == end;
    }

    private static boolean heapMatches4(
            byte[] input, int inputBase, int start, int end,
            int expected) {
        int offset = inputBase + start;
        return end - start == 4
            && (int) BYTE_ARRAY_INT_LE.get(input, offset) == expected;
    }

    private static boolean heapMatches5(
            byte[] input, int inputBase, int start, int end,
            int expectedFirstFour, int fifth) {
        int offset = inputBase + start;
        return end - start == 5
            && (int) BYTE_ARRAY_INT_LE.get(input, offset)
                == expectedFirstFour
            && input[offset + 4] == (byte) fifth;
    }

    private static int trimHeapWhitespace(
            byte[] input, int inputBase, int start, int end) {
        int index = end;
        while (index > start
                && isWhitespace(input[inputBase + index - 1])) {
            index--;
        }
        return index;
    }

    private static boolean isHeapWhitespaceOnly(
            byte[] input, int inputBase, int start, int end) {
        for (int index = start; index < end; index++) {
            if (!isWhitespace(input[inputBase + index])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isStructural(byte value) {
        return value == '{' || value == '}'
            || value == '[' || value == ']'
            || value == ':' || value == ',';
    }

    private static long tapeWord(char tag, long payload) {
        return ((long) tag << 56)
            | (payload & 0x00FFFFFFFFFFFFFFL);
    }

    private static byte acceptContainerValue(byte state) {
        if (isArray(state)) {
            if (state != ARRAY_FIRST_VALUE_OR_END
                    && state != ARRAY_VALUE_AFTER_COMMA) {
                return 0;
            }
            return ARRAY_COMPLETE;
        }
        if (state != OBJECT_VALUE) {
            return 0;
        }
        return OBJECT_COMPLETE;
    }

    static SimdJsonError parseKey(
            byte[] heapBytes, long sourceAddress,
            int start, int end, Tape tape,
            boolean mayContainBackslash) {
        int tokenStart = skipWhitespace(
            heapBytes, sourceAddress, start, end);
        int tokenEnd = trimWhitespace(
            heapBytes, sourceAddress, tokenStart, end);
        SimdJsonError error = validateString(
            heapBytes, sourceAddress, tokenStart, tokenEnd,
            mayContainBackslash);
        if (error == SimdJsonError.SUCCESS) {
            tape.addUnchecked(Tape.TAG_STRING, tokenStart);
        }
        return error;
    }

    static SimdJsonError validateKey(
            byte[] heapBytes, long sourceAddress,
            int start, int end, boolean mayContainBackslash) {
        int tokenStart = skipWhitespace(
            heapBytes, sourceAddress, start, end);
        int tokenEnd = trimWhitespace(
            heapBytes, sourceAddress, tokenStart, end);
        return validateKeyToken(
            heapBytes, sourceAddress, tokenStart, tokenEnd,
            mayContainBackslash);
    }

    static SimdJsonError validateKeyToken(
            byte[] heapBytes, long sourceAddress,
            int start, int end, boolean mayContainBackslash) {
        return validateString(
            heapBytes, sourceAddress, start, end,
            mayContainBackslash);
    }

    static SimdJsonError parseScalar(
            byte[] heapBytes, long sourceAddress,
            int start, int end, Tape tape,
            boolean mayContainBackslash) {
        int tokenStart = skipWhitespace(
            heapBytes, sourceAddress, start, end);
        int tokenEnd = trimWhitespace(
            heapBytes, sourceAddress, tokenStart, end);
        if (tokenStart >= tokenEnd) {
            return SimdJsonError.TAPE_ERROR;
        }

        byte first = getByte(heapBytes, sourceAddress, tokenStart);
        if (first == '"') {
            SimdJsonError error = validateString(
                heapBytes, sourceAddress, tokenStart, tokenEnd,
                mayContainBackslash);
            if (error == SimdJsonError.SUCCESS) {
                tape.addUnchecked(Tape.TAG_STRING, tokenStart);
            }
            return error;
        }
        if (first == 't') {
            if (!matches4(
                    heapBytes, sourceAddress, tokenStart, tokenEnd,
                    't', 'r', 'u', 'e')) {
                return SimdJsonError.T_OR_F_ERROR;
            }
            tape.addUnchecked(Tape.TAG_TRUE, 0L);
            return SimdJsonError.SUCCESS;
        }
        if (first == 'f') {
            if (!matches5(
                    heapBytes, sourceAddress, tokenStart, tokenEnd,
                    'f', 'a', 'l', 's', 'e')) {
                return SimdJsonError.T_OR_F_ERROR;
            }
            tape.addUnchecked(Tape.TAG_FALSE, 0L);
            return SimdJsonError.SUCCESS;
        }
        if (first == 'n') {
            if (!matches4(
                    heapBytes, sourceAddress, tokenStart, tokenEnd,
                    'n', 'u', 'l', 'l')) {
                return SimdJsonError.N_OR_V_ERROR;
            }
            tape.addUnchecked(Tape.TAG_NULL, 0L);
            return SimdJsonError.SUCCESS;
        }
        if (first == '-' || (first >= '0' && first <= '9')) {
            if (!isValidNumber(
                    heapBytes, sourceAddress, tokenStart, tokenEnd)) {
                return SimdJsonError.NUMBER_ERROR;
            }
            tape.addUnchecked(Tape.TAG_INT64, tokenStart);
            return SimdJsonError.SUCCESS;
        }
        return SimdJsonError.TAPE_ERROR;
    }

    static SimdJsonError validateScalar(
            byte[] heapBytes, long sourceAddress,
            int start, int end, boolean mayContainBackslash) {
        int tokenStart = skipWhitespace(
            heapBytes, sourceAddress, start, end);
        int tokenEnd = trimWhitespace(
            heapBytes, sourceAddress, tokenStart, end);
        return validateScalarToken(
            heapBytes, sourceAddress, tokenStart, tokenEnd,
            mayContainBackslash);
    }

    static SimdJsonError validateScalarToken(
            byte[] heapBytes, long sourceAddress,
            int tokenStart, int tokenEnd,
            boolean mayContainBackslash) {
        if (tokenStart >= tokenEnd) {
            return SimdJsonError.TAPE_ERROR;
        }

        byte first = getByte(heapBytes, sourceAddress, tokenStart);
        if (first == '"') {
            return validateString(
                heapBytes, sourceAddress, tokenStart, tokenEnd,
                mayContainBackslash);
        }
        if (first == 't') {
            return matches4(
                heapBytes, sourceAddress, tokenStart, tokenEnd,
                't', 'r', 'u', 'e')
                ? SimdJsonError.SUCCESS : SimdJsonError.T_OR_F_ERROR;
        }
        if (first == 'f') {
            return matches5(
                heapBytes, sourceAddress, tokenStart, tokenEnd,
                'f', 'a', 'l', 's', 'e')
                ? SimdJsonError.SUCCESS : SimdJsonError.T_OR_F_ERROR;
        }
        if (first == 'n') {
            return matches4(
                heapBytes, sourceAddress, tokenStart, tokenEnd,
                'n', 'u', 'l', 'l')
                ? SimdJsonError.SUCCESS : SimdJsonError.N_OR_V_ERROR;
        }
        if (first == '-' || (first >= '0' && first <= '9')) {
            return isValidNumber(
                heapBytes, sourceAddress, tokenStart, tokenEnd)
                ? SimdJsonError.SUCCESS : SimdJsonError.NUMBER_ERROR;
        }
        return SimdJsonError.TAPE_ERROR;
    }

    private static SimdJsonError parseRootScalar(
            byte[] heapBytes, long sourceAddress,
            int start, int end, Tape tape,
            boolean mayContainBackslash) {
        int tokenStart = skipWhitespace(
            heapBytes, sourceAddress, start, end);
        if (tokenStart >= end) {
            return SimdJsonError.EMPTY;
        }
        return parseScalar(
            heapBytes, sourceAddress, tokenStart, end, tape,
            mayContainBackslash);
    }

    private static SimdJsonError validateString(
            byte[] heapBytes, long sourceAddress,
            int start, int end, boolean mayContainBackslash) {
        if (end - start < 2
                || getByte(heapBytes, sourceAddress, start) != '"'
                || getByte(heapBytes, sourceAddress, end - 1) != '"') {
            return SimdJsonError.STRING_ERROR;
        }
        // Stage 1 already proves quote balance, UTF-8 validity and the
        // absence of unescaped control bytes. Only escape syntax remains for
        // strings that actually contain a backslash.
        if (!mayContainBackslash) {
            return SimdJsonError.SUCCESS;
        }

        int index = start + 1;
        int limit = end - 1;
        while (index < limit) {
            int value = getByte(heapBytes, sourceAddress, index) & 0xff;
            if (value == '\\') {
                if (++index >= limit) {
                    return SimdJsonError.STRING_ERROR;
                }
                int escape = getByte(
                    heapBytes, sourceAddress, index) & 0xff;
                if (escape == 'u') {
                    if (index + 4 >= limit) {
                        return SimdJsonError.STRING_ERROR;
                    }
                    for (int i = 1; i <= 4; i++) {
                        if (!isHex(getByte(
                                heapBytes, sourceAddress, index + i))) {
                            return SimdJsonError.STRING_ERROR;
                        }
                    }
                    index += 5;
                    continue;
                }
                if (escape != '"' && escape != '\\' && escape != '/'
                        && escape != 'b' && escape != 'f'
                        && escape != 'n' && escape != 'r'
                        && escape != 't') {
                    return SimdJsonError.STRING_ERROR;
                }
                index++;
                continue;
            }
            // Stage 1 has already validated UTF-8 for the entire document.
            index++;
        }

        return SimdJsonError.SUCCESS;
    }

    private static boolean isValidNumber(
            byte[] heapBytes, long sourceAddress,
            int start, int end) {
        int index = start;
        if (getByte(heapBytes, sourceAddress, index) == '-') {
            if (++index == end) {
                return false;
            }
        }

        byte firstDigit = getByte(heapBytes, sourceAddress, index);
        if (firstDigit == '0') {
            index++;
            if (index < end) {
                byte next = getByte(heapBytes, sourceAddress, index);
                if (next >= '0' && next <= '9') {
                    return false;
                }
            }
        } else if (firstDigit >= '1' && firstDigit <= '9') {
            do {
                index++;
            } while (index < end
                && isDigit(getByte(heapBytes, sourceAddress, index)));
        } else {
            return false;
        }

        if (index < end
                && getByte(heapBytes, sourceAddress, index) == '.') {
            index++;
            int fractionStart = index;
            while (index < end
                    && isDigit(getByte(
                        heapBytes, sourceAddress, index))) {
                index++;
            }
            if (index == fractionStart) {
                return false;
            }
        }

        if (index < end) {
            byte marker = getByte(heapBytes, sourceAddress, index);
            if (marker == 'e' || marker == 'E') {
                index++;
                if (index < end) {
                    byte sign = getByte(
                        heapBytes, sourceAddress, index);
                    if (sign == '+' || sign == '-') {
                        index++;
                    }
                }
                int exponentStart = index;
                while (index < end
                        && isDigit(getByte(
                            heapBytes, sourceAddress, index))) {
                    index++;
                }
                if (index == exponentStart) {
                    return false;
                }
            }
        }
        return index == end;
    }

    private static boolean matches4(
            byte[] heapBytes, long sourceAddress,
            int start, int end,
            int first, int second, int third, int fourth) {
        return end - start == 4
            && getByte(heapBytes, sourceAddress, start) == (byte) first
            && getByte(heapBytes, sourceAddress, start + 1) == (byte) second
            && getByte(heapBytes, sourceAddress, start + 2) == (byte) third
            && getByte(heapBytes, sourceAddress, start + 3) == (byte) fourth;
    }

    private static boolean matches5(
            byte[] heapBytes, long sourceAddress,
            int start, int end,
            int first, int second, int third, int fourth, int fifth) {
        return end - start == 5
            && getByte(heapBytes, sourceAddress, start) == (byte) first
            && getByte(heapBytes, sourceAddress, start + 1) == (byte) second
            && getByte(heapBytes, sourceAddress, start + 2) == (byte) third
            && getByte(heapBytes, sourceAddress, start + 3) == (byte) fourth
            && getByte(heapBytes, sourceAddress, start + 4) == (byte) fifth;
    }

    static int skipWhitespace(
            byte[] heapBytes, long sourceAddress,
            int start, int end) {
        int index = start;
        while (index < end && isWhitespace(
                getByte(heapBytes, sourceAddress, index))) {
            index++;
        }
        return index;
    }

    static int trimWhitespace(
            byte[] heapBytes, long sourceAddress,
            int start, int end) {
        int index = end;
        while (index > start && isWhitespace(
                getByte(heapBytes, sourceAddress, index - 1))) {
            index--;
        }
        return index;
    }

    static boolean isWhitespaceOnly(
            byte[] heapBytes, long sourceAddress,
            int start, int end) {
        for (int index = start; index < end; index++) {
            if (!isWhitespace(getByte(
                    heapBytes, sourceAddress, index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWhitespace(byte value) {
        return value == ' ' || value == '\t'
            || value == '\n' || value == '\r';
    }

    private static boolean isDigit(byte value) {
        return value >= '0' && value <= '9';
    }

    private static boolean isHex(byte value) {
        return (value >= '0' && value <= '9')
            || (value >= 'a' && value <= 'f')
            || (value >= 'A' && value <= 'F');
    }

    static byte getByte(
            byte[] heapBytes, long sourceAddress, int position) {
        return heapBytes != null
            ? heapBytes[Math.toIntExact(sourceAddress) + position]
            : (byte) RawSegment.BYTE.get(
                RawSegment.ADDRESS_SPACE, sourceAddress + position);
    }

    private static boolean isArray(byte state) {
        return state >= ARRAY_FIRST_VALUE_OR_END;
    }
}
