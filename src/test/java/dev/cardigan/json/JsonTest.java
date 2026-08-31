// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.json;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import dev.cardigan.http.Utf8Slice;

public class JsonTest {
    public record User(String name, int id, boolean active) {}

    public record FullTypesRecord(
        String title,
        int count,
        long bigId,
        float rating,
        double score,
        boolean enabled
    ) {}

    public record NestedRecord(
        String status,
        User userInfo
    ) {}

    public record UnicodeRecord(String text) {}

    @Test
    public void testJsonWriterAndReader() {
        User user = new User("Alice Smith", 427, true);
        
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(4096);
            
            // Serialize
            int bytesWritten = JsonWriter.writeRecord(segment, 0, user);
            
            assertTrue(bytesWritten > 0, "Should write positive number of bytes");
            
            byte[] rawBytes = segment.asSlice(0, bytesWritten).toArray(ValueLayout.JAVA_BYTE);
            String jsonStr = new String(rawBytes, StandardCharsets.UTF_8);
            
            // Expected JSON: {"name":"Alice Smith","id":427,"active":true}
            assertTrue(jsonStr.contains("\"name\":\"Alice Smith\""), "Serialized JSON should contain name");
            assertTrue(jsonStr.contains("\"id\":427"), "Serialized JSON should contain id");
            assertTrue(jsonStr.contains("\"active\":true"), "Serialized JSON should contain active flag");
            
            // Parse back using JsonReader
            Utf8Slice nameSlice = JsonReader.findString(segment, 0, bytesWritten, "name");
            assertNotNull(nameSlice, "Parsed name slice should not be null");
            assertTrue(nameSlice.equalsString("Alice Smith"), "Parsed name should match");
            
            int parsedId = JsonReader.findInt(segment, 0, bytesWritten, "id", -1);
            assertEquals(427, parsedId, "Parsed ID should match");
            
            boolean parsedActive = JsonReader.findBoolean(segment, 0, bytesWritten, "active", false);
            assertTrue(parsedActive, "Parsed active state should be true");

            JsonReader.IndexedObject indexed = JsonReader.indexObject(
                segment, 0, bytesWritten);
            assertEquals("Alice Smith", indexed.findString("name").toString());
            assertEquals(427, indexed.findInt("id", -1));
            assertTrue(indexed.findBoolean("active", false));
            assertEquals(-1, indexed.findInt("missing", -1));
        }
    }

    @Test
    void writerSizeMatchesEscapedUtf8Output() {
        UnicodeRecord record = new UnicodeRecord(
            "prefix € emoji 😀 newline\nquote\"");
        int expectedSize = JsonWriter.encodedRecordSize(record);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(expectedSize);
            int written = JsonWriter.writeRecord(output, 0, record);
            assertEquals(expectedSize, written);
            assertEquals(
                "{\"text\":\"prefix € emoji 😀 newline\\nquote\\\"\"}",
                new String(
                    output.toArray(ValueLayout.JAVA_BYTE),
                    StandardCharsets.UTF_8));
        }
    }

    @Test
    public void testNestedJsonReader() {
        String raw = "{\"user\":{\"id\":101,\"profile\":{\"roles\":[\"admin\",\"user\"]}},\"verified\":true}";
        
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocateFrom(raw);
            long length = segment.byteSize() - 1; // exclude null terminator
            
            // Extract the 'user' object boundary
            long userPacked = JsonReader.findValue(segment, 0, length, "user");
            assertNotEquals(-1, userPacked, "Should find 'user' object");
            
            long userOffset = userPacked >>> 32;
            long userLen = userPacked & 0xFFFFFFFFL;
            
            // Search inside the 'user' object boundary
            int id = JsonReader.findInt(segment, userOffset, userLen, "id", -1);
            assertEquals(101, id, "Nested ID should be 101");
            
            // Search at top level
            boolean verified = JsonReader.findBoolean(segment, 0, length, "verified", false);
            assertTrue(verified, "Verified should be true");
        }
    }

    @Test
    public void testFullTypesRecordScalarAndSIMD() {
        String smallJson = "{\"title\":\"Hello World\",\"count\":42,\"bigId\":987654321098765,\"rating\":4.5,\"score\":99.95,\"enabled\":true}";
        
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocateFrom(smallJson);
            long len = segment.byteSize() - 1;
            
            FullTypesRecord record = JsonReader.parseRecord(segment, 0, len, FullTypesRecord.class);
            assertNotNull(record, "Parsed record should not be null");
            assertEquals("Hello World", record.title());
            assertEquals(42, record.count());
            assertEquals(987654321098765L, record.bigId());
            assertEquals(4.5f, record.rating(), 0.001f);
            assertEquals(99.95, record.score(), 0.001);
            assertTrue(record.enabled());
        }

        // Payload > 128 bytes to test parseRecordSIMD path
        String largeJson = "{\"title\":\"Cardigan High Performance Java Framework Test Title with Extended Length Payload\",\"count\":1000000,\"bigId\":1234567890123456789,\"rating\":9.9,\"score\":100.0,\"enabled\":true}";
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocateFrom(largeJson);
            long len = segment.byteSize() - 1;
            
            FullTypesRecord record = JsonReader.parseRecord(segment, 0, len, FullTypesRecord.class);
            assertNotNull(record, "Parsed large SIMD record should not be null");
            assertEquals("Cardigan High Performance Java Framework Test Title with Extended Length Payload", record.title());
            assertEquals(1000000, record.count());
            assertEquals(1234567890123456789L, record.bigId());
            assertEquals(9.9f, record.rating(), 0.001f);
            assertEquals(100.0, record.score(), 0.001);
            assertTrue(record.enabled());
        }
    }

    @Test
    public void testStringUnescaping() {
        String jsonWithEscapes = "{\"name\":\"Hello\\nWorld\\t\\\"Quoted\\\" \\u0041\",\"id\":123,\"active\":false}";
        
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocateFrom(jsonWithEscapes);
            long len = segment.byteSize() - 1;
            
            User user = JsonReader.parseRecord(segment, 0, len, User.class);
            assertNotNull(user);
            assertEquals("Hello\nWorld\t\"Quoted\" A", user.name());
            assertEquals(123, user.id());
            assertFalse(user.active());
        }
    }

    @Test
    public void testNestedRecordParsing() {
        String nestedJson = "{\"status\":\"OK\",\"userInfo\":{\"name\":\"Bob Marley\",\"id\":777,\"active\":true}}";
        
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocateFrom(nestedJson);
            long len = segment.byteSize() - 1;
            
            NestedRecord record = JsonReader.parseRecord(segment, 0, len, NestedRecord.class);
            assertNotNull(record);
            assertEquals("OK", record.status());
            assertNotNull(record.userInfo());
            assertEquals("Bob Marley", record.userInfo().name());
            assertEquals(777, record.userInfo().id());
            assertTrue(record.userInfo().active());
        }
    }
}
