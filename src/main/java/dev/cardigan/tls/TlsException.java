// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.tls;

/** A failure reported by Cardigan's OpenSSL transport. */
public final class TlsException extends RuntimeException {
    public TlsException(String message) {
        super(message);
    }

    public TlsException(String message, Throwable cause) {
        super(message, cause);
    }
}
