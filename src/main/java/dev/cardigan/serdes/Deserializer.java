// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.serdes;

/**
 * Functional interface for custom zero-allocation deserialization.
 *
 * @param <T> The target type to deserialize.
 */
@FunctionalInterface
public interface Deserializer<T> {
    T deserialize(SimdJsonDeserializer de);
}
