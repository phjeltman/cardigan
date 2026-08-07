// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingBodyTest {
    @Test
    void validatesProducerCountsAndClosesOnce() {
        AtomicInteger offset = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        StreamingBody body = StreamingBody.of(
            7,
            destination -> {
                int remaining = 7 - offset.get();
                if (remaining == 0) {
                    return -1;
                }
                int count = Math.min(
                    remaining,
                    Math.toIntExact(destination.byteSize()));
                destination.asSlice(0, count).fill((byte) 'x');
                offset.addAndGet(count);
                return count;
            },
            closes::incrementAndGet
        );

        byte[] bytes = new byte[7];
        assertEquals(4, body.read(MemorySegment.ofArray(bytes).asSlice(0, 4)));
        assertEquals(3, body.read(MemorySegment.ofArray(bytes).asSlice(4)));
        assertEquals(-1, body.read(MemorySegment.ofArray(bytes)));
        body.close();
        body.close();
        assertEquals(1, closes.get());
        for (byte value : bytes) {
            assertEquals((byte) 'x', value);
        }
    }

    @Test
    void rejectsProducerOverflow() {
        StreamingBody body = StreamingBody.of(
            1, destination -> Math.toIntExact(destination.byteSize()) + 1);
        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> body.read(MemorySegment.ofArray(new byte[1])));
        assertTrue(failure.getMessage().contains("invalid byte count"));
    }

    @Test
    void representsUnknownLengthAndRejectsZeroProgress() {
        StreamingBody body = StreamingBody.unknownLength(
            destination -> 0);

        assertEquals(StreamingBody.UNKNOWN_LENGTH, body.length());
        assertFalse(body.hasKnownLength());
        assertThrows(
            IllegalStateException.class,
            () -> body.read(MemorySegment.ofArray(new byte[1])));
        assertThrows(
            IllegalArgumentException.class,
            () -> StreamingBody.of(-1, destination -> -1));
    }

    @Test
    void closeMakesAConcurrentProducerTerminal() {
        AtomicBoolean entered = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        AtomicInteger closes = new AtomicInteger();
        Thread[] readerThread = new Thread[1];
        StreamingBody body = StreamingBody.unknownLength(
            destination -> {
                readerThread[0] = Thread.currentThread();
                entered.set(true);
                while (!released.get()) {
                    java.util.concurrent.locks.LockSupport.park();
                }
                destination.set(
                    java.lang.foreign.ValueLayout.JAVA_BYTE,
                    0,
                    (byte) 'x'
                );
                return 1;
            },
            () -> {
                closes.incrementAndGet();
                released.set(true);
                java.util.concurrent.locks.LockSupport.unpark(readerThread[0]);
            }
        );

        Thread reader = Thread.ofVirtual().start(() -> assertEquals(
            -1, body.read(MemorySegment.ofArray(new byte[1]))));
        while (!entered.get()) {
            Thread.onSpinWait();
        }
        body.close();
        body.close();
        try {
            reader.join();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
        assertEquals(1, closes.get());
        assertTrue(body.isClosed());
    }
}
