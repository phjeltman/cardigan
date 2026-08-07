// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.serdes;

/**
 * Visitor interface for traversing object key-value pairs during deserialization.
 *
 * @param <T> The target type built by this visitor.
 */
public interface StructVisitor<T> {

    /**
     * Called for each field in a JSON object.
     *
     * @param keyOffset Byte offset of the key string in the underlying MemorySegment.
     * @param keyLen    Byte length of the key string.
     * @param de        The active deserializer driver to consume the field value.
     */
    void visitField(long keyOffset, int keyLen, SimdJsonDeserializer de);

    /**
     * Constructs and returns the target object after all fields have been visited.
     */
    T build();
}
