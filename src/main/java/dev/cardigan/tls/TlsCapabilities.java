// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.tls;

/** Directional TLS transport selected for an established connection. */
public record TlsCapabilities(
        String applicationProtocol,
        boolean kernelTransmit,
        boolean kernelReceive,
        boolean directTransmit,
        boolean directReceive) {
}
