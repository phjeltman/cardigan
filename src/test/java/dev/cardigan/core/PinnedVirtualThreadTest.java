// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class PinnedVirtualThreadTest {

    @Test
    public void testVirtualThreadPinnedCarrierAffinity() throws Exception {
        int targetCpu = 0;
        try (UringEventLoop loop = new UringEventLoop(targetCpu, 64)) {
            CountDownLatch latchBeforePark = new CountDownLatch(1);
            CountDownLatch latchAfterPark = new CountDownLatch(1);

            AtomicReference<String> carrierBefore = new AtomicReference<>();
            AtomicReference<String> carrierAfter = new AtomicReference<>();
            AtomicReference<Thread> vtRef = new AtomicReference<>();

            Thread vt = loop.startVirtualThread(() -> {
                vtRef.set(Thread.currentThread());
                String initialThreadStr = Thread.currentThread().toString();
                carrierBefore.set(initialThreadStr);
                latchBeforePark.countDown();

                // Park the virtual thread
                LockSupport.park();

                String afterUnparkThreadStr = Thread.currentThread().toString();
                carrierAfter.set(afterUnparkThreadStr);
                latchAfterPark.countDown();
            });

            assertTrue(latchBeforePark.await(5, TimeUnit.SECONDS), "Virtual thread failed to start and park");
            assertTrue(carrierBefore.get().contains("cardigan-loop-" + targetCpu),
                    "Carrier thread before park was not cardigan-loop-" + targetCpu + ", got: " + carrierBefore.get());

            // Unpark the virtual thread
            LockSupport.unpark(vtRef.get());

            assertTrue(latchAfterPark.await(5, TimeUnit.SECONDS), "Virtual thread failed to resume after unpark");
            assertTrue(carrierAfter.get().contains("cardigan-loop-" + targetCpu),
                    "Carrier thread after unpark was not cardigan-loop-" + targetCpu + ", got: " + carrierAfter.get());

            vt.join(2000);
        }
    }

    @Test
    public void testSpuriousWakeupDoesNotCompleteUringOperation() throws Exception {
        try (UringEventLoop loop = new UringEventLoop(0, 64)) {
            CountDownLatch ready = new CountDownLatch(1);
            AtomicBoolean enterNop = new AtomicBoolean();
            AtomicReference<Integer> result = new AtomicReference<>();

            Thread virtualThread = loop.startVirtualThread(() -> {
                ready.countDown();
                while (!enterNop.get()) {
                    Thread.onSpinWait();
                }
                result.set(loop.nop());
            });

            assertTrue(ready.await(2, TimeUnit.SECONDS));

            // Seed a LockSupport permit before submitOp parks. A one-shot park
            // would consume it and recycle the task ID before its CQE arrives.
            LockSupport.unpark(virtualThread);
            enterNop.set(true);

            virtualThread.join(2_000);
            assertFalse(virtualThread.isAlive(), "io_uring operation did not complete");
            assertEquals(0, result.get());
        }
    }

    @Test
    public void testLoopThreadUnparkIsDeferred() throws Exception {
        try (UringEventLoop loop = new UringEventLoop(0, 64)) {
            CountDownLatch parked = new CountDownLatch(1);
            CountDownLatch unparkTaskDone = new CountDownLatch(1);
            CountDownLatch resumed = new CountDownLatch(1);
            AtomicInteger phase = new AtomicInteger();
            AtomicInteger resumedPhase = new AtomicInteger();
            AtomicReference<String> carrier = new AtomicReference<>();

            Thread virtualThread = loop.startVirtualThread(() -> {
                parked.countDown();
                LockSupport.park();
                resumedPhase.set(phase.get());
                carrier.set(Thread.currentThread().toString());
                resumed.countDown();
            });

            assertTrue(parked.await(2, TimeUnit.SECONDS));
            loop.execute(() -> {
                phase.set(1);
                LockSupport.unpark(virtualThread);
                phase.set(2);
                unparkTaskDone.countDown();
            });

            assertTrue(unparkTaskDone.await(2, TimeUnit.SECONDS));
            assertTrue(resumed.await(2, TimeUnit.SECONDS),
                "loop slept with a queued continuation");
            assertEquals(2, resumedPhase.get(),
                "continuation ran inline during unpark");
            assertTrue(carrier.get().contains("cardigan-loop-0"));
            virtualThread.join(2_000);
        }
    }

    @Test
    public void testRepeatedCompletionsResumeVirtualThread() throws Exception {
        try (UringEventLoop loop = new UringEventLoop(0, 64)) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread virtualThread = loop.startVirtualThread(() -> {
                try {
                    for (int i = 0; i < 1_000; i++) {
                        int result = loop.nop();
                        if (result != 0) {
                            throw new AssertionError(
                                "NOP " + i + " completed with " + result);
                        }
                    }
                } catch (Throwable t) {
                    failure.set(t);
                }
            });

            virtualThread.join(10_000);
            assertFalse(virtualThread.isAlive(),
                "virtual thread stopped making completion progress");
            assertNull(failure.get());
        }
    }
}
