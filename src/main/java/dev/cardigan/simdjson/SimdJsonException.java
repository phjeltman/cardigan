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

/**
 * Exception thrown when JSON parsing or DOM access encounters an error.
 */
public class SimdJsonException extends RuntimeException {
    private final SimdJsonError error;

    public SimdJsonException(SimdJsonError error) {
        super(error.getDescription());
        this.error = error;
    }

    public SimdJsonException(SimdJsonError error, String message) {
        super(error.getDescription() + ": " + message);
        this.error = error;
    }

    public SimdJsonError getError() {
        return error;
    }
}
