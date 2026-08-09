// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import dev.cardigan.pico.PicoHTTPParser;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRequestDetachTest {

    @Test
    void detachesHttp1RequestWithoutReparsing() {
        byte[] bytes = (
            "POST /users HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: 4\r\n\r\n"
                + "body"
        ).getBytes(StandardCharsets.US_ASCII);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, segment, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, bytes.length);
            HttpRequest source = new HttpRequest();
            assertTrue(HttpRequestParser.parse(segment, bytes.length, source));

            HttpRequest detached = source.detachedCopy();
            source.init(MemorySegment.ofArray("GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));

            assertEquals("POST", detached.method().toString());
            assertEquals("/users", detached.path().toString());
            assertEquals("localhost", detached.getHeader("Host").toString());
            assertEquals("body", detached.body().toString());
            assertTrue(detached.isKeepAlive());
        }
    }

    @Test
    void detachesNormalizedHttp2RequestWithoutHttp1WireBytes() {
        byte[] bytes = "GET/users/423x-testvaluebody".getBytes(StandardCharsets.US_ASCII);
        HttpRequest source = new HttpRequest();
        source.initHttp2(MemorySegment.ofArray(bytes));
        source.picoRequest().methodOffset = 0;
        source.picoRequest().methodLen = 3;
        source.picoRequest().pathOffset = 3;
        source.picoRequest().pathLen = 10;
        source.picoRequest().numHeaders = 1;
        source.picoRequest().headers[0].nameOffset = 13;
        source.picoRequest().headers[0].nameLen = 6;
        source.picoRequest().headers[0].valueOffset = 19;
        source.picoRequest().headers[0].valueLen = 5;
        source.setBody(24, 4);

        HttpRequest detached = source.detachedCopy();

        assertEquals(2, detached.version());
        assertEquals("GET", detached.method().toString());
        assertEquals("/users/423", detached.path().toString());
        assertEquals("value", detached.getHeader("x-test").toString());
        assertEquals("body", detached.body().toString());
        assertTrue(detached.isKeepAlive());
    }

    @Test
    void detachesHttp1RequestParsedAfterAConsumedPipelinePrefix() {
        byte[] prefix = (
            "GET /users/1 HTTP/1.1\r\n"
                + "Host: localhost\r\n\r\n"
        ).getBytes(StandardCharsets.US_ASCII);
        byte[] request = (
            "POST /users?source=pipeline HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Length: 4\r\n\r\n"
                + "body"
        ).getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = new byte[prefix.length + request.length];
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        System.arraycopy(request, 0, bytes, prefix.length, request.length);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(bytes.length);
            MemorySegment.copy(
                bytes, 0, segment,
                java.lang.foreign.ValueLayout.JAVA_BYTE, 0, bytes.length);

            HttpRequest source = new HttpRequest();
            source.init(segment, prefix.length);
            long headerLength = PicoHTTPParser.parseRequest(
                segment, prefix.length, bytes.length,
                source.picoRequest(), 0);
            assertTrue(headerLength > 0);
            source.splitQuery();
            source.setBody(prefix.length + headerLength, 4);

            HttpRequest detached = source.detachedCopy();
            segment.fill((byte) 0);

            assertEquals("POST", detached.method().toString());
            assertEquals("/users", detached.path().toString());
            assertEquals("source=pipeline", detached.query().toString());
            assertEquals("localhost", detached.getHeader("Host").toString());
            assertEquals("body", detached.body().toString());
        }
    }
}
