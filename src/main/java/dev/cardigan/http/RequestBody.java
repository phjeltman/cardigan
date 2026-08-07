// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.lang.foreign.MemorySegment;

/**
 * A blocking, single-consumer view of an inbound request body.
 *
 * <p>{@link #read(MemorySegment)} parks the current virtual thread when the
 * next bytes have not arrived yet. It never blocks an io_uring carrier.</p>
 *
 * <p>A route that accepts a request body is request-then-response for that
 * exchange: Cardigan starts the returned response only after the route method
 * returns, then closes and discards any unread request bytes. Do not retain
 * this object or capture it in a response {@link StreamingBody}. HTTP/2 can
 * still advance unrelated streams concurrently on the same connection.</p>
 */
public interface RequestBody extends AutoCloseable {
    /** Returns the declared length, or {@code -1} when it was not declared. */
    long length();

    /** Returns the unread declared bytes, or {@code -1} for unknown length. */
    long remaining();

    /**
     * Reads at most {@code destination.byteSize()} bytes.
     *
     * @return bytes read, or {@code -1} after the end of the body
     * @throws RequestBodyException when the peer aborts the body
     */
    int read(MemorySegment destination);

    /** Discards unread bytes while preserving the connection when possible. */
    @Override
    void close();
}
