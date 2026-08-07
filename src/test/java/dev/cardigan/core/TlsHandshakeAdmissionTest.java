// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.tls.TlsConfig;
import dev.cardigan.tls.TlsHandshakeStats;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@Tag("tls")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class TlsHandshakeAdmissionTest {
    private static final int PORT = 8116;
    private static final String LIMIT_PROPERTY =
        "cardigan.tls.handshake.max.pending.per.loop";
    private static final String TIMEOUT_PROPERTY =
        "cardigan.tls.handshake.timeout.millis";
    private static CardiganServer server;
    private static SSLContext clientContext;

    @BeforeAll
    static void setUp() throws Exception {
        System.setProperty(LIMIT_PROPERTY, "1");
        System.setProperty(TIMEOUT_PROPERTY, "300");
        Path certificate = Path.of(TlsHandshakeAdmissionTest.class.getResource(
            "/tls/localhost-cert.pem").toURI());
        Path privateKey = Path.of(TlsHandshakeAdmissionTest.class.getResource(
            "/tls/localhost-key.pem").toURI());
        server = TestServers.example(
            PORT, 1, new TlsConfig(certificate, privateKey));
        server.start();
        Thread.sleep(100);

        TrustManager[] trustAll = {new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }

            @Override
            public void checkClientTrusted(
                    X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(
                    X509Certificate[] chain, String authType) {
            }
        }};
        clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, trustAll, new SecureRandom());
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.close();
        }
        System.clearProperty(LIMIT_PROPERTY);
        System.clearProperty(TIMEOUT_PROPERTY);
    }

    @Test
    @Timeout(10)
    void rejectsExcessHandshakesAndExpiresAStalledPeer() throws Exception {
        try (Socket stalled = new Socket("localhost", PORT)) {
            stalled.setSoTimeout(2_000);
            await(() -> server.tlsHandshakeStats().active() == 1);

            try (SSLSocket rejected = (SSLSocket) clientContext
                    .getSocketFactory().createSocket("localhost", PORT)) {
                rejected.setSoTimeout(2_000);
                assertThrows(IOException.class, rejected::startHandshake);
            }
            await(() -> server.tlsHandshakeStats().rejected() == 1);

            assertEquals(-1, stalled.getInputStream().read());
            await(() -> {
                TlsHandshakeStats stats = server.tlsHandshakeStats();
                return stats.timedOut() == 1 && stats.active() == 0;
            });
        }

        try (SSLSocket recovered = (SSLSocket) clientContext
                .getSocketFactory().createSocket("localhost", PORT)) {
            recovered.setSoTimeout(2_000);
            recovered.startHandshake();
            assertEquals("TLSv1.3", recovered.getSession().getProtocol());
        }
        assertTrue(server.tlsHandshakeStats().admitted() >= 2);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for TLS state");
            }
            Thread.sleep(10);
        }
    }
}
