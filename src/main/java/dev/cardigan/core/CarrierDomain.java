// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;

/**
 * Identifies every Java thread currently mounted on one platform carrier.
 *
 * <p>Cardigan already opens {@code java.lang} to construct virtual threads
 * with a private scheduler. Reusing that opening for
 * {@code Thread.currentCarrierThread()} avoids adding an export of
 * {@code jdk.internal.misc.CarrierThreadLocal} to every launcher.</p>
 */
final class CarrierDomain {
    private static final MethodHandle CURRENT_CARRIER_THREAD =
        findCurrentCarrierThread();

    private final Thread carrier;

    CarrierDomain(Thread carrier) {
        this.carrier = Objects.requireNonNull(carrier, "carrier");
        if (carrier.isVirtual()) {
            throw new IllegalArgumentException(
                "A carrier domain must be owned by a platform thread");
        }
    }

    boolean containsCurrentThread() {
        Thread current = Thread.currentThread();
        return current == carrier
            || (current.isVirtual() && currentCarrierThread() == carrier);
    }

    Thread carrierThread() {
        return carrier;
    }

    private static MethodHandle findCurrentCarrierThread() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                Thread.class, MethodHandles.lookup());
            return lookup.findStatic(
                Thread.class,
                "currentCarrierThread",
                MethodType.methodType(Thread.class)
            );
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                "Missing JVM argument: "
                    + "--add-opens java.base/java.lang=ALL-UNNAMED",
                failure
            );
        }
    }

    private static Thread currentCarrierThread() {
        try {
            return (Thread) CURRENT_CARRIER_THREAD.invokeExact();
        } catch (Throwable failure) {
            throw new IllegalStateException(
                "Unable to identify the current carrier thread", failure);
        }
    }
}
