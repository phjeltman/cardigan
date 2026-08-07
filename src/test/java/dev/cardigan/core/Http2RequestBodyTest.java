// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.RequestBodyException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Http2RequestBodyTest {
    @Test
    void wrapsRingAndCreditsOnlyConsumedBytes() {
        AtomicInteger credited = new AtomicInteger();
        Http2RequestBody body = new Http2RequestBody(
            12, 8, credited::addAndGet);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment first = arena.allocate(6);
            for (int i = 0; i < 6; i++) {
                first.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i,
                    (byte) i);
            }
            MemorySegment second = arena.allocate(6);
            for (int i = 0; i < 6; i++) {
                second.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i,
                    (byte) (i + 6));
            }
            MemorySegment output = arena.allocate(12);

            body.offer(first, 0, 6);
            assertEquals(4, body.read(output.asSlice(0, 4)));
            body.offer(second, 0, 6);
            assertEquals(8, body.read(output.asSlice(4, 8)));
            body.end();
            assertEquals(-1, body.read(output));
            assertEquals(12, credited.get());
            assertArrayEquals(
                new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11},
                output.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
        } finally {
            body.dispose();
        }
    }

    @Test
    void discardAndFailureCreditBufferedData() {
        AtomicInteger discardedCredit = new AtomicInteger();
        Http2RequestBody discarded = new Http2RequestBody(
            6, 8, discardedCredit::addAndGet);
        AtomicInteger failedCredit = new AtomicInteger();
        Http2RequestBody failed = new Http2RequestBody(
            6, 8, failedCredit::addAndGet);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment source = arena.allocate(6);
            discarded.offer(source, 0, 6);
            discarded.close();
            assertEquals(6, discardedCredit.get());
            assertEquals(0, discarded.remaining());

            failed.offer(source, 0, 6);
            failed.fail();
            assertEquals(6, failedCredit.get());
            assertThrows(
                RequestBodyException.class,
                () -> failed.read(source));
        } finally {
            discarded.dispose();
            failed.dispose();
        }
    }

    @Test
    void onlyRetainedBodiesUseSharedLifetime() {
        Http2RequestBody body = new Http2RequestBody(1, 8, ignored -> {});
        body.retainHandler();
        body.dispose();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment source = arena.allocate(1);
            assertDoesNotThrow(() -> body.offer(source, 0, 1));
            body.handlerComplete();
            assertThrows(
                IllegalStateException.class,
                () -> body.offer(source, 0, 1));
        }
    }
}
