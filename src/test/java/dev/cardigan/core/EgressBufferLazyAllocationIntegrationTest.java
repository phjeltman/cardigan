// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@Tag("integration")
@Timeout(10)
class EgressBufferLazyAllocationIntegrationTest {
    @Test
    void allocatesOnDemandInSlabsAndRetainsAllocatedStorage() throws Exception {
        try (UringEventLoop loop = new UringEventLoop(0, 64)) {
            int capacity = onLoop(loop, loop::egressBufferCapacity);
            assertEquals(0, onLoop(loop, loop::allocatedEgressSlabs));
            assertEquals(0L, onLoop(loop, loop::allocatedEgressBytes));
            assertEquals(capacity, onLoop(loop, loop::availableEgressBuffers));

            AcquiredBuffer first = onLoop(loop, () -> acquire(loop));
            assertEquals(1, onLoop(loop, loop::allocatedEgressSlabs));
            assertEquals(
                UringEventLoop.EGRESS_SLAB_SIZE,
                onLoop(loop, loop::allocatedEgressBytes)
            );

            onLoop(loop, () -> {
                loop.releaseEgressBuffer(first.id());
                return null;
            });
            AcquiredBuffer reused = onLoop(loop, () -> acquire(loop));
            assertEquals(first.id(), reused.id());
            assertEquals(first.address(), reused.address());

            int[] held = onLoop(loop, () -> {
                int[] ids = new int[UringEventLoop.EGRESS_BUFFERS_PER_SLAB];
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = loop.acquireEgressBuffer();
                }
                return ids;
            });
            assertEquals(2, onLoop(loop, loop::allocatedEgressSlabs));
            assertNotEquals(
                reused.address(),
                onLoop(
                    loop,
                    () -> loop.getEgressBufferSegment(
                        held[held.length - 1]).address()
                )
            );

            onLoop(loop, () -> {
                loop.releaseEgressBuffer(reused.id());
                for (int id : held) {
                    loop.releaseEgressBuffer(id);
                }
                return null;
            });
            assertEquals(
                capacity,
                onLoop(loop, loop::availableEgressBuffers)
            );
            assertEquals(2, onLoop(loop, loop::allocatedEgressSlabs));
            assertEquals(
                2L * UringEventLoop.EGRESS_SLAB_SIZE,
                onLoop(loop, loop::allocatedEgressBytes)
            );
        }
    }

    private static AcquiredBuffer acquire(UringEventLoop loop) {
        int id = loop.acquireEgressBuffer();
        MemorySegment segment = loop.getEgressBufferSegment(id);
        return new AcquiredBuffer(id, segment.address());
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

    private record AcquiredBuffer(int id, long address) {}
}
