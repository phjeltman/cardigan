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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Container storing structural character offset indexes produced by Stage 1.
 */
public final class StructuralIndexes {

    private int[] indexes;
    private MemorySegment segment;
    private long segmentAddress;
    private int[] backslashIndexes;
    private MemorySegment backslashSegment;
    private long backslashAddress;
    private int size;
    private int backslashSize;
    private final boolean useNativeMemory;
    private final boolean operatorsOnly;

    public StructuralIndexes(int initialCapacity, boolean useNativeMemory) {
        this(initialCapacity, useNativeMemory, false);
    }

    private StructuralIndexes(
            int initialCapacity, boolean useNativeMemory,
            boolean operatorsOnly) {
        this.useNativeMemory = useNativeMemory;
        this.operatorsOnly = operatorsOnly;
        this.size = 0;
        this.backslashSize = 0;
        int backslashCapacity = Math.max(
            4, Math.min(initialCapacity, 64));
        if (useNativeMemory) {
            this.segment = Arena.ofAuto().allocate(ValueLayout.JAVA_INT_UNALIGNED, initialCapacity);
            this.segmentAddress = segment.address();
            this.backslashSegment = Arena.ofAuto().allocate(
                ValueLayout.JAVA_INT_UNALIGNED, backslashCapacity);
            this.backslashAddress = backslashSegment.address();
        } else {
            this.indexes = new int[initialCapacity];
            this.backslashIndexes = new int[backslashCapacity];
        }
    }

    public StructuralIndexes(int initialCapacity) {
        this(initialCapacity, false);
    }

    /**
     * Produces punctuation-only indexes for consumers that locate scalar
     * starts independently.
     */
    public static StructuralIndexes operatorsOnly(
            int initialCapacity, boolean useNativeMemory) {
        return new StructuralIndexes(
            initialCapacity, useNativeMemory, true);
    }

    public static StructuralIndexes operatorsOnly(int initialCapacity) {
        return operatorsOnly(initialCapacity, false);
    }

    boolean operatorsOnly() {
        return operatorsOnly;
    }

    public int[] indexes() {
        return indexes;
    }

    public boolean isNative() {
        return useNativeMemory;
    }

    public int get(int index) {
        if (useNativeMemory) {
            return segment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, index);
        } else {
            return indexes[index];
        }
    }

    int getUnchecked(int index) {
        if (useNativeMemory) {
            return (int) RawSegment.INT_UNALIGNED.get(
                RawSegment.ADDRESS_SPACE,
                segmentAddress + (long) index * Integer.BYTES);
        }
        return indexes[index];
    }

    public void add(int offset) {
        ensureCapacity(size + 1);
        if (useNativeMemory) {
            RawSegment.INT_UNALIGNED.set(
                RawSegment.ADDRESS_SPACE,
                segmentAddress + (long) size * Integer.BYTES, offset);
        } else {
            indexes[size] = offset;
        }
        size++;
    }

    public void addUnchecked(int offset) {
        if (useNativeMemory) {
            RawSegment.INT_UNALIGNED.set(
                RawSegment.ADDRESS_SPACE,
                segmentAddress + (long) size * Integer.BYTES, offset);
        } else {
            indexes[size] = offset;
        }
        size++;
    }

    void addBits(int baseOffset, long bits) {
        if (!useNativeMemory) {
            addBitsHeap(baseOffset, bits);
            return;
        }
        int count = Long.bitCount(bits);
        if (count == 0) {
            return;
        }
        // simdjson deliberately writes four indexes at a time. The last group
        // may contain harmless values beyond the logical end, trading a little
        // padding for an unrolled, predictable hot path.
        ensureCapacity(size + ((count + 3) & ~3));
        long address = segmentAddress + (long) size * Integer.BYTES;
        bits = writeFour(address, baseOffset, bits);
        int written = 4;
        if (written < count) {
            bits = writeFour(
                address + 4L * Integer.BYTES,
                baseOffset, bits);
            written = 8;
        }
        if (written < count) {
            bits = writeFour(
                address + 8L * Integer.BYTES,
                baseOffset, bits);
            written = 12;
        }
        if (written < count) {
            bits = writeFour(
                address + 12L * Integer.BYTES,
                baseOffset, bits);
            written = 16;
        }
        if (written < count) {
            bits = writeFour(
                address + 16L * Integer.BYTES,
                baseOffset, bits);
            written = 20;
        }
        if (written < count) {
            bits = writeFour(
                address + 20L * Integer.BYTES,
                baseOffset, bits);
            written = 24;
        }
        while (written < count) {
            bits = writeFour(
                address + (long) written * Integer.BYTES,
                baseOffset, bits);
            written += 4;
        }
        size += count;
    }

    /** Heap-only writer kept small enough for C2 to inline into Stage 1. */
    void addBitsHeap(int baseOffset, long bits) {
        int count = Long.bitCount(bits);
        if (count == 0) {
            return;
        }
        ensureCapacity(size + ((count + 3) & ~3));
        int writeIndex = size;
        bits = writeFour(indexes, writeIndex, baseOffset, bits);
        int written = 4;
        if (written < count) {
            bits = writeFour(
                indexes, writeIndex + 4, baseOffset, bits);
            written = 8;
        }
        if (written < count) {
            bits = writeFour(
                indexes, writeIndex + 8, baseOffset, bits);
            written = 12;
        }
        if (written < count) {
            bits = writeFour(
                indexes, writeIndex + 12, baseOffset, bits);
            written = 16;
        }
        if (written < count) {
            bits = writeFour(
                indexes, writeIndex + 16, baseOffset, bits);
            written = 20;
        }
        if (written < count) {
            bits = writeFour(
                indexes, writeIndex + 20, baseOffset, bits);
            written = 24;
        }
        while (written < count) {
            bits = writeFour(
                indexes, writeIndex + written,
                baseOffset, bits);
            written += 4;
        }
        size += count;
    }

    private static long writeFour(
            int[] target, int index, int baseOffset, long bits) {
        target[index] = baseOffset
            + Long.numberOfTrailingZeros(bits);
        bits &= bits - 1;
        target[index + 1] = baseOffset
            + Long.numberOfTrailingZeros(bits);
        bits &= bits - 1;
        target[index + 2] = baseOffset
            + Long.numberOfTrailingZeros(bits);
        bits &= bits - 1;
        target[index + 3] = baseOffset
            + Long.numberOfTrailingZeros(bits);
        return bits & (bits - 1);
    }

    private static long writeFour(
            long address, int baseOffset, long bits) {
        RawSegment.INT_UNALIGNED.set(
            RawSegment.ADDRESS_SPACE, address,
            baseOffset + Long.numberOfTrailingZeros(bits));
        bits &= bits - 1;
        RawSegment.INT_UNALIGNED.set(
            RawSegment.ADDRESS_SPACE, address + Integer.BYTES,
            baseOffset + Long.numberOfTrailingZeros(bits));
        bits &= bits - 1;
        RawSegment.INT_UNALIGNED.set(
            RawSegment.ADDRESS_SPACE, address + 2L * Integer.BYTES,
            baseOffset + Long.numberOfTrailingZeros(bits));
        bits &= bits - 1;
        RawSegment.INT_UNALIGNED.set(
            RawSegment.ADDRESS_SPACE, address + 3L * Integer.BYTES,
            baseOffset + Long.numberOfTrailingZeros(bits));
        return bits & (bits - 1);
    }

    public void ensureCapacityForExtra(int extraCount) {
        ensureCapacity(size + extraCount);
    }

    public int size() {
        return size;
    }

    /** Records the sparse raw backslashes found while classifying input. */
    void addBackslashBits(int baseOffset, long bits) {
        int count = Long.bitCount(bits);
        if (count == 0) {
            return;
        }
        ensureBackslashCapacity(backslashSize + count);
        if (useNativeMemory) {
            long address = backslashAddress
                + (long) backslashSize * Integer.BYTES;
            while (bits != 0) {
                RawSegment.INT_UNALIGNED.set(
                    RawSegment.ADDRESS_SPACE, address,
                    baseOffset + Long.numberOfTrailingZeros(bits));
                address += Integer.BYTES;
                bits &= bits - 1;
            }
        } else {
            int writeIndex = backslashSize;
            while (bits != 0) {
                backslashIndexes[writeIndex++] = baseOffset
                    + Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
            }
        }
        backslashSize += count;
    }

    int backslashCount() {
        return backslashSize;
    }

    int backslashAtUnchecked(int index) {
        if (useNativeMemory) {
            return (int) RawSegment.INT_UNALIGNED.get(
                RawSegment.ADDRESS_SPACE,
                backslashAddress + (long) index * Integer.BYTES);
        }
        return backslashIndexes[index];
    }

    int[] backslashIndexes() {
        return backslashIndexes;
    }

    public void reset() {
        size = 0;
        backslashSize = 0;
    }

    private void ensureCapacity(int minCapacity) {
        int currentCapacity = useNativeMemory ? (int) (segment.byteSize() / 4) : indexes.length;
        if (minCapacity > currentCapacity) {
            int newCapacity = Math.max(currentCapacity * 2, minCapacity);
            if (useNativeMemory) {
                MemorySegment newSegment = Arena.ofAuto().allocate(ValueLayout.JAVA_INT_UNALIGNED, newCapacity);
                MemorySegment.copy(segment, 0, newSegment, 0, (long) size * 4);
                this.segment = newSegment;
                this.segmentAddress = newSegment.address();
            } else {
                int[] newIndexes = new int[newCapacity];
                System.arraycopy(indexes, 0, newIndexes, 0, size);
                this.indexes = newIndexes;
            }
        }
    }

    private void ensureBackslashCapacity(int minCapacity) {
        int currentCapacity = useNativeMemory
            ? (int) (backslashSegment.byteSize() / Integer.BYTES)
            : backslashIndexes.length;
        if (minCapacity <= currentCapacity) {
            return;
        }
        int newCapacity = Math.max(currentCapacity * 2, minCapacity);
        if (useNativeMemory) {
            MemorySegment newSegment = Arena.ofAuto().allocate(
                ValueLayout.JAVA_INT_UNALIGNED, newCapacity);
            MemorySegment.copy(
                backslashSegment, 0, newSegment, 0,
                (long) backslashSize * Integer.BYTES);
            backslashSegment = newSegment;
            backslashAddress = newSegment.address();
        } else {
            int[] newIndexes = new int[newCapacity];
            System.arraycopy(
                backslashIndexes, 0, newIndexes, 0,
                backslashSize);
            backslashIndexes = newIndexes;
        }
    }

    public int[] toArray() {
        int[] result = new int[size];
        if (useNativeMemory) {
            MemorySegment.copy(segment, ValueLayout.JAVA_INT_UNALIGNED, 0, result, 0, size);
        } else {
            System.arraycopy(indexes, 0, result, 0, size);
        }
        return result;
    }
}
