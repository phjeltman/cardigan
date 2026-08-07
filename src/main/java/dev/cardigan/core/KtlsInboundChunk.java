// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.lang.foreign.MemorySegment;

/** Returns a direct-kTLS payload to its dedicated provided-buffer group. */
final class KtlsInboundChunk extends InboundChunk {
    private final UringEventLoop ktlsOwner;
    private final int ktlsBufferId;

    KtlsInboundChunk(
            UringEventLoop owner, MemorySegment segment,
            int bufferId, int length) {
        super(owner, segment, bufferId, length);
        this.ktlsOwner = owner;
        this.ktlsBufferId = bufferId;
    }

    @Override
    public void run() {
        ktlsOwner.returnKtlsBuffer(ktlsBufferId);
    }
}
