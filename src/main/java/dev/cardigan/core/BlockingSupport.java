// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Attaches a blocking caller to a reactor-owned progress callback. */
interface BlockingSupport {
    void await(
        Object blocker,
        BooleanSupplier blocked,
        Consumer<Runnable> registerWakeup,
        Consumer<Runnable> unregisterWakeup
    );

    void awaitInterruptibly(
        Object blocker,
        BooleanSupplier blocked,
        Consumer<Runnable> registerWakeup,
        Consumer<Runnable> unregisterWakeup
    ) throws InterruptedException;

    static BlockingSupport nonBlocking() {
        return new BlockingSupport() {
            @Override
            public void await(
                    Object blocker,
                    BooleanSupplier blocked,
                    Consumer<Runnable> registerWakeup,
                    Consumer<Runnable> unregisterWakeup) {
                requireReady(blocked);
            }

            @Override
            public void awaitInterruptibly(
                    Object blocker,
                    BooleanSupplier blocked,
                    Consumer<Runnable> registerWakeup,
                    Consumer<Runnable> unregisterWakeup) {
                requireReady(blocked);
            }

            private static void requireReady(BooleanSupplier blocked) {
                if (blocked.getAsBoolean()) {
                    throw new IllegalStateException(
                        "This component has no blocking runtime attached");
                }
            }
        };
    }
}
