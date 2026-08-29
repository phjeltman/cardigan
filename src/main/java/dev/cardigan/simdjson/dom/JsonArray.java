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

package dev.cardigan.simdjson.dom;

import dev.cardigan.simdjson.SimdJsonError;
import dev.cardigan.simdjson.SimdJsonException;
import dev.cardigan.simdjson.Tape;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * DOM Array view.
 */
public final class JsonArray implements Iterable<JsonValue> {

    private final MemorySegment segment;
    private final Tape tape;
    private final int startTapeIndex;
    private final int endTapeIndex;
    private final int[] elementTapeIndexes;

    public JsonArray(MemorySegment segment, Tape tape, int startTapeIndex) {
        this.segment = segment;
        this.tape = tape;
        this.startTapeIndex = startTapeIndex;
        this.endTapeIndex = (int) tape.getPayload(startTapeIndex);
        int size = 0;
        int current = startTapeIndex + 1;
        while (current < endTapeIndex) {
            size++;
            current = skipValue(current);
        }
        this.elementTapeIndexes = new int[size];
        current = startTapeIndex + 1;
        for (int index = 0; index < size; index++) {
            elementTapeIndexes[index] = current;
            current = skipValue(current);
        }
    }

    public JsonValue get(int index) {
        if (index < 0) {
            throw new SimdJsonException(SimdJsonError.INDEX_OUT_OF_BOUNDS, "Negative index: " + index);
        }
        if (index >= elementTapeIndexes.length) {
            throw new SimdJsonException(
                SimdJsonError.INDEX_OUT_OF_BOUNDS,
                "Index " + index + " out of bounds, size: "
                    + elementTapeIndexes.length);
        }
        return new JsonValue(segment, tape, elementTapeIndexes[index]);
    }

    public int size() {
        return elementTapeIndexes.length;
    }

    @Override
    public Iterator<JsonValue> iterator() {
        return new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                return index < elementTapeIndexes.length;
            }

            @Override
            public JsonValue next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return new JsonValue(
                    segment, tape, elementTapeIndexes[index++]);
            }
        };
    }

    private int skipValue(int tapeIndex) {
        char tag = tape.getTag(tapeIndex);
        if (tag == Tape.TAG_START_OBJECT || tag == Tape.TAG_START_ARRAY) {
            return (int) tape.getPayload(tapeIndex) + 1;
        } else {
            return tapeIndex + 1;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (JsonValue elem : this) {
            if (!first) sb.append(", ");
            sb.append(elem.toString());
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }
}
