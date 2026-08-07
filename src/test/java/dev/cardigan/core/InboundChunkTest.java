// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InboundChunkTest {

    @Test
    void returnsProvidedBufferExactlyOnceAfterLastReference() {
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

        chunk.retain();
        assertEquals(2, chunk.referenceCount());

        chunk.close();
        assertEquals(0, releases.get());
        assertEquals(1, chunk.referenceCount());

        chunk.close();
        assertEquals(1, releases.get());
        assertEquals(17, releasedBuffer.get());
        assertEquals(0, chunk.referenceCount());

        assertThrows(IllegalStateException.class, chunk::retain);
        assertThrows(IllegalStateException.class, chunk::close);
        assertEquals(1, releases.get());
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
}
