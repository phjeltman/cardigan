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
 * Tape data structure storing the compiled JSON node representation.
 * Each tape entry is a 64-bit word (Tag in top 8 bits, Payload in bottom 56 bits).
 * Supports both on-heap long[] and zero-allocation off-heap MemorySegment backing.
 */
public final class Tape {

    public static final char TAG_ROOT = 'r';
    public static final char TAG_START_OBJECT = '{';
    public static final char TAG_END_OBJECT = '}';
    public static final char TAG_START_ARRAY = '[';
    public static final char TAG_END_ARRAY = ']';
    public static final char TAG_STRING = 's';
    public static final char TAG_INT64 = 'l';
    public static final char TAG_UINT64 = 'u';
    public static final char TAG_DOUBLE = 'd';
    public static final char TAG_TRUE = 't';
    public static final char TAG_FALSE = 'f';
    public static final char TAG_NULL = 'n';

    private long[] tapeArray;
    private MemorySegment tapeSegment;
    private long tapeAddress;
    private int size;
    private final boolean useNativeMemory;

    public Tape(int initialCapacity, boolean useNativeMemory) {
        this.useNativeMemory = useNativeMemory;
        this.size = 0;
        if (useNativeMemory) {
            this.tapeSegment = Arena.ofAuto().allocate(ValueLayout.JAVA_LONG_UNALIGNED, initialCapacity);
            this.tapeAddress = tapeSegment.address();
        } else {
            this.tapeArray = new long[initialCapacity];
        }
    }

    public Tape(int initialCapacity) {
        this(initialCapacity, false);
    }

    public void add(char tag, long payload) {
        long word = (((long) tag) << 56) | (payload & 0x00FFFFFFFFFFFFFFL);
        write(size++, word);
    }

    void reserve(int capacity) {
        ensureCapacity(capacity);
    }

    void addUnchecked(char tag, long payload) {
        long word = (((long) tag) << 56) | (payload & 0x00FFFFFFFFFFFFFFL);
        if (useNativeMemory) {
            RawSegment.LONG_UNALIGNED.set(
                RawSegment.ADDRESS_SPACE, tapeAddress + (long) size * Long.BYTES,
                word);
        } else {
            tapeArray[size] = word;
        }
        size++;
    }

    public void add(char tag) {
        add(tag, 0L);
    }

    public void addRaw64(long value) {
        write(size++, value);
    }

    public void set(int index, char tag, long payload) {
        long word = (((long) tag) << 56) | (payload & 0x00FFFFFFFFFFFFFFL);
        write(index, word);
    }

    void setUnchecked(int index, char tag, long payload) {
        long word = (((long) tag) << 56) | (payload & 0x00FFFFFFFFFFFFFFL);
        if (useNativeMemory) {
            RawSegment.LONG_UNALIGNED.set(
                RawSegment.ADDRESS_SPACE, tapeAddress + (long) index * Long.BYTES,
                word);
        } else {
            tapeArray[index] = word;
        }
    }

    public char getTag(int index) {
        long word = read(index);
        return (char) (word >>> 56);
    }

    public long getPayload(int index) {
        long word = read(index);
        return word & 0x00FFFFFFFFFFFFFFL;
    }

    public long getRaw64(int index) {
        return read(index);
    }

    public double getDouble(int index) {
        return Double.longBitsToDouble(read(index));
    }

    public int size() {
        return size;
    }

    boolean isNative() {
        return useNativeMemory;
    }

    long[] heapArray() {
        return tapeArray;
    }

    void commitHeapSize(int newSize) {
        size = newSize;
    }

    public void reset() {
        size = 0;
    }

    private void write(int index, long word) {
        ensureCapacity(index + 1);
        if (useNativeMemory) {
            RawSegment.LONG_UNALIGNED.set(
                RawSegment.ADDRESS_SPACE,
                tapeAddress + (long) index * Long.BYTES, word);
        } else {
            tapeArray[index] = word;
        }
    }

    private long read(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Tape index out of bounds: " + index + ", size: " + size);
        }
        if (useNativeMemory) {
            return (long) RawSegment.LONG_UNALIGNED.get(
                RawSegment.ADDRESS_SPACE,
                tapeAddress + (long) index * Long.BYTES);
        } else {
            return tapeArray[index];
        }
    }

    private void ensureCapacity(int minCapacity) {
        int currentCapacity = useNativeMemory ? (int) (tapeSegment.byteSize() / 8) : tapeArray.length;
        if (minCapacity > currentCapacity) {
            int newCapacity = Math.max(currentCapacity * 2, minCapacity);
            if (useNativeMemory) {
                MemorySegment newSeg = Arena.ofAuto().allocate(ValueLayout.JAVA_LONG_UNALIGNED, newCapacity);
                MemorySegment.copy(tapeSegment, 0, newSeg, 0, (long) size * 8);
                this.tapeSegment = newSeg;
                this.tapeAddress = newSeg.address();
            } else {
                long[] newArray = new long[newCapacity];
                System.arraycopy(tapeArray, 0, newArray, 0, size);
                this.tapeArray = newArray;
            }
        }
    }
}
