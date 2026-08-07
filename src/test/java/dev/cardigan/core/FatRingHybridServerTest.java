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
public class FatRingHybridServerTest {

    private static final int PORT = 8089;
    private CardiganServer server;

    @BeforeEach
    public void setUp() {
        server = TestServers.example(PORT, 1);
        server.start();
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {}
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void testPhase1And2_FastPath() throws Exception {
        try (Socket socket = new Socket("localhost", PORT)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String req = "GET /users/427 HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            byte[] buf = new byte[1024];
            int read = in.read(buf);
            assertTrue(read > 0);
            String responseStr = new String(buf, 0, read, StandardCharsets.US_ASCII);
            assertTrue(responseStr.contains("HTTP/1.1 200 OK"));
            assertTrue(responseStr.contains("User details for ID: 427"));
        }
    }

    @Test
    public void testPhase3_FragmentedRequest() throws Exception {
        try (Socket socket = new Socket("localhost", PORT)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String req = "GET /users/999 HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n";
            byte[] bytes = req.getBytes(StandardCharsets.US_ASCII);

            // Send in small 5-byte fragments
            for (int i = 0; i < bytes.length; i += 5) {
                int len = Math.min(5, bytes.length - i);
                out.write(bytes, i, len);
                out.flush();
                Thread.sleep(10);
            }

            byte[] buf = new byte[1024];
            int read = in.read(buf);
            assertTrue(read > 0);
            String responseStr = new String(buf, 0, read, StandardCharsets.US_ASCII);
            assertTrue(responseStr.contains("HTTP/1.1 200 OK"));
            assertTrue(responseStr.contains("User details for ID: 999"));
        }
    }

    @Test
    public void testPhase4_TcpPipelining() throws Exception {
        try (Socket socket = new Socket("localhost", PORT)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String req1 = "GET /users/1 HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n";
            String req2 = "GET /users/2 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";

            // Send both requests in a single write (pipelining)
            byte[] combined = (req1 + req2).getBytes(StandardCharsets.US_ASCII);
            out.write(combined);
            out.flush();

            byte[] buf = new byte[2048];
            int totalRead = 0;
            int bytesRead;
            while ((bytesRead = in.read(buf, totalRead, buf.length - totalRead)) != -1) {
                totalRead += bytesRead;
                if (totalRead >= buf.length) break;
            }

            String responseStr = new String(buf, 0, totalRead, StandardCharsets.US_ASCII);
            assertTrue(responseStr.contains("User details for ID: 1"));
            assertTrue(responseStr.contains("User details for ID: 2"));
        }
    }

    @Test
    public void testPhase5_JumboPayload() throws Exception {
        try (Socket socket = new Socket("localhost", PORT)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Construct 100KB JSON body (exceeds 64KB frame)
            StringBuilder nameBuilder = new StringBuilder();
            for (int i = 0; i < 10000; i++) {
                nameBuilder.append("UserNumber").append(i).append("_");
            }
            String jumboName = nameBuilder.toString();
            String jsonBody = "{\"name\":\"" + jumboName + "\",\"id\":12345,\"active\":true}";
            byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

            String headers = "POST /users HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: "
                    + bodyBytes.length + "\r\nConnection: close\r\n\r\n";
            byte[] headerBytes = headers.getBytes(StandardCharsets.US_ASCII);

            // Send headers + body
            out.write(headerBytes);
            out.write(bodyBytes);
            out.flush();

            byte[] buf = new byte[8192];
            int totalRead = 0;
            int bytesRead;
            while ((bytesRead = in.read(buf, totalRead, buf.length - totalRead)) != -1) {
                totalRead += bytesRead;
                if (totalRead >= buf.length) break;
            }
            assertTrue(totalRead > 0);
            String responseStr = new String(buf, 0, totalRead, StandardCharsets.UTF_8);
            assertTrue(responseStr.contains("HTTP/1.1 200 OK"));
            assertTrue(responseStr.contains("UserNumber0"));
        }
    }
}
