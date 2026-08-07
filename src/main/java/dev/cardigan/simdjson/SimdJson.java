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

import dev.cardigan.simdjson.dom.JsonValue;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/**
 * Main entry point for Cardigan's simdjson-derived parser.
 */
public final class SimdJson {

    private final Stage1Indexer indexer;
    private final Stage2Parser parser;
    private final StructuralIndexes indexes;
    private final Tape tape;

    public SimdJson() {
        this(1024, false);
    }

    public SimdJson(int initialCapacity, boolean useNativeMemory) {
        this.indexer = new Stage1Indexer();
        this.parser = new Stage2Parser();
        this.indexes = new StructuralIndexes(initialCapacity, useNativeMemory);
        this.tape = new Tape(initialCapacity, useNativeMemory);
    }

    public JsonValue parse(MemorySegment jsonSegment) {
        SimdJsonError err1 = indexer.index(jsonSegment, indexes);
        if (err1 != SimdJsonError.SUCCESS) {
            throw new SimdJsonException(err1);
        }

        SimdJsonError err2 = parser.parse(jsonSegment, indexes, tape);
        if (err2 != SimdJsonError.SUCCESS) {
            throw new SimdJsonException(err2);
        }

        return new JsonValue(jsonSegment, tape, 0);
    }

    public JsonValue parse(byte[] bytes) {
        return parse(MemorySegment.ofArray(bytes));
    }

    public JsonValue parse(String jsonString) {
        return parse(jsonString.getBytes(StandardCharsets.UTF_8));
    }

    public static JsonValue parseJson(String jsonString) {
        return new SimdJson().parse(jsonString);
    }

    public static JsonValue parseJson(byte[] bytes) {
        return new SimdJson().parse(bytes);
    }

    public static JsonValue parseJson(MemorySegment segment) {
        return new SimdJson().parse(segment);
    }
}
