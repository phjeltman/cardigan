/*
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

import java.lang.foreign.MemorySegment;

/**
 * Full Stage 2 grammar validation without DOM tape construction.
 *
 * <p>This is used by direct/on-demand consumers that need conforming input
 * validation but should not pay to construct a representation they will
 * immediately discard.</p>
 */
public final class Stage2Validator {

    private static final int MAX_DEPTH = 2048;

    private static final byte OBJECT_FIRST_KEY_OR_END = 1;
    private static final byte OBJECT_KEY_AFTER_COMMA = 2;
    private static final byte OBJECT_VALUE = 3;
    private static final byte OBJECT_COMPLETE = 4;
    private static final byte ARRAY_FIRST_VALUE_OR_END = 5;
    private static final byte ARRAY_VALUE_AFTER_COMMA = 6;
    private static final byte ARRAY_COMPLETE = 7;

    private static final byte FULL_OBJECT_FIRST_KEY_OR_END = 11;
    private static final byte FULL_OBJECT_KEY_AFTER_COMMA = 12;
    private static final byte FULL_OBJECT_COLON = 13;
    private static final byte FULL_OBJECT_VALUE = 14;
    private static final byte FULL_OBJECT_COMPLETE = 15;
    private static final byte FULL_ARRAY_FIRST_VALUE_OR_END = 16;
    private static final byte FULL_ARRAY_VALUE_AFTER_COMMA = 17;
    private static final byte FULL_ARRAY_COMPLETE = 18;

    private final byte[] stackStates = new byte[MAX_DEPTH];

    public SimdJsonError validate(
            MemorySegment jsonSegment, StructuralIndexes indexes) {
        return indexes.operatorsOnly()
            ? validateOperators(jsonSegment, indexes)
            : validateScalarStarts(jsonSegment, indexes);
    }

    private SimdJsonError validateScalarStarts(
            MemorySegment jsonSegment, StructuralIndexes indexes) {
        int depth = 0;
        byte state = 0;
        int jsonLength = Math.toIntExact(jsonSegment.byteSize());
        int numIndexes = indexes.size();
        byte[] heapBytes = jsonSegment.heapBase()
            .map(o -> (byte[]) o).orElse(null);
        long sourceAddress = jsonSegment.address();
        if (numIndexes == 0) {
            return SimdJsonError.EMPTY;
        }

        int previousOffset = -1;
        boolean rootSeen = false;
        boolean rootScalar = false;
        int backslashCursor = 0;
        int backslashCount = indexes.backslashCount();
        int nextBackslash = backslashCount == 0
            ? Integer.MAX_VALUE
            : indexes.backslashAtUnchecked(0);

        for (int i = 0; i < numIndexes; i++) {
            int structuralOffset = indexes.getUnchecked(i);
            byte structural = Stage2Parser.getByte(
                heapBytes, sourceAddress, structuralOffset);

            switch (structural) {
                case '{' -> {
                    if (depth == 0) {
                        if (rootSeen) {
                            return SimdJsonError.TAPE_ERROR;
                        }
                        rootSeen = true;
                    } else {
                        byte parentState =
                            acceptFullContainerValue(state);
                        if (parentState == 0) {
                            return SimdJsonError.TAPE_ERROR;
                        }
                        stackStates[depth - 1] = parentState;
                    }
                    if (depth >= MAX_DEPTH) {
                        throw new SimdJsonException(
                            SimdJsonError.DEPTH_ERROR);
                    }
                    depth++;
                    state = FULL_OBJECT_FIRST_KEY_OR_END;
                }
                case '[' -> {
                    if (depth == 0) {
                        if (rootSeen) {
                            return SimdJsonError.TAPE_ERROR;
                        }
                        rootSeen = true;
                    } else {
                        byte parentState =
                            acceptFullContainerValue(state);
                        if (parentState == 0) {
                            return SimdJsonError.TAPE_ERROR;
                        }
                        stackStates[depth - 1] = parentState;
                    }
                    if (depth >= MAX_DEPTH) {
                        throw new SimdJsonException(
                            SimdJsonError.DEPTH_ERROR);
                    }
                    depth++;
                    state = FULL_ARRAY_FIRST_VALUE_OR_END;
                }
                case '}' -> {
                    if (depth == 0 || isFullArray(state)
                            || (state != FULL_OBJECT_FIRST_KEY_OR_END
                                && state != FULL_OBJECT_COMPLETE)) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    depth--;
                    if (depth != 0) {
                        state = stackStates[depth - 1];
                    }
                }
                case ']' -> {
                    if (depth == 0 || !isFullArray(state)
                            || (state != FULL_ARRAY_FIRST_VALUE_OR_END
                                && state != FULL_ARRAY_COMPLETE)) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    depth--;
                    if (depth != 0) {
                        state = stackStates[depth - 1];
                    }
                }
                case ':' -> {
                    if (depth == 0 || isFullArray(state)
                            || state != FULL_OBJECT_COLON) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    state = FULL_OBJECT_VALUE;
                }
                case ',' -> {
                    if (depth == 0) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    if (isFullArray(state)) {
                        if (state != FULL_ARRAY_COMPLETE) {
                            return SimdJsonError.TAPE_ERROR;
                        }
                        state = FULL_ARRAY_VALUE_AFTER_COMMA;
                    } else {
                        if (state != FULL_OBJECT_COMPLETE) {
                            return SimdJsonError.TAPE_ERROR;
                        }
                        state = FULL_OBJECT_KEY_AFTER_COMMA;
                    }
                }
                default -> {
                    int nextOffset = i + 1 < numIndexes
                        ? indexes.getUnchecked(i + 1) : jsonLength;
                    while (nextBackslash < structuralOffset) {
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
                        SimdJsonError error = Stage2Parser.validateScalar(
                            heapBytes, sourceAddress,
                            structuralOffset, nextOffset,
                            tokenMayContainBackslash);
                        if (error != SimdJsonError.SUCCESS) {
                            return error;
                        }
                        rootSeen = true;
                        rootScalar = true;
                    } else if (isFullArray(state)) {
                        if (state != FULL_ARRAY_FIRST_VALUE_OR_END
                                && state != FULL_ARRAY_VALUE_AFTER_COMMA) {
                            return SimdJsonError.TAPE_ERROR;
                        }
                        SimdJsonError error = Stage2Parser.validateScalar(
                            heapBytes, sourceAddress,
                            structuralOffset, nextOffset,
                            tokenMayContainBackslash);
                        if (error != SimdJsonError.SUCCESS) {
                            return error;
                        }
                        state = FULL_ARRAY_COMPLETE;
                    } else if (state == FULL_OBJECT_FIRST_KEY_OR_END
                            || state == FULL_OBJECT_KEY_AFTER_COMMA) {
                        SimdJsonError error = Stage2Parser.validateKey(
                            heapBytes, sourceAddress,
                            structuralOffset, nextOffset,
                            tokenMayContainBackslash);
                        if (error != SimdJsonError.SUCCESS) {
                            return error;
                        }
                        state = FULL_OBJECT_COLON;
                    } else if (state == FULL_OBJECT_VALUE) {
                        SimdJsonError error = Stage2Parser.validateScalar(
                            heapBytes, sourceAddress,
                            structuralOffset, nextOffset,
                            tokenMayContainBackslash);
                        if (error != SimdJsonError.SUCCESS) {
                            return error;
                        }
                        state = FULL_OBJECT_COMPLETE;
                    } else {
                        return SimdJsonError.TAPE_ERROR;
                    }
                }
            }
            previousOffset = structuralOffset;
        }

        if (depth != 0 || !rootSeen) {
            return SimdJsonError.TAPE_ERROR;
        }
        if (!rootScalar && !Stage2Parser.isWhitespaceOnly(
                heapBytes, sourceAddress,
                previousOffset + 1, jsonLength)) {
            return SimdJsonError.TAPE_ERROR;
        }
        return SimdJsonError.SUCCESS;
    }

    private static byte acceptFullContainerValue(byte state) {
        if (isFullArray(state)) {
            if (state != FULL_ARRAY_FIRST_VALUE_OR_END
                    && state != FULL_ARRAY_VALUE_AFTER_COMMA) {
                return 0;
            }
            return FULL_ARRAY_COMPLETE;
        }
        if (state != FULL_OBJECT_VALUE) {
            return 0;
        }
        return FULL_OBJECT_COMPLETE;
    }

    private static boolean isFullArray(byte state) {
        return state >= FULL_ARRAY_FIRST_VALUE_OR_END;
    }

    private SimdJsonError validateOperators(
            MemorySegment jsonSegment, StructuralIndexes indexes) {
        int depth = 0;
        byte state = 0;

        int jsonLength = Math.toIntExact(jsonSegment.byteSize());
        int numIndexes = indexes.size();
        byte[] heapBytes = jsonSegment.heapBase()
            .map(o -> (byte[]) o).orElse(null);
        long sourceAddress = jsonSegment.address();

        if (numIndexes == 0) {
            if (Stage2Parser.isWhitespaceOnly(
                    heapBytes, sourceAddress, 0, jsonLength)) {
                return SimdJsonError.EMPTY;
            }
            return Stage2Parser.validateScalar(
                heapBytes, sourceAddress, 0, jsonLength,
                indexes.backslashCount() != 0);
        }

        int previousOffset = -1;
        boolean rootSeen = false;
        int backslashCursor = 0;
        int backslashCount = indexes.backslashCount();
        int nextBackslash = backslashCount == 0
            ? Integer.MAX_VALUE
            : indexes.backslashAtUnchecked(0);

        for (int i = 0; i < numIndexes; i++) {
            int structuralOffset = indexes.getUnchecked(i);
            byte structural = Stage2Parser.getByte(
                heapBytes, sourceAddress, structuralOffset);
            int tokenStart = previousOffset + 1;
            while (nextBackslash < tokenStart) {
                nextBackslash = ++backslashCursor < backslashCount
                    ? indexes.backslashAtUnchecked(backslashCursor)
                    : Integer.MAX_VALUE;
            }
            boolean tokenMayContainBackslash =
                nextBackslash < structuralOffset;

            switch (structural) {
                case '{' -> {
                    if (!Stage2Parser.isWhitespaceOnly(
                            heapBytes, sourceAddress,
                            tokenStart, structuralOffset)) {
                        return SimdJsonError.TAPE_ERROR;
                    }
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
                    if (depth >= MAX_DEPTH) {
                        throw new SimdJsonException(
                            SimdJsonError.DEPTH_ERROR);
                    }
                    depth++;
                    state = OBJECT_FIRST_KEY_OR_END;
                }
                case '[' -> {
                    if (!Stage2Parser.isWhitespaceOnly(
                            heapBytes, sourceAddress,
                            tokenStart, structuralOffset)) {
                        return SimdJsonError.TAPE_ERROR;
                    }
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
                    if (depth >= MAX_DEPTH) {
                        throw new SimdJsonException(
                            SimdJsonError.DEPTH_ERROR);
                    }
                    depth++;
                    state = ARRAY_FIRST_VALUE_OR_END;
                }
                case '}' -> {
                    if (depth == 0 || isArray(state)) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    if (state == OBJECT_VALUE) {
                        SimdJsonError error = Stage2Parser.validateScalar(
                            heapBytes, sourceAddress,
                            tokenStart, structuralOffset,
                            tokenMayContainBackslash);
                        if (error != SimdJsonError.SUCCESS) {
                            return error;
                        }
                    } else if (state == OBJECT_FIRST_KEY_OR_END
                            || state == OBJECT_COMPLETE) {
                        if (!Stage2Parser.isWhitespaceOnly(
                                heapBytes, sourceAddress,
                                tokenStart, structuralOffset)) {
                            return SimdJsonError.TAPE_ERROR;
                        }
                    } else {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    depth--;
                    if (depth != 0) {
                        state = stackStates[depth - 1];
                    }
                }
                case ']' -> {
                    if (depth == 0 || !isArray(state)) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    if (state == ARRAY_FIRST_VALUE_OR_END) {
                        if (!Stage2Parser.isWhitespaceOnly(
                                heapBytes, sourceAddress,
                                tokenStart, structuralOffset)) {
                            SimdJsonError error = Stage2Parser.validateScalar(
                                heapBytes, sourceAddress,
                                tokenStart, structuralOffset,
                                tokenMayContainBackslash);
                            if (error != SimdJsonError.SUCCESS) {
                                return error;
                            }
                        }
                    } else if (state == ARRAY_VALUE_AFTER_COMMA) {
                        SimdJsonError error = Stage2Parser.validateScalar(
                            heapBytes, sourceAddress,
                            tokenStart, structuralOffset,
                            tokenMayContainBackslash);
                        if (error != SimdJsonError.SUCCESS) {
                            return error;
                        }
                    } else if (state == ARRAY_COMPLETE) {
                        if (!Stage2Parser.isWhitespaceOnly(
                                heapBytes, sourceAddress,
                                tokenStart, structuralOffset)) {
                            return SimdJsonError.TAPE_ERROR;
                        }
                    } else {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    depth--;
                    if (depth != 0) {
                        state = stackStates[depth - 1];
                    }
                }
                case ':' -> {
                    if (depth == 0 || isArray(state)) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    if (state != OBJECT_FIRST_KEY_OR_END
                            && state != OBJECT_KEY_AFTER_COMMA) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    SimdJsonError error = Stage2Parser.validateKey(
                        heapBytes, sourceAddress,
                        tokenStart, structuralOffset,
                        tokenMayContainBackslash);
                    if (error != SimdJsonError.SUCCESS) {
                        return error;
                    }
                    state = OBJECT_VALUE;
                }
                case ',' -> {
                    if (depth == 0) {
                        return SimdJsonError.TAPE_ERROR;
                    }
                    if (isArray(state)) {
                        if (state == ARRAY_FIRST_VALUE_OR_END
                                || state == ARRAY_VALUE_AFTER_COMMA) {
                            SimdJsonError error = Stage2Parser.validateScalar(
                                heapBytes, sourceAddress,
                                tokenStart, structuralOffset,
                                tokenMayContainBackslash);
                            if (error != SimdJsonError.SUCCESS) {
                                return error;
                            }
                        } else if (state == ARRAY_COMPLETE) {
                            if (!Stage2Parser.isWhitespaceOnly(
                                    heapBytes, sourceAddress,
                                    tokenStart, structuralOffset)) {
                                return SimdJsonError.TAPE_ERROR;
                            }
                        } else {
                            return SimdJsonError.TAPE_ERROR;
                        }
                        state = ARRAY_VALUE_AFTER_COMMA;
                    } else {
                        if (state == OBJECT_VALUE) {
                            SimdJsonError error = Stage2Parser.validateScalar(
                                heapBytes, sourceAddress,
                                tokenStart, structuralOffset,
                                tokenMayContainBackslash);
                            if (error != SimdJsonError.SUCCESS) {
                                return error;
                            }
                        } else if (state == OBJECT_COMPLETE) {
                            if (!Stage2Parser.isWhitespaceOnly(
                                    heapBytes, sourceAddress,
                                    tokenStart, structuralOffset)) {
                                return SimdJsonError.TAPE_ERROR;
                            }
                        } else {
                            return SimdJsonError.TAPE_ERROR;
                        }
                        state = OBJECT_KEY_AFTER_COMMA;
                    }
                }
                default -> {
                    return SimdJsonError.TAPE_ERROR;
                }
            }

            previousOffset = structuralOffset;
        }

        if (depth != 0 || !rootSeen
                || !Stage2Parser.isWhitespaceOnly(
                    heapBytes, sourceAddress,
                    previousOffset + 1, jsonLength)) {
            return SimdJsonError.TAPE_ERROR;
        }
        return SimdJsonError.SUCCESS;
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

    private static boolean isArray(byte state) {
        return state >= ARRAY_FIRST_VALUE_OR_END;
    }
}
