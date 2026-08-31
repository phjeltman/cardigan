// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.HttpRequest;
import dev.cardigan.pico.PicoHTTPParser;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** Microbenchmark for the server's HTTP/1 parse and framing path. */
public final class Http1ParsingBenchmark {
    private static final int WARMUP_SECONDS = Integer.getInteger(
        "cardigan.benchmark.warmup.seconds", 2);
    private static final int RUN_SECONDS = Integer.getInteger(
        "cardigan.benchmark.run.seconds", 3);
    private static final int BATCH_SIZE = 1_024;

    private static final String GET_REQUEST =
        "GET /users/423 HTTP/1.1\r\n"
            + "Host: localhost\r\n\r\n";
    private static final String POST_REQUEST =
        "POST /users HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: 45\r\n\r\n"
            + "{\"name\":\"Alice Smith\",\"id\":427,\"active\":true}";
    private static final String HEADER_HEAVY_REQUEST =
        "POST /api/v1/users/427/profile/settings HTTP/1.1\r\n"
            + "Host: api.example.com\r\n"
            + "User-Agent: Cardigan-Benchmark/1.0\r\n"
            + "Accept: application/json, text/plain, */*\r\n"
            + "Accept-Language: en-US,en;q=0.9\r\n"
            + "Accept-Encoding: gzip, deflate, br\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: 0\r\n"
            + "Connection: keep-alive\r\n\r\n";

    private static volatile long blackhole;

    private Http1ParsingBenchmark() {
    }

    public static void run() {
        System.out.println("\n--- HTTP/1 parse and framing ---");
        runCase("benchmark GET", GET_REQUEST);
        runCase("benchmark POST", POST_REQUEST);
        runCase("eight headers", HEADER_HEAVY_REQUEST);
    }

    private static void runCase(String label, String encoded) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocateFrom(encoded);
            int headerEnd = encoded.indexOf("\r\n\r\n") + 4;
            HttpRequest parseOnlyRequest = new HttpRequest();
            HttpRequest framedRequest = new HttpRequest();

            Operation parseOnly = () -> {
                parseOnlyRequest.init(input);
                long parsed = PicoHTTPParser.parseRequest(
                    input, 0, headerEnd,
                    parseOnlyRequest.picoRequest(), 0);
                if (parsed < 0) {
                    throw new IllegalStateException("fixture did not parse");
                }
                return parsed + parseOnlyRequest.headerCount();
            };
            Operation parseAndFrame = () -> {
                framedRequest.init(input);
                long parsed = PicoHTTPParser.parseRequest(
                    input, 0, headerEnd,
                    framedRequest.picoRequest(), 0);
                if (parsed < 0) {
                    throw new IllegalStateException("fixture did not parse");
                }
                framedRequest.splitQuery();
                return parsed + CardiganServer.parseHttp1Framing(
                    framedRequest);
            };

            blackhole ^= parseOnly.run();
            blackhole ^= parseAndFrame.run();
            print(label + ", parser only", measure(parseOnly));
            print(label + ", parser + framing", measure(parseAndFrame));
        }
    }

    private static Measurement measure(Operation operation) {
        long checksum = 0;
        long warmupDeadline = System.nanoTime()
            + WARMUP_SECONDS * 1_000_000_000L;
        do {
            for (int index = 0; index < BATCH_SIZE; index++) {
                checksum += operation.run();
            }
        } while (System.nanoTime() < warmupDeadline);
        blackhole = checksum;

        long operations = 0;
        checksum = 0;
        long start = System.nanoTime();
        long deadline = start + RUN_SECONDS * 1_000_000_000L;
        do {
            for (int index = 0; index < BATCH_SIZE; index++) {
                checksum += operation.run();
            }
            operations += BATCH_SIZE;
        } while (System.nanoTime() < deadline);
        long elapsed = System.nanoTime() - start;
        blackhole = checksum;
        return new Measurement(operations, elapsed);
    }

    private static void print(String label, Measurement measurement) {
        System.out.printf(
            "  %-54s %,14.2f ops/s  %9.2f ns/op%n",
            label + ":",
            measurement.operations()
                / (measurement.elapsedNanos() / 1_000_000_000.0),
            (double) measurement.elapsedNanos() / measurement.operations());
    }

    @FunctionalInterface
    private interface Operation {
        long run();
    }

    private record Measurement(long operations, long elapsedNanos) {
    }
}
