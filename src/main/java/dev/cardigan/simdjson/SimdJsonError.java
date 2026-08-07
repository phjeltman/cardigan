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
 * Error codes matching simdjson C++ error codes.
 */
public enum SimdJsonError {
    SUCCESS("No error"),
    CAPACITY("This offer was declined due to capacity constraints"),
    MEMALLOC("Error allocating memory"),
    TAPE_ERROR("The JSON document has an improper structure"),
    DEPTH_ERROR("JSON document was too deeply nested"),
    STRING_ERROR("Problem parsing string"),
    T_OR_F_ERROR("Problem parsing true or false"),
    N_OR_V_ERROR("Problem parsing null or undefined"),
    NUMBER_ERROR("Problem parsing number"),
    UTF8_ERROR("The JSON document contains invalid UTF-8"),
    UNINITIALIZED("Uninitialized parser state"),
    EMPTY("Empty JSON document"),
    UNCLOSED_STRING("Unclosed string in JSON"),
    UNESCAPED_CHARS("Unescaped control character in string"),
    UNEXPECTED_ERROR("Unexpected error"),
    INCORRECT_TYPE("Element has a different type than requested"),
    NUMBER_OUT_OF_RANGE("Number value out of range"),
    INDEX_OUT_OF_BOUNDS("Array index out of bounds"),
    NO_SUCH_FIELD("Object key not found"),
    INVALID_JSON_POINTER("Invalid JSON pointer format"),
    INVALID_URI_FRAGMENT("Invalid URI fragment");

    private final String description;

    SimdJsonError(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
