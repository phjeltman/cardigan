// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.benchmark;

import dev.cardigan.core.Http2ResponseBenchmark;
import dev.cardigan.http.Get;
import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.HttpRequestParser;
import dev.cardigan.http.Post;
import dev.cardigan.http.PreparedInvocation;
import dev.cardigan.http.Response;
import dev.cardigan.http.Router;
import dev.cardigan.http2.HpackDecoder;
import dev.cardigan.http2.HpackFields;
import dev.cardigan.http2.HpackHuffmanBenchmark;
import dev.cardigan.http2.Http2Frames;
import dev.cardigan.http2.Http2RequestParser;
import dev.cardigan.json.JsonWriter;
import dev.cardigan.serdes.Serdes;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** Microbenchmarks for code paths used by the Cardigan runtime. */
public final class BenchmarkSuite {
    private static final int WARMUP_SECONDS = 2;
    private static final int RUN_SECONDS = 3;
    private static final int BATCH_SIZE = 1_024;
    private static final int FRAME_VARIANTS = 256;
    private static final int FRAME_VARIANT_MASK = FRAME_VARIANTS - 1;
    private static final int MAX_HEADER_LIST_SIZE = 16 * 1_024;

    private static volatile long blackhole;

    private static final byte[] HTTP2_LITERAL_BLOCK = hex(
        "82048762d416c430d09986418a089d5c0b8170dc780f03"
            + "7a8825b650c3abbc15c153032a2f2a"
    );
    private static final byte[] HTTP2_PRIME_BLOCK = hex(
        "82448762d416c430d09986418a089d5c0b8170dc780f03"
            + "7a8825b650c3abbc15c153032a2f2a"
    );
    private static final byte[] HTTP2_INDEXED_BLOCK = hex("82c186c0bfbe");

    private static final String SMALL_REQUEST =
        "GET /users/123 HTTP/1.1\r\n"
            + "Host: localhost:8080\r\n"
            + "Accept: */*\r\n\r\n";

    private static final String MEDIUM_REQUEST =
        "POST /api/v1/users/427/profile/settings HTTP/1.1\r\n"
            + "Host: api.example.com\r\n"
            + "User-Agent: Mozilla/5.0 (X11; Linux x86_64) "
            + "AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36\r\n"
            + "Accept: application/json, text/plain, */*\r\n"
            + "Accept-Language: en-US,en;q=0.9,sv;q=0.8\r\n"
            + "Accept-Encoding: gzip, deflate, br\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: 0\r\n"
            + "Connection: keep-alive\r\n\r\n";

    private static final String LARGE_REQUEST =
        "POST /api/v2/analytics/telemetry/batch?session_id=9876543210"
            + "&source=dashboard_v3 HTTP/1.1\r\n"
            + "Host: telemetry.enterprise-platform.internal:8443\r\n"
            + "Authorization: Bearer abcdef0123456789abcdef0123456789\r\n"
            + "Cookie: session=abcdef1234567890; theme=dark; "
            + "feature_flags=vector_search,realtime\r\n"
            + "User-Agent: Mozilla/5.0 (X11; Linux x86_64) "
            + "AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36\r\n"
            + "Accept: application/json, text/event-stream, */*\r\n"
            + "Accept-Language: en-US,en;q=0.9,sv;q=0.8,de;q=0.7\r\n"
            + "Accept-Encoding: gzip, deflate, br, zstd\r\n"
            + "Content-Type: application/json; charset=utf-8\r\n"
            + "Content-Length: 0\r\n"
            + "Origin: https://dashboard.enterprise-platform.internal\r\n"
            + "X-Request-ID: req_1234567890abcdef\r\n"
            + "X-Forwarded-For: 203.0.113.195, 70.41.3.18\r\n"
            + "X-Forwarded-Proto: https\r\n"
            + "Cache-Control: no-cache, no-store\r\n"
            + "Connection: keep-alive\r\n\r\n";

    public record User(String name, int id, boolean active) {
    }

    public static final class TestController {
        @Get("/users/{id}")
        public Response getUser(long id) {
            return Response.text("User: " + id);
        }

        @Post("/users")
        public Response createUser(User user) {
            return Response.json(user);
        }

        @Get("/api/v1/users/{id}")
        public Response getV1User(long id) {
            return Response.text("V1 User: " + id);
        }

        @Get("/api/v2/analytics/telemetry/sessions/{id}")
        public Response getSessionTelemetry(long id) {
            return Response.text("Telemetry: " + id);
        }
    }

    private BenchmarkSuite() {
    }

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println("     CARDIGAN PRODUCTION-PATH MICROBENCHMARKS    ");
        System.out.println("=================================================");

        if (args.length == 1) {
            switch (args[0]) {
                case "--request-parsers" -> runRequestParserBenchmarks();
                case "--hpack-huffman" -> HpackHuffmanBenchmark.run();
                case "--http2-response" -> Http2ResponseBenchmark.run();
                case "--http1-chunked" -> Http1ChunkedBenchmark.run();
                default -> throw usage();
            }
            return;
        }
        if (args.length != 0) {
            throw usage();
        }

        runRoutingBenchmarks();
        runRequestParserBenchmarks();
        HpackHuffmanBenchmark.run();
        Http2ResponseBenchmark.run();
        Http1ChunkedBenchmark.run();
        runJsonBenchmarks();
        System.out.println(
            "\nAll production-path microbenchmarks completed successfully.");
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
            "Usage: BenchmarkSuite "
                + "[--request-parsers|--hpack-huffman|--http2-response"
                + "|--http1-chunked]");
    }

    private static void runRoutingBenchmarks() {
        Router router = new Router();
        router.registerController(new TestController());
        runRoutingBenchmark(
            "GET /users/427",
            "GET /users/427 HTTP/1.1\r\nHost: localhost\r\n\r\n",
            router);
        runRoutingBenchmark(
            "GET /api/v1/users/427",
            "GET /api/v1/users/427 HTTP/1.1\r\nHost: localhost\r\n\r\n",
            router);
        runRoutingBenchmark(
            "GET /api/v2/analytics/telemetry/sessions/98765",
            "GET /api/v2/analytics/telemetry/sessions/98765 HTTP/1.1\r\n"
                + "Host: localhost\r\n\r\n",
            router);
    }

    private static void runRoutingBenchmark(
            String label, String encoded, Router router) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocateFrom(encoded);
            int length = (int) input.byteSize() - 1;
            HttpRequest request = new HttpRequest();
            PreparedInvocation invocation = new PreparedInvocation();
            measure("HTTP/1 parse + route preparation, " + label, () -> {
                if (!HttpRequestParser.parse(input, length, request)) {
                    return -1;
                }
                router.prepare(request, invocation);
                return invocation.isSafe()
                    ? request.picoRequest().pathLen : -1;
            });
        }
    }

    private static void runRequestParserBenchmarks() {
        System.out.println("\n--- Request parsing ---");
        runHttp1ParserBenchmark("HTTP/1 small request", SMALL_REQUEST);
        runHttp1ParserBenchmark("HTTP/1 medium request", MEDIUM_REQUEST);
        runHttp1ParserBenchmark("HTTP/1 large request", LARGE_REQUEST);
        runHttp2RequestBenchmarks();
    }

    private static void runHttp1ParserBenchmark(
            String label, String encoded) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocateFrom(encoded);
            int length = (int) input.byteSize() - 1;
            HttpRequest request = new HttpRequest();
            measure(label, () -> {
                if (!HttpRequestParser.parse(input, length, request)) {
                    return -1;
                }
                return request.picoRequest().pathLen + request.headerCount();
            });
        }
    }

    private static void runHttp2RequestBenchmarks() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment literalBlock = copyToArena(arena, HTTP2_LITERAL_BLOCK);
            MemorySegment primeBlock = copyToArena(arena, HTTP2_PRIME_BLOCK);
            MemorySegment indexedBlock = copyToArena(arena, HTTP2_INDEXED_BLOCK);
            MemorySegment literalFrames = createHeadersFrames(
                arena, literalBlock, HTTP2_LITERAL_BLOCK.length);
            MemorySegment indexedFrames = createHeadersFrames(
                arena, indexedBlock, HTTP2_INDEXED_BLOCK.length);
            int literalStride =
                Http2Frames.HEADER_SIZE + HTTP2_LITERAL_BLOCK.length;
            int indexedStride =
                Http2Frames.HEADER_SIZE + HTTP2_INDEXED_BLOCK.length;
            MemorySegment decodedHeaders = arena.allocate(MAX_HEADER_LIST_SIZE);

            HpackDecoder literalDecoder =
                new HpackDecoder(4_096, MAX_HEADER_LIST_SIZE);
            HpackFields literalFields = new HpackFields(64);
            HpackDecoder indexedDecoder =
                new HpackDecoder(4_096, MAX_HEADER_LIST_SIZE);
            HpackFields indexedFields = new HpackFields(64);
            Http2RequestParser requestParser =
                new Http2RequestParser(10 * 1_024 * 1_024L);
            HttpRequest request = new HttpRequest();
            requestParser.bind(decodedHeaders);

            validateHttp2Fixture(
                literalDecoder, literalBlock, HTTP2_LITERAL_BLOCK.length,
                decodedHeaders, literalFields, requestParser, request);
            int primed = indexedDecoder.decodeRequest(
                primeBlock, 0, HTTP2_PRIME_BLOCK.length,
                decodedHeaders, indexedFields);
            if (primed < 0) {
                throw new IllegalStateException(
                    "Could not prime HTTP/2 dynamic table: " + primed);
            }
            validateHttp2Fixture(
                indexedDecoder, indexedBlock, HTTP2_INDEXED_BLOCK.length,
                decodedHeaders, indexedFields, requestParser, request);

            measure("HTTP/2 frame header", new Operation() {
                private int variant;

                @Override
                public long run() {
                    long offset =
                        (long) (variant++ & FRAME_VARIANT_MASK) * indexedStride;
                    long word = Http2Frames.readHeaderWord(indexedFrames, offset);
                    return Http2Frames.payloadLength(word)
                        + Http2Frames.type(word)
                        + Http2Frames.flags(word)
                        + Http2Frames.streamId(indexedFrames, offset, word);
                }
            });

            measure("HTTP/2 literal/Huffman request", new Operation() {
                private int variant;

                @Override
                public long run() {
                    long offset =
                        (long) (variant++ & FRAME_VARIANT_MASK) * literalStride;
                    long word = Http2Frames.readHeaderWord(literalFrames, offset);
                    int encodedLength = Http2Frames.payloadLength(word);
                    int decodedLength = literalDecoder.decodeRequest(
                        literalFrames, offset + Http2Frames.HEADER_SIZE,
                        encodedLength, decodedHeaders, literalFields);
                    int contentLength = requestParser.prepare(
                        literalDecoder, literalFields, decodedLength,
                        false, true, request);
                    return decodedLength + contentLength
                        + request.picoRequest().pathLen;
                }
            });

            measure("HTTP/2 indexed request", new Operation() {
                private int variant;

                @Override
                public long run() {
                    long offset =
                        (long) (variant++ & FRAME_VARIANT_MASK) * indexedStride;
                    long word = Http2Frames.readHeaderWord(indexedFrames, offset);
                    int encodedLength = Http2Frames.payloadLength(word);
                    int decodedLength = indexedDecoder.decodeRequest(
                        indexedFrames, offset + Http2Frames.HEADER_SIZE,
                        encodedLength, decodedHeaders, indexedFields);
                    int contentLength = requestParser.prepare(
                        indexedDecoder, indexedFields, decodedLength,
                        false, true, request);
                    return decodedLength + contentLength
                        + request.picoRequest().pathLen;
                }
            });
        }
    }

    private static void validateHttp2Fixture(
            HpackDecoder decoder, MemorySegment encoded, int length,
            MemorySegment decodedHeaders, HpackFields fields,
            Http2RequestParser requestParser, HttpRequest request) {
        int decodedLength = decoder.decodeRequest(
            encoded, 0, length, decodedHeaders, fields);
        int contentLength = decodedLength < 0
            ? Http2RequestParser.ERROR
            : requestParser.prepare(
                decoder, fields, decodedLength, false, true, request);
        if (decodedLength < 0
                || contentLength == Http2RequestParser.ERROR
                || fields.count() != 6
                || request.picoRequest().methodCode != 1
                || !"/users/423".equals(request.path().toString())) {
            throw new IllegalStateException(
                "Invalid HTTP/2 benchmark fixture: " + decodedLength);
        }
    }

    private static void runJsonBenchmarks() {
        System.out.println("\n--- JSON record processing ---");
        String encoded =
            "{\"name\":\"Alice Smith\",\"id\":427,\"active\":true}";
        User user = new User("Alice Smith", 427, true);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocateFrom(encoded);
            int length = (int) input.byteSize() - 1;
            MemorySegment output = arena.allocate(1_024);
            measure("JSON record deserialization", () -> {
                User decoded = Serdes.fromJson(
                    input, 0, length, User.class);
                return decoded.id();
            });
            measure("JSON record serialization",
                () -> JsonWriter.writeRecord(output, 0, user));
        }
    }

    private static void measure(String label, Operation operation) {
        long checksum = 0;
        long warmupDeadline =
            System.nanoTime() + WARMUP_SECONDS * 1_000_000_000L;
        do {
            for (int i = 0; i < BATCH_SIZE; i++) {
                checksum += operation.run();
            }
        } while (System.nanoTime() < warmupDeadline);
        blackhole = checksum;

        long operations = 0;
        checksum = 0;
        long start = System.nanoTime();
        long deadline = start + RUN_SECONDS * 1_000_000_000L;
        do {
            for (int i = 0; i < BATCH_SIZE; i++) {
                checksum += operation.run();
            }
            operations += BATCH_SIZE;
        } while (System.nanoTime() < deadline);
        long elapsed = System.nanoTime() - start;
        blackhole = checksum;
        System.out.printf(
            "  %-54s %,14.2f ops/s  %9.2f ns/op%n",
            label + ":", operations / (elapsed / 1_000_000_000.0),
            (double) elapsed / operations);
    }

    private static MemorySegment copyToArena(Arena arena, byte[] bytes) {
        MemorySegment copy = arena.allocate(bytes.length);
        MemorySegment.copy(
            MemorySegment.ofArray(bytes), 0, copy, 0, bytes.length);
        return copy;
    }

    private static MemorySegment createHeadersFrames(
            Arena arena, MemorySegment block, int length) {
        int stride = Http2Frames.HEADER_SIZE + length;
        MemorySegment frames = arena.allocate((long) stride * FRAME_VARIANTS);
        for (int i = 0; i < FRAME_VARIANTS; i++) {
            long offset = (long) i * stride;
            Http2Frames.writeHeader(
                frames, offset, length, Http2Frames.HEADERS,
                Http2Frames.FLAG_END_HEADERS | Http2Frames.FLAG_END_STREAM,
                i * 2 + 1);
            MemorySegment.copy(
                block, 0, frames, offset + Http2Frames.HEADER_SIZE, length);
        }
        return frames;
    }

    private static byte[] hex(String encoded) {
        if ((encoded.length() & 1) != 0) {
            throw new IllegalArgumentException("Odd-length hexadecimal input");
        }
        byte[] bytes = new byte[encoded.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(encoded.charAt(i * 2), 16);
            int low = Character.digit(encoded.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Invalid hexadecimal input");
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    @FunctionalInterface
    private interface Operation {
        long run();
    }
}
