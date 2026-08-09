// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CarrierDomainTest {
    @Test
    void recognizesOwnerCarrierAndItsMountedVirtualThreads() throws Exception {
        try (ExecutorService owner = carrierExecutor("domain-owner");
             ExecutorService foreign = carrierExecutor("domain-foreign")) {
            Thread ownerThread = owner.submit(Thread::currentThread).get(
                5, TimeUnit.SECONDS);
            CarrierDomain domain = new CarrierDomain(ownerThread);

            assertSame(ownerThread, domain.carrierThread());
            assertTrue(owner.submit(domain::containsCurrentThread).get(
                5, TimeUnit.SECONDS));
            assertFalse(domain.containsCurrentThread());
            assertFalse(foreign.submit(domain::containsCurrentThread).get(
                5, TimeUnit.SECONDS));

            AtomicBoolean beforePark = new AtomicBoolean();
            AtomicBoolean afterPark = new AtomicBoolean();
            CompletableFuture<Void> readyToPark = new CompletableFuture<>();
            CompletableFuture<Void> ownerVirtualDone = new CompletableFuture<>();
            Thread ownerVirtual = newVirtualThread(owner, () -> {
                try {
                    beforePark.set(domain.containsCurrentThread());
                    readyToPark.complete(null);
                    LockSupport.park(domain);
                    afterPark.set(domain.containsCurrentThread());
                    ownerVirtualDone.complete(null);
                } catch (Throwable failure) {
                    ownerVirtualDone.completeExceptionally(failure);
                }
            }, "domain-owner-vt");
            ownerVirtual.start();
            readyToPark.get(5, TimeUnit.SECONDS);
            LockSupport.unpark(ownerVirtual);
            ownerVirtualDone.get(5, TimeUnit.SECONDS);
            ownerVirtual.join(5_000);

            assertTrue(beforePark.get());
            assertTrue(afterPark.get());

            CompletableFuture<Boolean> foreignVirtualResult =
                new CompletableFuture<>();
            Thread foreignVirtual = newVirtualThread(
                foreign,
                () -> foreignVirtualResult.complete(
                    domain.containsCurrentThread()),
                "domain-foreign-vt"
            );
            foreignVirtual.start();

            assertFalse(foreignVirtualResult.get(5, TimeUnit.SECONDS));
            foreignVirtual.join(5_000);
        }
    }

    private static ExecutorService carrierExecutor(String name) {
        return Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name(name).factory());
    }

    private static Thread newVirtualThread(
            Executor scheduler, Runnable task, String name) throws Exception {
        Class<?> builderClass = Class.forName(
            "java.lang.ThreadBuilders$VirtualThreadBuilder");
        Constructor<?> constructor = builderClass.getDeclaredConstructor(
            Executor.class);
        constructor.setAccessible(true);
        Thread.Builder.OfVirtual builder =
            (Thread.Builder.OfVirtual) constructor.newInstance(scheduler);
        return builder.name(name).unstarted(task);
    }
}
