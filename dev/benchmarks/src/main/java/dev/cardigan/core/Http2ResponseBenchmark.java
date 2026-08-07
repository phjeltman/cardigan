// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.Response;
import dev.cardigan.http2.HpackEncoder;
import dev.cardigan.http2.Http2Frames;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Focused benchmark for the allocation-free portion of a small HTTP/2 text
 * response. It deliberately excludes egress-pool and io_uring queue costs.
 */
public final class Http2ResponseBenchmark {
    private static final int WARMUP_SECONDS = 1;
    private static final int RUN_SECONDS = 2;
    private static final int BATCH_SIZE = 1_024;
    private static final int STREAM_ID = 423;
    private static final String TEXT =
        "User details for ID: 423 parsed directly off-heap!";
    private static final Response RESPONSE = Response.text(TEXT);
    private static volatile long blackhole;

    private Http2ResponseBenchmark() {
    }

    public static void run() {
        System.out.println("\n--- HTTP/2 Small Text Response ---");
        System.out.println(
            "Native egress slab; excludes pool acquisition, flow control, and queueing");

        byte[] body = Http2ResponseWriter.asciiBytes(TEXT);
        if (body == null) {
            throw new IllegalStateException("Benchmark body is not compact ASCII");
        }
        String[] texts = new String[256];
        for (int i = 0; i < texts.length; i++) {
            texts[i] = TEXT + Integer.toHexString(i);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(
                UringEventLoop.EGRESS_FRAME_SIZE);
            measure("Compact ASCII body extraction", new Operation() {
                private int index;

                @Override
                public int run() {
                    byte[] bytes = Http2ResponseWriter.asciiBytes(
                        texts[index++ & 255]);
                    return bytes == null ? -1 : bytes.length;
                }
            });
            measure("HPACK response block",
                () -> HpackEncoder.writeResponseHeaders(
                    output, Http2Frames.HEADER_SIZE,
                    RESPONSE.statusCode(), RESPONSE.contentType(),
                    body.length));
            measure("Frame header",
                () -> {
                    Http2Frames.writeHeader(
                        output, 0, body.length, Http2Frames.DATA,
                        Http2Frames.FLAG_END_STREAM, STREAM_ID);
                    return output.get(ValueLayout.JAVA_BYTE, 8);
                });
        }
    }

    private static void measure(String label, Operation operation) {
        long checksum = 0;
        long warmupEnd =
            System.nanoTime() + WARMUP_SECONDS * 1_000_000_000L;
        do {
            for (int i = 0; i < BATCH_SIZE; i++) {
                checksum += operation.run();
            }
        } while (System.nanoTime() < warmupEnd);
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
            "  %-32s %9.2f ns/op%n",
            label + ":", (double) elapsed / operations);
    }

    @FunctionalInterface
    private interface Operation {
        int run();
    }
}
