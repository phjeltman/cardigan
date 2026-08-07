// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.util.concurrent.atomic.AtomicLong;

/** Process-wide admission for native HTTP/2 request-body rings. */
final class Http2StreamingAdmission {
    private static final long MAX_BYTES = Math.max(
        65_535L,
        Long.getLong(
            "cardigan.http2.max.streaming.buffer.bytes",
            256L * 1024 * 1024));
    private static final AtomicLong RESERVED_BYTES = new AtomicLong();

    private Http2StreamingAdmission() {
    }

    static boolean tryAcquire(int bytes) {
        long current = RESERVED_BYTES.get();
        while (bytes <= MAX_BYTES - current) {
            if (RESERVED_BYTES.compareAndSet(current, current + bytes)) {
                return true;
            }
            current = RESERVED_BYTES.get();
        }
        return false;
    }

    static void release(int bytes) {
        RESERVED_BYTES.addAndGet(-bytes);
    }

    static long reservedBytes() {
        return RESERVED_BYTES.get();
    }
}
