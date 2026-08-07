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
 * On-Demand zero-allocation Object view.
 */
public final class ObjectVal {

    private final MemorySegment segment;
    private final byte[] heapBytes;
    private final int heapOffset;
    private final StructuralIndexes indexes;
    private final int startIndexIdx;

    public ObjectVal(MemorySegment segment, byte[] heapBytes, StructuralIndexes indexes, int startIndexIdx) {
        this.segment = segment;
        this.heapBytes = heapBytes;
        this.heapOffset = heapBytes == null
            ? 0 : Math.toIntExact(segment.address());
        this.indexes = indexes;
        this.startIndexIdx = startIndexIdx;
    }

    public Value get(String targetKey) {
        int numIndexes = indexes.size();
        int idx = startIndexIdx + 1;
        int depth = 1;

        while (idx < numIndexes && depth > 0) {
            int offset = indexes.get(idx);
            byte c = getByte(offset);

            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            } else if (c == ':' && depth == 1) {
                // Full Stage 1 indexes place the key immediately before ':'
                // and the value immediately after it.
                int keyIndex = idx - 1;
                Value keyVal = new Value(
                    segment, heapBytes, indexes, keyIndex,
                    indexes.get(keyIndex));
                String k = keyVal.getString();
                if (k.equals(targetKey)) {
                    int valueIndex = idx + 1;
                    return new Value(
                        segment, heapBytes, indexes, valueIndex,
                        indexes.get(valueIndex));
                }
            }
            idx++;
        }

        throw new SimdJsonException(SimdJsonError.NO_SUCH_FIELD, "Key not found: " + targetKey);
    }

    public boolean containsKey(String key) {
        try {
            get(key);
            return true;
        } catch (SimdJsonException e) {
            if (e.getError() == SimdJsonError.NO_SUCH_FIELD) {
                return false;
            }
            throw e;
        }
    }

    private byte getByte(int pos) {
        return heapBytes != null
            ? heapBytes[heapOffset + pos]
            : segment.get(ValueLayout.JAVA_BYTE, pos);
    }
}
