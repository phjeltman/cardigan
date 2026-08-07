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
            "Native buffers; fixture restoration and decoder reset are "
                + "outside timed regions");

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
            ChunkedDecoder[] decoders = new ChunkedDecoder[fixtureCount];
            for (int i = 0; i < fixtureCount; i++) {
                decoders[i] = new ChunkedDecoder();
            }

            Scenario scenario = new Scenario(
                pristine,
                work,
                decoders,
                new long[1],
                encoded.length,
                payloadLength,
                effectiveReceiveLength,
                stride
            );
            scenario.prepare();
            long validation = scenario.decodeBatch(true);
            blackhole ^= validation;

            measureFor(scenario, WARMUP_NANOS);
            Measurement result = measureFor(scenario, RUN_NANOS);
            int callsPerMessage =
                (encoded.length + effectiveReceiveLength - 1)
                    / effectiveReceiveLength;

            System.out.printf(
                "%-43s %8.2f ns/msg  %6.2f GiB/s payload  "
                    + "%6.2f GiB/s input  (%d decode call%s/msg)%n",
                label + ":",
                result.nanosecondsPerMessage(),
                result.decodedGiBPerSecond(payloadLength),
                result.encodedGiBPerSecond(encoded.length),
                callsPerMessage,
                callsPerMessage == 1 ? "" : "s"
            );
        }
    }

    private static Measurement measureFor(
        Scenario scenario,
        long targetMeasuredNanos
    ) {
        long messages = 0;
        long measuredNanos = 0;
        long checksum = 0;
        while (measuredNanos < targetMeasuredNanos) {
            scenario.prepare();
            long start = System.nanoTime();
            checksum ^= scenario.decodeBatch(false);
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
        private final ChunkedDecoder[] decoders;
        private final long[] decodeLength;
        private final int encodedLength;
        private final int payloadLength;
        private final int receiveLength;
        private final int stride;

        private Scenario(
            MemorySegment pristine,
            MemorySegment work,
            ChunkedDecoder[] decoders,
            long[] decodeLength,
            int encodedLength,
            int payloadLength,
            int receiveLength,
            int stride
        ) {
            this.pristine = pristine;
            this.work = work;
            this.decoders = decoders;
            this.decodeLength = decodeLength;
            this.encodedLength = encodedLength;
            this.payloadLength = payloadLength;
            this.receiveLength = receiveLength;
            this.stride = stride;
        }

        int fixtureCount() {
            return decoders.length;
        }

        void prepare() {
            for (int i = 0; i < decoders.length; i++) {
                MemorySegment.copy(
                    pristine, 0, work, (long) i * stride, encodedLength);
                ChunkedDecoder decoder = decoders[i];
                decoder.reset();
                decoder.consumeTrailer = true;
            }
        }

        long decodeBatch(boolean validate) {
            long checksum = 0;
            for (int fixture = 0; fixture < decoders.length; fixture++) {
                long base = (long) fixture * stride;
                int encodedOffset = 0;
                int decoded = 0;
                long result = PicoHTTPParser.ERROR_PARTIAL;
                while (encodedOffset < encodedLength) {
                    int count = Math.min(
                        receiveLength, encodedLength - encodedOffset);
                    decodeLength[0] = count;
                    result = PicoHTTPParser.decodeChunked(
                        decoders[fixture],
                        work,
                        base + encodedOffset,
                        decodeLength
                    );
                    int produced = Math.toIntExact(decodeLength[0]);
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
    }
}
