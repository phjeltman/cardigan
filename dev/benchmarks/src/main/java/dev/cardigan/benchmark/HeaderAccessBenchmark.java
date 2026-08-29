// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.benchmark;

import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.HttpRequestParser;
import dev.cardigan.http.Utf8Slice;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/** Microbenchmark for request-header lookup and HTTP/1 metadata access. */
public final class HeaderAccessBenchmark {
    private static final int WARMUP_SECONDS = Integer.getInteger(
        "cardigan.benchmark.warmup.seconds", 2);
    private static final int RUN_SECONDS = Integer.getInteger(
        "cardigan.benchmark.run.seconds", 3);

    private static volatile long blackhole;

    private HeaderAccessBenchmark() {
    }

    public static void run() {
        System.out.println("\n--- Request-header access ---");
        try (Arena arena = Arena.ofConfined()) {
            Fixture four = fixture(arena, 4);
            Fixture sixteen = fixture(arena, 16);
            Fixture sixtyFour = fixture(arena, 64);

            measure("late header hit among 4 headers",
                () -> length(four.request().getHeader("x-target")));
            measure("late header hit among 16 headers",
                () -> length(sixteen.request().getHeader("x-target")));
            measure("late header hit among 64 headers",
                () -> length(sixtyFour.request().getHeader("x-target")));
            measure("missing header among 64 headers",
                () -> length(sixtyFour.request().getHeader("x-missing")));
            measure("eight lookups among 64 headers", () ->
                eightLookups(sixtyFour.request()));
            measure("parse + keep-alive, 4 headers",
                () -> parseAndKeepAlive(four));
            measure("parse + keep-alive, 64 headers",
                () -> parseAndKeepAlive(sixtyFour));
            measure("parse + eight lookups, 64 headers",
                () -> parseAndLookup(sixtyFour));
        }
    }

    private static Fixture fixture(Arena arena, int headerCount) {
        String encoded = request(headerCount);
        byte[] bytes = encoded.getBytes(StandardCharsets.US_ASCII);
        MemorySegment segment = arena.allocate(bytes.length);
        MemorySegment.copy(
            bytes, 0, segment,
            java.lang.foreign.ValueLayout.JAVA_BYTE, 0, bytes.length);
        HttpRequest request = new HttpRequest();
        if (!HttpRequestParser.parse(segment, bytes.length, request)) {
            throw new IllegalStateException("invalid benchmark request");
        }
        return new Fixture(segment, bytes.length, request);
    }

    private static String request(int headerCount) {
        StringBuilder encoded = new StringBuilder(headerCount * 32);
        encoded.append("GET /headers HTTP/1.1\r\n")
            .append("Host: localhost\r\n");
        for (int index = 1; index < headerCount - 2; index++) {
            encoded.append("X-Benchmark-")
                .append(index)
                .append(": value-")
                .append(index)
                .append("\r\n");
        }
        return encoded.append("X-Target: target-value\r\n")
            .append("Connection: keep-alive\r\n\r\n")
            .toString();
    }

    private static long parseAndKeepAlive(Fixture fixture) {
        if (!HttpRequestParser.parse(
                fixture.segment(), fixture.length(), fixture.request())) {
            return -1;
        }
        return fixture.request().isKeepAlive()
            ? fixture.request().headerCount() : -1;
    }

    private static long parseAndLookup(Fixture fixture) {
        if (!HttpRequestParser.parse(
                fixture.segment(), fixture.length(), fixture.request())) {
            return -1;
        }
        return eightLookups(fixture.request());
    }

    private static long eightLookups(HttpRequest request) {
        return length(request.getHeader("host"))
            + length(request.getHeader("x-benchmark-8"))
            + length(request.getHeader("x-benchmark-16"))
            + length(request.getHeader("x-benchmark-24"))
            + length(request.getHeader("x-benchmark-32"))
            + length(request.getHeader("x-benchmark-40"))
            + length(request.getHeader("x-target"))
            + length(request.getHeader("connection"));
    }

    private static long length(Utf8Slice slice) {
        return slice == null ? -1 : slice.length();
    }

    private static void measure(String label, Operation operation) {
        long checksum = 0;
        long warmupDeadline =
            System.nanoTime() + WARMUP_SECONDS * 1_000_000_000L;
        do {
            checksum += operation.run();
        } while (System.nanoTime() < warmupDeadline);
        blackhole = checksum;

        long operations = 0;
        checksum = 0;
        long start = System.nanoTime();
        long deadline = start + RUN_SECONDS * 1_000_000_000L;
        do {
            checksum += operation.run();
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

    @FunctionalInterface
    private interface Operation {
        long run();
    }

    private record Fixture(
        MemorySegment segment,
        int length,
        HttpRequest request
    ) {
    }
}
