// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http2;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/** Benchmarks the two HPACK Huffman entry points used by request decoding. */
public final class HpackHuffmanBenchmark {
    private static final int WARMUP_SECONDS = 1;
    private static final int RUN_SECONDS = 2;
    private static final int BATCH_SIZE = 1_024;

    private static volatile long blackhole;

    private HpackHuffmanBenchmark() {
    }

    public static void run() {
        System.out.println("\n--- HPACK Huffman decoding ---");

        byte[] corpus = (
            "get /users/423?filter=active&sort=name host example.com "
                + "user-agent cardigan accept application/json "
                + "authorization bearer abcdef0123456789 "
                + "cookie session=0123456789abcdef; theme=dark ").getBytes(
                    StandardCharsets.US_ASCII);

        try (Arena arena = Arena.ofConfined()) {
            runCase(arena, "common header bytes, 32 B", repeat(corpus, 32));
            runCase(arena, "common header bytes, 256 B", repeat(corpus, 256));
            runCase(arena, "common header bytes, 2 KiB", repeat(corpus, 2_048));

            byte[] longCodes = new byte[64];
            for (int i = 0; i < longCodes.length; i++) {
                longCodes[i] = (byte) (128 + (i * 37 & 0x7f));
            }
            runCase(arena, "long-code header bytes, 64 B", longCodes);
        }
    }

    private static void runCase(
            Arena arena, String label, byte[] decoded) {
        byte[] encoded = encode(decoded);
        MemorySegment source = arena.allocate(encoded.length);
        MemorySegment.copy(
            MemorySegment.ofArray(encoded), 0, source, 0, encoded.length);
        MemorySegment destination = arena.allocate(decoded.length + 16L);

        verifyFixture(source, encoded.length, destination, decoded);
        warmup(source, encoded.length, destination, decoded.length);
        System.out.printf("  %s:%n", label);
        measure(
            "header-only native-address path", encoded.length,
            () -> HpackHuffman.decodeAddress(
                source.address(), encoded.length, destination.address(),
                0, decoded.length));
        measure(
            "eager segment path", encoded.length,
            () -> HpackHuffman.decode(
                source, 0, encoded.length, destination, 0, decoded.length));
    }

    private static void verifyFixture(
            MemorySegment source, int encodedLength,
            MemorySegment destination, byte[] expected) {
        int addressLength = HpackHuffman.decodeAddress(
            source.address(), encodedLength, destination.address(),
            0, expected.length);
        if (addressLength != expected.length
                || MemorySegment.mismatch(
                    destination, 0, expected.length,
                    MemorySegment.ofArray(expected), 0, expected.length) != -1) {
            throw new IllegalStateException(
                "Native-address Huffman decoder rejected fixture");
        }
        int segmentLength = HpackHuffman.decode(
            source, 0, encodedLength, destination, 0, expected.length);
        if (segmentLength != expected.length
                || MemorySegment.mismatch(
                    destination, 0, expected.length,
                    MemorySegment.ofArray(expected), 0, expected.length) != -1) {
            throw new IllegalStateException(
                "Segment Huffman decoder rejected fixture");
        }
    }

    private static void warmup(
            MemorySegment source, int encodedLength,
            MemorySegment destination, int destinationLimit) {
        long checksum = 0;
        long deadline =
            System.nanoTime() + WARMUP_SECONDS * 1_000_000_000L;
        do {
            for (int i = 0; i < BATCH_SIZE; i++) {
                checksum += HpackHuffman.decodeAddress(
                    source.address(), encodedLength, destination.address(),
                    0, destinationLimit);
                checksum += HpackHuffman.decode(
                    source, 0, encodedLength, destination,
                    0, destinationLimit);
            }
        } while (System.nanoTime() < deadline);
        blackhole = checksum + destination.get(ValueLayout.JAVA_BYTE, 0);
    }

    private static void measure(
            String label, int encodedLength, Decoder decoder) {
        long operations = 0;
        long checksum = 0;
        long start = System.nanoTime();
        long deadline = start + RUN_SECONDS * 1_000_000_000L;
        do {
            for (int i = 0; i < BATCH_SIZE; i++) {
                checksum += decoder.decode();
            }
            operations += BATCH_SIZE;
        } while (System.nanoTime() < deadline);
        long elapsed = System.nanoTime() - start;
        blackhole = checksum;
        double seconds = elapsed / 1_000_000_000.0;
        System.out.printf(
            "    %-34s %9.2f ns/op  %8.2f MiB/s%n",
            label + ":", (double) elapsed / operations,
            operations * (double) encodedLength / (1 << 20) / seconds);
    }

    private static byte[] repeat(byte[] source, int length) {
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) {
            result[i] = source[i % source.length];
        }
        return result;
    }

    private static byte[] encode(byte[] decoded) {
        long totalBits = 0;
        for (byte value : decoded) {
            totalBits += HpackHuffman.LENGTHS[value & 0xff] & 0xff;
        }
        byte[] encoded = new byte[(int) ((totalBits + 7) >>> 3)];
        int bitPosition = 0;
        for (byte value : decoded) {
            int symbol = value & 0xff;
            int code = HpackHuffman.CODES[symbol];
            int length = HpackHuffman.LENGTHS[symbol] & 0xff;
            for (int bit = length - 1; bit >= 0; bit--) {
                if (((code >>> bit) & 1) != 0) {
                    encoded[bitPosition >>> 3] |=
                        (byte) (1 << (7 - (bitPosition & 7)));
                }
                bitPosition++;
            }
        }
        while ((bitPosition & 7) != 0) {
            encoded[bitPosition >>> 3] |=
                (byte) (1 << (7 - (bitPosition & 7)));
            bitPosition++;
        }
        return encoded;
    }

    @FunctionalInterface
    private interface Decoder {
        int decode();
    }
}
