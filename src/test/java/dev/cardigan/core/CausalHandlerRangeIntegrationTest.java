// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
final class CausalHandlerRangeIntegrationTest {
    @Test
    void eachProducerRangeHandsOffToEgressBeforeTheNextRange()
            throws Exception {
        try (UringEventLoop loop = epochLoop()) {
            ExchangeExecutor executor = loop.exchangeExecutor();
            List<String> order = new CopyOnWriteArrayList<>();
            AtomicLong firstBoundarySubmits = new AtomicLong(-1);
            AtomicLong secondBoundarySubmits = new AtomicLong(-1);
            CountDownLatch completed = new CountDownLatch(1);

            enqueueProtocolProducers(loop, () -> assertTrue(executor.submit(() -> {
                order.add("handler-1");
                loop.executeEgress(() -> {
                    order.add("egress-1");
                    firstBoundarySubmits.set(
                        loop.schedulerStats().submits());
                });
            })), () -> assertTrue(executor.submit(() -> {
                order.add("handler-2");
                loop.executeEgress(() -> {
                    order.add("egress-2");
                    secondBoundarySubmits.set(
                        loop.schedulerStats().submits());
                    completed.countDown();
                });
            })));

            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(
                List.of("handler-1", "egress-1", "handler-2", "egress-2"),
                order);
            assertEquals(firstBoundarySubmits.get(), secondBoundarySubmits.get(),
                "a causal range performed its own io_uring_enter");
            assertEquals(0, executor.pendingHandlerRanges());
            UringEventLoop.SchedulerStats stats = loop.schedulerStats();
            assertEquals(2, stats.handlerRanges());
            assertEquals(2, stats.handlerRangeBoundaries());
        }
    }

    @Test
    void parkedLastTaskDoesNotStrandTheNextSealedRange()
            throws Exception {
        try (UringEventLoop loop = epochLoop()) {
            ExchangeExecutor executor = loop.exchangeExecutor();
            List<String> order = new CopyOnWriteArrayList<>();
            AtomicReference<Thread> firstWorker = new AtomicReference<>();
            AtomicBoolean releaseFirst = new AtomicBoolean();
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch secondCompleted = new CountDownLatch(1);
            CountDownLatch firstCompleted = new CountDownLatch(1);

            enqueueProtocolProducers(loop, () -> assertTrue(executor.submit(() -> {
                order.add("handler-1-start");
                firstWorker.set(Thread.currentThread());
                firstStarted.countDown();
                while (!releaseFirst.get()) {
                    LockSupport.park();
                }
                order.add("handler-1-finish");
                loop.executeEgress(() -> {
                    order.add("egress-1");
                    firstCompleted.countDown();
                });
            })), () -> assertTrue(executor.submit(() -> {
                order.add("handler-2");
                loop.executeEgress(() -> {
                    order.add("egress-2");
                    secondCompleted.countDown();
                });
            })));

            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            assertTrue(secondCompleted.await(5, TimeUnit.SECONDS),
                "a claimed range waited for its parked final task to complete");
            releaseFirst.set(true);
            LockSupport.unpark(firstWorker.get());
            assertTrue(firstCompleted.await(5, TimeUnit.SECONDS));
            assertEquals(
                List.of(
                    "handler-1-start", "handler-2", "egress-2",
                    "handler-1-finish", "egress-1"),
                order);
            assertEquals(0, executor.pendingHandlerRanges());
        }
    }

    @Test
    void resumedParkedWorkerCanJoinAnotherActiveRange()
            throws Exception {
        try (UringEventLoop loop = epochLoop()) {
            ExchangeExecutor executor = loop.exchangeExecutor();
            List<String> order = new CopyOnWriteArrayList<>();
            AtomicReference<Thread> firstWorker = new AtomicReference<>();
            AtomicBoolean releaseFirst = new AtomicBoolean();
            AtomicBoolean finishSecondRangeLeader = new AtomicBoolean();
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch secondRangeStarted = new CountDownLatch(1);
            CountDownLatch secondRangeFollowerCompleted =
                new CountDownLatch(1);
            CountDownLatch allCompleted = new CountDownLatch(2);

            enqueueProtocolProducers(loop, () -> assertTrue(executor.submit(() -> {
                order.add("range-1-park");
                firstWorker.set(Thread.currentThread());
                firstStarted.countDown();
                while (!releaseFirst.get()) {
                    LockSupport.park();
                }
                order.add("range-1-resume");
                allCompleted.countDown();
            })), () -> {
                assertTrue(executor.submit(() -> {
                    order.add("range-2-leader");
                    secondRangeStarted.countDown();
                    while (!finishSecondRangeLeader.get()) {
                        Thread.yield();
                    }
                    allCompleted.countDown();
                }));
                assertTrue(executor.submit(() -> {
                    order.add("range-2-follower");
                    secondRangeFollowerCompleted.countDown();
                }));
            });

            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            assertTrue(secondRangeStarted.await(5, TimeUnit.SECONDS));
            assertTrue(executor.pendingHandlerRanges() > 0,
                "the second range was not active before the parked worker resumed");

            releaseFirst.set(true);
            LockSupport.unpark(firstWorker.get());
            assertTrue(secondRangeFollowerCompleted.await(5, TimeUnit.SECONDS));
            assertTrue(order.indexOf("range-1-resume")
                    < order.indexOf("range-2-follower"),
                "the resumed worker did not continue through the active range");

            finishSecondRangeLeader.set(true);
            assertTrue(allCompleted.await(5, TimeUnit.SECONDS));
            assertEquals(0, executor.pendingHandlerRanges());
        }
    }

    @Test
    void handlerSelfOfferIsSealedOnlyByTheNextEpoch()
            throws Exception {
        try (UringEventLoop loop = epochLoop()) {
            ExchangeExecutor executor = loop.exchangeExecutor();
            AtomicLong firstEpoch = new AtomicLong();
            AtomicLong secondEpoch = new AtomicLong();
            CountDownLatch completed = new CountDownLatch(1);

            loop.execute(() -> assertTrue(executor.submit(() -> {
                firstEpoch.set(loop.schedulerEpoch());
                assertTrue(executor.submit(() -> {
                    secondEpoch.set(loop.schedulerEpoch());
                    completed.countDown();
                }));
            })));

            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertTrue(secondEpoch.get() > firstEpoch.get());
            assertEquals(0, executor.pendingHandlerRanges());
            UringEventLoop.SchedulerStats stats = loop.schedulerStats();
            assertEquals(2, stats.handlerRanges());
            assertEquals(2, stats.handlerRangeBoundaries());
        }
    }

    @Test
    void shutdownReleasesParkedWorkersIncludingASelfOfferedTask()
            throws Exception {
        UringEventLoop loop = epochLoop();
        try {
            ExchangeExecutor executor = loop.exchangeExecutor();
            CountDownLatch workersParked = new CountDownLatch(3);

            enqueueProtocolProducers(loop, () -> assertTrue(executor.submit(() -> {
                // This offer begins beyond the producer-sealed cutoff. Let a
                // later epoch seal and mount it before closing, so the test
                // does not race that legitimate epoch transition.
                assertTrue(executor.submit(() -> {
                    workersParked.countDown();
                    LockSupport.park();
                }));
                workersParked.countDown();
                LockSupport.park();
            })), () -> assertTrue(executor.submit(() -> {
                workersParked.countDown();
                LockSupport.park();
            })));

            assertTrue(workersParked.await(5, TimeUnit.SECONDS));
            assertDoesNotThrow(loop::close);
        } finally {
            assertDoesNotThrow(loop::close);
        }
    }

    private static UringEventLoop epochLoop() {
        return new UringEventLoop(0, 64, 512, false);
    }

    private static void enqueueProtocolProducers(
            UringEventLoop loop, Runnable first, Runnable second) {
        // Both appends occur from one owner-domain mount, so they enter the
        // next protocol phase snapshot together as distinct producers.
        loop.execute(() -> {
            loop.execute(first);
            loop.execute(second);
        });
    }
}
