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

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import dev.cardigan.simdjson.dom.JsonType;
import dev.cardigan.simdjson.ondemand.ArrayVal;
import dev.cardigan.simdjson.ondemand.ObjectVal;
import dev.cardigan.simdjson.ondemand.OnDemandParser;
import dev.cardigan.simdjson.ondemand.Value;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

public class OnDemandTest {

    @Test
    public void testSimpleObject() {
        String json = "{\"name\": \"Alice\", \"age\": 28, \"isStudent\": false}";
        OnDemandParser parser = new OnDemandParser();
        Value val = parser.parse(json);

        assertEquals(JsonType.OBJECT, val.getType(), "Root type");
        ObjectVal obj = val.getObject();

        assertEquals("Alice", obj.get("name").getString(), "name field");
        assertEquals(28L, obj.get("age").getLong());
        assertFalse(obj.get("isStudent").getBoolean(), "isStudent false");
    }

    @Test
    public void testNestedStructures() {
        String json = """
                {
                    "user": {
                        "id": 1001,
                        "tags": ["java", "simd", "panama"]
                    },
                    "status": "OK"
                }
                """;
        OnDemandParser parser = new OnDemandParser();
        Value root = parser.parse(json);
        ObjectVal rootObj = root.getObject();

        assertEquals("OK", rootObj.get("status").getString(), "status");

        ObjectVal userObj = rootObj.get("user").getObject();
        assertEquals(1001L, userObj.get("id").getLong());

        ArrayVal tagsArr = userObj.get("tags").getArray();
        assertEquals("java", tagsArr.get(0).getString(), "tag 0");
        assertEquals("simd", tagsArr.get(1).getString(), "tag 1");
        assertEquals("panama", tagsArr.get(2).getString(), "tag 2");
    }

    @Test
    public void testFieldLookups() {
        String json = "{\"a\": 10, \"b\": 20, \"c\": 30}";
        OnDemandParser parser = new OnDemandParser();
        Value val = parser.parse(json);
        assertEquals(30L, val.get("c").getLong());
        assertEquals(10L, val.get("a").getLong());
        assertEquals(20L, val.get("b").getLong());
    }

    @Test
    public void testHeapSliceUsesRelativeOffsets() {
        byte[] bytes = "prefix:[\"slice\",42]:suffix"
            .getBytes(StandardCharsets.UTF_8);
        MemorySegment slice = MemorySegment.ofArray(bytes)
            .asSlice("prefix:".length(), "[\"slice\",42]".length());

        ArrayVal array = new OnDemandParser().parse(slice).getArray();
        assertEquals("slice", array.get(0).getString());
        assertEquals(42L, array.get(1).getLong());
    }

    @Test
    public void testHeapSliceRootStringUsesRelativeOffset() {
        byte[] bytes = "prefix:\"slice\":suffix"
            .getBytes(StandardCharsets.UTF_8);
        MemorySegment slice = MemorySegment.ofArray(bytes)
            .asSlice("prefix:".length(), "\"slice\"".length());

        Value value = new OnDemandParser().parse(slice);
        assertEquals("slice", value.getString());
        assertEquals("\"slice\"", value.toString());
    }

    @Test
    public void testEscapedStringPreservesRawUtf8() {
        Value root = new OnDemandParser().parse(
            "{\"value\":\"Euro €\\nEmoji 😀 \\u20ac\"}");

        assertEquals(
            "Euro €\nEmoji 😀 €",
            root.get("value").getString());
    }
}
