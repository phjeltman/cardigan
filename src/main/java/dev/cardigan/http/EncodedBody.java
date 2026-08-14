// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/** A fixed-length response encoded directly into transport-owned memory. */
public final class EncodedBody {
    @FunctionalInterface
    public interface Encoder {
        /** Encodes into the supplied exact-length destination. */
        int encode(MemorySegment destination);
    }

    private final int length;
    private final Encoder encoder;

    private EncodedBody(int length, Encoder encoder) {
        if (length < 0) {
            throw new IllegalArgumentException("Negative body length");
        }
        this.length = length;
        this.encoder = Objects.requireNonNull(encoder, "encoder");
    }

    public static EncodedBody of(int length, Encoder encoder) {
        return new EncodedBody(length, encoder);
    }

    public int length() {
        return length;
    }

    /**
     * Invokes the encoder for one response emission. The destination is owned
     * by Cardigan and must not be retained after this method returns.
     */
    public void write(MemorySegment destination) {
        Objects.requireNonNull(destination, "destination");
        if (destination.byteSize() != length) {
            throw new IllegalArgumentException(
                "Encoded body requires " + length
                    + " destination bytes, got " + destination.byteSize());
        }
        int written = encoder.encode(destination);
        if (written != length) {
            throw new IllegalStateException(
                "Encoded body declared " + length
                    + " bytes but wrote " + written);
        }
    }
}
