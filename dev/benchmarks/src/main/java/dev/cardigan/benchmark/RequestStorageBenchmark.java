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

/** Microbenchmark for request storage retained by prepared invocations. */
public final class RequestStorageBenchmark {
    private static final int WARMUP_SECONDS = Integer.getInteger(
        "cardigan.benchmark.warmup.seconds", 2);
    private static final int RUN_SECONDS = Integer.getInteger(
        "cardigan.benchmark.run.seconds", 3);

    private static volatile long blackhole;
    private static final AutoCloseable RETAINED_STORAGE = () -> {
    };

    private RequestStorageBenchmark() {
    }

    public static void run() {
        System.out.println("\n--- Request-aware handler storage ---");
        Router router = registeredRouter(new RequestController());
        try (Arena arena = Arena.ofShared()) {
            measure("no-request handler control",
                fixture(router, arena, request("/plain", "", "")));
            measure("request-aware small GET",
                fixture(router, arena, request("/inspect", "", "")));
            measure("request-aware retained small GET",
                retainedFixture(
                    router, arena, request("/inspect", "", "")));
            measure("request-aware 32-header GET",
                fixture(router, arena, request(
                    "/inspect", heavyHeaders(), "")));
            measure("request-aware 16 KiB body",
                fixture(router, arena, request(
                    "/inspect", "", "x".repeat(16 * 1_024))));
            measure("request-aware retained 16 KiB body",
                retainedFixture(router, arena, request(
                    "/inspect", "", "x".repeat(16 * 1_024))));
        }
    }

    private static Fixture fixture(
            Router router, Arena arena, String encoded) {
        byte[] bytes = encoded.getBytes(StandardCharsets.US_ASCII);
        MemorySegment segment = arena.allocate(bytes.length);
        MemorySegment.copy(
            bytes, 0, segment,
            java.lang.foreign.ValueLayout.JAVA_BYTE, 0, bytes.length);
        HttpRequest request = new HttpRequest();
        if (!HttpRequestParser.parse(segment, bytes.length, request)) {
            throw new IllegalStateException("invalid benchmark request");
        }
        return new Fixture(
            router, request, new PreparedInvocation(), null);
    }

    private static Fixture retainedFixture(
            Router router, Arena arena, String encoded) {
        Fixture fixture = fixture(router, arena, encoded);
        return new Fixture(
            fixture.router(),
            fixture.request(),
            fixture.invocation(),
            RETAINED_STORAGE);
    }

    private static String request(
            String path, String extraHeaders, String body) {
        return "GET " + path + " HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + extraHeaders
            + (body.isEmpty()
                ? ""
                : "Content-Length: " + body.length() + "\r\n")
            + "\r\n"
            + body;
    }

    private static String heavyHeaders() {
        StringBuilder headers = new StringBuilder(2_048);
        for (int index = 0; index < 32; index++) {
            headers.append("X-Benchmark-")
                .append(index)
                .append(": ")
                .append("v".repeat(40))
                .append("\r\n");
        }
        return headers.toString();
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

    public static final class RequestController {
        private static final Response PLAIN = Response.text("plain");
        private static final Response[] OBSERVED = {
            new Response(200, "text/plain", Response.CT_TEXT, "zero"),
            new Response(201, "text/plain", Response.CT_TEXT, "one"),
            new Response(202, "text/plain", Response.CT_TEXT, "two"),
            new Response(203, "text/plain", Response.CT_TEXT, "three")
        };

        @Get("/plain")
        public Response plain() {
            return PLAIN;
        }

        @Get("/inspect")
        public Response inspect(HttpRequest request) {
            int observed = request.headerCount()
                + Math.toIntExact(request.bodyLength());
            return OBSERVED[observed & 3];
        }
    }

    private record Fixture(
        Router router,
        HttpRequest request,
        PreparedInvocation invocation,
        AutoCloseable requestStorage
    ) {
        private int invoke() {
            PreparedInvocation prepared = requestStorage == null
                ? router.prepare(request, invocation)
                : router.prepare(
                    request, invocation, null, requestStorage);
            return prepared.invoke().statusCode();
        }
    }
}
