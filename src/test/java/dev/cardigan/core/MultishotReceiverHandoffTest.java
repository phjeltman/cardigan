// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.foreign.Arena;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultishotReceiverHandoffTest {
    private static final Method SET_RECEIVE_WAITER = method(
        "setReceiveWaiter", Thread.class);
    private static final Method RECEIVE_WAITER = method("receiveWaiter");
    private static final Method TRY_HANDOFF = method(
        "tryHandoff", InboundChunk.class);
    private static final Method TAKE_HANDOFF = method("takeHandoff");
    private static final Method SIGNAL_RECEIVE_WAITER = method(
        "signalReceiveWaiter");

    @Test
    void ownerSerializedHandoffIsConsumedOnceAndCanBeReused()
            throws Exception {
        MultishotReceiver receiver = receiver();
        AtomicInteger releases = new AtomicInteger();

        try (Arena arena = Arena.ofConfined()) {
            InboundChunk first = new InboundChunk(
                arena.allocate(8), 3, 8, ignored -> releases.incrementAndGet());
            InboundChunk second = new InboundChunk(
                arena.allocate(8), 4, 8, ignored -> releases.incrementAndGet());

            setWaiter(receiver, Thread.ofPlatform().unstarted(() -> {}));
            assertTrue(tryHandoff(receiver, first));
            assertNull(receiveWaiter(receiver));
            assertSame(first, takeHandoff(receiver));
            assertNull(takeHandoff(receiver));

            setWaiter(receiver, Thread.ofPlatform().unstarted(() -> {}));
            assertTrue(tryHandoff(receiver, second));
            assertNull(receiveWaiter(receiver));
            assertSame(second, takeHandoff(receiver));
            assertNull(takeHandoff(receiver));

            first.close();
            second.close();
        }

        assertEquals(2, releases.get());
    }

    @Test
    void noWaiterLeavesChunkWithProducer() throws Exception {
        MultishotReceiver receiver = receiver();
        AtomicInteger releases = new AtomicInteger();

        try (Arena arena = Arena.ofConfined()) {
            InboundChunk chunk = new InboundChunk(
                arena.allocate(8), 5, 8, ignored -> releases.incrementAndGet());

            assertFalse(tryHandoff(receiver, chunk));
            assertNull(takeHandoff(receiver));
            chunk.close();
        }

        assertEquals(1, releases.get());
    }

    @Test
    void ownerSerializedSignalClearsExactlyOneWaiter() throws Exception {
        MultishotReceiver receiver = receiver();
        Thread waiter = Thread.ofPlatform().unstarted(() -> {});

        setWaiter(receiver, waiter);
        SIGNAL_RECEIVE_WAITER.invoke(receiver);
        assertNull(receiveWaiter(receiver));

        // A repeated terminal/error signal observes the already-cleared slot.
        SIGNAL_RECEIVE_WAITER.invoke(receiver);
        assertNull(receiveWaiter(receiver));
    }

    private static MultishotReceiver receiver() {
        return new MultishotReceiver(null, -1, -1, new Observer());
    }

    private static void setWaiter(
            MultishotReceiver receiver, Thread waiter) throws Exception {
        SET_RECEIVE_WAITER.invoke(receiver, waiter);
    }

    private static Thread receiveWaiter(MultishotReceiver receiver)
            throws Exception {
        return (Thread) RECEIVE_WAITER.invoke(receiver);
    }

    private static boolean tryHandoff(
            MultishotReceiver receiver, InboundChunk chunk) throws Exception {
        return (boolean) TRY_HANDOFF.invoke(receiver, chunk);
    }

    private static InboundChunk takeHandoff(MultishotReceiver receiver)
            throws Exception {
        return (InboundChunk) TAKE_HANDOFF.invoke(receiver);
    }

    private static Method method(String name, Class<?>... parameterTypes) {
        try {
            Method method = MultishotReceiver.class.getDeclaredMethod(
                name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static final class Observer
            implements MultishotReceiver.Observer {
        @Override
        public void multishotCompletionObserved() {
        }

        @Override
        public void fallbackActivated() {
        }

        @Override
        public void backpressureActivated() {
        }

        @Override
        public void requiredFeatureRejected(String feature, int result) {
        }
    }
}
