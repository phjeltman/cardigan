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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * DOM Array view.
 */
public final class JsonArray implements Iterable<JsonValue> {

    private final MemorySegment segment;
    private final Tape tape;
    private final int startTapeIndex;
    private final int endTapeIndex;

    public JsonArray(MemorySegment segment, Tape tape, int startTapeIndex) {
        this.segment = segment;
        this.tape = tape;
        this.startTapeIndex = startTapeIndex;
        this.endTapeIndex = (int) tape.getPayload(startTapeIndex);
    }

    public JsonValue get(int index) {
        if (index < 0) {
            throw new SimdJsonException(SimdJsonError.INDEX_OUT_OF_BOUNDS, "Negative index: " + index);
        }
        int current = startTapeIndex + 1;
        int count = 0;
        while (current < endTapeIndex) {
            if (count == index) {
                return new JsonValue(segment, tape, current);
            }
            count++;
            current = skipValue(current);
        }
        throw new SimdJsonException(SimdJsonError.INDEX_OUT_OF_BOUNDS, "Index " + index + " out of bounds, size: " + count);
    }

    public int size() {
        int count = 0;
        int current = startTapeIndex + 1;
        while (current < endTapeIndex) {
            count++;
            current = skipValue(current);
        }
        return count;
    }

    @Override
    public Iterator<JsonValue> iterator() {
        List<JsonValue> list = new ArrayList<>();
        int current = startTapeIndex + 1;
        while (current < endTapeIndex) {
            list.add(new JsonValue(segment, tape, current));
            current = skipValue(current);
        }
        return list.iterator();
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
