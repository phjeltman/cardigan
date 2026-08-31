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

package dev.cardigan.simdjson.ondemand;

import dev.cardigan.simdjson.SimdJsonError;
import dev.cardigan.simdjson.SimdJsonException;
import dev.cardigan.simdjson.StructuralIndexes;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * On-demand array view that indexes elements after repeated access.
 */
public final class ArrayVal {

    private final MemorySegment segment;
    private final byte[] heapBytes;
    private final int heapOffset;
    private final StructuralIndexes indexes;
    private final int startIndexIdx;
    private int[] elementIndexes;
    private int lookups;

    public ArrayVal(MemorySegment segment, byte[] heapBytes, StructuralIndexes indexes, int startIndexIdx) {
        this.segment = segment;
        this.heapBytes = heapBytes;
        this.heapOffset = heapBytes == null
            ? 0 : Math.toIntExact(segment.address());
        this.indexes = indexes;
        this.startIndexIdx = startIndexIdx;
    }

    public Value get(int targetIndex) {
        if (targetIndex < 0) {
            throw new SimdJsonException(SimdJsonError.INDEX_OUT_OF_BOUNDS, "Negative index: " + targetIndex);
        }

        int[] cached = elementIndexes;
        if (cached != null) {
            return valueAt(cached, targetIndex);
        }
        if (++lookups >= 2) {
            cached = indexElements();
            elementIndexes = cached;
            return valueAt(cached, targetIndex);
        }
        return scanForElement(targetIndex);
    }

    private Value valueAt(int[] cached, int targetIndex) {
        if (targetIndex >= cached.length) {
            throw new SimdJsonException(
                SimdJsonError.INDEX_OUT_OF_BOUNDS,
                "Index out of bounds: " + targetIndex);
        }
        int index = cached[targetIndex];
        return new Value(
            segment, heapBytes, indexes, index, indexes.get(index));
    }

    private Value scanForElement(int targetIndex) {
        int numIndexes = indexes.size();
        int idx = startIndexIdx + 1;
        int depth = 1;
        int count = 0;

        while (idx < numIndexes && depth > 0) {
            int offset = indexes.get(idx);
            byte c = getByte(offset);

            boolean containerStart = c == '{' || c == '[';
            boolean scalarStart = c != ',' && c != ':'
                && c != '}' && c != ']'
                && !containerStart;
            if (depth == 1 && (containerStart || scalarStart)) {
                if (count == targetIndex) {
                    return new Value(
                        segment, heapBytes, indexes, idx, offset);
                }
                count++;
            }

            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
            idx++;
        }

        throw new SimdJsonException(SimdJsonError.INDEX_OUT_OF_BOUNDS, "Index out of bounds: " + targetIndex);
    }

    private int[] indexElements() {
        int count = countElements();
        int[] elements = new int[count];
        int numIndexes = indexes.size();
        int idx = startIndexIdx + 1;
        int depth = 1;
        int element = 0;
        while (idx < numIndexes && depth > 0) {
            int offset = indexes.get(idx);
            byte current = getByte(offset);
            boolean containerStart = current == '{' || current == '[';
            boolean scalarStart = current != ',' && current != ':'
                && current != '}' && current != ']'
                && !containerStart;
            if (depth == 1 && (containerStart || scalarStart)) {
                elements[element++] = idx;
            }
            if (containerStart) {
                depth++;
            } else if (current == '}' || current == ']') {
                depth--;
            }
            idx++;
        }
        return elements;
    }

    private int countElements() {
        int count = 0;
        int numIndexes = indexes.size();
        int idx = startIndexIdx + 1;
        int depth = 1;
        while (idx < numIndexes && depth > 0) {
            byte current = getByte(indexes.get(idx));
            boolean containerStart = current == '{' || current == '[';
            boolean scalarStart = current != ',' && current != ':'
                && current != '}' && current != ']'
                && !containerStart;
            if (depth == 1 && (containerStart || scalarStart)) {
                count++;
            }
            if (containerStart) {
                depth++;
            } else if (current == '}' || current == ']') {
                depth--;
            }
            idx++;
        }
        return count;
    }

    private byte getByte(int pos) {
        return heapBytes != null
            ? heapBytes[heapOffset + pos]
            : segment.get(ValueLayout.JAVA_BYTE, pos);
    }
}
