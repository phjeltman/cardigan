// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.serdes;

/**
 * Functional interface for custom zero-allocation serialization.
 *
 * @param <T> The source type to serialize.
 */
@FunctionalInterface
public interface Serialize<T> {
    void serialize(T value, SimdJsonSerializer ser);
}
