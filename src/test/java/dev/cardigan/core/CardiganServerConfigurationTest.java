// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.Get;
import dev.cardigan.http.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class CardiganServerConfigurationTest {
    private static final int PORT = 8131;

    @Test
    void builderInstallsOnlyExplicitApplicationRoutes() throws Exception {
        try (CardiganServer server = CardiganServer.builder()
                .port(PORT)
                .eventLoops(1)
                .protocol(ProtocolMode.HTTP1_ONLY)
                .plaintext()
            .routes(new ApplicationController())
                .build()) {
            server.start();

            assertTrue(request("/application").startsWith(
                "HTTP/1.1 200 OK"));
            assertTrue(request("/users/1").startsWith(
                "HTTP/1.1 404 Not Found"));
        }
    }

    @Test
    void listenerFailureIsReportedByStart() throws Exception {
        try (ServerSocket blocker = new ServerSocket()) {
            blocker.setReuseAddress(false);
            blocker.bind(new InetSocketAddress("0.0.0.0", 0));

            try (CardiganServer server = CardiganServer.builder()
                    .port(blocker.getLocalPort())
                    .eventLoops(1)
                    .protocol(ProtocolMode.HTTP1_ONLY)
                    .plaintext()
                    .routes(new ApplicationController())
                    .build()) {
                IllegalStateException failure = assertThrows(
                    IllegalStateException.class, server::start);
                assertTrue(failure.getMessage().contains(
                    "Listener initialization failed"));
            }
        }
    }

    @Test
    void listenersShareOneRuntimeLoopSet() throws Exception {
        int firstPort = PORT + 1;
        int secondPort = PORT + 2;
        try (CardiganRuntime runtime = CardiganRuntime.builder()
                .eventLoops(1)
                .build();
             CardiganServer first = CardiganServer.builder()
                .port(firstPort)
                .runtime(runtime)
                .protocol(ProtocolMode.HTTP1_ONLY)
                .plaintext()
                .routes(new ApplicationController())
                .build();
             CardiganServer second = CardiganServer.builder()
                .port(secondPort)
                .runtime(runtime)
                .protocol(ProtocolMode.HTTP1_ONLY)
                .plaintext()
                .routes(new ApplicationController())
                .build()) {
            first.start();
            second.start();

            assertEquals(1, runtime.eventLoops().size());
            assertTrue(request(firstPort, "/application").startsWith(
                "HTTP/1.1 200 OK"));
            assertTrue(request(secondPort, "/application").startsWith(
                "HTTP/1.1 200 OK"));
        }
    }

    private static String request(String path) throws Exception {
        return request(PORT, path);
    }

    private static String request(int port, String path) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2_000);
            socket.getOutputStream().write((
                "GET " + path + " HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(
                socket.getInputStream().readAllBytes(),
                StandardCharsets.US_ASCII);
        }
    }

    public static final class ApplicationController {
        @Get("/application")
        public Response application() {
            return Response.text("configured");
        }
    }
}
