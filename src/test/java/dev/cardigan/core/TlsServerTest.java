// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.tls.TlsConfig;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@Tag("tls")
class TlsServerTest {
    private static final int PORT = 8112;
    private static CardiganServer server;
    private static SSLContext clientContext;

    @BeforeAll
    static void setUp() throws Exception {
        Path certificate = Path.of(TlsServerTest.class.getResource(
            "/tls/localhost-cert.pem").toURI());
        Path privateKey = Path.of(TlsServerTest.class.getResource(
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
    }

    @Test
    void servesHttp11OverTls() throws Exception {
        HttpResponse<String> response = request(HttpClient.Version.HTTP_1_1);
        assertEquals(HttpClient.Version.HTTP_1_1, response.version());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("ID: 423"));
    }

    @Test
    void openSslNegotiatesTls13() throws Exception {
        SSLParameters tls13 = new SSLParameters();
        tls13.setProtocols(new String[] {"TLSv1.3"});
        try (SSLSocket socket = (SSLSocket) clientContext
                .getSocketFactory().createSocket("localhost", PORT)) {
            socket.setSSLParameters(tls13);
            socket.setSoTimeout(3_000);
            socket.startHandshake();
            assertEquals("TLSv1.3", socket.getSession().getProtocol());
        }
    }

    @Test
    void negotiatesHttp2WithAlpn() throws Exception {
        HttpResponse<String> response = request(HttpClient.Version.HTTP_2);
        assertEquals(HttpClient.Version.HTTP_2, response.version());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("ID: 423"));
    }

    @Test
    void survivesAbruptPostHandshakeEof() throws Exception {
        Socket transport = new Socket("localhost", PORT);
        SSLSocket tls = (SSLSocket) clientContext.getSocketFactory()
            .createSocket(transport, "localhost", PORT, false);
        tls.setSoTimeout(3_000);
        tls.startHandshake();
        transport.close();

        HttpResponse<String> response = request(HttpClient.Version.HTTP_1_1);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("ID: 423"));
    }

    @Test
    void servesVectoredLargeResponseOverTls() throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .sslContext(clientContext)
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(3))
                .build()) {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("https://localhost:" + PORT
                        + "/some/response/large"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
            HttpResponse<byte[]> response = client.send(
                request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(200, response.statusCode());
            assertEquals(65_536, response.body().length);
            assertEquals((byte) 'A', response.body()[0]);
            assertEquals((byte) 'A', response.body()[32_768]);
            assertEquals((byte) 'A', response.body()[65_535]);
        }
    }

    @Test
    void streamsBodiesOverHttp1AndHttp2Tls() throws Exception {
        byte[] upload = new byte[128 * 1024];
        for (HttpClient.Version version : new HttpClient.Version[] {
                HttpClient.Version.HTTP_1_1,
                HttpClient.Version.HTTP_2}) {
            try (HttpClient client = HttpClient.newBuilder()
                    .sslContext(clientContext)
                    .version(version)
                    .connectTimeout(Duration.ofSeconds(3))
                    .build()) {
                HttpResponse<String> uploadResponse = client.send(
                    HttpRequest.newBuilder(URI.create(
                            "https://localhost:" + PORT + "/stream/upload"))
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(upload))
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(version, uploadResponse.version());
                assertEquals(200, uploadResponse.statusCode());
                assertEquals(
                    "Received 131072 bytes", uploadResponse.body());

                HttpResponse<byte[]> downloadResponse = client.send(
                    HttpRequest.newBuilder(URI.create(
                            "https://localhost:" + PORT + "/stream/131072"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(version, downloadResponse.version());
                assertEquals(200, downloadResponse.statusCode());
                assertEquals(131_072, downloadResponse.body().length);
                assertEquals((byte) 'A', downloadResponse.body()[0]);
                assertEquals(
                    (byte) 'A', downloadResponse.body()[131_071]);

                HttpResponse<byte[]> unknownResponse = client.send(
                    HttpRequest.newBuilder(URI.create(
                            "https://localhost:" + PORT
                                + "/stream-unknown/131072"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(version, unknownResponse.version());
                assertEquals(200, unknownResponse.statusCode());
                assertEquals(131_072, unknownResponse.body().length);
                assertTrue(unknownResponse.headers()
                    .firstValue("content-length").isEmpty());
                assertEquals((byte) 'A', unknownResponse.body()[0]);
                assertEquals(
                    (byte) 'A', unknownResponse.body()[131_071]);
            }
        }
    }

    @Test
    @Timeout(30)
    void reusesTlsEgressBuffersBeyondPoolCapacity() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("https://localhost:" + PORT + "/users/423"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
        try (HttpClient client = HttpClient.newBuilder()
                .sslContext(clientContext)
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(3))
                .build()) {
            for (int i = 0; i < 4_100; i++) {
                HttpResponse<Void> response = client.send(
                    request, HttpResponse.BodyHandlers.discarding());
                assertEquals(HttpClient.Version.HTTP_2, response.version());
                assertEquals(200, response.statusCode());
            }
        }
    }

    private static HttpResponse<String> request(HttpClient.Version version)
            throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .sslContext(clientContext)
                .version(version)
                .connectTimeout(Duration.ofSeconds(3))
                .build()) {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("https://localhost:" + PORT + "/users/423"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
