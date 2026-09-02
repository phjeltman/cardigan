// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
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
                    implements UringEventLoop.ApplicationTask {
                private int invocations;

                @Override
                public void run() {
                    if (invocations++ == 0) {
                        firstEpoch.set(loop.schedulerEpoch());
                        loop.executeApplication(this);
                    } else {
                        secondEpoch.set(loop.schedulerEpoch());
                        completed.countDown();
                    }
                }
            }

            loop.executeApplication(new SelfAppendingHandler());
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
            UringEventLoop.ApplicationTask handler = () -> {
                handlerEpoch.set(loop.schedulerEpoch());
                loop.executeEgress(egress);
            };

            loop.executeProtocol(() -> {
                protocolEpoch.set(loop.schedulerEpoch());
                loop.executeApplication(handler);
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

            ApplicationRuntime.RuntimeTask protocol = loop.applicationRuntime().startTask(() -> {
                beforeBoundary.set(loop.schedulerEpoch());
                loop.executeApplication(() ->
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
    void immediateInboundChunkRetainsTheEpochBoundary() throws Exception {
        try (UringEventLoop loop = epochLoop();
             Arena arena = Arena.ofShared()) {
            MemorySegment buffer = arena.allocate(1);
            AtomicLong receiveEpoch = new AtomicLong();
            AtomicLong handlerEpoch = new AtomicLong();
            AtomicLong returnedEpoch = new AtomicLong();
            CountDownLatch completed = new CountDownLatch(1);
            CountDownLatch handlerCompleted = new CountDownLatch(1);

            InboundReceiver receiver = immediateReceiver(
                buffer,
                () -> {
                    receiveEpoch.set(loop.schedulerEpoch());
                    loop.executeApplication(() -> {
                        handlerEpoch.set(loop.schedulerEpoch());
                        handlerCompleted.countDown();
                    });
                }
            );

            ApplicationRuntime.RuntimeTask protocol = loop.applicationRuntime().startTask(() -> {
                try (InboundChunkStream inbound =
                         new InboundChunkStream(loop, receiver);
                     InboundChunk ignored = inbound.nextChunk()) {
                    returnedEpoch.set(loop.schedulerEpoch());
                } finally {
                    completed.countDown();
                }
            });

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertTrue(handlerCompleted.await(2, TimeUnit.SECONDS));
            protocol.join(2_000);
            assertEquals(receiveEpoch.get(), handlerEpoch.get());
            assertTrue(returnedEpoch.get() > receiveEpoch.get(),
                "an immediately available chunk bypassed its epoch boundary");
        }
    }

    @Test
    void parkedInboundReceiveAlreadySatisfiesTheEpochBoundary()
            throws Exception {
        try (UringEventLoop loop = epochLoop();
             Arena arena = Arena.ofShared()) {
            MemorySegment buffer = arena.allocate(1);
            AtomicInteger nopResult = new AtomicInteger(Integer.MIN_VALUE);
            AtomicLong resumedEpoch = new AtomicLong();
            AtomicLong handlerEpoch = new AtomicLong();
            AtomicLong returnedEpoch = new AtomicLong();
            CountDownLatch completed = new CountDownLatch(1);
            CountDownLatch handlerCompleted = new CountDownLatch(1);

            InboundReceiver receiver = immediateReceiver(
                buffer,
                () -> {
                    nopResult.set(loop.nop());
                    // A completion continuation is a phase before protocol.
                    // Continue from protocol so a second boundary would have
                    // to defer this continuation to another epoch.
                    Thread.yield();
                    resumedEpoch.set(loop.schedulerEpoch());
                    loop.executeApplication(() -> {
                        handlerEpoch.set(loop.schedulerEpoch());
                        handlerCompleted.countDown();
                    });
                }
            );

            ApplicationRuntime.RuntimeTask protocol = loop.applicationRuntime().startTask(() -> {
                try (InboundChunkStream inbound =
                         new InboundChunkStream(loop, receiver);
                     InboundChunk ignored = inbound.nextChunk()) {
                    returnedEpoch.set(loop.schedulerEpoch());
                } finally {
                    completed.countDown();
                }
            });

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertTrue(handlerCompleted.await(2, TimeUnit.SECONDS));
            protocol.join(2_000);
            assertEquals(0, nopResult.get());
            assertEquals(resumedEpoch.get(), returnedEpoch.get(),
                "a parked receive paid a second epoch boundary");
            assertEquals(resumedEpoch.get(), handlerEpoch.get());
        }
    }

    @Test
    void quiescentSubmissionCompletesInALaterCqEpoch() throws Exception {
        try (UringEventLoop loop = epochLoop()) {
            AtomicLong submissionEpoch = new AtomicLong();
            AtomicLong completionEpoch = new AtomicLong();
            AtomicInteger nopResult = new AtomicInteger(Integer.MIN_VALUE);
            CountDownLatch completed = new CountDownLatch(1);

            loop.applicationRuntime().startTask(() -> {
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
        return new UringEventLoop(
            0, 64, 512, false,
            UringEventLoop.SchedulerMode.EPOCH);
    }

    private static InboundReceiver immediateReceiver(
            MemorySegment buffer, Runnable beforeReturn) {
        return new InboundReceiver() {
            @Override
            public void start() {
            }

            @Override
            public InboundChunk receive() {
                beforeReturn.run();
                return new InboundChunk(buffer, 0, 1, ignored -> {
                });
            }

            @Override
            public void close() {
            }
        };
    }
}
