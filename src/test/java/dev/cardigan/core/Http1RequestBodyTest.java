// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.RequestBodyException;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Http1RequestBodyTest {
    private static final byte[] PIPELINED_REQUEST =
        "GET /next HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    @Test
    void decodesExtensionsAndTrailersAcrossEverySplitBoundary() {
        byte[] encoded = concat(
            ("4;foo=bar; quoted=\"a\\\"b\"\r\n"
                + "Wiki\r\n"
                + "5\r\npedia\r\n"
                + "0\r\nX-Checksum: accepted\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII),
            PIPELINED_REQUEST
        );

        for (int split = 1; split < encoded.length; split++) {
            Fixture fixture = fixture(encoded, split, 64, 512);
            try (fixture; Arena arena = Arena.ofConfined()) {
                Http1RequestBody body = fixture.body;
                MemorySegment scratch = arena.allocate(3);
                ByteArrayOutputStream decoded = new ByteArrayOutputStream();
                int count;
                while ((count = body.read(scratch)) >= 0) {
                    decoded.writeBytes(scratch.asSlice(0, count)
                        .toArray(ValueLayout.JAVA_BYTE));
                }

                assertEquals(-1, body.length());
                assertEquals(-1, body.remaining());
                assertArrayEquals(
                    "Wikipedia".getBytes(StandardCharsets.US_ASCII),
                    decoded.toByteArray(),
                    "split at byte " + split
                );
                assertTrue(body.discardRemaining());
                assertArrayEquals(
                    PIPELINED_REQUEST,
                    fixture.takeLeftover(),
                    "split at byte " + split
                );
            }
        }
    }

    @Test
    void discardsUnreadDecodedDataAndPreservesNextRequest() {
        byte[] encoded = concat(
            "3\r\nabc\r\n4\r\ndefg\r\n0\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII),
            PIPELINED_REQUEST
        );
        Fixture fixture = fixture(encoded, 11, 64, 256);
        try (fixture; Arena arena = Arena.ofConfined()) {
            MemorySegment first = arena.allocate(2);
            assertEquals(2, fixture.body.read(first));
            assertArrayEquals(
                new byte[] {'a', 'b'},
                first.toArray(ValueLayout.JAVA_BYTE));
            assertTrue(fixture.body.discardRemaining());
            assertArrayEquals(PIPELINED_REQUEST, fixture.takeLeftover());
        }
    }

    @Test
    void rejectsMalformedPicoChunkFraming() {
        assertFailure("z\r\n", 400, 64, 256);
        assertFailure("1\r\naX\n0\r\n\r\n", 400, 64, 256);
        assertFailure("1x\r\na\r\n0\r\n\r\n", 400, 64, 256);
        assertFailure("1\na\r\n0\r\n\r\n", 400, 64, 256);
    }

    @Test
    void enforcesDecodedAndMetadataLimits() {
        assertFailure("5\r\nhello\r\n0\r\n\r\n", 413, 4, 256);
        assertFailure("1;extension=value\r\na\r\n0\r\n\r\n", 431, 64, 8);
    }

    @Test
    void reportsPrematureEndWithoutTryingToRespond() {
        Fixture fixture = fixture(
            "5\r\nabc".getBytes(StandardCharsets.US_ASCII),
            3,
            64,
            256
        );
        try (fixture; Arena arena = Arena.ofConfined()) {
            MemorySegment destination = arena.allocate(8);
            assertEquals(3, fixture.body.read(destination));
            assertThrows(
                RequestBodyException.class,
                () -> fixture.body.read(destination));
            assertEquals(0, fixture.body.failureStatus());
            assertTrue(!fixture.body.discardRemaining());
        }
    }

    private static void assertFailure(
        String input,
        int expectedStatus,
        long maximumDecodedLength,
        int maximumMetadataLength
    ) {
        byte[] bytes = input.getBytes(StandardCharsets.US_ASCII);
        Fixture fixture = fixture(
            bytes,
            Math.max(1, bytes.length / 2),
            maximumDecodedLength,
            maximumMetadataLength
        );
        try (fixture; Arena arena = Arena.ofConfined()) {
            MemorySegment destination = arena.allocate(8);
            assertThrows(RequestBodyException.class, () -> {
                while (fixture.body.read(destination) >= 0) {
                    // Drain until the malformed framing is reached.
                }
            });
            assertEquals(expectedStatus, fixture.body.failureStatus());
            assertTrue(!fixture.body.discardRemaining());
        }
    }

    private static Fixture fixture(
        byte[] bytes,
        int split,
        long maximumDecodedLength,
        int maximumMetadataLength
    ) {
        List<Integer> released = new ArrayList<>();
        InboundChunk initial = chunk(
            bytes, 0, split, 0, released);
        QueueReceiver receiver = new QueueReceiver();
        receiver.add(chunk(
            bytes, split, bytes.length - split, 1, released));
        InboundChunkStream inbound = new InboundChunkStream(receiver);
        Http1RequestBody body = Http1RequestBody.chunked(
            inbound,
            initial,
            0,
            maximumDecodedLength,
            maximumMetadataLength
        );
        return new Fixture(body, inbound, receiver, released);
    }

    private static InboundChunk chunk(
        byte[] source,
        int offset,
        int length,
        int id,
        List<Integer> released
    ) {
        byte[] copy = new byte[length];
        System.arraycopy(source, offset, copy, 0, length);
        return new InboundChunk(
            MemorySegment.ofArray(copy), id, length, released::add);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static final class QueueReceiver implements InboundReceiver {
        private final ArrayDeque<InboundChunk> chunks = new ArrayDeque<>();

        void add(InboundChunk chunk) {
            chunks.add(chunk);
        }

        @Override
        public void start() {
        }

        @Override
        public InboundChunk receive() {
            return chunks.poll();
        }

        @Override
        public void close() {
            InboundChunk chunk;
            while ((chunk = chunks.poll()) != null) {
                chunk.close();
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Http1RequestBody body;
        private final InboundChunkStream inbound;
        private final QueueReceiver receiver;
        @SuppressWarnings("unused")
        private final List<Integer> released;

        private Fixture(
            Http1RequestBody body,
            InboundChunkStream inbound,
            QueueReceiver receiver,
            List<Integer> released
        ) {
            this.body = body;
            this.inbound = inbound;
            this.receiver = receiver;
            this.released = released;
        }

        byte[] takeLeftover() {
            InboundChunk chunk = body.takeChunk();
            if (chunk == null) {
                chunk = inbound.nextChunk();
            }
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            while (chunk != null) {
                try {
                    result.writeBytes(
                        chunk.segment().asSlice(0, chunk.length())
                            .toArray(ValueLayout.JAVA_BYTE));
                } finally {
                    chunk.close();
                }
                chunk = inbound.nextChunk();
            }
            return result.toByteArray();
        }

        @Override
        public void close() {
            body.release();
            inbound.close();
            receiver.close();
        }
    }
}
