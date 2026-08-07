// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Immutable, process-lifetime response bytes suitable for asynchronous
 * zero-copy gathering. This type is intentionally limited to static content:
 * its native storage is owned by the global arena and is never reclaimed.
 */
public final class StaticBody {
    private final MemorySegment bytes;

    private StaticBody(byte[] source) {
        MemorySegment storage = Arena.global().allocate(source.length, 64);
        MemorySegment.copy(
            source, 0, storage, ValueLayout.JAVA_BYTE, 0, source.length);
        bytes = storage.asReadOnly();
    }

    public static StaticBody copyOf(byte[] source) {
        Objects.requireNonNull(source, "source");
        return new StaticBody(source);
    }

    public static StaticBody utf8(String source) {
        Objects.requireNonNull(source, "source");
        return new StaticBody(source.getBytes(StandardCharsets.UTF_8));
    }

    public MemorySegment segment() {
        return bytes;
    }

    public int length() {
        return Math.toIntExact(bytes.byteSize());
    }
}
