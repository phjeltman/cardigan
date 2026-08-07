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
public class JumboPayloadDosProtectionTest {

    private static final int PORT = 8094;
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
    public void testOversizedContentLengthReturns413WithoutOOM() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", PORT)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Send a header claiming 2GB Content-Length
            String attackHeaders = "POST /users HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: 2147483648\r\n\r\n";

            out.write(attackHeaders.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            byte[] responseBuffer = new byte[1024];
            int read = in.read(responseBuffer);
            assertTrue(read > 0, "Expected response from server");

            String responseStr = new String(responseBuffer, 0, read, StandardCharsets.US_ASCII);
            assertTrue(responseStr.contains("413 Payload Too Large"), "Expected HTTP 413 Payload Too Large response, got: " + responseStr);
        }
    }
}
