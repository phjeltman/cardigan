// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.Get;
import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.HttpRequestParser;
import dev.cardigan.http.Response;
import dev.cardigan.http.Router;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** Microbenchmark for the ordered HTTP/1 exchange lifecycle. */
public final class Http1ExchangeSequencerBenchmark {
    private static final int WARMUP_SECONDS = Integer.getInteger(
        "cardigan.benchmark.warmup.seconds", 2);
    private static final int RUN_SECONDS = Integer.getInteger(
        "cardigan.benchmark.run.seconds", 3);
    private static final String REQUEST =
        "GET /users/423 HTTP/1.1\r\nHost: localhost\r\n\r\n";
    private static final Response RESPONSE = Response.text("ok");

    private static volatile long blackhole;

    private Http1ExchangeSequencerBenchmark() {
    }

    public static final class Controller {
        @Get("/users/{id}")
        public Response get(long id) {
            return RESPONSE;
        }
    }

    public static void run() {
        System.out.println("\n--- HTTP/1 ordered exchange lifecycle ---");
        runCase(1);
        runCase(16);
        runCase(64);
    }

    private static void runCase(int depth) {
        Router router = new Router();
        PrintStream originalOut = System.out;
        try (PrintStream sink = new PrintStream(
                OutputStream.nullOutputStream())) {
            System.setOut(sink);
            router.registerController(new Controller());
        } finally {
            System.setOut(originalOut);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocateFrom(REQUEST);
            HttpRequest request = new HttpRequest();
            if (!HttpRequestParser.parse(
                    input, (int) input.byteSize() - 1, request)) {
                throw new IllegalStateException(
                    "sequencer benchmark request did not parse");
            }

            DeferredExecutor executor = new DeferredExecutor(depth);
            long[] responses = new long[1];
            Http1ExchangeSequencer sequencer =
                new Http1ExchangeSequencer(
                    executor, depth,
                    (response, keepAlive) -> {
                        responses[0] += response.statusCode();
                        return true;
                    });
            Measurement measurement = measure(
                sequencer, executor, router, request, depth);
            blackhole ^= responses[0];
            System.out.printf(
                "  %-54s %,14.2f exchanges/s  %9.2f ns/exchange%n",
                "submit + ordered completion, depth " + depth + ":",
                measurement.exchanges()
                    / (measurement.elapsedNanos() / 1_000_000_000.0),
                (double) measurement.elapsedNanos()
                    / measurement.exchanges());
        }
    }

    private static Measurement measure(
            Http1ExchangeSequencer sequencer,
            DeferredExecutor executor,
            Router router,
            HttpRequest request,
            int depth) {
        long warmupDeadline = System.nanoTime()
            + WARMUP_SECONDS * 1_000_000_000L;
        do {
            cycle(sequencer, executor, router, request, depth);
        } while (System.nanoTime() < warmupDeadline);

        long exchanges = 0;
        long start = System.nanoTime();
        long deadline = start + RUN_SECONDS * 1_000_000_000L;
        do {
            cycle(sequencer, executor, router, request, depth);
            exchanges += depth;
        } while (System.nanoTime() < deadline);
        return new Measurement(exchanges, System.nanoTime() - start);
    }

    private static void cycle(
            Http1ExchangeSequencer sequencer,
            DeferredExecutor executor,
            Router router,
            HttpRequest request,
            int depth) {
        for (int index = 0; index < depth; index++) {
            if (!sequencer.submit(router, request, true, null)) {
                throw new IllegalStateException(
                    "sequencer benchmark submission failed");
            }
        }
        executor.runAll();
        if (sequencer.hasInFlight()) {
            throw new IllegalStateException(
                "sequencer benchmark did not retire its exchanges");
        }
    }

    private static final class DeferredExecutor
            implements Http1ExchangeSequencer.TaskExecutor {
        private final Runnable[] tasks;
        private int size;

        private DeferredExecutor(int capacity) {
            tasks = new Runnable[capacity];
        }

        @Override
        public boolean submit(Runnable task) {
            tasks[size++] = task;
            return true;
        }

        private void runAll() {
            int count = size;
            size = 0;
            for (int index = 0; index < count; index++) {
                Runnable task = tasks[index];
                tasks[index] = null;
                task.run();
            }
        }
    }

    private record Measurement(long exchanges, long elapsedNanos) {
    }
}
