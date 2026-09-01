// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@Timeout(10)
class ConnectionWriterOwnershipIntegrationTest {
    @Test
    void terminalScalarFailureReturnsOwnedBuffer() throws Exception {
        try (UringEventLoop loop = new UringEventLoop(0, 64)) {
            ConnectionWriter writer = new ConnectionWriter(loop, -1, -1);
            int capacity = onLoop(loop, loop::egressBufferCapacity);

            assertTrue(onLoop(loop, () -> {
                int bufferId = loop.acquireEgressBuffer();
                return writer.enqueueOwned(bufferId, 1);
            }));
            assertFalse(awaitDrain(loop, writer));
            assertEquals(
                capacity,
                onLoop(loop, loop::availableEgressBuffers));

            assertFalse(onLoop(loop, () -> {
                int bufferId = loop.acquireEgressBuffer();
                return writer.enqueueOwned(bufferId, 1);
            }));
            assertEquals(
                capacity,
                onLoop(loop, loop::availableEgressBuffers));
        }
    }

    @Test
    void terminalVectorFailureReturnsEveryOwnedBuffer() throws Exception {
        try (UringEventLoop loop = new UringEventLoop(0, 64)) {
            ConnectionWriter writer = new ConnectionWriter(loop, -1, -1);
            int capacity = onLoop(loop, loop::egressBufferCapacity);

            assertTrue(onLoop(loop, () -> {
                int first = loop.acquireEgressBuffer();
                int second = loop.acquireEgressBuffer();
                return writer.enqueueOwned(
                        first, UringEventLoop.EGRESS_FRAME_SIZE)
                    && writer.enqueueOwned(
                        second, UringEventLoop.EGRESS_FRAME_SIZE);
            }));
            assertFalse(awaitDrain(loop, writer));
            assertEquals(
                capacity,
                onLoop(loop, loop::availableEgressBuffers));
        }
    }

    @Test
    void queuedOwnedBufferSupportsCommittedAndAbortedAppends()
            throws Exception {
        try (UringEventLoop loop = new UringEventLoop(0, 64)) {
            ConnectionWriter writer = new ConnectionWriter(loop, -1, -1);
            int capacity = onLoop(loop, loop::egressBufferCapacity);

            assertTrue(onLoop(loop, () -> {
                int bufferId = loop.acquireEgressBuffer();
                MemorySegment buffer =
                    loop.getEgressBufferSegment(bufferId);
                buffer.set(ValueLayout.JAVA_BYTE, 0, (byte) 'A');
                assertTrue(writer.enqueueOwned(bufferId, 1));

                MemorySegment aborted = writer.beginOwnedAppend(3);
                assertNotNull(aborted);
                aborted.set(ValueLayout.JAVA_BYTE, 0, (byte) 'B');
                writer.abortOwnedAppend();

                MemorySegment fullTail = writer.beginOwnedAppend(
                    UringEventLoop.EGRESS_FRAME_SIZE - 1);
                assertNotNull(fullTail);
                writer.abortOwnedAppend();

                MemorySegment committed = writer.beginOwnedAppend(8);
                assertNotNull(committed);
                committed.set(ValueLayout.JAVA_BYTE, 0, (byte) 'C');
                committed.set(ValueLayout.JAVA_BYTE, 1, (byte) 'D');
                committed.set(ValueLayout.JAVA_BYTE, 2, (byte) 'E');
                writer.commitOwnedAppend(3);

                assertEquals(
                    (byte) 'A', buffer.get(ValueLayout.JAVA_BYTE, 0));
                assertEquals(
                    (byte) 'C', buffer.get(ValueLayout.JAVA_BYTE, 1));
                assertEquals(
                    (byte) 'D', buffer.get(ValueLayout.JAVA_BYTE, 2));
                assertEquals(
                    (byte) 'E', buffer.get(ValueLayout.JAVA_BYTE, 3));
                assertNull(writer.beginOwnedAppend(
                    UringEventLoop.EGRESS_FRAME_SIZE - 3));
                return true;
            }));

            assertFalse(awaitDrain(loop, writer));
            assertEquals(
                capacity,
                onLoop(loop, loop::availableEgressBuffers));
        }
    }

    private static boolean awaitDrain(
            UringEventLoop loop,
            ConnectionWriter writer) throws Exception {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        loop.applicationRuntime().startTask(
            () -> result.complete(writer.awaitDrained()));
        return result.get(5, TimeUnit.SECONDS);
    }

    private static <T> T onLoop(
            UringEventLoop loop,
            Supplier<T> operation) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        loop.executeProtocol(() -> {
            try {
                result.complete(operation.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.get(5, TimeUnit.SECONDS);
    }
}
