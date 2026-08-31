// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.benchmark;

import dev.cardigan.http.Get;
import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.HttpRequestParser;
import dev.cardigan.http.PreparedInvocation;
import dev.cardigan.http.Response;
import dev.cardigan.http.Router;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/** Microbenchmark for registration-time route argument binding. */
public final class RouteBindingBenchmark {
    private static final int WARMUP_SECONDS = Integer.getInteger(
        "cardigan.benchmark.warmup.seconds", 2);
    private static final int RUN_SECONDS = Integer.getInteger(
        "cardigan.benchmark.run.seconds", 3);

    private static volatile long blackhole;

    private RouteBindingBenchmark() {
    }

    public static void run() {
        System.out.println("\n--- Route argument binding ---");
        Router router = registeredRouter(new BindingController());
        try (Arena arena = Arena.ofConfined()) {
            measure("primitive long fast-path control",
                fixture(router, arena, "/primitive/427"));
            measure("String path parameter",
                fixture(router, arena, "/string/cardigan"));
            measure("boxed Long path parameter",
                fixture(router, arena, "/boxed/427"));
            measure("two primitive path parameters",
                fixture(router, arena, "/pair/41/386"));
            measure("String path parameter + HttpRequest",
                fixture(router, arena, "/mixed/cardigan"));
        }
    }

    private static Fixture fixture(
            Router router, Arena arena, String path) {
        String encoded = "GET " + path + " HTTP/1.1\r\n"
            + "Host: localhost\r\nX-Test: binding\r\n\r\n";
        byte[] bytes = encoded.getBytes(StandardCharsets.US_ASCII);
        MemorySegment segment = arena.allocate(bytes.length);
        MemorySegment.copy(
            bytes, 0, segment,
            java.lang.foreign.ValueLayout.JAVA_BYTE, 0, bytes.length);
        HttpRequest request = new HttpRequest();
        if (!HttpRequestParser.parse(segment, bytes.length, request)) {
            throw new IllegalStateException("invalid benchmark request");
        }
        return new Fixture(router, request, new PreparedInvocation());
    }

    private static Router registeredRouter(Object controller) {
        Router router = new Router();
        PrintStream originalOut = System.out;
        try (PrintStream sink = new PrintStream(
                OutputStream.nullOutputStream())) {
            System.setOut(sink);
            router.registerController(controller);
            return router;
        } finally {
            System.setOut(originalOut);
        }
    }

    private static void measure(String label, Fixture fixture) {
        long checksum = 0;
        long warmupDeadline =
            System.nanoTime() + WARMUP_SECONDS * 1_000_000_000L;
        do {
            checksum += fixture.invoke();
        } while (System.nanoTime() < warmupDeadline);
        blackhole = checksum;

        long operations = 0;
        checksum = 0;
        long start = System.nanoTime();
        long deadline = start + RUN_SECONDS * 1_000_000_000L;
        do {
            checksum += fixture.invoke();
            operations++;
        } while (System.nanoTime() < deadline);
        long elapsed = System.nanoTime() - start;
        blackhole = checksum;

        System.out.printf(
            "  %-54s %,14.2f ops/s  %9.2f ns/op%n",
            label + ":",
            operations / (elapsed / 1_000_000_000.0),
            (double) elapsed / operations);
    }

    public static final class BindingController {
        private static final Response[] RESPONSES = {
            new Response(200, "text/plain", Response.CT_TEXT, "zero"),
            new Response(201, "text/plain", Response.CT_TEXT, "one"),
            new Response(202, "text/plain", Response.CT_TEXT, "two"),
            new Response(203, "text/plain", Response.CT_TEXT, "three")
        };

        @Get("/primitive/{id}")
        public Response primitive(long id) {
            return RESPONSES[(int) id & 3];
        }

        @Get("/string/{name}")
        public Response string(String name) {
            return RESPONSES[name.length() & 3];
        }

        @Get("/boxed/{id}")
        public Response boxed(Long id) {
            return RESPONSES[id.intValue() & 3];
        }

        @Get("/pair/{left}/{right}")
        public Response pair(long left, long right) {
            return RESPONSES[(int) (left + right) & 3];
        }

        @Get("/mixed/{name}")
        public Response mixed(String name, HttpRequest request) {
            return RESPONSES[(name.length() + request.headerCount()) & 3];
        }
    }

    private record Fixture(
        Router router,
        HttpRequest request,
        PreparedInvocation invocation
    ) {
        private int invoke() {
            Response response = router.prepare(request, invocation).invoke();
            return response.statusCode();
        }
    }
}
