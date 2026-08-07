// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.httparena;

import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.HttpRequestParser;
import dev.cardigan.http.RequestBody;
import dev.cardigan.http.Response;
import dev.cardigan.http.Router;
import dev.cardigan.http.PreparedInvocation;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpArenaControllerTest {
    @Test
    void routesQuerySeparatelyFromPath() {
        byte[] wire = (
            "GET /baseline11?a=13&b=42 HTTP/1.1\r\n"
                + "Host: localhost\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        HttpRequest request = new HttpRequest();
        MemorySegment segment = Arena.global().allocate(wire.length);
        MemorySegment.copy(
            wire, 0, segment, ValueLayout.JAVA_BYTE, 0, wire.length);
        assertTrue(HttpRequestParser.parse(segment, wire.length, request));

        assertEquals("/baseline11", request.path().toString());
        assertEquals(
            "/baseline11?a=13&b=42",
            request.requestTarget().toString());
        assertEquals("a=13&b=42", request.query().toString());

        Router router = new Router();
        router.registerController(new HttpArenaController());
        int[] materializations = {0};
        PreparedInvocation invocation = router.prepare(
            request, new PreparedInvocation(),
            () -> materializations[0]++);
        Response response = invocation.invoke();
        assertEquals(200, response.statusCode());
        assertEquals("55", response.body());
        assertEquals(0, materializations[0]);
    }

    @Test
    void combinesQueryWithStreamingPostBody() {
        byte[] wire = (
            "POST /baseline11?a=13&b=42 HTTP/1.1\r\n"
                + "Content-Length: 2\r\n\r\n20")
            .getBytes(StandardCharsets.US_ASCII);
        HttpRequest request = new HttpRequest();
        MemorySegment segment = Arena.global().allocate(wire.length);
        MemorySegment.copy(
            wire, 0, segment, ValueLayout.JAVA_BYTE, 0, wire.length);
        assertTrue(HttpRequestParser.parse(segment, wire.length, request));
        request.setBodyStream(new ArrayBody("20"));

        Router router = new Router();
        router.registerController(new HttpArenaController());
        assertEquals(Router.BODY_STREAMING, router.streamingBodyMode(request));
        Response response = router.dispatch(request);
        assertEquals("75", response.body());
    }

    private static final class ArrayBody implements RequestBody {
        private final byte[] bytes;
        private boolean read;

        ArrayBody(String value) {
            bytes = value.getBytes(StandardCharsets.US_ASCII);
        }

        @Override public long length() { return bytes.length; }
        @Override public long remaining() { return read ? 0 : bytes.length; }

        @Override
        public int read(MemorySegment destination) {
            if (read) return -1;
            MemorySegment.copy(
                MemorySegment.ofArray(bytes), 0,
                destination, 0, bytes.length);
            read = true;
            return bytes.length;
        }

        @Override public void close() { read = true; }
    }
}
