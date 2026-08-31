// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.benchmark;

import dev.cardigan.pico.ChunkedDecoder;
import dev.cardigan.pico.PicoHTTPParser;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/** Focused microbenchmarks for the Java port of phr_decode_chunked. */
public final class Http1ChunkedBenchmark {
    private static final long WARMUP_NANOS = 1_000_000_000L;
    private static final long RUN_NANOS = 2_000_000_000L;
    private static final int TARGET_WORKING_SET = 16 * 1024 * 1024;
    private static final int MIN_FIXTURES = 16;
    private static final int MAX_FIXTURES = 1_024;

    private static volatile long blackhole;

    private Http1ChunkedBenchmark() {
    }

    public static void run() {
        System.out.println(
            "\n--- HTTP/1 phr_decode_chunked Microbenchmarks ---");
        System.out.println(
            "Native buffers; decoding plus application copy are timed; "
                + "fixture restoration and decoder reset are not");

        runCase("1 KiB, one chunk", 1 * 1024, 1 * 1024,
            Integer.MAX_VALUE, "", "");
        runCase("64 KiB, one chunk", 64 * 1024, 64 * 1024,
            Integer.MAX_VALUE, "", "");
        runCase("64 KiB, one chunk / 16 KiB receives",
            64 * 1024, 64 * 1024, 16 * 1024, "", "");
        runCase("64 KiB, 1 KiB chunks", 64 * 1024, 1 * 1024,
            Integer.MAX_VALUE, "", "");
        runCase("64 KiB, 64 B chunks", 64 * 1024, 64,
            Integer.MAX_VALUE, "", "");
        runCase("64 KiB, 1 KiB chunks + metadata",
            64 * 1024, 1 * 1024, 16 * 1024,
            ";source=cardigan", "X-Checksum: accepted\r\n");

        System.out.println(
            "\nChunked decoder microbenchmarks completed successfully.");
    }

    private static void runCase(
        String label,
        int payloadLength,
        int chunkLength,
        int receiveLength,
        String extension,
        String trailers
    ) {
        byte[] encoded = encode(
            payloadLength, chunkLength, extension, trailers);
        int effectiveReceiveLength = Math.min(receiveLength, encoded.length);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pristine = arena.allocate(encoded.length, 64);
            MemorySegment.copy(
                MemorySegment.ofArray(encoded), 0,
                pristine, 0, encoded.length);

            int stride = (encoded.length + 63) & ~63;
            int fixtureCount = Math.max(
                MIN_FIXTURES,
                Math.min(MAX_FIXTURES, TARGET_WORKING_SET / stride));
            MemorySegment work = arena.allocate(
                (long) stride * fixtureCount, 64);
            int outputStride = (payloadLength + 63) & ~63;
            MemorySegment output = arena.allocate(
                (long) outputStride * fixtureCount, 64);
            ChunkedDecoder[] decoders = new ChunkedDecoder[fixtureCount];
            for (int i = 0; i < fixtureCount; i++) {
                decoders[i] = new ChunkedDecoder();
            }

            Scenario scenario = new Scenario(
                pristine,
                work,
                output,
                decoders,
                new long[2],
                encoded.length,
                payloadLength,
                effectiveReceiveLength,
                stride,
                outputStride
            );
            scenario.prepare(true);
            long validation = scenario.decodeBatch(true, false);
            scenario.prepare(true);
            validation ^= scenario.decodeBatch(true, true);
            blackhole ^= validation;

            measureFor(scenario, false, WARMUP_NANOS);
            Measurement compact = measureFor(
                scenario, false, RUN_NANOS);
            scenario.prepare(true);
            measureFor(scenario, true, WARMUP_NANOS);
            Measurement direct = measureFor(scenario, true, RUN_NANOS);
            int callsPerMessage =
                (encoded.length + effectiveReceiveLength - 1)
                    / effectiveReceiveLength;

            System.out.printf(
                "%-43s %8.2f ns/msg  %6.2f GiB/s payload  "
                    + "%6.2f GiB/s input  (%d decode call%s/msg)%n",
                label + " compact:",
                compact.nanosecondsPerMessage(),
                compact.decodedGiBPerSecond(payloadLength),
                compact.encodedGiBPerSecond(encoded.length),
                callsPerMessage,
                callsPerMessage == 1 ? "" : "s"
            );
            System.out.printf(
                "%-43s %8.2f ns/msg  %6.2f GiB/s payload  "
                    + "%6.2f GiB/s input  (%+.1f%%)%n",
                label + " direct:",
                direct.nanosecondsPerMessage(),
                direct.decodedGiBPerSecond(payloadLength),
                direct.encodedGiBPerSecond(encoded.length),
                (compact.nanosecondsPerMessage()
                    / direct.nanosecondsPerMessage() - 1.0) * 100.0
            );
        }
    }

    private static Measurement measureFor(
        Scenario scenario,
        boolean direct,
        long targetMeasuredNanos
    ) {
        long messages = 0;
        long measuredNanos = 0;
        long checksum = 0;
        while (measuredNanos < targetMeasuredNanos) {
            scenario.prepare(!direct);
            long start = System.nanoTime();
            checksum ^= scenario.decodeBatch(false, direct);
            measuredNanos += System.nanoTime() - start;
            messages += scenario.fixtureCount();
        }
        blackhole ^= checksum;
        return new Measurement(messages, measuredNanos);
    }

    private static byte[] encode(
        int payloadLength,
        int chunkLength,
        String extension,
        String trailers
    ) {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(
            payloadLength + payloadLength / Math.max(1, chunkLength) * 16);
        byte[] extensionBytes = extension.getBytes(StandardCharsets.US_ASCII);
        int offset = 0;
        while (offset < payloadLength) {
            int count = Math.min(chunkLength, payloadLength - offset);
            encoded.writeBytes(Integer.toHexString(count)
                .getBytes(StandardCharsets.US_ASCII));
            encoded.writeBytes(extensionBytes);
            encoded.write('\r');
            encoded.write('\n');
            for (int i = 0; i < count; i++) {
                encoded.write('A' + ((offset + i) & 15));
            }
            encoded.write('\r');
            encoded.write('\n');
            offset += count;
        }
        encoded.writeBytes("0\r\n".getBytes(StandardCharsets.US_ASCII));
        encoded.writeBytes(trailers.getBytes(StandardCharsets.US_ASCII));
        encoded.write('\r');
        encoded.write('\n');
        return encoded.toByteArray();
    }

    private record Measurement(long messages, long elapsedNanos) {
        double nanosecondsPerMessage() {
            return (double) elapsedNanos / messages;
        }

        double decodedGiBPerSecond(int decodedLength) {
            return gibibytesPerSecond(messages * (double) decodedLength);
        }

        double encodedGiBPerSecond(int encodedLength) {
            return gibibytesPerSecond(messages * (double) encodedLength);
        }

        private double gibibytesPerSecond(double bytes) {
            return bytes / (1L << 30)
                / (elapsedNanos / 1_000_000_000.0);
        }
    }

    private static final class Scenario {
        private final MemorySegment pristine;
        private final MemorySegment work;
        private final MemorySegment output;
        private final ChunkedDecoder[] decoders;
        private final long[] decodeOutput;
        private final int encodedLength;
        private final int payloadLength;
        private final int receiveLength;
        private final int stride;
        private final int outputStride;

        private Scenario(
            MemorySegment pristine,
            MemorySegment work,
            MemorySegment output,
            ChunkedDecoder[] decoders,
            long[] decodeOutput,
            int encodedLength,
            int payloadLength,
            int receiveLength,
            int stride,
            int outputStride
        ) {
            this.pristine = pristine;
            this.work = work;
            this.output = output;
            this.decoders = decoders;
            this.decodeOutput = decodeOutput;
            this.encodedLength = encodedLength;
            this.payloadLength = payloadLength;
            this.receiveLength = receiveLength;
            this.stride = stride;
            this.outputStride = outputStride;
        }

        int fixtureCount() {
            return decoders.length;
        }

        void prepare(boolean restoreInput) {
            for (int i = 0; i < decoders.length; i++) {
                if (restoreInput) {
                    MemorySegment.copy(
                        pristine, 0, work, (long) i * stride, encodedLength);
                }
                ChunkedDecoder decoder = decoders[i];
                decoder.reset();
                decoder.consumeTrailer = true;
            }
        }

        long decodeBatch(boolean validate, boolean direct) {
            return direct
                ? decodeDirectBatch(validate) : decodeCompactBatch(validate);
        }

        private long decodeCompactBatch(boolean validate) {
            long checksum = 0;
            for (int fixture = 0; fixture < decoders.length; fixture++) {
                long base = (long) fixture * stride;
                long outputBase = (long) fixture * outputStride;
                int encodedOffset = 0;
                int decoded = 0;
                long result = PicoHTTPParser.ERROR_PARTIAL;
                while (encodedOffset < encodedLength) {
                    int count = Math.min(
                        receiveLength, encodedLength - encodedOffset);
                    decodeOutput[0] = count;
                    result = PicoHTTPParser.decodeChunked(
                        decoders[fixture],
                        work,
                        base + encodedOffset,
                        decodeOutput
                    );
                    int produced = Math.toIntExact(decodeOutput[0]);
                    if (validate) {
                        for (int i = 0; i < produced; i++) {
                            byte expected = (byte) (
                                'A' + ((decoded + i) & 15));
                            byte actual = work.get(
                                ValueLayout.JAVA_BYTE,
                                base + encodedOffset + i
                            );
                            if (actual != expected) {
                                throw new IllegalStateException(
                                    "Decoded fixture mismatch at "
                                        + (decoded + i));
                            }
                        }
                    }
                    MemorySegment.copy(
                        work, base + encodedOffset,
                        output, outputBase + decoded,
                        produced
                    );
                    decoded += produced;
                    encodedOffset += count;
                }
                if (validate && (result != 0 || decoded != payloadLength)) {
                    throw new IllegalStateException(
                        "Invalid benchmark fixture: result=" + result
                            + ", decoded=" + decoded
                            + ", expected=" + payloadLength);
                }
                checksum += decoded * 31L + result;
            }
            return checksum;
        }

        private long decodeDirectBatch(boolean validate) {
            long checksum = 0;
            for (int fixture = 0; fixture < decoders.length; fixture++) {
                long base = (long) fixture * stride;
                long outputBase = (long) fixture * outputStride;
                int encodedOffset = 0;
                int decoded = 0;
                int result = PicoHTTPParser.ERROR_PARTIAL;
                while (encodedOffset < encodedLength
                        && result != PicoHTTPParser.CHUNKED_COMPLETE) {
                    int receiveEnd = Math.min(
                        encodedLength, encodedOffset + receiveLength);
                    while (encodedOffset < receiveEnd
                            && result
                                != PicoHTTPParser.CHUNKED_COMPLETE) {
                        int callOffset = encodedOffset;
                        result = PicoHTTPParser.decodeChunkedTo(
                            decoders[fixture],
                            work,
                            base + callOffset,
                            receiveEnd - callOffset,
                            output,
                            outputBase + decoded,
                            payloadLength - decoded,
                            payloadLength - decoded,
                            Long.MAX_VALUE,
                            decodeOutput
                        );
                        int consumed = Math.toIntExact(decodeOutput[0]);
                        int produced = Math.toIntExact(decodeOutput[1]);
                        decoded += produced;
                        encodedOffset += consumed;
                        if (result < 0
                                && result != PicoHTTPParser.ERROR_PARTIAL) {
                            throw new IllegalStateException(
                                "Direct fixture failed to decode: " + result);
                        }
                        if (consumed == 0) {
                            throw new IllegalStateException(
                                "Span fixture decoder stalled");
                        }
                    }
                }
                if (validate && (result
                        != PicoHTTPParser.CHUNKED_COMPLETE
                        || decoded != payloadLength
                        || encodedOffset != encodedLength)) {
                    throw new IllegalStateException(
                        "Invalid direct fixture: result=" + result
                            + ", consumed=" + encodedOffset
                            + ", decoded=" + decoded
                            + ", expected=" + payloadLength);
                }
                if (validate) {
                    for (int i = 0; i < decoded; i++) {
                        byte expected = (byte) ('A' + (i & 15));
                        byte actual = output.get(
                            ValueLayout.JAVA_BYTE, outputBase + i);
                        if (actual != expected) {
                            throw new IllegalStateException(
                                "Direct fixture mismatch at " + i);
                        }
                    }
                }
                checksum += decoded * 31L + result;
            }
            return checksum;
        }
    }
}
