// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.tls;

import dev.cardigan.core.UringEventLoop;

/** Process-wide immutable TLS server context. */
public final class TlsContext implements AutoCloseable {
    private final boolean directKtlsReceive;
    private final boolean directKtlsSend;
    private final TlsHandshakeAdmission handshakeAdmission =
        new TlsHandshakeAdmission();
    private final KtlsKernelStats initialKernelStats = KtlsKernelStats.read();
    private PanamaTls.Context handle;

    public TlsContext(TlsConfig config, boolean http2Only) {
        this(config, http2Only, false);
    }

    public TlsContext(
            TlsConfig config, boolean http2Only, boolean http1Only) {
        directKtlsReceive = config.directKtlsReceive();
        directKtlsSend = Boolean.parseBoolean(System.getProperty(
                TlsConfig.DIRECT_KTLS_SEND_PROPERTY, "true"));
        handle = PanamaTls.Context.create(config, http2Only, http1Only);
    }

    public TlsConnection accept(
            UringEventLoop loop, int fd, int fixedSlot) {
        TlsHandshakeAdmission.Lease lease =
            handshakeAdmission.tryAcquire(loop, fd);
        if (lease == null) {
            return null;
        }
        try (lease) {
            PanamaTls.Context current = handle;
            if (current == null) {
                throw new IllegalStateException("TLS context is closed");
            }
            return TlsConnection.accept(this, current, loop, fd, fixedSlot);
        }
    }

    public TlsHandshakeStats handshakeStats() {
        return handshakeAdmission.snapshot();
    }

    public String kernelStatsSummary() {
        return KtlsKernelStats.read().since(initialKernelStats).summary();
    }

    boolean directKtlsReceive() {
        return directKtlsReceive;
    }

    boolean directKtlsSend() {
        return directKtlsSend;
    }

    @Override
    public void close() {
        PanamaTls.Context current = handle;
        handle = null;
        if (current != null) {
            current.close();
        }
    }
}
