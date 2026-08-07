// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

/** Supplies ownership-bearing plaintext chunks to the HTTP protocol layer. */
interface InboundReceiver extends AutoCloseable {
    void start();

    InboundChunk receive();

    @Override
    void close();
}
