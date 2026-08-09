// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
final class SchedulerProgressIntegrationTest {
    @Test
    void yieldingContinuationCannotKeepPendingIoAwayFromTheRing() throws Exception {
        try (UringEventLoop loop = new UringEventLoop(0, 64)) {
            AtomicBoolean stop = new AtomicBoolean();
            CountDownLatch spinnerStarted = new CountDownLatch(1);
            Thread spinner = loop.startVirtualThread(() -> {
                spinnerStarted.countDown();
                while (!stop.get()) {
                    Thread.yield();
                }
            });

            assertTrue(spinnerStarted.await(2, TimeUnit.SECONDS));

            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread ioWaiter = loop.startVirtualThread(() -> {
                try {
                    int result = loop.nop();
                    if (result != 0) {
                        throw new AssertionError(
                            "io_uring NOP completed with " + result);
                    }
                } catch (Throwable thrown) {
                    failure.set(thrown);
                } finally {
                    completed.countDown();
                }
            });

            try {
                assertTrue(completed.await(2, TimeUnit.SECONDS),
                    "a yielding continuation starved io_uring submission or completion");
                assertNull(failure.get());
            } finally {
                stop.set(true);
            }

            spinner.join(2_000);
            ioWaiter.join(2_000);
            assertFalse(spinner.isAlive());
            assertFalse(ioWaiter.isAlive());
        }
    }

    @Test
    void ownerDomainSchedulingStaysOffTheExternalQueue() throws Exception {
        try (UringEventLoop loop = new UringEventLoop(0, 64)) {
            CountDownLatch scheduled = new CountDownLatch(1);
            CountDownLatch ran = new CountDownLatch(1);

            Thread ownerVirtual = loop.startVirtualThread(() -> {
                loop.execute(ran::countDown);
                scheduled.countDown();
            });

            assertTrue(scheduled.await(2, TimeUnit.SECONDS));
            assertTrue(ran.await(2, TimeUnit.SECONDS));
            ownerVirtual.join(2_000);

            UringEventLoop.SchedulerStats stats = loop.schedulerStats();
            assertTrue(stats.protocolTasks() > 0,
                "mounted virtual-thread work did not use the owner lane");
            // Starting ownerVirtual from the JUnit thread accounts for one
            // external scheduler submission; the nested execute must not add
            // a second one.
            assertTrue(stats.externalTasks() <= 1,
                "owner-domain scheduling bounced through the external queue");
        }
    }
}
