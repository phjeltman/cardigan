// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.HttpRequestParser;
import dev.cardigan.http.Response;
import dev.cardigan.http.Router;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.*;

public class MultithreadedRouteTest {

    @Test
    public void testScopedValuePropagationInParallelRoute() throws Throwable {
        Router router = new Router();
        router.registerController(new ParallelController());

        try (Arena arena = Arena.ofShared()) {
            String rawReq = "GET /p/99 HTTP/1.1\r\n" +
                            "Host: localhost\r\n" +
                            "X-Custom-Header: CardiganLoom\r\n" +
                            "X-Test-Id: 42\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(rawReq);

            HttpRequest request = new HttpRequest();
            boolean parsed = HttpRequestParser.parse(segment, (int) segment.byteSize(), request);
            assertTrue(parsed, "HTTP Request parsing failed");

            Response response = router.dispatch(request);

            assertEquals(200, response.statusCode(), "Status should be 200, got body: " + response.body());
            assertEquals("ID:99,H1:CardiganLoom,H2:42", response.body());
        }
    }

    @Test
    public void testSharedArenaOffHeapAccessAcrossVirtualThreads() throws Throwable {
        Router router = new Router();
        router.registerController(new ParallelController());

        try (Arena sharedArena = Arena.ofShared()) {
            String payloadStr = "A".repeat(20000);
            String rawReq = "POST /jumbo-parallel HTTP/1.1\r\n" +
                            "Host: localhost\r\n" +
                            "Content-Length: " + payloadStr.length() + "\r\n\r\n" +
                            payloadStr;
            MemorySegment segment = sharedArena.allocateFrom(rawReq);

            HttpRequest request = new HttpRequest();
            boolean parsed = HttpRequestParser.parse(segment, (int) segment.byteSize(), request);
            assertTrue(parsed, "HTTP Request parsing failed");

            Response response = router.dispatch(request);

            assertEquals(200, response.statusCode(), "Status should be 200, got body: " + response.body());
            assertEquals("Received 20000 bytes", response.body());
        }
    }

    @Test
    public void testSleepyRoute() throws Throwable {
        Router router = new Router();
        router.registerController(new TestController());

        try (Arena arena = Arena.ofShared()) {
            String rawReq = "GET /sleepy HTTP/1.1\r\nHost: localhost\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(rawReq);

            HttpRequest request = new HttpRequest();
            boolean parsed = HttpRequestParser.parse(segment, (int) segment.byteSize(), request);
            assertTrue(parsed, "HTTP Request parsing failed");

            long start = System.currentTimeMillis();
            Response response = router.dispatch(request);
            long duration = System.currentTimeMillis() - start;

            assertEquals(200, response.statusCode());
            assertEquals("Slept like a baby for 2000ms!", response.body());
            assertTrue(duration >= 1900, "Should take at least ~2000ms, took: " + duration);
        }
    }
}
