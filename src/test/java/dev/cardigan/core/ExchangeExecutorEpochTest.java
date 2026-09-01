// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangeExecutorEpochTest {

    @Test
    @Tag("integration")
    void offerFromRunningHandlerExecutesInLaterSchedulerEpoch()
            throws Exception {
        try (UringEventLoop loop = new UringEventLoop(
                0, 64, 512, false,
                UringEventLoop.SchedulerMode.EPOCH)) {
            ExchangeExecutor executor = loop.exchangeExecutor();
            CompletableFuture<Long> firstEpoch = new CompletableFuture<>();
            CompletableFuture<Long> deferredEpoch = new CompletableFuture<>();
            CompletableFuture<Boolean> firstAccepted =
                new CompletableFuture<>();
            CompletableFuture<Boolean> deferredAccepted =
                new CompletableFuture<>();

            loop.executeProtocol(() -> firstAccepted.complete(executor.submit(() -> {
                    firstEpoch.complete(loop.schedulerEpoch());
                    deferredAccepted.complete(executor.submit(
                        () -> deferredEpoch.complete(loop.schedulerEpoch())));
                })));

            assertTrue(firstAccepted.get(5, TimeUnit.SECONDS));
            long first = firstEpoch.get(5, TimeUnit.SECONDS);
            assertTrue(deferredAccepted.get(5, TimeUnit.SECONDS));
            long deferred = deferredEpoch.get(5, TimeUnit.SECONDS);
            assertTrue(deferred > first,
                "a handler offer crossed its scheduler-epoch cutoff");
        }
    }

    @Test
    void tailCutoffDefersOffersFromTheRunningEpoch() {
        ExchangeExecutor.TaskQueue queue =
            new ExchangeExecutor.TaskQueue(4);
        Runnable first = () -> { };
        Runnable second = () -> { };
        Runnable deferred = () -> { };

        assertTrue(queue.offer(first));
        assertTrue(queue.offer(second));
        long cutoff = queue.tailSnapshot();
        assertTrue(queue.offer(deferred));

        assertTrue(queue.hasWorkBefore(cutoff));
        assertSame(first, queue.pollBefore(cutoff));
        assertSame(second, queue.pollBefore(cutoff));
        assertNull(queue.pollBefore(cutoff));
        assertFalse(queue.hasWorkBefore(cutoff));

        long nextCutoff = queue.tailSnapshot();
        assertSame(deferred, queue.pollBefore(nextCutoff));
        assertNull(queue.pollBefore(nextCutoff));
    }

    @Test
    void taskOfferedByRunningEpochWaitsForNextSharedCutoff() {
        ExchangeExecutor.TaskQueue queue =
            new ExchangeExecutor.TaskQueue(4);
        Runnable offeredWhileWorkerRan = () -> { };
        Runnable workerOneTask = () ->
            assertTrue(queue.offer(offeredWhileWorkerRan));
        Runnable workerTwoTask = () -> { };

        assertTrue(queue.offer(workerOneTask));
        assertTrue(queue.offer(workerTwoTask));
        long sharedCutoff = queue.tailSnapshot();

        Runnable claimedByWorkerOne = queue.pollBefore(sharedCutoff);
        assertSame(workerOneTask, claimedByWorkerOne);
        claimedByWorkerOne.run();
        assertSame(workerTwoTask, queue.pollBefore(sharedCutoff));
        assertNull(queue.pollBefore(sharedCutoff));

        long nextCutoff = queue.tailSnapshot();
        assertSame(offeredWhileWorkerRan, queue.pollBefore(nextCutoff));
        assertNull(queue.pollBefore(nextCutoff));
    }

    @Test
    void absoluteCutoffSurvivesRingSlotReuse() {
        ExchangeExecutor.TaskQueue queue =
            new ExchangeExecutor.TaskQueue(2);
        Runnable first = () -> { };
        Runnable second = () -> { };
        Runnable wrapped = () -> { };

        assertTrue(queue.offer(first));
        assertTrue(queue.offer(second));
        long cutoff = queue.tailSnapshot();
        assertSame(first, queue.pollBefore(cutoff));
        assertTrue(queue.offer(wrapped));
        assertSame(second, queue.pollBefore(cutoff));
        assertNull(queue.pollBefore(cutoff));

        long wrappedCutoff = queue.tailSnapshot();
        assertSame(wrapped, queue.pollBefore(wrappedCutoff));
        assertNull(queue.pollBefore(wrappedCutoff));
    }
}
