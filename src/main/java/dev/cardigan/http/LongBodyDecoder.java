// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.lang.foreign.MemorySegment;

/**
 * Decodes a buffered request body into a compact primitive route argument.
 * Implementations are instantiated once per route and may be called
 * concurrently by multiple event loops, so they must be thread-safe and
 * should normally be stateless.
 */
@FunctionalInterface
public interface LongBodyDecoder {
    long decode(MemorySegment source, long offset, long length);
}
