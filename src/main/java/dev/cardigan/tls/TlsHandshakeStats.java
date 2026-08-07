// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.tls;

/** Snapshot of the bounded TLS handshake stage. */
public record TlsHandshakeStats(
        int limitPerLoop,
        long timeoutMillis,
        int active,
        long admitted,
        long rejected,
        long timedOut) {
    public String summary() {
        return "limit.per.loop=" + limitPerLoop
            + ", timeout.ms=" + timeoutMillis
            + ", active=" + active
            + ", admitted=" + admitted
            + ", rejected=" + rejected
            + ", timed_out=" + timedOut;
    }
}
