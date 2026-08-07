// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

/** Indicates that an inbound streaming body ended or was cancelled early. */
public final class RequestBodyException extends IllegalStateException {
    public RequestBodyException(String message) {
        super(message);
    }
}
