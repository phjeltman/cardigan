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
public class TcpPipeliningTest {

    private static final int PORT = 8096;
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
    public void testPipelinedRequestsCompleteAndPartial() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", PORT)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Request 1: Complete GET /users/1
            // Request 2: Partial POST /users (header split)
            String req1 = "GET /users/1 HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n";
            String req2HeaderPartial = "POST /users HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-";

            // Send req1 + partial req2 header in a single TCP frame
            out.write((req1 + req2HeaderPartial).getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Read response for Request 1
            byte[] responseBuffer = new byte[1024];
            int read = in.read(responseBuffer);
            assertTrue(read > 0, "Expected response for Request 1");
            String resp1 = new String(responseBuffer, 0, read, StandardCharsets.US_ASCII);
            assertTrue(resp1.contains("HTTP/1.1 200 OK"), "Expected 200 OK for Request 1");

            // Sleep briefly to simulate network packet split
            Thread.sleep(50);

            // Now send the rest of Request 2 (completing header and body)
            String jsonBody = "{\"name\":\"PipelinedUser\",\"id\":99}";
            byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            String req2Remaining = "Length: " + bodyBytes.length + "\r\nConnection: close\r\n\r\n" + jsonBody;

            out.write(req2Remaining.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Read response for Request 2
            int read2 = in.read(responseBuffer);
            assertTrue(read2 > 0, "Expected response for Request 2");
            String resp2 = new String(responseBuffer, 0, read2, StandardCharsets.US_ASCII);
            assertTrue(resp2.contains("HTTP/1.1 200 OK"), "Expected 200 OK for Request 2");
            assertTrue(resp2.contains("PipelinedUser"), "Expected body for Request 2");
        }
    }
}
