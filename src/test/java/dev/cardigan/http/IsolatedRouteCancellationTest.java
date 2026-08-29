// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsolatedRouteCancellationTest {

    @Test
    void cancellationInterruptsRunningIsolatedHandlerAndReleasesCaller()
            throws Exception {
        int activeBefore = IsolatedRouteStats.snapshot().active();
        Controller controller = new Controller();
        PreparedInvocation invocation = invocation(controller);
        AtomicReference<Response> response = new AtomicReference<>();

        Thread caller = Thread.ofPlatform().start(
            () -> response.set(invocation.invoke()));
        assertTrue(controller.started.await(5, TimeUnit.SECONDS));

        invocation.cancel();

        caller.join(5_000);
        assertFalse(caller.isAlive(), "Cancelled invocation remained parked");
        assertNull(response.get(), "Cancelled invocation produced a response");
        assertTrue(controller.finished.await(5, TimeUnit.SECONDS));
        assertTrue(controller.interrupted,
            "Isolated virtual thread did not receive interruption");
        awaitActiveTasks(activeBefore);
    }

    @Test
    void cancellationBeforeInvocationSkipsHandler() throws Exception {
        Controller controller = new Controller();
        PreparedInvocation invocation = invocation(controller);

        invocation.cancel();

        assertNull(invocation.invoke());
        assertEquals(0, controller.invocations.get(),
            "Handler ran after its invocation was cancelled");
    }

    @Test
    void nonCooperativeHandlerRetainsAdmissionUntilItActuallyStops()
            throws Exception {
        int activeBefore = IsolatedRouteStats.snapshot().active();
        NonCooperativeController controller = new NonCooperativeController();
        PreparedInvocation invocation = invocation(controller);
        AtomicInteger completions = new AtomicInteger();
        AtomicReference<Response> response = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(
            () -> response.set(invocation.invokeWithCompletion(
                completions::incrementAndGet)));
        assertTrue(controller.started.await(5, TimeUnit.SECONDS));

        try {
            invocation.cancel();
            caller.join(5_000);
            assertFalse(caller.isAlive(), "Cancelled invocation remained parked");
            assertNull(response.get());
            assertEquals(
                activeBefore + 1,
                IsolatedRouteStats.snapshot().active(),
                "Admission was released while user code was still running"
            );
            assertEquals(0, completions.get(),
                "Completion ran before user code actually stopped");
        } finally {
            controller.release = true;
        }

        assertTrue(controller.finished.await(5, TimeUnit.SECONDS));
        awaitActiveTasks(activeBefore);
        long completionDeadline = System.nanoTime()
            + TimeUnit.SECONDS.toNanos(5);
        while (completions.get() == 0
            && System.nanoTime() < completionDeadline) {
            Thread.sleep(1);
        }
        assertEquals(1, completions.get());
    }

    @Test
    void actualCompletionSurvivesInvocationReuseAfterCancellation()
            throws Exception {
        int activeBefore = IsolatedRouteStats.snapshot().active();
        NonCooperativeController oldController =
            new NonCooperativeController();
        PreparedInvocation invocation = invocation(oldController);
        AtomicInteger oldCompletions = new AtomicInteger();
        AtomicInteger newCompletions = new AtomicInteger();

        Thread caller = Thread.ofPlatform().start(
            () -> invocation.invokeWithCompletion(
                oldCompletions::incrementAndGet));
        assertTrue(oldController.started.await(5, TimeUnit.SECONDS));
        invocation.cancel();
        caller.join(5_000);
        assertFalse(caller.isAlive());

        try {
            invocation.resetCancellation();
            configureInvocation(invocation, new ImmediateController());
            assertEquals("new", invocation.invokeWithCompletion(
                newCompletions::incrementAndGet).body());
            long newDeadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5);
            while (newCompletions.get() == 0
                && System.nanoTime() < newDeadline) {
                Thread.sleep(1);
            }
            assertEquals(1, newCompletions.get());
            assertEquals(0, oldCompletions.get(),
                "Reusing the invocation stole the old task's completion");
        } finally {
            oldController.release = true;
        }

        assertTrue(oldController.finished.await(5, TimeUnit.SECONDS));
        awaitActiveTasks(activeBefore);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (oldCompletions.get() == 0
            && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(1, oldCompletions.get());
        assertEquals(1, newCompletions.get());
    }

    private static PreparedInvocation invocation(Object controller)
            throws Exception {
        return configureInvocation(new PreparedInvocation(), controller);
    }

    private static PreparedInvocation configureInvocation(
            PreparedInvocation invocation, Object controller)
            throws Exception {
        MethodHandle method = MethodHandles.lookup().findVirtual(
            controller.getClass(),
            "handle",
            MethodType.methodType(Response.class)
        );
        IsolatedRouteHandler handler = new IsolatedRouteHandler(
            controller, method, FastRouteHandler.TYPE_NO_ARG);
        return invocation.setHandler(
            handler, null, 0, null, true);
    }

    private static void awaitActiveTasks(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (IsolatedRouteStats.snapshot().active() != expected
               && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(expected, IsolatedRouteStats.snapshot().active());
    }

    static final class Controller {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicInteger invocations = new AtomicInteger();
        private volatile boolean interrupted;

        public Response handle() {
            invocations.incrementAndGet();
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException expected) {
                interrupted = true;
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
            return Response.text("finished");
        }
    }

    static final class NonCooperativeController {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile boolean release;

        public Response handle() {
            started.countDown();
            try {
                while (!release) {
                    Thread.onSpinWait();
                }
                return Response.text("finished");
            } finally {
                finished.countDown();
            }
        }
    }

    static final class ImmediateController {
        public Response handle() {
            return Response.text("new");
        }
    }
}
