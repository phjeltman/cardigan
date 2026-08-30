// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

/** Supplies ownership-bearing plaintext chunks to the HTTP protocol layer. */
interface InboundReceiver extends AutoCloseable {
    void start();

    InboundChunk receive();

    /**
     * Returns a currently queued chunk without parking, or {@code null} when
     * no chunk is immediately available.
     */
    default InboundChunk tryReceive() {
        return null;
    }

    /**
     * Installs a carrier-local notification for queued data or terminal input.
     * Receivers without an asynchronous completion source return {@code false}.
     */
    default boolean registerAvailabilityListener(Runnable listener) {
        return false;
    }

    default void clearAvailabilityListener(Runnable listener) {
    }

    /** Reports terminal input after {@link #tryReceive()} has drained its queue. */
    default boolean inputTerminated() {
        return false;
    }

    @Override
    void close();
}
