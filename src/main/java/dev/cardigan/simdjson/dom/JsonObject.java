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

    public JsonObject(MemorySegment segment, Tape tape, int startTapeIndex) {
        this.segment = segment;
        this.tape = tape;
        this.startTapeIndex = startTapeIndex;
        this.endTapeIndex = (int) tape.getPayload(startTapeIndex);
    }

    public JsonValue get(String key) {
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
        throw new SimdJsonException(SimdJsonError.NO_SUCH_FIELD, "Key not found: " + key);
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

    public int size() {
        int count = 0;
        int current = startTapeIndex + 1;
        while (current < endTapeIndex) {
            count++;
            int valIndex = current + 1;
            current = skipValue(valIndex);
        }
        return count;
    }

    @Override
    public Iterator<Map.Entry<String, JsonValue>> iterator() {
        List<Map.Entry<String, JsonValue>> entries = new ArrayList<>();
        int current = startTapeIndex + 1;
        while (current < endTapeIndex) {
            JsonValue keyVal = new JsonValue(segment, tape, current);
            int valIndex = current + 1;
            JsonValue val = new JsonValue(segment, tape, valIndex);
            entries.add(Map.entry(keyVal.getString(), val));
            current = skipValue(valIndex);
        }
        return entries.iterator();
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
