// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class HeaderTooLargeTest {

    private static final int PORT = 8095;
    private CardiganServer server;

    @BeforeEach
    public void setUp() throws Exception {
        server = TestServers.example(PORT, 1);
        server.start();
        Thread.sleep(100);
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void testOversizedHeadersReturn431() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", PORT)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Create a massive cookie header > 8KB
            StringBuilder largeCookie = new StringBuilder("Cookie: ");
            for (int i = 0; i < 900; i++) {
                largeCookie.append("session_token_part_").append(i).append("=abcdef123456789; ");
            }
            largeCookie.append("\r\n");

            String requestStr = "GET /users/1 HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    largeCookie +
                    "\r\n";

            out.write(requestStr.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            byte[] responseBuffer = new byte[1024];
            int read = in.read(responseBuffer);
            assertTrue(read > 0, "Expected response from server");

            String responseStr = new String(responseBuffer, 0, read, StandardCharsets.US_ASCII);
            assertTrue(responseStr.contains("431 Request Header Fields Too Large"),
                    "Expected HTTP 431 Request Header Fields Too Large, got: " + responseStr);
        }
    }
}
