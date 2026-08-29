// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MpscArrayQueueTest {
    private static final VarHandle LONG_ARRAY_HANDLE =
        MethodHandles.arrayElementVarHandle(long[].class);

    @Test
    void preservesRoundedCapacityAndFifoOrder() {
        UringEventLoop.MpscArrayQueue<Integer> queue =
            new UringEventLoop.MpscArrayQueue<>(3);

        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertTrue(queue.offer(0));
        assertTrue(queue.offer(1));
        assertTrue(queue.offer(2));
        assertTrue(queue.offer(3));
        assertFalse(queue.offer(4));
        long snapshotTail = queue.snapshotTail();

        assertEquals(0, queue.pollSnapshot(snapshotTail));
        assertTrue(queue.offer(4));
        assertEquals(1, queue.pollSnapshot(snapshotTail));
        assertEquals(2, queue.pollSnapshot(snapshotTail));
        assertEquals(3, queue.pollSnapshot(snapshotTail));
        assertNull(queue.pollSnapshot(snapshotTail),
            "a claim after the snapshot boundary must be deferred");
        assertEquals(4, queue.poll());
        assertNull(queue.poll());
        assertTrue(queue.isEmpty());
    }

    @Test
    void consumerDoesNotSpinBehindAnUnpublishedReservation() throws Exception {
        UringEventLoop.MpscArrayQueue<String> queue =
            new UringEventLoop.MpscArrayQueue<>(4);
        AtomicLong tail = field(queue, "tail", AtomicLong.class);
        Object[] buffer = field(queue, "buffer", Object[].class);
        long[] sequences = field(queue, "sequences", long[].class);

        // Model a producer preempted after claiming position zero but before
        // publishing it. A later producer can publish its own position.
        assertTrue(tail.compareAndSet(0, 1));
        assertTrue(queue.offer("second"));
        long snapshotTail = queue.snapshotTail();

        AtomicReference<String> result = new AtomicReference<>("not-run");
        Thread consumer = Thread.ofPlatform().daemon(true).start(
            () -> result.set(queue.pollSnapshot(snapshotTail)));
        consumer.join(1_000);
        boolean returnedWithoutPublication = !consumer.isAlive();

        // Publish the claimed head slot, also allowing a failed implementation
        // to finish so the test does not leave a spinning thread behind.
        buffer[0] = "first";
        LONG_ARRAY_HANDLE.setRelease(sequences, 0, 1L);
        if (!returnedWithoutPublication) {
            consumer.join(1_000);
        }

        assertTrue(returnedWithoutPublication,
            "poll spun behind a producer that had only reserved the head slot");
        assertNull(result.get());
        long nextSnapshotTail = queue.snapshotTail();
        assertEquals("first", queue.pollSnapshot(nextSnapshotTail));
        assertEquals("second", queue.pollSnapshot(nextSnapshotTail));
        assertNull(queue.pollSnapshot(nextSnapshotTail));
        assertTrue(queue.isEmpty());
    }

    @Test
    void snapshotExcludesPositionsClaimedAfterItsTailBoundary() {
        UringEventLoop.MpscArrayQueue<Integer> queue =
            new UringEventLoop.MpscArrayQueue<>(4);

        assertTrue(queue.offer(1));
        long snapshotTail = queue.snapshotTail();
        assertTrue(queue.offer(2));

        assertEquals(1, queue.pollSnapshot(snapshotTail));
        assertNull(queue.pollSnapshot(snapshotTail));
        assertEquals(2, queue.poll(),
            "work published after capture belongs to the next snapshot");
    }

    @Test
    void concurrentProducersPublishEveryElementExactlyOnce() throws Exception {
        int producerCount = 4;
        int elementsPerProducer = 20_000;
        int totalElements = producerCount * elementsPerProducer;
        UringEventLoop.MpscArrayQueue<Integer> queue =
            new UringEventLoop.MpscArrayQueue<>(64);
        CountDownLatch producersReady = new CountDownLatch(producerCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread[] producers = new Thread[producerCount];

        for (int producer = 0; producer < producerCount; producer++) {
            int producerId = producer;
            producers[producer] = Thread.ofPlatform().daemon(true).start(() -> {
                producersReady.countDown();
                try {
                    start.await();
                    for (int i = 0; i < elementsPerProducer; i++) {
                        int value = producerId * elementsPerProducer + i;
                        while (!queue.offer(value)) {
                            Thread.onSpinWait();
                        }
                    }
                } catch (Throwable failure) {
                    producerFailure.compareAndSet(null, failure);
                }
            });
        }

        assertTrue(producersReady.await(2, TimeUnit.SECONDS));
        start.countDown();
        boolean[] seen = new boolean[totalElements];
        int received = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (received < totalElements && System.nanoTime() < deadline) {
            Throwable failure = producerFailure.get();
            if (failure != null) {
                fail("Producer failed", failure);
            }
            int beforeSnapshot = received;
            long snapshotTail = queue.snapshotTail();
            Integer value;
            while ((value = queue.pollSnapshot(snapshotTail)) != null) {
                assertTrue(value >= 0 && value < totalElements);
                assertFalse(seen[value], "duplicate value " + value);
                seen[value] = true;
                received++;
            }
            if (received == beforeSnapshot) {
                Thread.onSpinWait();
            }
        }

        for (Thread producer : producers) {
            producer.join(2_000);
            assertFalse(producer.isAlive(), "producer did not finish");
        }
        Throwable failure = producerFailure.get();
        if (failure != null) {
            fail("Producer failed", failure);
        }
        assertEquals(totalElements, received);
        assertTrue(queue.isEmpty());
    }

    private static <T> T field(
            Object target, String name, Class<T> fieldType) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return fieldType.cast(field.get(target));
    }
}
