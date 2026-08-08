// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.httparena;

import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.HttpRequestParser;
import dev.cardigan.http.PreparedInvocation;
import dev.cardigan.http.Response;
import dev.cardigan.http.Router;
import dev.cardigan.http.StreamingBody;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class HttpArenaGrpcControllerTest {
    private final Router router = createRouter();

    @Test
    void returnsCanonicalUnaryReply() {
        Response response = invoke(
            "/benchmark.BenchmarkService/GetSum",
            request(1, 2, null));

        assertGrpcStatus(response, "0");
        assertEquals(List.of(3), decodeReplies(response));
    }

    @Test
    void streamsExactlyTheRequestedSequenceAcrossSmallReads() {
        Response response = invoke(
            "/benchmark.BenchmarkService/StreamSum",
            request(13, 42, 10));

        assertGrpcStatus(response, "0");
        assertEquals(
            List.of(55, 56, 57, 58, 59, 60, 61, 62, 63, 64),
            decodeReplies(response)
        );
    }

    @Test
    void acceptsTheArenaLargeCountAndCompletesAtExpectedValue() {
        Response response = invoke(
            "/benchmark.BenchmarkService/StreamSum",
            request(13, 42, 5_000));

        List<Integer> replies = decodeReplies(response);
        assertGrpcStatus(response, "0");
        assertEquals(5_000, replies.size());
        assertEquals(55, replies.getFirst());
        assertEquals(5_054, replies.getLast());
    }

    @Test
    void emitsAnEmptySuccessfulStreamForZeroCount() {
        Response response = invoke(
            "/benchmark.BenchmarkService/StreamSum",
            request(13, 42, 0));

        assertGrpcStatus(response, "0");
        assertEquals(List.of(), decodeReplies(response));
    }

    @Test
    void encodesNegativeInt32RepliesAsTenByteVarints() {
        Response response = invoke(
            "/benchmark.BenchmarkService/GetSum",
            request(-2, 1, null));

        assertGrpcStatus(response, "0");
        assertEquals(List.of(-1), decodeReplies(response));
    }

    @Test
    void rejectsMalformedGrpcEnvelopeWithInvalidArgumentStatus() {
        byte[] malformed = {0, 0, 0, 0, 9, 8, 1};
        Response response = invoke(
            "/benchmark.BenchmarkService/GetSum", malformed);

        assertGrpcStatus(response, "3");
        assertEquals(null, response.body());
    }

    private static byte[] request(int a, int b, Integer count) {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        writeVarintField(message, 1, a);
        writeVarintField(message, 2, b);
        if (count != null) {
            writeVarintField(message, 3, count);
        }
        byte[] protobuf = message.toByteArray();
        byte[] frame = new byte[5 + protobuf.length];
        frame[1] = (byte) (protobuf.length >>> 24);
        frame[2] = (byte) (protobuf.length >>> 16);
        frame[3] = (byte) (protobuf.length >>> 8);
        frame[4] = (byte) protobuf.length;
        System.arraycopy(protobuf, 0, frame, 5, protobuf.length);

        return frame;
    }

    private Response invoke(String path, byte[] body) {
        byte[] headers = (
            "POST " + path + " HTTP/1.1\r\n"
                + "Content-Type: application/grpc\r\n"
                + "Content-Length: " + body.length + "\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        byte[] wire = new byte[headers.length + body.length];
        System.arraycopy(headers, 0, wire, 0, headers.length);
        System.arraycopy(body, 0, wire, headers.length, body.length);

        PreparedInvocation invocation;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(wire.length + 16L);
            MemorySegment.copy(
                wire, 0, segment, ValueLayout.JAVA_BYTE, 0, wire.length);
            HttpRequest request = new HttpRequest();
            assertEquals(true, HttpRequestParser.parse(
                segment, wire.length, request));
            int[] materializations = {0};
            invocation = router.prepare(
                request,
                new PreparedInvocation(),
                () -> materializations[0]++
            );
            assertEquals(0, materializations[0]);
        }
        // Only the decoded primitive crosses the handover boundary.
        return invocation.invoke();
    }

    private static Router createRouter() {
        Router router = new Router();
        router.registerController(new HttpArenaGrpcController());
        return router;
    }

    private static void writeVarintField(
        ByteArrayOutputStream output,
        int field,
        int value
    ) {
        output.write(field << 3);
        long remaining = value;
        while ((remaining & ~0x7fL) != 0) {
            output.write((int) ((remaining & 0x7f) | 0x80));
            remaining >>>= 7;
        }
        output.write((int) remaining);
    }

    private static List<Integer> decodeReplies(Response response) {
        if (response.body() == null) {
            return List.of();
        }
        StreamingBody body = assertInstanceOf(
            StreamingBody.class, response.body());
        byte[] encoded = new byte[body.length()];
        MemorySegment destination = MemorySegment.ofArray(encoded);
        int offset = 0;
        while (offset < encoded.length) {
            int chunk = Math.min(3, encoded.length - offset);
            int produced = body.read(destination.asSlice(offset, chunk));
            if (produced < 0) {
                break;
            }
            offset += produced;
        }
        assertEquals(encoded.length, offset);

        List<Integer> replies = new ArrayList<>();
        int cursor = 0;
        while (cursor < encoded.length) {
            assertEquals(0, encoded[cursor++] & 0xff);
            int messageLength = (encoded[cursor++] & 0xff) << 24
                | (encoded[cursor++] & 0xff) << 16
                | (encoded[cursor++] & 0xff) << 8
                | encoded[cursor++] & 0xff;
            int messageEnd = cursor + messageLength;
            assertEquals(8, encoded[cursor++] & 0xff);
            long value = 0;
            int shift = 0;
            int current;
            do {
                current = encoded[cursor++] & 0xff;
                value |= (long) (current & 0x7f) << shift;
                shift += 7;
            } while ((current & 0x80) != 0);
            assertEquals(messageEnd, cursor);
            replies.add((int) value);
        }
        return replies;
    }

    private static void assertGrpcStatus(
        Response response,
        String expected
    ) {
        assertEquals(200, response.statusCode());
        assertEquals("application/grpc", response.contentType());
        assertEquals(1, response.trailers().size());
        assertEquals("grpc-status", response.trailers().name(0));
        assertEquals(expected, response.trailers().value(0));
    }
}
