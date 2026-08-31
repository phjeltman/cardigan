// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@Tag("integration")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class UringCompletionDispatchIntegrationTest {
    private static final Field TASKS = field("tasks");
    private static final Method DISPATCH_COMPLETION = method(
        "dispatchCompletion", long.class, int.class, int.class);

    @Test
    void asyncHandlerReceivesCqeWithoutPublishingSynchronousTaskState() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (UringEventLoop loop = new UringEventLoop(0, 64)) {
                UringTask task = task(loop);
                int resultSentinel = 0x1357_2468;
                int flagsSentinel = 0x2468_1357;
                Thread threadSentinel = Thread.currentThread();
                AtomicInteger callbackResult = new AtomicInteger();
                AtomicInteger callbackFlags = new AtomicInteger();
                AtomicBoolean callbackTerminal = new AtomicBoolean(true);

                task.result = resultSentinel;
                task.flags = flagsSentinel;
                task.thread = threadSentinel;
                task.vectorSlot = -1;
                task.completionHandler = (result, flags, terminal) -> {
                    callbackResult.set(result);
                    callbackFlags.set(flags);
                    callbackTerminal.set(terminal);
                };

                try {
                    int cqeFlags = Opcodes.IORING_CQE_F_MORE;
                    DISPATCH_COMPLETION.invoke(
                        loop, task.userData, 37, cqeFlags);

                    assertEquals(37, callbackResult.get());
                    assertEquals(cqeFlags, callbackFlags.get());
                    assertFalse(callbackTerminal.get());
                    assertEquals(resultSentinel, task.result);
                    assertEquals(flagsSentinel, task.flags);
                    assertSame(threadSentinel, task.thread);
                } finally {
                    task.completionHandler = null;
                    task.thread = null;
                }
            }
        });
    }

    @Test
    void synchronousCompletionPublishesStateBeforeWakingWaiter() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (UringEventLoop loop = new UringEventLoop(0, 64)) {
                UringTask task = task(loop);
                CountDownLatch parked = new CountDownLatch(1);
                AtomicBoolean resumed = new AtomicBoolean();
                Thread waiter = Thread.ofPlatform().start(() -> {
                    parked.countDown();
                    LockSupport.park(task);
                    resumed.set(true);
                });
                assertTrue(parked.await(1, java.util.concurrent.TimeUnit.SECONDS));

                task.completionHandler = null;
                task.thread = waiter;
                try {
                    DISPATCH_COMPLETION.invoke(loop, task.userData, -123, 0);
                    waiter.join(1_000);

                    assertTrue(resumed.get());
                    assertEquals(-123, task.result);
                    assertEquals(0, task.flags);
                    assertNull(task.thread);
                } finally {
                    LockSupport.unpark(waiter);
                    waiter.join(1_000);
                    task.thread = null;
                }
            }
        });
    }

    private static UringTask task(UringEventLoop loop)
            throws IllegalAccessException {
        UringTask task = ((UringTask[]) TASKS.get(loop))[0];
        // A generation-tagged token exercises the normal stale-CQE guard while
        // leaving this otherwise-free test task out of pool accounting.
        task.generation = 1;
        task.userData = (1L << 32) | (task.id & 0xffff_ffffL);
        return task;
    }

    private static Field field(String name) {
        try {
            Field field = UringEventLoop.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Method method(String name, Class<?>... parameterTypes) {
        try {
            Method method = UringEventLoop.class.getDeclaredMethod(
                name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
