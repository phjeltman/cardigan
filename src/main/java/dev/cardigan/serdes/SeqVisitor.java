// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.serdes;

/**
 * Visitor interface for traversing array elements during deserialization.
 *
 * @param <T> The target container/type built by this visitor.
 */
public interface SeqVisitor<T> {

    /**
     * Called for each element in a JSON array.
     *
     * @param index Zero-based element index.
     * @param de    The active deserializer driver to consume the element value.
     */
    void visitElement(int index, SimdJsonDeserializer de);

    /**
     * Constructs and returns the target sequence/container after all elements have been visited.
     */
    T build();
}
