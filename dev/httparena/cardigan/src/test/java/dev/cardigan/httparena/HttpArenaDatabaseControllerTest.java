// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.httparena;

import dev.cardigan.http.EncodedBody;
import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.HttpRequestParser;
import dev.cardigan.http.Response;
import dev.cardigan.http.Router;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class HttpArenaDatabaseControllerTest {
    @Test
    void routerAppliesDefaults() throws Exception {
        int[] captured = new int[3];
        HttpArenaDatabaseController controller =
            new HttpArenaDatabaseController((minimum, maximum, limit) -> {
                captured[0] = minimum;
                captured[1] = maximum;
                captured[2] = limit;
                return HttpArenaDatabaseResult.empty();
            });
        Router router = new Router();
        router.registerController(controller);

        Response response = router.prepare(request("/async-db")).invoke();

        assertArrayEquals(new int[] {10, 50, 50}, captured);
        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.contentType());
        assertInstanceOf(EncodedBody.class, response.body());
    }

    @Test
    void clampsLimitsAtBothBounds() {
        int[] captured = new int[1];
        HttpArenaDatabaseController controller =
            new HttpArenaDatabaseController((minimum, maximum, limit) -> {
                captured[0] = limit;
                return HttpArenaDatabaseResult.empty();
            });

        controller.asyncDatabase(request(
            "/async-db?min=5&max=80&limit=0"));
        assertEquals(1, captured[0]);
        controller.asyncDatabase(request(
            "/async-db?min=5&max=80&limit=99"));
        assertEquals(50, captured[0]);
    }

    @Test
    void returnsTheEmptyJsonShapeWhenPostgresIsUnavailable() {
        HttpArenaDatabaseController controller =
            new HttpArenaDatabaseController((minimum, maximum, limit) -> {
                throw new IllegalStateException("offline");
            });

        Response response = controller.asyncDatabase(request(
            "/async-db?min=10&max=50&limit=5"));

        EncodedBody body = assertInstanceOf(
            EncodedBody.class, response.body());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment destination = arena.allocate(body.length());
            body.write(destination);
            assertEquals(
                "{\"items\":[],\"count\":0}",
                new String(
                    destination.toArray(ValueLayout.JAVA_BYTE),
                    StandardCharsets.UTF_8));
        }
    }

    private static HttpRequest request(String target) {
        byte[] wire = ("GET " + target
            + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        MemorySegment segment = Arena.global().allocate(wire.length);
        MemorySegment.copy(
            wire, 0, segment, ValueLayout.JAVA_BYTE, 0, wire.length);
        HttpRequest request = new HttpRequest();
        assertEquals(true, HttpRequestParser.parse(
            segment, wire.length, request));
        return request;
    }
}
