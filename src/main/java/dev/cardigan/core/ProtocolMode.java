// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

/** The HTTP protocols accepted by a Cardigan listener. */
public enum ProtocolMode {
    HTTP1_ONLY,
    HTTP2_ONLY,
    HTTP1_AND_HTTP2
}
