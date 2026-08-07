// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class SharedExchangeExecutorTest {
    private static final int PORT = 8102;
    private static final int CONNECTION_COUNT = 64;

    private CardiganServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = TestServers.example(PORT, 1);
        server.start();
        Thread.sleep(100);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void depthOneConnectionsReuseEventLoopWorkers() throws Exception {
        List<Socket> sockets = new ArrayList<>(CONNECTION_COUNT);
        try {
            byte[] request = (
                "GET /users/1 HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: keep-alive\r\n\r\n"
            ).getBytes(StandardCharsets.US_ASCII);

            for (int i = 0; i < CONNECTION_COUNT; i++) {
                Socket socket = new Socket("127.0.0.1", PORT);
                socket.setSoTimeout(5_000);
                socket.getOutputStream().write(request);
                socket.getOutputStream().flush();
                sockets.add(socket);
            }

            for (Socket socket : sockets) {
                String response = readResponse(socket.getInputStream());
                assertTrue(response.contains("HTTP/1.1 200 OK"));
                socket.close();
            }

            int workers = server.exchangeWorkerCount();
            assertTrue(workers > 0, "No shared exchange worker was created");
            assertTrue(
                workers <= 2,
                "Depth-one requests created " + workers + " workers for "
                    + CONNECTION_COUNT + " connections"
            );
        } finally {
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }

    private static String readResponse(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        byte[] buffer = new byte[256];
        int headerEnd = -1;
        int responseLength = Integer.MAX_VALUE;

        while (bytes.size() < responseLength) {
            int read = input.read(buffer);
            if (read < 0) {
                break;
            }
            bytes.write(buffer, 0, read);

            if (headerEnd < 0) {
                String response = bytes.toString(StandardCharsets.US_ASCII);
                headerEnd = response.indexOf("\r\n\r\n");
                if (headerEnd >= 0) {
                    int contentLengthStart = response.indexOf("Content-Length: ");
                    int contentLengthEnd = response.indexOf("\r\n", contentLengthStart);
                    int contentLength = Integer.parseInt(
                        response.substring(contentLengthStart + 16, contentLengthEnd)
                    );
                    responseLength = headerEnd + 4 + contentLength;
                }
            }
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }
}
