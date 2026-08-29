// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.benchmark;

import dev.cardigan.core.Http2ResponseBenchmark;
import dev.cardigan.core.MpscArrayQueueBenchmark;
import dev.cardigan.http.Get;
import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.HttpRequestParser;
import dev.cardigan.http.Post;
import dev.cardigan.http.PreparedInvocation;
import dev.cardigan.http.Response;
import dev.cardigan.http.ResponseHeaders;
import dev.cardigan.http.Router;
import dev.cardigan.http2.HpackDecoder;
import dev.cardigan.http2.HpackFields;
import dev.cardigan.http2.HpackHuffmanBenchmark;
import dev.cardigan.http2.Http2Frames;
import dev.cardigan.http2.Http2RequestParser;
import dev.cardigan.json.JsonWriter;
import dev.cardigan.json.JsonReader;
import dev.cardigan.serdes.Serdes;
import dev.cardigan.simdjson.SimdJson;
import dev.cardigan.simdjson.ondemand.OnDemandParser;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

/** Microbenchmarks for code paths used by the Cardigan runtime. */
public final class BenchmarkSuite {
    private static final int WARMUP_SECONDS = Integer.getInteger(
        "cardigan.benchmark.warmup.seconds", 2);
    private static final int RUN_SECONDS = Integer.getInteger(
        "cardigan.benchmark.run.seconds", 3);
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

    public record WideJsonRecord(
        int field03,
        int field07,
        int field11,
        int field15,
        int field19,
        int field23,
        int field27,
        int field31
    ) {
    }

    public record KeyHeavyRecord(
        int customerAccountIdentifier,
        int organizationMembershipIdentifier,
        int primaryShippingAddressIdentifier,
        int preferredPaymentMethodIdentifier,
        int activeSubscriptionPlanIdentifier,
        int customerSupportRegionIdentifier,
        int marketingCampaignSourceIdentifier,
        int authenticationProviderIdentifier,
        int lastSuccessfulOrderIdentifier,
        int currentShoppingCartIdentifier,
        int preferredNotificationChannelIdentifier,
        int accountSecurityPolicyIdentifier
    ) {
    }

    public record LargeStringRecord(String payload) {
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

        @Get("/a/b/{id}")
        public Response getShortControl(Long id) {
            return Response.text("Short: " + id);
        }

        @Get("/box/item/{id}")
        public Response getShortMultiword(long id) {
            return Response.text("Short multiword: " + id);
        }
    }

    public static final class RouteScaleController {
        public Response route() {
            return Response.text("ok");
        }

        public Response parameter(long id) {
            return Response.text("ok");
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
                case "--optimization-routing" ->
                    runRoutingScaleBenchmarks();
                case "--optimization-json-fields" ->
                    runJsonFieldAccessBenchmarks();
                case "--optimization-json-writer" ->
                    runJsonWriterOptimizationBenchmarks();
                case "--optimization-quadratic" ->
                    runQuadraticBehaviorBenchmarks();
                case "--optimization-scheduler" ->
                    MpscArrayQueueBenchmark.run();
                case "--optimization-request-storage" ->
                    RequestStorageBenchmark.run();
                case "--optimization-headers" ->
                    HeaderAccessBenchmark.run();
                case "--optimization-route-binding" ->
                    RouteBindingBenchmark.run();
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
                + "[--optimization-routing|--optimization-json-fields"
                + "|--optimization-json-writer"
                + "|--optimization-quadratic"
                + "|--optimization-scheduler"
                + "|--optimization-request-storage"
                + "|--optimization-headers"
                + "|--optimization-route-binding"
                + "|--request-parsers"
                + "|--hpack-huffman|--http2-response"
                + "|--http1-chunked]");
    }

    private static void runRoutingScaleBenchmarks() {
        System.out.println("\n--- Route lookup scaling ---");
        Router parameterized = registeredRouter(new TestController());
        Router small = syntheticRouter(8);
        Router large = syntheticRouter(256);
        Router prefix1 = syntheticFastPrefixRouter(1);
        Router prefix8 = syntheticFastPrefixRouter(8);
        Router prefix64 = syntheticFastPrefixRouter(64);
        Router prefix256 = syntheticFastPrefixRouter(256);
        measureQuietly(
            "register 64 same-depth routes",
            1,
            () -> System.identityHashCode(syntheticRouter(64)));
        measureQuietly(
            "register 256 same-depth routes",
            1,
            () -> System.identityHashCode(syntheticRouter(256)));
        measureQuietly(
            "register 64 fast-prefix routes",
            1,
            () -> System.identityHashCode(
                syntheticFastPrefixRouter(64)));
        measureQuietly(
            "register 256 fast-prefix routes",
            1,
            () -> System.identityHashCode(
                syntheticFastPrefixRouter(256)));
        runRoutingBenchmark(
            "single parameterized-route hit",
            "GET /users/427 HTTP/1.1\r\nHost: localhost\r\n\r\n",
            parameterized);
        runRoutingLookupBenchmark(
            "short path with separators in first word",
            "GET /a/b/427 HTTP/1.1\r\nHost: localhost\r\n\r\n",
            parameterized);
        runRoutingLookupBenchmark(
            "short path with separator after first word",
            "GET /box/item/427 HTTP/1.1\r\nHost: localhost\r\n\r\n",
            parameterized);
        runRoutingLookupBenchmark(
            "late fast-prefix hit among 1 route",
            "GET /p00000/427 HTTP/1.1\r\nHost: localhost\r\n\r\n",
            prefix1);
        runRoutingLookupBenchmark(
            "late fast-prefix hit among 8 routes",
            "GET /p00007/427 HTTP/1.1\r\nHost: localhost\r\n\r\n",
            prefix8);
        runRoutingLookupBenchmark(
            "late fast-prefix hit among 64 routes",
            "GET /p0003f/427 HTTP/1.1\r\nHost: localhost\r\n\r\n",
            prefix64);
        runRoutingLookupBenchmark(
            "early fast-prefix hit among 256 routes",
            "GET /p00000/427 HTTP/1.1\r\nHost: localhost\r\n\r\n",
            prefix256);
        runRoutingLookupBenchmark(
            "late fast-prefix hit among 256 routes",
            "GET /p000ff/427 HTTP/1.1\r\nHost: localhost\r\n\r\n",
            prefix256);
        runRoutingLookupBenchmark(
            "fast-prefix miss among 256 routes",
            "GET /pfffff/427 HTTP/1.1\r\nHost: localhost\r\n\r\n",
            prefix256);
        runRoutingBenchmark(
            "uncached miss among 8 same-depth routes",
            "GET /catalog/candidate9999/detail HTTP/1.1\r\n"
                + "Host: localhost\r\n\r\n",
            small);
        runRoutingBenchmark(
            "uncached miss among 256 same-depth routes",
            "GET /catalog/candidate9999/detail HTTP/1.1\r\n"
                + "Host: localhost\r\n\r\n",
            large);
    }

    private static Router registeredRouter(Object controller) {
        Router router = new Router();
        PrintStream originalOut = System.out;
        try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(sink);
            router.registerController(controller);
            return router;
        } finally {
            System.setOut(originalOut);
        }
    }

    private static void runJsonFieldAccessBenchmarks() {
        System.out.println("\n--- On-demand JSON field access ---");
        String encoded = wideJsonObject(32);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocateFrom(encoded);
            int length = (int) input.byteSize() - 1;
            measure("one late field from a 32-field object", () ->
                Serdes.parseOnDemand(input, 0, length)
                    .get("field31").getLong());
            measure("eight fields from a 32-field object", () -> {
                var value = Serdes.parseOnDemand(input, 0, length);
                return value.get("field03").getLong()
                    + value.get("field07").getLong()
                    + value.get("field11").getLong()
                    + value.get("field15").getLong()
                    + value.get("field19").getLong()
                    + value.get("field23").getLong()
                    + value.get("field27").getLong()
                    + value.get("field31").getLong();
            });
            measure("record projection from on-demand value", () -> {
                var value = Serdes.parseOnDemand(input, 0, length);
                WideJsonRecord record = Serdes.readRecordFromValue(
                    value, WideJsonRecord.class);
                return record.field31();
            });
        }
    }

    private static String wideJsonObject(int fields) {
        StringBuilder json = new StringBuilder(fields * 14);
        json.append('{');
        for (int index = 0; index < fields; index++) {
            if (index != 0) {
                json.append(',');
            }
            json.append('"')
                .append("field%02d".formatted(index))
                .append("\":")
                .append(index);
        }
        return json.append('}').toString();
    }

    private static void runJsonWriterOptimizationBenchmarks() {
        System.out.println("\n--- JSON record writer ---");
        User small = new User("Alice Smith", 427, true);
        KeyHeavyRecord keyHeavy = new KeyHeavyRecord(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        LargeStringRecord oneKiB = new LargeStringRecord("x".repeat(1_024));
        LargeStringRecord sixteenKiB =
            new LargeStringRecord("x".repeat(16 * 1_024));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(32 * 1_024);
            measure("write 3-field record into caller buffer",
                () -> JsonWriter.writeRecord(output, 0, small));
            measure("write 12 long-key fields into caller buffer",
                () -> JsonWriter.writeRecord(output, 0, keyHeavy));
            measure("materialize 1 KiB record as byte[]",
                () -> Serdes.toJsonBytes(oneKiB).length);
            measure("materialize 16 KiB record as byte[]",
                () -> Serdes.toJsonBytes(sixteenKiB).length);
        }
    }

    private static void runQuadraticBehaviorBenchmarks() {
        System.out.println("\n--- Quadratic API behavior ---");
        String arrayJson = integerArray(128);
        var domArray = new SimdJson().parse(arrayJson).getArray();
        var onDemandArray = new OnDemandParser()
            .parse(arrayJson).getArray();
        String objectJson = wideJsonObject(32);
        var domObject = new SimdJson().parse(objectJson).getObject();
        SimdJson domArrayParser = new SimdJson();
        OnDemandParser onDemandArrayParser = new OnDemandParser();
        SimdJson domObjectParser = new SimdJson();
        String[] headerNames = new String[32];
        for (int index = 0; index < headerNames.length; index++) {
            headerNames[index] = "x-benchmark-" + index;
        }

        measure("DOM array indexed traversal, 128 elements", () -> {
            long sum = 0;
            for (int index = 0; index < domArray.size(); index++) {
                sum += domArray.get(index).getLong();
            }
            return sum;
        });
        measure("DOM array iterator traversal, 128 elements", () -> {
            long sum = 0;
            for (var value : domArray) {
                sum += value.getLong();
            }
            return sum;
        });
        measure("DOM parse + indexed traversal, 128 elements", () -> {
            var array = domArrayParser.parse(arrayJson).getArray();
            long sum = 0;
            for (int index = 0; index < array.size(); index++) {
                sum += array.get(index).getLong();
            }
            return sum;
        });
        measure("on-demand indexed traversal, 128 elements", () -> {
            long sum = 0;
            for (int index = 0; index < 128; index++) {
                sum += onDemandArray.get(index).getLong();
            }
            return sum;
        });
        measure("on-demand parse + traversal, 128 elements", () -> {
            var array = onDemandArrayParser.parse(arrayJson).getArray();
            long sum = 0;
            for (int index = 0; index < 128; index++) {
                sum += array.get(index).getLong();
            }
            return sum;
        });
        measure("eight DOM object field lookups", () ->
            domObject.get("field03").getLong()
                + domObject.get("field07").getLong()
                + domObject.get("field11").getLong()
                + domObject.get("field15").getLong()
                + domObject.get("field19").getLong()
                + domObject.get("field23").getLong()
                + domObject.get("field27").getLong()
                + domObject.get("field31").getLong());
        measure("DOM parse + eight object field lookups", () -> {
            var object = domObjectParser.parse(objectJson).getObject();
            return object.get("field03").getLong()
                + object.get("field07").getLong()
                + object.get("field11").getLong()
                + object.get("field15").getLong()
                + object.get("field19").getLong()
                + object.get("field23").getLong()
                + object.get("field27").getLong()
                + object.get("field31").getLong();
        });

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment object = arena.allocateFrom(wideJsonObject(32));
            int objectLength = (int) object.byteSize() - 1;
            measure("eight JsonReader field lookups", () -> {
                JsonReader.IndexedObject indexed = JsonReader.indexObject(
                    object, 0, objectLength);
                return indexed.findInt("field03", -1)
                    + indexed.findInt("field07", -1)
                    + indexed.findInt("field11", -1)
                    + indexed.findInt("field15", -1)
                    + indexed.findInt("field19", -1)
                    + indexed.findInt("field23", -1)
                    + indexed.findInt("field27", -1)
                    + indexed.findInt("field31", -1);
            });
        }

        measure("copy/revalidate 32 response headers (control)", () -> {
            Response response = Response.text("ok");
            for (String name : headerNames) {
                response = response.withHeaders(
                    ResponseHeaders.builder()
                        .addAll(response.headers())
                        .add(name, "value")
                        .build());
            }
            return consumeHeaders(response);
        });
        measure("chain 32 response headers", () -> {
            Response response = Response.text("ok");
            for (String name : headerNames) {
                response = response.withHeader(name, "value");
            }
            return consumeHeaders(response);
        });
    }

    private static long consumeHeaders(Response response) {
        long size = response.headers().byteSize();
        for (int index = 0; index < response.headers().size(); index++) {
            size += response.headers().name(index).length();
            size += response.headers().value(index).length();
        }
        return size;
    }

    private static String integerArray(int elements) {
        StringBuilder json = new StringBuilder(elements * 4);
        json.append('[');
        for (int index = 0; index < elements; index++) {
            if (index != 0) {
                json.append(',');
            }
            json.append(index);
        }
        return json.append(']').toString();
    }

    private static Router syntheticRouter(int routeCount) {
        Router router = new Router();
        RouteScaleController controller = new RouteScaleController();
        PrintStream originalOut = System.out;
        try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream())) {
            Method register = Router.class.getDeclaredMethod(
                "registerRoute",
                String.class,
                String.class,
                Method.class,
                Object.class);
            Method route = RouteScaleController.class.getDeclaredMethod("route");
            register.setAccessible(true);
            System.setOut(sink);
            for (int i = 0; i < routeCount; i++) {
                register.invoke(
                    router,
                    "GET",
                    "/catalog/candidate%04d/detail".formatted(i),
                    route,
                    controller);
            }
            return router;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                "Could not construct routing benchmark fixture", failure);
        } finally {
            System.setOut(originalOut);
        }
    }

    private static Router syntheticFastPrefixRouter(int routeCount) {
        Router router = new Router();
        RouteScaleController controller = new RouteScaleController();
        PrintStream originalOut = System.out;
        try (PrintStream sink = new PrintStream(
                OutputStream.nullOutputStream())) {
            Method register = Router.class.getDeclaredMethod(
                "registerRoute",
                String.class,
                String.class,
                Method.class,
                Object.class);
            Method route = RouteScaleController.class.getDeclaredMethod(
                "parameter", long.class);
            register.setAccessible(true);
            System.setOut(sink);
            for (int index = 0; index < routeCount; index++) {
                register.invoke(
                    router,
                    "GET",
                    "/p%05x/{id}".formatted(index),
                    route,
                    controller);
            }
            return router;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                "Could not construct fast-prefix benchmark fixture",
                failure);
        } finally {
            System.setOut(originalOut);
        }
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

    private static void runRoutingLookupBenchmark(
            String label, String encoded, Router router) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocateFrom(encoded);
            int length = (int) input.byteSize() - 1;
            HttpRequest request = new HttpRequest();
            if (!HttpRequestParser.parse(input, length, request)) {
                throw new IllegalStateException(
                    "Invalid routing benchmark request");
            }
            boolean hit = router.findRoute(request) != null;
            System.out.println("  " + label + " result: "
                + (hit ? "hit" : "MISS"));

            measure("HTTP/1 parse + lookup, " + label, () -> {
                if (!HttpRequestParser.parse(input, length, request)) {
                    return -1;
                }
                return router.findRoute(request) == null ? 0 : 1;
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
        Measurement measurement = measure(operation, BATCH_SIZE);
        printMeasurement(label, measurement);
    }

    private static void measureQuietly(
            String label, int batchSize, Operation operation) {
        PrintStream originalOut = System.out;
        Measurement measurement;
        try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(sink);
            measurement = measure(operation, batchSize);
        } finally {
            System.setOut(originalOut);
        }
        printMeasurement(label, measurement);
    }

    private static Measurement measure(Operation operation, int batchSize) {
        long checksum = 0;
        long warmupDeadline =
            System.nanoTime() + WARMUP_SECONDS * 1_000_000_000L;
        do {
            for (int i = 0; i < batchSize; i++) {
                checksum += operation.run();
            }
        } while (System.nanoTime() < warmupDeadline);
        blackhole = checksum;

        long operations = 0;
        checksum = 0;
        long start = System.nanoTime();
        long deadline = start + RUN_SECONDS * 1_000_000_000L;
        do {
            for (int i = 0; i < batchSize; i++) {
                checksum += operation.run();
            }
            operations += batchSize;
        } while (System.nanoTime() < deadline);
        long elapsed = System.nanoTime() - start;
        blackhole = checksum;
        return new Measurement(operations, elapsed);
    }

    private static void printMeasurement(
            String label, Measurement measurement) {
        System.out.printf(
            "  %-54s %,14.2f ops/s  %9.2f ns/op%n",
            label + ":",
            measurement.operations()
                / (measurement.elapsedNanos() / 1_000_000_000.0),
            (double) measurement.elapsedNanos() / measurement.operations());
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

    private record Measurement(long operations, long elapsedNanos) {
    }
}
