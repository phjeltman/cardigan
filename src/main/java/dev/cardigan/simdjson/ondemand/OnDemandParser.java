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
import dev.cardigan.simdjson.Stage1Indexer;
import dev.cardigan.simdjson.Stage2Validator;
import dev.cardigan.simdjson.StructuralIndexes;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/**
 * On-Demand SIMD JSON Parser facade.
 * Executes Stage 1 SIMD indexing and returns lazy on-demand values.
 */
public final class OnDemandParser {

    private final Stage1Indexer indexer;
    private final Stage2Validator validator;
    private final StructuralIndexes indexes;

    public OnDemandParser() {
        this(1024, false);
    }

    public OnDemandParser(int initialCapacity, boolean useNativeMemory) {
        this.indexer = new Stage1Indexer();
        this.validator = new Stage2Validator();
        this.indexes = new StructuralIndexes(initialCapacity, useNativeMemory);
    }

    public Value parse(MemorySegment jsonSegment) {
        SimdJsonError err = indexer.index(jsonSegment, indexes);
        if (err != SimdJsonError.SUCCESS) {
            throw new SimdJsonException(err);
        }
        SimdJsonError validationError = validator.validate(
            jsonSegment, indexes);
        if (validationError != SimdJsonError.SUCCESS) {
            throw new SimdJsonException(validationError);
        }

        byte[] heapBytes = jsonSegment.heapBase().map(o -> (byte[]) o).orElse(null);
        return new Value(jsonSegment, heapBytes, indexes, 0, 0);
    }

    public Value parse(byte[] bytes) {
        return parse(MemorySegment.ofArray(bytes));
    }

    public Value parse(String jsonString) {
        return parse(jsonString.getBytes(StandardCharsets.UTF_8));
    }
}
