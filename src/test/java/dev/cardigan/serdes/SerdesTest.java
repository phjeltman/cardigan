// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.serdes;

import dev.cardigan.http.Utf8Slice;
import dev.cardigan.simdjson.SimdJsonError;
import dev.cardigan.simdjson.SimdJsonException;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SerdesTest {

    // Test Record definitions
    public record SimpleUser(long id, String name, boolean active, double score) {}
    public record ZeroCopyUser(long id, Utf8Slice name, int age) {}
    public record Address(String city, String zip) {}
    public record PersonWithAddress(String name, Address address) {}

    // Custom class for non-record testing
    public static class Point {
        public double x;
        public double y;

        public Point(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    @Test
    public void testSimpleRecordDeserialization() {
        String json = "{\"id\":1001,\"name\":\"Alice\",\"active\":true,\"score\":98.5}";
        SimpleUser user = Serdes.fromJson(json, SimpleUser.class);

        assertNotNull(user);
        assertEquals(1001L, user.id());
        assertEquals("Alice", user.name());
        assertTrue(user.active());
        assertEquals(98.5, user.score(), 0.001);
    }

    @Test
    public void testRejectsUnclosedStringBeforeRecordMaterialization() {
        assertThrows(
            SimdJsonException.class,
            () -> Serdes.fromJson(
                "{\"id\":1001,\"name\":\"Alice}",
                SimpleUser.class));
    }

    @Test
    public void testRejectsMalformedJsonBeforeDirectRecordMaterialization() {
        String[] malformed = {
            "{\"id\":01,\"name\":\"Alice\",\"active\":true,\"score\":1}",
            "{\"id\":1,\"name\":\"Alice\",\"active\":truth,\"score\":1}",
            "{\"id\":1,\"name\":\"bad\\x\",\"active\":true,\"score\":1}",
            "{\"id\":1,\"name\":\"Alice\",\"active\":true,\"score\":1,}",
            "{\"id\":1 \"name\":\"Alice\",\"active\":true,\"score\":1}",
            "{\"id\":1,\"name\":\"Alice\",\"active\":true,\"score\":}"
        };
        for (String json : malformed) {
            assertThrows(
                SimdJsonException.class,
                () -> Serdes.fromJson(json, SimpleUser.class), json);
        }
    }

    @Test
    public void testRejectsMalformedJsonBeforeOnDemandAccess() {
        assertThrows(
            SimdJsonException.class,
            () -> Serdes.parseOnDemand("{\"accepted\":true false}"));
        assertThrows(
            SimdJsonException.class,
            () -> Serdes.fromJson("[1,]", String.class));
    }

    @Test
    public void testZeroCopyRecordDeserialization() {
        String json = "{\"id\":42,\"name\":\"Bob\",\"age\":30}";
        MemorySegment seg = MemorySegment.ofArray(json.getBytes(StandardCharsets.UTF_8));

        ZeroCopyUser user = Serdes.fromJson(seg, ZeroCopyUser.class);

        assertNotNull(user);
        assertEquals(42L, user.id());
        assertNotNull(user.name());
        assertEquals("Bob", user.name().toString());
        assertEquals(30, user.age());
    }

    @Test
    public void testNestedRecordSerdes() {
        PersonWithAddress person = new PersonWithAddress("Charlie", new Address("Stockholm", "11122"));
        String json = Serdes.toJson(person);

        assertTrue(json.contains("\"name\":\"Charlie\""));
        assertTrue(json.contains("\"city\":\"Stockholm\""));

        PersonWithAddress deserialized = Serdes.fromJson(json, PersonWithAddress.class);
        assertNotNull(deserialized);
        assertEquals("Charlie", deserialized.name());
        assertNotNull(deserialized.address());
        assertEquals("Stockholm", deserialized.address().city());
        assertEquals("11122", deserialized.address().zip());
    }

    @Test
    public void testCustomVisitorDeserializer() {
        String json = "{\"x\":12.34,\"y\":56.78,\"ignoredField\":\"skipMe\"}";

        Deserializer<Point> pointDeserializer = de -> de.visitStruct(new StructVisitor<Point>() {
            private double x;
            private double y;

            @Override
            public void visitField(long keyOffset, int keyLen, SimdJsonDeserializer de) {
                if (de.keyEquals(keyOffset, keyLen, "x")) {
                    x = de.readDouble();
                } else if (de.keyEquals(keyOffset, keyLen, "y")) {
                    y = de.readDouble();
                } else {
                    de.skipValue();
                }
            }

            @Override
            public Point build() {
                return new Point(x, y);
            }
        });

        Point pt = Serdes.fromJson(json, pointDeserializer);
        assertNotNull(pt);
        assertEquals(12.34, pt.x, 0.001);
        assertEquals(56.78, pt.y, 0.001);
    }

    @Test
    public void testSeqVisitorDeserializer() {
        String json = "[10, 20, 30, 40]";

        Deserializer<List<Integer>> listDeserializer = de -> de.visitSeq(new SeqVisitor<List<Integer>>() {
            private final List<Integer> list = new ArrayList<>();

            @Override
            public void visitElement(int index, SimdJsonDeserializer de) {
                list.add(de.readInt());
            }

            @Override
            public List<Integer> build() {
                return list;
            }
        });

        List<Integer> result = Serdes.fromJson(json, listDeserializer);
        assertNotNull(result);
        assertEquals(4, result.size());
        assertEquals(10, result.get(0));
        assertEquals(20, result.get(1));
        assertEquals(30, result.get(2));
        assertEquals(40, result.get(3));
    }

    @Test
    public void testCustomDeserializerUsesHeapSliceOffset() {
        byte[] bytes = "prefix:2147483647:suffix"
            .getBytes(StandardCharsets.UTF_8);
        MemorySegment segment = MemorySegment.ofArray(bytes);

        int value = Serdes.fromJson(
            segment, "prefix:".length(), "2147483647".length(),
            SimdJsonDeserializer::readInt);

        assertEquals(Integer.MAX_VALUE, value);
    }

    @Test
    public void testCustomSerializer() {
        Point pt = new Point(3.14, 2.71);
        Serialize<Point> pointSerializer = (val, ser) -> {
            ser.startObject();
            ser.writeKey("x");
            ser.writeDouble(val.x);
            ser.writeComma();
            ser.writeKey("y");
            ser.writeDouble(val.y);
            ser.endObject();
        };

        byte[] buf = new byte[256];
        MemorySegment seg = MemorySegment.ofArray(buf);
        long written = Serdes.toJson(seg, pt, pointSerializer);

        String json = new String(buf, 0, (int) written, StandardCharsets.UTF_8);
        assertEquals("{\"x\":3.14,\"y\":2.71}", json);
    }

    @Test
    public void testRecordSerialization() {
        SimpleUser user = new SimpleUser(99L, "Dave", false, 4.5);
        String json = Serdes.toJson(user);

        assertTrue(json.contains("\"id\":99"));
        assertTrue(json.contains("\"name\":\"Dave\""));
        assertTrue(json.contains("\"active\":false"));
    }

    public record NonLastNested(Address address, String name, int score) {}
    public record EmptyRecord() {}

    @Test
    public void testNestedRecordAsNonLastField() {
        String json = "{\"address\":{\"city\":\"Gothenburg\",\"zip\":\"41101\"},\"name\":\"Emma\",\"score\":95}";
        NonLastNested obj = Serdes.fromJson(json, NonLastNested.class);

        assertNotNull(obj);
        assertNotNull(obj.address());
        assertEquals("Gothenburg", obj.address().city());
        assertEquals("41101", obj.address().zip());
        assertEquals("Emma", obj.name());
        assertEquals(95, obj.score());
    }

    @Test
    public void testEmptyRecordSerdes() {
        EmptyRecord empty = new EmptyRecord();
        String json = Serdes.toJson(empty);
        assertEquals("{}", json);

        EmptyRecord deserialized = Serdes.fromJson(json, EmptyRecord.class);
        assertNotNull(deserialized);
    }

    @Test
    public void testControlCharacterEscaping() {
        SimpleUser user = new SimpleUser(1L, "Line1\nLine2\tTabbed", true, 1.0);
        String json = Serdes.toJson(user);
        assertTrue(json.contains("Line1\\nLine2\\tTabbed"));

        SimpleUser deserialized = Serdes.fromJson(json, SimpleUser.class);
        assertEquals("Line1\nLine2\tTabbed", deserialized.name());
    }

    @Test
    public void testEscapedStringPreservesRawUtf8() {
        String json = "{\"id\":1,\"name\":\"Euro €\\nEmoji 😀 \\u20ac\"," +
            "\"active\":true,\"score\":1}";

        SimpleUser deserialized = Serdes.fromJson(json, SimpleUser.class);

        assertEquals("Euro €\nEmoji 😀 €", deserialized.name());
    }

    @Test
    public void testIntNarrowingRejectsOverflow() {
        SimdJsonException overflow = assertThrows(
            SimdJsonException.class,
            () -> Serdes.fromJson(
                "{\"id\":42,\"name\":\"Bob\",\"age\":2147483648}",
                ZeroCopyUser.class));

        assertEquals(SimdJsonError.NUMBER_OUT_OF_RANGE, overflow.getError());
    }

    public record DoubleRecord(double val1, double val2) {}

    @Test
    public void testScientificNotationAndDoublePrecision() {
        String json = "{\"val1\":1.23e-4,\"val2\":-5.67e8}";
        DoubleRecord rec = Serdes.fromJson(json, DoubleRecord.class);

        assertNotNull(rec);
        assertEquals(1.23e-4, rec.val1(), 1e-9);
        assertEquals(-5.67e8, rec.val2(), 1.0);
    }

    @Test
    public void testDenseJsonIndexing() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < 150; i++) {
            if (i > 0) sb.append(",");
            sb.append(i);
        }
        sb.append("]");
        String json = sb.toString();

        Deserializer<List<Integer>> listDeserializer = de -> de.visitSeq(new SeqVisitor<List<Integer>>() {
            private final List<Integer> list = new ArrayList<>();
            @Override
            public void visitElement(int index, SimdJsonDeserializer de) {
                list.add(de.readInt());
            }
            @Override
            public List<Integer> build() {
                return list;
            }
        });

        List<Integer> list = Serdes.fromJson(json, listDeserializer);
        assertEquals(150, list.size());
        assertEquals(0, list.get(0));
        assertEquals(149, list.get(149));
    }

    public record LargePayloadRecord(String bigData) {}

    @Test
    public void testLargePayloadSerializationResizing() {
        String data = "A".repeat(10000);
        LargePayloadRecord rec = new LargePayloadRecord(data);
        byte[] bytes = Serdes.toJsonBytes(rec);
        assertTrue(bytes.length > 10000);

        LargePayloadRecord deserialized = Serdes.fromJson(bytes, LargePayloadRecord.class);
        assertEquals(data, deserialized.bigData());
    }

    @Test
    public void testSerdesValueAndString() {
        String json = "{\"name\":\"Cardigan\",\"version\":1}";
        dev.cardigan.simdjson.ondemand.Value val = Serdes.fromJson(json, dev.cardigan.simdjson.ondemand.Value.class);
        assertNotNull(val);
        assertEquals("Cardigan", val.get("name").getString());

        String rawString = Serdes.fromJson(json, String.class);
        assertEquals(json, rawString);

        String serializedVal = Serdes.toJson(val);
        assertTrue(serializedVal.contains("Cardigan"));
    }
}
