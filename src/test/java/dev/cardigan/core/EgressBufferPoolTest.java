// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EgressBufferPoolTest {
    @Test
    void allocatesLazilyAndReturnsLocalContentsAtClose() {
        EgressBufferPool pool = new EgressBufferPool(8, 128, 4);
        int[] localIds = new int[8];
        pool.registerLocal();
        assertEquals(0, pool.stats().allocatedBuffers());

        int supplied = pool.refill(localIds, 0, localIds.length);
        assertEquals(8, supplied);
        int bufferId = localIds[--supplied];
        assertNotEquals(-1, bufferId);
        MemorySegment segment = pool.segment(bufferId);
        assertEquals(128, segment.byteSize());
        segment.set(ValueLayout.JAVA_LONG, 0, 0x1234_5678L);
        assertEquals(
            0x1234_5678L,
            pool.segment(bufferId).get(ValueLayout.JAVA_LONG, 0));

        localIds[supplied++] = bufferId;
        pool.closeLocal(
            localIds, supplied, 0, 1,
            1, 0, 1, 1, 8, 0);
        EgressBufferPool.Stats stats = pool.stats();
        assertEquals(8, stats.allocatedBuffers());
        assertEquals(8, stats.sharedFreeBuffers());
        assertEquals(0, stats.localFreeBuffers());
        pool.close();
    }

    @Test
    void reportsBoundedExhaustionWithoutReusingALiveLease() {
        EgressBufferPool pool = new EgressBufferPool(6, 64, 3);
        int[] leasedIds = new int[6];
        int[] attemptedRefill = new int[1];
        pool.registerLocal();
        assertEquals(6, pool.refill(leasedIds, 0, leasedIds.length));
        Set<Integer> leased = new HashSet<>();
        for (int bufferId : leasedIds) {
            assertTrue(leased.add(bufferId));
        }
        assertEquals(0, pool.refill(attemptedRefill, 0, 1));
        assertEquals(1, pool.stats().capacityExhaustions());

        pool.closeLocal(
            leasedIds, leasedIds.length, 0, 6,
            2, 0, 6, 6, 6, 0);
        pool.close();
    }

    @Test
    void transfersOnlyFreeBuffersBetweenLoopLocals() {
        EgressBufferPool pool = new EgressBufferPool(96, 64, 32);
        int[] first = new int[64];
        int[] second = new int[32];
        pool.registerLocal();
        pool.registerLocal();
        assertEquals(64, pool.refill(first, 0, first.length));

        pool.spill(first, 32, 32);
        assertEquals(32, pool.stats().sharedFreeBuffers());
        assertEquals(32, pool.refill(second, 0, second.length));

        pool.closeLocal(
            first, 32, 0, 64,
            1, 1, 64, 64, 64, 32);
        pool.closeLocal(
            second, 32, 0, 32,
            1, 0, 32, 32, 32, 0);
        assertEquals(
            pool.stats().allocatedBuffers(),
            pool.stats().sharedFreeBuffers());
        pool.close();
    }

    @Test
    void refusesToCloseALocalWithAnOutstandingLease() {
        EgressBufferPool pool = new EgressBufferPool(4, 64, 4);
        int[] localIds = new int[4];
        pool.registerLocal();
        int localCount = pool.refill(localIds, 0, localIds.length);
        int bufferId = localIds[--localCount];

        int remaining = localCount;
        assertThrows(
            IllegalStateException.class,
            () -> pool.closeLocal(
                localIds, remaining, 1, 1,
                1, 0, 1, 0, 4, 0));
        localIds[localCount++] = bufferId;
        pool.closeLocal(
            localIds, localCount, 0, 1,
            1, 0, 1, 1, 4, 0);
        pool.close();
    }

    @Test
    void refusesToCloseBeforeAllLocalsHaveClosed() {
        EgressBufferPool pool = new EgressBufferPool(4, 64, 4);
        int[] localIds = new int[4];
        pool.registerLocal();

        assertThrows(IllegalStateException.class, pool::close);
        pool.closeLocal(
            localIds, 0, 0, 0,
            0, 0, 0, 0, 0, 0);
        pool.close();
    }
}
