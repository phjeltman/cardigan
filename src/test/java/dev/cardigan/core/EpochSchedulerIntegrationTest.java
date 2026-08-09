// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
final class EpochSchedulerIntegrationTest {
    @Test
    void sameLaneAppendWaitsForTheNextEpoch() throws Exception {
        try (UringEventLoop loop = epochLoop()) {
            AtomicLong firstEpoch = new AtomicLong();
            AtomicLong secondEpoch = new AtomicLong();
            CountDownLatch completed = new CountDownLatch(1);

            final class SelfAppendingHandler
                    implements UringEventLoop.HandlerContinuation {
                private int invocations;

                @Override
                public void run() {
                    if (invocations++ == 0) {
                        firstEpoch.set(loop.schedulerEpoch());
                        loop.executeHandler(this);
                    } else {
                        secondEpoch.set(loop.schedulerEpoch());
                        completed.countDown();
                    }
                }
            }

            loop.executeHandler(new SelfAppendingHandler());
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertTrue(secondEpoch.get() > firstEpoch.get(),
                "a handler appended during its phase ran in the same epoch");
        }
    }

    @Test
    void downstreamLanesRemainFusedWithinOneEpoch() throws Exception {
        try (UringEventLoop loop = epochLoop()) {
            AtomicLong protocolEpoch = new AtomicLong();
            AtomicLong handlerEpoch = new AtomicLong();
            AtomicLong egressEpoch = new AtomicLong();
            CountDownLatch completed = new CountDownLatch(1);

            UringEventLoop.EgressTask egress = () -> {
                egressEpoch.set(loop.schedulerEpoch());
                completed.countDown();
            };
            UringEventLoop.HandlerContinuation handler = () -> {
                handlerEpoch.set(loop.schedulerEpoch());
                loop.executeEgress(egress);
            };

            loop.execute(() -> {
                protocolEpoch.set(loop.schedulerEpoch());
                loop.executeHandler(handler);
            });

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(protocolEpoch.get(), handlerEpoch.get());
            assertEquals(protocolEpoch.get(), egressEpoch.get());
        }
    }

    @Test
    void inboundChunkBoundaryDefersProtocolWithoutATimeQuantum()
            throws Exception {
        try (UringEventLoop loop = epochLoop()) {
            AtomicLong beforeBoundary = new AtomicLong();
            AtomicLong handlerEpoch = new AtomicLong();
            AtomicLong afterBoundary = new AtomicLong();
            CountDownLatch completed = new CountDownLatch(1);

            Thread protocol = loop.startVirtualThread(() -> {
                beforeBoundary.set(loop.schedulerEpoch());
                loop.executeHandler(() ->
                    handlerEpoch.set(loop.schedulerEpoch()));
                loop.inboundChunkBoundary();
                afterBoundary.set(loop.schedulerEpoch());
                completed.countDown();
            });

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            protocol.join(2_000);
            assertEquals(beforeBoundary.get(), handlerEpoch.get(),
                "downstream handler work did not stay in the source epoch");
            assertTrue(afterBoundary.get() > beforeBoundary.get(),
                "the protocol continuation crossed its RX-chunk boundary");
        }
    }

    @Test
    void quiescentSubmissionCompletesInALaterCqEpoch() throws Exception {
        try (UringEventLoop loop = epochLoop()) {
            AtomicLong submissionEpoch = new AtomicLong();
            AtomicLong completionEpoch = new AtomicLong();
            AtomicInteger nopResult = new AtomicInteger(Integer.MIN_VALUE);
            CountDownLatch completed = new CountDownLatch(1);

            loop.startVirtualThread(() -> {
                submissionEpoch.set(loop.schedulerEpoch());
                nopResult.set(loop.nop());
                completionEpoch.set(loop.schedulerEpoch());
                completed.countDown();
            });

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(0, nopResult.get());
            assertTrue(completionEpoch.get() > submissionEpoch.get(),
                "a CQ completion escaped the next epoch's CQ snapshot");
        }
    }

    private static UringEventLoop epochLoop() {
        UringEventLoop loop = new UringEventLoop(
            0, 64, 512, false, UringEventLoop.SchedulerMode.EPOCH);
        assertTrue(loop.usesEpochScheduler());
        return loop;
    }
}
