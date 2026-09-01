// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@Tag("integration")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class UringCompletionDispatchIntegrationTest {
    private static final Field TASKS = field("tasks");
    private static final Method DISPATCH_COMPLETION = method(
        "dispatchCompletion", long.class, int.class, int.class);

    @Test
    void asyncHandlerReceivesCqeDirectly() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (UringEventLoop loop = new UringEventLoop(0, 64)) {
                UringTask task = task(loop);
                AtomicInteger callbackResult = new AtomicInteger();
                AtomicInteger callbackFlags = new AtomicInteger();
                AtomicBoolean callbackTerminal = new AtomicBoolean(true);

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
                } finally {
                    task.completionHandler = null;
                }
            }
        });
    }

    @Test
    void loomWaiterOwnsSynchronousCompletionState() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (UringEventLoop loop = new UringEventLoop(0, 64)) {
                UringTask task = task(loop);
                LoomRuntime.CompletionWaiter waiter =
                    loop.loomRuntime().beginCompletionWait();
                task.completionHandler = waiter;

                DISPATCH_COMPLETION.invoke(loop, task.userData, -123, 7);

                assertEquals(-123, waiter.awaitResult());
                assertEquals(7, waiter.flags());
                assertNull(task.completionHandler);
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
