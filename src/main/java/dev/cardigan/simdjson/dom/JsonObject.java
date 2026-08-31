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
import java.util.*;

/**
 * DOM Object view.
 */
public final class JsonObject implements Iterable<Map.Entry<String, JsonValue>> {

    private final MemorySegment segment;
    private final Tape tape;
    private final int startTapeIndex;
    private final int endTapeIndex;
    private Map<String, Integer> indexedFields;
    private int lookups;
    private int fieldCount = -1;

    public JsonObject(MemorySegment segment, Tape tape, int startTapeIndex) {
        this.segment = segment;
        this.tape = tape;
        this.startTapeIndex = startTapeIndex;
        this.endTapeIndex = (int) tape.getPayload(startTapeIndex);
    }

    public JsonValue get(String key) {
        JsonValue value = getOrNull(key);
        if (value != null) {
            return value;
        }
        throw new SimdJsonException(
            SimdJsonError.NO_SUCH_FIELD,
            "Key not found: " + key);
    }

    private JsonValue getOrNull(String key) {
        Map<String, Integer> fields = indexedFields;
        if (fields != null) {
            Integer valueIndex = fields.get(key);
            return valueIndex == null
                ? null : new JsonValue(segment, tape, valueIndex);
        }
        if (++lookups >= 2) {
            fields = indexFields();
            indexedFields = fields;
            Integer valueIndex = fields.get(key);
            return valueIndex == null
                ? null : new JsonValue(segment, tape, valueIndex);
        }

        int current = startTapeIndex + 1;
        while (current < endTapeIndex) {
            char keyTag = tape.getTag(current);
            if (keyTag != Tape.TAG_STRING) {
                break;
            }
            JsonValue keyVal = new JsonValue(segment, tape, current);
            String k = keyVal.getString();
            int valIndex = current + 1;
            JsonValue value = new JsonValue(segment, tape, valIndex);

            if (k.equals(key)) {
                return value;
            }

            current = skipValue(valIndex);
        }
        return null;
    }

    public boolean containsKey(String key) {
        return getOrNull(key) != null;
    }

    public int size() {
        if (fieldCount >= 0) {
            return fieldCount;
        }
        int count = 0;
        int current = startTapeIndex + 1;
        while (current < endTapeIndex) {
            count++;
            int valIndex = current + 1;
            current = skipValue(valIndex);
        }
        fieldCount = count;
        return count;
    }

    @Override
    public Iterator<Map.Entry<String, JsonValue>> iterator() {
        return new Iterator<>() {
            private int current = startTapeIndex + 1;

            @Override
            public boolean hasNext() {
                return current < endTapeIndex;
            }

            @Override
            public Map.Entry<String, JsonValue> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                JsonValue key = new JsonValue(segment, tape, current);
                int valueIndex = current + 1;
                JsonValue value = new JsonValue(
                    segment, tape, valueIndex);
                current = skipValue(valueIndex);
                return Map.entry(key.getString(), value);
            }
        };
    }

    private Map<String, Integer> indexFields() {
        Map<String, Integer> fields = new HashMap<>();
        int current = startTapeIndex + 1;
        int count = 0;
        while (current < endTapeIndex) {
            JsonValue key = new JsonValue(segment, tape, current);
            int valueIndex = current + 1;
            fields.putIfAbsent(key.getString(), valueIndex);
            count++;
            current = skipValue(valueIndex);
        }
        fieldCount = count;
        return fields;
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
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, JsonValue> entry : this) {
            if (!first) sb.append(", ");
            sb.append("\"").append(entry.getKey()).append("\": ").append(entry.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}
