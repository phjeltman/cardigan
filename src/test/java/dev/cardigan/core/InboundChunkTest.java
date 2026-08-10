// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboundChunkTest {

    @Test
    void exclusiveChunkReturnsProvidedBufferExactlyOnce() {
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger releasedBuffer = new AtomicInteger(-1);
        InboundChunk chunk = new InboundChunk(
            MemorySegment.ofArray(new byte[64]),
            17,
            23,
            bufferId -> {
                releasedBuffer.set(bufferId);
                releases.incrementAndGet();
            }
        );

        chunk.close();
        assertEquals(1, releases.get());
        assertEquals(17, releasedBuffer.get());

        assertThrows(IllegalStateException.class, chunk::close);
        assertEquals(1, releases.get());
    }

    @Test
    void reusableChunkCyclesOnlyAfterPendingReturnIsPublished()
            throws Exception {
        InboundChunk chunk = reusableChunk(
            MemorySegment.ofArray(new byte[64]), 9);

        assertTrue(chunk.isProvided());
        assertSame(chunk, chunk.lease(23));
        assertFalse(chunk.isProvided());
        assertEquals(23, chunk.length());
        assertThrows(IllegalStateException.class, () -> chunk.lease(8));

        chunk.beginOwnerReturn();
        assertFalse(chunk.isProvided());
        assertThrows(IllegalStateException.class, chunk::beginOwnerReturn);
        assertThrows(IllegalStateException.class, () -> chunk.lease(8));

        chunk.markProvided();
        assertTrue(chunk.isProvided());
        assertEquals(0, chunk.length());
        assertSame(chunk, chunk.lease(8));
        assertEquals(8, chunk.length());
        chunk.beginOwnerReturn();
        chunk.markProvided();
    }

    @Test
    void externalReturnClaimsExclusiveLeaseExactlyOnce() throws Exception {
        InboundChunk chunk = reusableChunk(
            MemorySegment.ofArray(new byte[32]), 4);
        chunk.lease(16);

        chunk.beginExternalReturn();
        assertThrows(IllegalStateException.class, chunk::beginExternalReturn);
        assertFalse(chunk.isProvided());

        chunk.markProvided();
        assertTrue(chunk.isProvided());
    }

    @Test
    void validatesReadableLengthAgainstCapacity() {
        InboundChunk chunk = new InboundChunk(
            MemorySegment.ofArray(new byte[32]),
            3,
            8,
            ignored -> {
            }
        );

        chunk.length(32);
        assertEquals(32, chunk.length());
        assertThrows(IllegalArgumentException.class, () -> chunk.length(33));
        chunk.close();
    }

    private static InboundChunk reusableChunk(
            MemorySegment segment, int bufferId) throws Exception {
        Constructor<InboundChunk> constructor =
            InboundChunk.class.getDeclaredConstructor(
                UringEventLoop.class,
                MemorySegment.class,
                int.class,
                int.class,
                IntConsumer.class,
                boolean.class
            );
        constructor.setAccessible(true);
        return constructor.newInstance(
            null, segment, bufferId, 0, (IntConsumer) ignored -> {}, true);
    }
}
