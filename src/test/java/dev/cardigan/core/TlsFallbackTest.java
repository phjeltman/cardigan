// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.tls.TlsCapabilities;
import dev.cardigan.tls.TlsConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("integration")
@Tag("tls")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class TlsFallbackTest {
    private static final int PORT = 8117;
    private static Path certificate;
    private static Path privateKey;
    private static SSLContext clientContext;

    @BeforeAll
    static void setUp() throws Exception {
        certificate = Path.of(TlsFallbackTest.class.getResource(
            "/tls/localhost-cert.pem").toURI());
        privateKey = Path.of(TlsFallbackTest.class.getResource(
            "/tls/localhost-key.pem").toURI());
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

    @Test
    @Timeout(20)
    void servesThroughOpenSslAndCompleteUserspaceFallbacks() throws Exception {
        withServerProperty(TlsConfig.DIRECT_KTLS_SEND_PROPERTY, "false",
            server -> {
                HttpResponse<byte[]> response = largeRequest();
                assertEquals(200, response.statusCode());
                assertEquals(65_536, response.body().length);
                TlsCapabilities capabilities = server.tlsCapabilities();
                assertFalse(capabilities.directTransmit());
            });

        withServerProperty(TlsConfig.KTLS_PROPERTY, "false", server -> {
            HttpResponse<byte[]> response = largeRequest();
            assertEquals(200, response.statusCode());
            assertEquals(65_536, response.body().length);
            TlsCapabilities capabilities = server.tlsCapabilities();
            assertFalse(capabilities.kernelTransmit());
            assertFalse(capabilities.kernelReceive());
            assertFalse(capabilities.directTransmit());
            assertFalse(capabilities.directReceive());
        });
    }

    private static HttpResponse<byte[]> largeRequest() throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .sslContext(clientContext)
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(3))
                .build()) {
            return client.send(
                HttpRequest.newBuilder(URI.create(
                        "https://localhost:" + PORT
                            + "/some/response/large"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
        }
    }

    private static void withServerProperty(
            String property, String value, ServerAction action)
            throws Exception {
        System.setProperty(property, value);
        CardiganServer server;
        try {
            server = TestServers.example(
                PORT, 1, new TlsConfig(certificate, privateKey));
        } finally {
            System.clearProperty(property);
        }
        server.start();
        Thread.sleep(100);
        try (server) {
            action.run(server);
        }
    }

    @FunctionalInterface
    private interface ServerAction {
        void run(CardiganServer server) throws Exception;
    }
}
