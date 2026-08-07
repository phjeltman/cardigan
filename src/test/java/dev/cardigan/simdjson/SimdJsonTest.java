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

import dev.cardigan.simdjson.dom.JsonArray;
import dev.cardigan.simdjson.dom.JsonObject;
import dev.cardigan.simdjson.dom.JsonType;
import dev.cardigan.simdjson.dom.JsonValue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

public class SimdJsonTest {

    @Test
    public void testStage1SimpleObject() {
        String json = "{\"name\": \"John\", \"age\": 30, \"cities\": [\"NY\", \"LA\"]}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        MemorySegment segment = MemorySegment.ofArray(bytes);

        Stage1Indexer indexer = new Stage1Indexer();
        StructuralIndexes indexes = new StructuralIndexes(64);

        SimdJsonError error = indexer.index(segment, indexes);
        assertEquals(SimdJsonError.SUCCESS, error, "Stage 1 error");
        assertFalse(indexer.hasBackslash());

        int[] expectedOffsets = new int[]{
            0, 1, 7, 9, 15, 17, 22, 24, 26,
            28, 36, 38, 39, 43, 45, 49, 50
        };
        assertEquals(expectedOffsets.length, indexes.size());
        for (int i = 0; i < expectedOffsets.length; i++) {
            assertEquals((long) expectedOffsets[i], (long) indexes.get(i));
        }
    }

    @Test
    public void testStage1EscapedQuotes() {
        String json = "{\"escaped\": \"hello \\\"world\\\"\", \"count\": 1}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        MemorySegment segment = MemorySegment.ofArray(bytes);

        Stage1Indexer indexer = new Stage1Indexer();
        StructuralIndexes indexes = new StructuralIndexes(64);

        SimdJsonError error = indexer.index(segment, indexes);
        assertEquals(SimdJsonError.SUCCESS, error, "Stage 1 error");
        assertTrue(indexer.hasBackslash());

        assertStage1Indexes(bytes, segment);
        assertFalse(containsIndex(indexes, json.indexOf("\\\"world") + 1));
    }

    @Test
    public void testStage1TailBoundariesOnHeapAndNativeSegments() {
        int[] lengths = {8, 15, 16, 17, 31, 32, 33, 47, 63};
        for (int length : lengths) {
            String prefix = "{\"v\":\"";
            String suffix = "\"}";
            String json = prefix
                + "a".repeat(length - prefix.length() - suffix.length())
                + suffix;
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

            assertStage1Indexes(bytes, MemorySegment.ofArray(bytes));
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment nativeSegment = arena.allocate(bytes.length);
                MemorySegment.copy(
                    MemorySegment.ofArray(bytes), 0,
                    nativeSegment, 0, bytes.length);
                assertStage1Indexes(bytes, nativeSegment);
            }
        }
    }

    @Test
    public void testStage1EscapeCrossingVectorTailBoundary() {
        String json = "{\"v\":\"" + "a".repeat(57) + "\\\"tail\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        assertEquals('\\', bytes[63]);
        assertEquals('"', bytes[64]);

        assertStage1Indexes(bytes, MemorySegment.ofArray(bytes));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nativeSegment = arena.allocate(bytes.length);
            MemorySegment.copy(
                MemorySegment.ofArray(bytes), 0,
                nativeSegment, 0, bytes.length);
            assertStage1Indexes(bytes, nativeSegment);
        }
    }

    @Test
    public void testStage1PairAndTailBoundaries() {
        int[] lengths = {127, 128, 129, 191, 192, 193, 255, 256, 257};
        for (int length : lengths) {
            byte[] bytes = ("{\"v\":\""
                + "a".repeat(length - 8) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
            assertEquals(length, bytes.length);
            assertHeapAndNativeStage1(bytes);

            byte[] padded = new byte[length + 13];
            System.arraycopy(bytes, 0, padded, 7, bytes.length);
            assertStage1Indexes(
                bytes,
                MemorySegment.ofArray(padded).asSlice(7, length));
        }
    }

    @Test
    public void testStage1CarriesAcrossEveryPairLaneBoundary() {
        int[] boundaries = {31, 32, 63, 64, 95, 96, 127, 128};
        for (int boundary : boundaries) {
            String unicode = "{\"v\":\""
                + "a".repeat(boundary - 6)
                + "€"
                + "a".repeat(140 - boundary)
                + "\"}";
            assertHeapAndNativeStage1(
                unicode.getBytes(StandardCharsets.UTF_8));
        }

        String escaped = "{\"v\":\""
            + "a".repeat(121) + "\\\"tail\"}";
        byte[] escapedBytes = escaped.getBytes(StandardCharsets.UTF_8);
        assertEquals('\\', escapedBytes[127]);
        assertEquals('"', escapedBytes[128]);
        assertHeapAndNativeStage1(escapedBytes);
    }

    @Test
    public void testStage1RecordsSparseBackslashesAcrossBackendsAndReuse() {
        int[] positions = {
            1, 31, 32, 63, 64, 95, 96, 127, 128, 255, 256, 382
        };
        for (int position : positions) {
            byte[] bytes = new byte[385];
            java.util.Arrays.fill(bytes, (byte) 'a');
            bytes[0] = '"';
            bytes[position] = '\\';
            bytes[position + 1] = 'n';
            bytes[bytes.length - 1] = '"';

            for (boolean nativeSource : new boolean[] {false, true}) {
                for (boolean nativeState : new boolean[] {false, true}) {
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment selectedSource =
                            MemorySegment.ofArray(bytes);
                        if (nativeSource) {
                            selectedSource = arena.allocate(bytes.length);
                            MemorySegment.copy(
                                MemorySegment.ofArray(bytes), 0,
                                selectedSource, 0, bytes.length);
                        }
                        MemorySegment source = selectedSource;

                        assertBackslashMetadata(
                            source, position, nativeState, false);
                        assertBackslashMetadata(
                            source, position, nativeState, true);
                        assertDoesNotThrow(
                            () -> new SimdJson(4, nativeState)
                                .parse(source).getString());
                    }
                }
            }
        }
    }

    private static void assertBackslashMetadata(
            MemorySegment source, int expectedPosition,
            boolean nativeState, boolean operatorsOnly) {
        StructuralIndexes indexes = operatorsOnly
            ? StructuralIndexes.operatorsOnly(4, nativeState)
            : new StructuralIndexes(4, nativeState);
        Stage1Indexer indexer = new Stage1Indexer();
        assertEquals(SimdJsonError.SUCCESS, indexer.index(source, indexes));
        assertEquals(1, indexes.backslashCount());
        assertEquals(expectedPosition, indexes.backslashAtUnchecked(0));
        assertEquals(
            SimdJsonError.SUCCESS,
            new Stage2Validator().validate(source, indexes));

        byte[] plain = "{\"plain\":\"value\"}"
            .getBytes(StandardCharsets.UTF_8);
        assertEquals(
            SimdJsonError.SUCCESS,
            indexer.index(MemorySegment.ofArray(plain), indexes));
        assertEquals(0, indexes.backslashCount());
    }

    private static void assertHeapAndNativeStage1(byte[] bytes) {
        assertStage1Indexes(bytes, MemorySegment.ofArray(bytes));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nativeSegment = arena.allocate(bytes.length);
            MemorySegment.copy(
                MemorySegment.ofArray(bytes), 0,
                nativeSegment, 0, bytes.length);
            assertStage1Indexes(bytes, nativeSegment);
        }
    }

    private static void assertStage1Indexes(
            byte[] json, MemorySegment segment) {
        Stage1Indexer indexer = new Stage1Indexer();
        StructuralIndexes indexes = new StructuralIndexes(16);
        assertEquals(SimdJsonError.SUCCESS, indexer.index(segment, indexes));

        int expectedIndex = 0;
        boolean inString = false;
        boolean escaped = false;
        boolean inScalar = false;
        for (int i = 0; i < json.length; i++) {
            byte value = json[i];
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    inString = false;
                    inScalar = false;
                }
                continue;
            }
            if (value == '"') {
                assertTrue(expectedIndex < indexes.size());
                assertEquals(i, indexes.get(expectedIndex++));
                inString = true;
                inScalar = false;
            } else if (value == '{' || value == '}'
                    || value == '[' || value == ']'
                    || value == ':' || value == ',') {
                assertTrue(expectedIndex < indexes.size());
                assertEquals(i, indexes.get(expectedIndex++));
                inScalar = false;
            } else if (value == ' ' || value == '\t'
                    || value == '\n' || value == '\r') {
                inScalar = false;
            } else if (!inScalar) {
                assertTrue(expectedIndex < indexes.size());
                assertEquals(i, indexes.get(expectedIndex++));
                inScalar = true;
            }
        }
        assertEquals(expectedIndex, indexes.size());
    }

    private static boolean containsIndex(
            StructuralIndexes indexes, int expected) {
        for (int i = 0; i < indexes.size(); i++) {
            if (indexes.get(i) == expected) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void testDomSimpleObject() {
        String json = "{\"name\": \"Alice\", \"age\": 28, \"isStudent\": false, \"gpa\": 3.85, \"extra\": null}";
        JsonValue val = SimdJson.parseJson(json);

        assertEquals(JsonType.OBJECT, val.getType(), "Root type");
        JsonObject obj = val.getObject();

        assertEquals(5, obj.size());
        assertEquals("Alice", obj.get("name").getString(), "name field");
        assertEquals(28L, obj.get("age").getLong());
        assertFalse(obj.get("isStudent").getBoolean(), "isStudent false");
        assertEquals(3.85, obj.get("gpa").getDouble(), 0.0001);
        assertTrue(obj.get("extra").isNull(), "extra is null");
    }

    @Test
    public void testDomNestedStructures() {
        String json = """
                {
                    "user": {
                        "id": 1001,
                        "tags": ["java", "simd", "panama"]
                    },
                    "status": "OK"
                }
                """;
        JsonValue root = SimdJson.parseJson(json);
        JsonObject rootObj = root.getObject();

        assertEquals("OK", rootObj.get("status").getString(), "status");

        JsonObject userObj = rootObj.get("user").getObject();
        assertEquals(1001L, userObj.get("id").getLong());

        JsonArray tagsArr = userObj.get("tags").getArray();
        assertEquals(3, tagsArr.size());
        assertEquals("java", tagsArr.get(0).getString(), "tag 0");
        assertEquals("simd", tagsArr.get(1).getString(), "tag 1");
        assertEquals("panama", tagsArr.get(2).getString(), "tag 2");
    }

    @Test
    public void testDomStringUnescaping() {
        String json = "{\"msg\": \"Hello \\\"World\\\"\\nLine 2\"}";
        JsonValue root = SimdJson.parseJson(json);
        assertEquals("Hello \"World\"\nLine 2", root.getObject().get("msg").getString(), "escaped string");
    }

    @Test
    public void testDomNativeMemorySegment() {
        String json = "[10, 20, 30, 40]";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        MemorySegment segment = MemorySegment.ofArray(bytes);

        SimdJson parser = new SimdJson(128, true);
        JsonValue val = parser.parse(segment);

        assertEquals(JsonType.ARRAY, val.getType(), "Root array");
        JsonArray arr = val.getArray();
        assertEquals(4, arr.size());
        assertEquals(10L, arr.get(0).getLong());
        assertEquals(20L, arr.get(1).getLong());
        assertEquals(30L, arr.get(2).getLong());
        assertEquals(40L, arr.get(3).getLong());
    }

    @Test
    public void testDomHeapSliceUsesRelativeOffsets() {
        byte[] bytes = "prefix:{\"name\":\"slice\",\"value\":42}:suffix"
            .getBytes(StandardCharsets.UTF_8);
        int offset = "prefix:".length();
        int length = "{\"name\":\"slice\",\"value\":42}".length();
        MemorySegment slice = MemorySegment.ofArray(bytes)
            .asSlice(offset, length);

        JsonObject object = new SimdJson().parse(slice).getObject();
        assertEquals("slice", object.get("name").getString());
        assertEquals(42L, object.get("value").getLong());
    }

    @Test
    public void testTapeGrowthAcrossSourceAndStateBackends() {
        String json = "[{\"id\":1,\"ok\":true},{\"id\":2,\"ok\":false}]";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        for (boolean nativeState : new boolean[]{false, true}) {
            assertTapeGrowth(
                MemorySegment.ofArray(bytes), nativeState);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment nativeSegment = arena.allocate(bytes.length);
                MemorySegment.copy(
                    MemorySegment.ofArray(bytes), 0,
                    nativeSegment, 0, bytes.length);
                assertTapeGrowth(nativeSegment, nativeState);
            }
        }
    }

    private static void assertTapeGrowth(
            MemorySegment segment, boolean nativeState) {
        JsonArray array = new SimdJson(1, nativeState)
            .parse(segment).getArray();
        assertEquals(2, array.size());
        assertEquals(1, array.get(0).getObject().get("id").getLong());
        assertTrue(array.get(0).getObject().get("ok").getBoolean());
        assertEquals(2, array.get(1).getObject().get("id").getLong());
        assertFalse(array.get(1).getObject().get("ok").getBoolean());
    }

    @Test
    public void testDomUnclosedStringError() {
        String json = "{\"invalid\": \"unclosed}";
        boolean caught = false;
        try {
            SimdJson.parseJson(json);
        } catch (SimdJsonException e) {
            caught = true;
            assertEquals(SimdJsonError.UNCLOSED_STRING, e.getError(), "Expected UNCLOSED_STRING error");
        }
        assertTrue(caught, "Should catch unclosed string exception");
    }

    @Test
    public void testStage2AcceptsCompleteJsonGrammar() {
        String[] valid = {
            "{}",
            "[]",
            " { \"a\" : [true, false, null, -0, 1.25e+3] } ",
            "{\"\":0,\"nested\":{\"array\":[{},[],[0]]}}",
            "[\n\t-12.5e-2,\r\n\"x\", {\"y\":true}\n]",
            "\"plain string\"",
            "\"escaped \\\" quote \\\\ slash \\/ tab \\t unicode \\u20ac\"",
            "\"raw UTF-8: € 😀\"",
            "0",
            "-0",
            "1234567890",
            "-0.125",
            "6.022e23",
            "1E-9",
            "0.0",
            "-123E+45",
            "true",
            "false",
            "null"
        };

        for (String json : valid) {
            assertDoesNotThrow(
                () -> new SimdJson(4, false).parse(json), json);
            assertDoesNotThrow(
                () -> new SimdJson(4, true).parse(json), json);
        }
    }

    @Test
    public void testStage2RejectsInvalidStructure() {
        String[] invalid = {
            "{\"a\":}",
            "{\"a\":1,}",
            "[1,]",
            "[,1]",
            "{\"a\" 1}",
            "{\"a\"::1}",
            "{,}",
            "{\"a\",\"b\":2}",
            "[1,,2]",
            "[1:2]",
            "[1}{2]",
            "null,",
            "\u000c[]",
            "{}[]",
            "{\"a\":1}{\"b\":2}",
            "]",
            "[}",
            "{]"
        };
        for (String json : invalid) {
            assertParseError(json, SimdJsonError.TAPE_ERROR);
        }
        assertParseError("{a:1}", SimdJsonError.STRING_ERROR);
        assertParseError("[1 2]", SimdJsonError.TAPE_ERROR);
        assertParseError("true false", SimdJsonError.TAPE_ERROR);
    }

    @Test
    public void testStage2RejectsInvalidLiteralsAndNumbers() {
        assertParseError("truth", SimdJsonError.T_OR_F_ERROR);
        assertParseError("truex", SimdJsonError.T_OR_F_ERROR);
        assertParseError("fals", SimdJsonError.T_OR_F_ERROR);
        assertParseError("nul", SimdJsonError.N_OR_V_ERROR);
        assertParseError("nullish", SimdJsonError.N_OR_V_ERROR);
        assertParseError("undefined", SimdJsonError.TAPE_ERROR);

        String[] invalidNumbers = {
            "01", "-", "1.", "1e", "1e+", "--1", "1a", "0x1",
            "00", "-01", "1.e2", "1e+-2"
        };
        for (String json : invalidNumbers) {
            assertParseError(json, SimdJsonError.NUMBER_ERROR);
        }
        assertParseError("1 2", SimdJsonError.TAPE_ERROR);
        assertParseError("+1", SimdJsonError.TAPE_ERROR);
        assertParseError(".1", SimdJsonError.TAPE_ERROR);
        assertParseError("NaN", SimdJsonError.TAPE_ERROR);
        assertParseError("Infinity", SimdJsonError.TAPE_ERROR);
    }

    @Test
    public void testStage2RejectsInvalidStringsAndUtf8() {
        assertParseError("\"bad\\xescape\"", SimdJsonError.STRING_ERROR);
        assertParseError("\"bad\\u12\"", SimdJsonError.STRING_ERROR);
        assertParseError(
            "\"two\" \"strings\"", SimdJsonError.TAPE_ERROR);
        assertParseError(
            "\"raw\nnewline\"", SimdJsonError.UNESCAPED_CHARS);
        assertParseError(
            "\"raw\ttab\"", SimdJsonError.UNESCAPED_CHARS);
        assertParseError("\"bad\\u12xz\"", SimdJsonError.STRING_ERROR);

        byte[] overlongUtf8 = {
            '"', (byte) 0xc0, (byte) 0xaf, '"'
        };
        assertParseError(overlongUtf8, SimdJsonError.UTF8_ERROR);

        assertParseError(
            new byte[] {'"', (byte) 0x80, '"'},
            SimdJsonError.UTF8_ERROR);
        assertParseError(
            new byte[] {'"', (byte) 0xed, (byte) 0xa0,
                (byte) 0x80, '"'},
            SimdJsonError.UTF8_ERROR);
        assertParseError(
            new byte[] {'"', (byte) 0xf4, (byte) 0x90,
                (byte) 0x80, (byte) 0x80, '"'},
            SimdJsonError.UTF8_ERROR);
        assertParseError(
            new byte[] {'"', (byte) 0xc2, '"'},
            SimdJsonError.UTF8_ERROR);
        assertParseError(
            new byte[] {'"', (byte) 0xe2, (byte) 0x82, '"'},
            SimdJsonError.UTF8_ERROR);
        assertParseError(
            new byte[] {'"', (byte) 0xf0, (byte) 0x9f,
                (byte) 0x98, '"'},
            SimdJsonError.UTF8_ERROR);
    }

    @Test
    public void testStage1Utf8AcrossVectorBoundaries() {
        for (int leadOffset : new int[] {31, 32, 63, 64}) {
            byte[] json = new byte[leadOffset + 4];
            json[0] = '"';
            java.util.Arrays.fill(json, 1, leadOffset, (byte) 'a');
            json[leadOffset] = (byte) 0xe2;
            json[leadOffset + 1] = (byte) 0x82;
            json[leadOffset + 2] = (byte) 0xac;
            json[leadOffset + 3] = '"';
            assertDoesNotThrow(() -> new SimdJson(json.length, false)
                .parse(json));

            byte[] invalid = json.clone();
            invalid[leadOffset + 1] = 'x';
            assertParseError(invalid, SimdJsonError.UTF8_ERROR);
        }
    }

    @Test
    public void testHeapFastPathUtf8AcrossBlockAndSliceBoundaries() {
        for (int leadOffset : new int[]{
                31, 32, 63, 64, 127, 128, 255, 256, 287, 288}) {
            byte[] json = new byte[385];
            java.util.Arrays.fill(json, (byte) 'a');
            json[0] = '"';
            json[leadOffset] = (byte) 0xe2;
            json[leadOffset + 1] = (byte) 0x82;
            json[leadOffset + 2] = (byte) 0xac;
            json[json.length - 1] = '"';
            assertDoesNotThrow(() -> new SimdJson(json.length, false)
                .parse(json));

            byte[] padded = new byte[json.length + 11];
            System.arraycopy(json, 0, padded, 7, json.length);
            assertDoesNotThrow(() -> new SimdJson(json.length, false)
                .parse(MemorySegment.ofArray(padded)
                    .asSlice(7, json.length)));

            byte[] invalid = json.clone();
            invalid[leadOffset + 1] = 'x';
            assertParseError(invalid, SimdJsonError.UTF8_ERROR);
        }

        byte[] truncated = new byte[385];
        java.util.Arrays.fill(truncated, (byte) 'a');
        truncated[0] = '"';
        truncated[381] = (byte) 0xf0;
        truncated[382] = (byte) 0x9f;
        truncated[383] = (byte) 0x98;
        truncated[384] = '"';
        assertParseError(truncated, SimdJsonError.UTF8_ERROR);
    }

    @Test
    public void testDomUnescapesUtf8AndEscapesTogether() {
        JsonObject object = SimdJson.parseJson(
            "{\"ключ\":\"€ \\n 😀 \\u20ac\"}").getObject();
        assertEquals("€ \n 😀 €", object.get("ключ").getString());
    }

    @Test
    public void testHeapFastStage2MatchesGenericNativeTape() {
        byte[] json = ("{\"emptyObject\":{},\"emptyArray\":[],"
            + "\"nested\":[{\"message\":\"line\\nvalue\","
            + "\"active\":true,\"missing\":null,"
            + "\"number\":-12.5e+3}],\"tail\":false}")
            .getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[json.length + 13];
        System.arraycopy(json, 0, padded, 7, json.length);
        MemorySegment source = MemorySegment.ofArray(padded)
            .asSlice(7, json.length);

        StructuralIndexes heapIndexes =
            new StructuralIndexes(json.length, false);
        StructuralIndexes nativeIndexes =
            new StructuralIndexes(json.length, true);
        Stage1Indexer indexer = new Stage1Indexer();
        assertEquals(
            SimdJsonError.SUCCESS,
            indexer.index(source, heapIndexes));
        assertEquals(
            SimdJsonError.SUCCESS,
            indexer.index(source, nativeIndexes));

        Tape heapTape = new Tape(json.length, false);
        Tape nativeTape = new Tape(json.length, true);
        Stage2Parser parser = new Stage2Parser();
        assertEquals(
            SimdJsonError.SUCCESS,
            parser.parse(source, heapIndexes, heapTape));
        assertEquals(
            SimdJsonError.SUCCESS,
            parser.parse(source, nativeIndexes, nativeTape));

        assertEquals(nativeTape.size(), heapTape.size());
        for (int i = 0; i < heapTape.size(); i++) {
            assertEquals(nativeTape.getRaw64(i), heapTape.getRaw64(i));
        }
    }

    @Test
    public void testStage2EnforcesDepthAndRecoversForReuse() {
        SimdJson parser = new SimdJson(4, false);
        String atLimit = "[".repeat(2048) + "]".repeat(2048);
        assertDoesNotThrow(() -> parser.parse(atLimit));

        String beyondLimit = "[".repeat(2049) + "]".repeat(2049);
        SimdJsonException error = assertThrows(
            SimdJsonException.class, () -> parser.parse(beyondLimit));
        assertEquals(SimdJsonError.DEPTH_ERROR, error.getError());

        JsonObject recovered = parser.parse("{\"ok\":true}").getObject();
        assertTrue(recovered.get("ok").getBoolean());
    }

    private static void assertParseError(
            String json, SimdJsonError expected) {
        for (boolean nativeStorage : new boolean[] {false, true}) {
            SimdJsonException error = assertThrows(
                SimdJsonException.class,
                () -> new SimdJson(4, nativeStorage).parse(json),
                json + " (nativeStorage=" + nativeStorage + ")");
            assertEquals(
                expected, error.getError(),
                json + " (nativeStorage=" + nativeStorage + ")");
        }
    }

    private static void assertParseError(
            byte[] json, SimdJsonError expected) {
        for (boolean nativeStorage : new boolean[] {false, true}) {
            SimdJsonException heapError = assertThrows(
                SimdJsonException.class,
                () -> new SimdJson(4, nativeStorage).parse(json));
            assertEquals(expected, heapError.getError());

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment nativeJson = arena.allocate(json.length);
                MemorySegment.copy(
                    MemorySegment.ofArray(json), 0,
                    nativeJson, 0, json.length);
                SimdJsonException nativeError = assertThrows(
                    SimdJsonException.class,
                    () -> new SimdJson(4, nativeStorage)
                        .parse(nativeJson));
                assertEquals(expected, nativeError.getError());
            }
        }
    }

    @Test
    public void testDomPrimitives() {
        JsonValue valTrue = SimdJson.parseJson("true");
        assertEquals(JsonType.BOOLEAN, valTrue.getType(), "True type");
        assertTrue(valTrue.getBoolean(), "True val");

        JsonValue valFalse = SimdJson.parseJson("false");
        assertEquals(JsonType.BOOLEAN, valFalse.getType(), "False type");
        assertFalse(valFalse.getBoolean(), "False val");

        JsonValue valNull = SimdJson.parseJson("null");
        assertEquals(JsonType.NULL, valNull.getType(), "Null type");
        assertTrue(valNull.isNull(), "Null check");

        JsonValue valNum = SimdJson.parseJson("-12345");
        assertEquals(JsonType.INT64, valNum.getType(), "Int type");
        assertEquals(-12345L, valNum.getLong());
    }

    @Test
    public void testNumberMaterializationDetectsOverflowAndKeepsPrecision() {
        for (boolean nativeSource : new boolean[] {false, true}) {
            for (boolean nativeState : new boolean[] {false, true}) {
                assertNumberMaterialization(nativeSource, nativeState);
            }
        }
    }

    private static void assertNumberMaterialization(
            boolean nativeSource, boolean nativeState) {
        String largeDecimal = "123456789012345678901.1234567890123456789";
        byte[] maxLong = "9223372036854775807"
            .getBytes(StandardCharsets.US_ASCII);
        byte[] minLong = "-9223372036854775808"
            .getBytes(StandardCharsets.US_ASCII);
        byte[] overflow = "9223372036854775808"
            .getBytes(StandardCharsets.US_ASCII);
        byte[] underflow = "-9223372036854775809"
            .getBytes(StandardCharsets.US_ASCII);
        byte[] decimal = largeDecimal.getBytes(StandardCharsets.US_ASCII);

        try (Arena arena = Arena.ofConfined()) {
            assertEquals(
                Long.MAX_VALUE,
                parseNumber(maxLong, nativeSource, nativeState, arena)
                    .getLong());
            assertEquals(
                Long.MIN_VALUE,
                parseNumber(minLong, nativeSource, nativeState, arena)
                    .getLong());

            for (byte[] value : new byte[][] {overflow, underflow}) {
                SimdJsonException error = assertThrows(
                    SimdJsonException.class,
                    () -> parseNumber(
                        value, nativeSource, nativeState, arena).getLong());
                assertEquals(
                    SimdJsonError.NUMBER_OUT_OF_RANGE, error.getError());
            }

            assertEquals(
                Double.parseDouble(largeDecimal),
                parseNumber(decimal, nativeSource, nativeState, arena)
                    .getDouble());
        }
    }

    private static JsonValue parseNumber(
            byte[] bytes, boolean nativeSource,
            boolean nativeState, Arena arena) {
        MemorySegment source;
        if (nativeSource) {
            source = arena.allocate(bytes.length);
            MemorySegment.copy(
                MemorySegment.ofArray(bytes), 0,
                source, 0, bytes.length);
        } else {
            source = MemorySegment.ofArray(bytes);
        }
        return new SimdJson(4, nativeState).parse(source);
    }

    @Test
    public void testValueMaterialization() {
        String json = "{\"count\": 99999, \"label\": \"test_label\"}";
        JsonValue root = SimdJson.parseJson(json);
        JsonObject obj = root.getObject();

        JsonValue countVal = obj.get("count");
        assertFalse(countVal.isMaterialized(), "Should start unmaterialized");

        // First read parses and caches
        long count1 = countVal.getLong();
        assertEquals(99999L, count1);
        assertTrue(countVal.isMaterialized(), "Should be materialized after getLong()");

        // Second read returns cached value
        long count2 = countVal.getLong();
        assertEquals(99999L, count2);

        JsonValue labelVal = obj.get("label");
        labelVal.materialize();
        assertTrue(labelVal.isMaterialized(), "Should be materialized after materialize()");
        assertEquals((Object) "test_label", labelVal.getString(), "label string");
    }
}
