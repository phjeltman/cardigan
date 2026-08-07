// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.tls.TlsConfig;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@Tag("tls")
class Tls12DirectReceiveTest {
    private static final int PORT = 8113;
    private static CardiganServer server;
    private static SSLContext clientContext;

    @BeforeAll
    static void setUp() throws Exception {
        Path certificate = Path.of(Tls12DirectReceiveTest.class.getResource(
            "/tls/localhost-cert.pem").toURI());
        Path privateKey = Path.of(Tls12DirectReceiveTest.class.getResource(
            "/tls/localhost-key.pem").toURI());
        server = TestServers.example(
            PORT,
            1,
            new TlsConfig(certificate, privateKey, true, true));
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
    void servesHttp2ThroughMultishotKtlsReceive() throws Exception {
        SSLParameters parameters = new SSLParameters();
        parameters.setProtocols(new String[] {"TLSv1.2"});
        try (HttpClient client = HttpClient.newBuilder()
                .sslContext(clientContext)
                .sslParameters(parameters)
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
            assertTrue(server.isMultishotReceiveObserved());
            assertFalse(server.isReceiveFallbackUsed());
        }
    }

    @Test
    void consumesPeerCloseNotifyThroughRecvmsgAncillaryData() throws Exception {
        SSLParameters parameters = new SSLParameters();
        parameters.setProtocols(new String[] {"TLSv1.2"});
        parameters.setApplicationProtocols(new String[] {"http/1.1"});

        try (SSLSocket socket = (SSLSocket) clientContext
                .getSocketFactory().createSocket("localhost", PORT)) {
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(3_000);
            socket.startHandshake();
            socket.getOutputStream().write((
                "GET /users/423 HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: keep-alive\r\n\r\n"
            ).getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            String headers = readHeaders(socket);
            assertTrue(headers.startsWith("HTTP/1.1 200 OK\r\n"));
            int contentLength = contentLength(headers);
            assertEquals(contentLength,
                socket.getInputStream().readNBytes(contentLength).length);
        }

        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!server.isTlsCloseNotifyObserved()
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(server.isTlsCloseNotifyObserved());
        assertFalse(server.isTlsDirectReceiveFailed());
    }

    private static String readHeaders(SSLSocket socket) throws Exception {
        byte[] bytes = new byte[8_192];
        int length = 0;
        while (length < bytes.length) {
            int value = socket.getInputStream().read();
            if (value < 0) {
                break;
            }
            bytes[length++] = (byte) value;
            if (length >= 4
                    && bytes[length - 4] == '\r'
                    && bytes[length - 3] == '\n'
                    && bytes[length - 2] == '\r'
                    && bytes[length - 1] == '\n') {
                return new String(
                    bytes, 0, length, StandardCharsets.US_ASCII);
            }
        }
        throw new IllegalStateException("Incomplete HTTP response headers");
    }

    private static int contentLength(String headers) {
        String marker = "Content-Length: ";
        int start = headers.indexOf(marker);
        int end = headers.indexOf("\r\n", start);
        if (start < 0 || end < 0) {
            throw new IllegalStateException("Missing Content-Length");
        }
        return Integer.parseInt(headers.substring(start + marker.length(), end));
    }
}
