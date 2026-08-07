// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import dev.cardigan.ffi.ThreadAffinity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
class IsolatedRouteAffinityTest {

    @Test
    void carrierRestoresAffinityInheritedFromPinnedCreator() throws Exception {
        byte[] processMask = ThreadAffinity.processMask();
        int firstCpu = firstCpu(processMask);
        assumeTrue(firstCpu >= 0 && cpuCount(processMask) > 1,
            "Affinity inheritance requires at least two allowed CPUs");

        AtomicReference<byte[]> pinnedParentMask = new AtomicReference<>();
        AtomicReference<byte[]> carrierMask = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread parent = Thread.ofPlatform().start(() -> {
            ForkJoinPool pool = null;
            try {
                assertEquals(0, ThreadAffinity.pinCurrentThread(firstCpu));
                pinnedParentMask.set(ThreadAffinity.currentMask());

                pool = new ForkJoinPool(
                    1,
                    IsolatedRouteExecutor::newCarrier,
                    null,
                    true
                );
                carrierMask.set(
                    pool.submit(ThreadAffinity::currentMask)
                        .get(5, TimeUnit.SECONDS)
                );
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                if (pool != null) {
                    pool.shutdownNow();
                }
            }
        });
        parent.join(5_000);

        assertTrue(!parent.isAlive(), "Pinned parent did not terminate");
        assertNull(failure.get(), "Affinity probe failed");
        assertEquals(1, cpuCount(pinnedParentMask.get()));
        assertArrayEquals(processMask, carrierMask.get(),
            "Isolated carrier retained its creator's pinned CPU mask");
    }

    @Test
    void explicitCpuListPinsCallingThread() throws Exception {
        byte[] processMask = ThreadAffinity.processMask();
        int cpu = firstCpu(processMask);
        assumeTrue(cpu >= 0, "No CPU is available to the test process");

        AtomicReference<byte[]> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                assertEquals(
                    0,
                    ThreadAffinity.setCurrentCpus(Integer.toString(cpu))
                );
                result.set(ThreadAffinity.currentMask());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        thread.join(5_000);

        assertTrue(!thread.isAlive(), "Affinity probe did not terminate");
        assertNull(failure.get(), "Explicit affinity probe failed");
        assertEquals(1, cpuCount(result.get()));
        assertEquals(cpu, firstCpu(result.get()));
    }

    private static int firstCpu(byte[] mask) {
        for (int byteIndex = 0; byteIndex < mask.length; byteIndex++) {
            int value = mask[byteIndex] & 0xff;
            if (value != 0) {
                return byteIndex * 8 + Integer.numberOfTrailingZeros(value);
            }
        }
        return -1;
    }

    private static int cpuCount(byte[] mask) {
        return Arrays.stream(toUnsigned(mask))
            .map(Integer::bitCount)
            .sum();
    }

    private static int[] toUnsigned(byte[] mask) {
        int[] values = new int[mask.length];
        for (int i = 0; i < mask.length; i++) {
            values[i] = mask[i] & 0xff;
        }
        return values;
    }
}
