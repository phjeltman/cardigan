// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Stores one value for each platform carrier and shares that value with
 * virtual threads while they are mounted on the carrier.
 */
public final class CarrierLocal<T> {
    private final Supplier<? extends T> initialValue;
    private final ConcurrentHashMap<Thread, T> values =
        new ConcurrentHashMap<>();
    private final ThreadLocal<Entry<T>> cache = new ThreadLocal<>();

    private CarrierLocal(Supplier<? extends T> initialValue) {
        this.initialValue = Objects.requireNonNull(initialValue, "initialValue");
    }

    /** Creates a carrier-local value initialized on first use. */
    public static <T> CarrierLocal<T> withInitial(
            Supplier<? extends T> initialValue) {
        return new CarrierLocal<>(initialValue);
    }

    /** Returns the value belonging to the calling thread's current carrier. */
    public T get() {
        Thread current = Thread.currentThread();
        Thread carrier = current.isVirtual()
            ? CarrierDomain.currentCarrierThread()
            : current;
        Entry<T> cached = cache.get();
        if (cached != null && cached.carrier() == carrier) {
            return cached.value();
        }
        T value = values.computeIfAbsent(
            carrier, ignored -> Objects.requireNonNull(
                initialValue.get(), "Carrier-local initial value"));
        cache.set(new Entry<>(carrier, value));
        return value;
    }

    private record Entry<T>(Thread carrier, T value) {
    }
}
