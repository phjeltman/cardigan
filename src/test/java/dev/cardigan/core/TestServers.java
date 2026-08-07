// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.tls.TlsConfig;

/** Integration-test servers with Cardigan's explicit example route set. */
final class TestServers {
    private TestServers() {
    }

    static CardiganServer example(int port, int eventLoops) {
        return CardiganServer.builder()
            .port(port)
            .eventLoops(eventLoops)
            .routes(new TestController())
            .build();
    }

    static CardiganServer example(
            int port, int eventLoops, TlsConfig tlsConfig) {
        return CardiganServer.builder()
            .port(port)
            .eventLoops(eventLoops)
            .tls(tlsConfig)
            .routes(new TestController())
            .build();
    }

    static CardiganServer example(
            int port,
            int eventLoops,
            TlsConfig tlsConfig,
            ProtocolMode protocolMode) {
        CardiganServer.Builder builder = CardiganServer.builder()
            .port(port)
            .eventLoops(eventLoops)
            .protocol(protocolMode)
            .routes(new TestController());
        if (tlsConfig == null) {
            builder.plaintext();
        } else {
            builder.tls(tlsConfig);
        }
        return builder.build();
    }
}
