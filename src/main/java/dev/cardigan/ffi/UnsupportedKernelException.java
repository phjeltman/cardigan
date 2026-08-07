// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.ffi;

/** Raised when the kernel lacks io_uring features required by Cardigan. */
public final class UnsupportedKernelException extends IllegalStateException {
    public UnsupportedKernelException(String message) {
        super(message);
    }
}
