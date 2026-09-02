// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

final class CarrierLocalTest {
    @Test
    void sharesValuesWithinEachCarrier() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        CarrierLocal<Object> local = CarrierLocal.withInitial(() -> {
            sequence.incrementAndGet();
            return new Object();
        });

        try (ExecutorService first = carrierExecutor("carrier-local-first");
             ExecutorService second = carrierExecutor("carrier-local-second")) {
            Object firstValue = virtualGet(first, local);
            Object firstAgain = virtualGet(first, local);
            Object secondValue = virtualGet(second, local);

            assertSame(firstValue, firstAgain);
            assertNotSame(firstValue, secondValue);
            assertSame(local.get(), local.get());
        }
    }

    private static Object virtualGet(
            Executor carrier, CarrierLocal<Object> local) throws Exception {
        CompletableFuture<Object> result = new CompletableFuture<>();
        Thread thread = newVirtualThread(
            carrier, () -> result.complete(local.get()));
        thread.start();
        Object value = result.get(5, TimeUnit.SECONDS);
        thread.join(5_000);
        return value;
    }

    private static ExecutorService carrierExecutor(String name) {
        return Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name(name).factory());
    }

    private static Thread newVirtualThread(
            Executor scheduler, Runnable task) throws Exception {
        Class<?> builderClass = Class.forName(
            "java.lang.ThreadBuilders$VirtualThreadBuilder");
        Constructor<?> constructor = builderClass.getDeclaredConstructor(
            Executor.class);
        constructor.setAccessible(true);
        Thread.Builder.OfVirtual builder =
            (Thread.Builder.OfVirtual) constructor.newInstance(scheduler);
        return builder.unstarted(task);
    }
}
