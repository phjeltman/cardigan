// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

public class HttpRequestParserTest {

    @Test
    public void testSimpleRequest() {
        String raw = "GET /users/123 HTTP/1.1\r\n" +
                     "Host: localhost:8080\r\n" +
                     "User-Agent: curl/8.18.0\r\n" +
                     "Accept: */*\r\n\r\n";

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocateFrom(raw);
            HttpRequest request = new HttpRequest();
            
            boolean success = HttpRequestParser.parse(segment, (int) segment.byteSize() - 1, request); // exclude null terminator
            
            assertTrue(success, "Should successfully parse simple request");
            assertTrue(request.method().equalsString("GET"), "Method should be GET");
            assertTrue(request.path().equalsString("/users/123"), "Path should be /users/123");
            assertEquals(3, request.headerCount(), "Should have 3 headers");
            
            assertTrue(request.headerName(0).equalsString("Host"), "Header 0 should be Host");
            assertTrue(request.headerValue(0).equalsString("localhost:8080"), "Header 0 value mismatch");
            
            assertTrue(request.getHeader("User-Agent").equalsString("curl/8.18.0"), "User-Agent value mismatch");
            assertTrue(request.getHeader("Accept").equalsString("*/*"), "Accept value mismatch");
            assertNull(request.getHeader("Non-Existent"), "Non-existent header should return null");
            
            assertEquals(0, request.body().length(), "Body length should be 0");
        }
    }

    @Test
    public void testRequestWithBody() {
        String body = "{\"name\":\"Alice\",\"age\":30}";
        String raw = "POST /api/users HTTP/1.1\r\n" +
                     "Content-Type: application/json\r\n" +
                     "Content-Length: " + body.length() + "\r\n\r\n" +
                     body;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocateFrom(raw);
            HttpRequest request = new HttpRequest();
            
            boolean success = HttpRequestParser.parse(segment, (int) segment.byteSize() - 1, request);
            
            assertTrue(success, "Should successfully parse request with body");
            assertTrue(request.method().equalsString("POST"), "Method should be POST");
            assertTrue(request.path().equalsString("/api/users"), "Path should be /api/users");
            assertEquals(2, request.headerCount(), "Should have 2 headers");
            assertTrue(request.body().equalsString(body), "Body mismatch");
        }
    }

    @Test
    public void testIncompleteRequest() {
        String raw = "GET /index.html HTTP/1.1\r\n" +
                     "Host: localhost"; // missing trailing \r\n\r\n

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocateFrom(raw);
            HttpRequest request = new HttpRequest();
            
            boolean success = HttpRequestParser.parse(segment, (int) segment.byteSize() - 1, request);
            
            assertFalse(success, "Should fail to parse incomplete request");
        }
    }
}
