// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@Tag("stress")
public class HighConcurrencyIdleConnectionsTest {

    private static final int BASE_PORT = 8091;
    private final List<CardiganServer> servers = new ArrayList<>();

    @AfterEach
    public void tearDown() {
        for (CardiganServer s : servers) {
            try {
                s.close();
            } catch (Exception ignored) {}
        }
        servers.clear();
    }

    @Test
    public void testHighConcurrencyIdleConnections() throws Exception {
        int connectionCount = Integer.getInteger("cardigan.concurrency.test.count", 10000);
        System.out.println("Testing " + connectionCount + " concurrent idle connections...");

        int requiredServers = Math.max(1, (connectionCount + 24999) / 25000);
        System.out.println("Starting " + requiredServers + " io_uring server instances...");
        for (int s = 0; s < requiredServers; s++) {
            CardiganServer server = TestServers.example(BASE_PORT + s, 2);
            server.start();
            servers.add(server);
        }
        Thread.sleep(300);

        List<SocketChannel> channels = new ArrayList<>(connectionCount);
        long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        try {
            for (int i = 0; i < connectionCount; i++) {
                int targetPort = BASE_PORT + (i / 25000);
                SocketChannel channel = SocketChannel.open();
                channel.configureBlocking(true);
                channel.connect(new InetSocketAddress("127.0.0.1", targetPort));
                channels.add(channel);

                if ((i + 1) % 5000 == 0 || (i + 1) == connectionCount) {
                    System.out.println("Opened " + (i + 1) + " / " + connectionCount + " idle connections...");
                }
            }

            System.out.println("Successfully established " + channels.size() + " concurrent idle connections!");
            for (int i = 0; i < channels.size(); i++) {
                byte[] request = ("GET /users/" + i + " HTTP/1.1\r\n"
                    + "Host: localhost\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
                channels.get(i).socket().getOutputStream().write(request);
            }
            awaitActiveConnections(connectionCount);

            System.gc();
            Thread.sleep(200);
            long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            System.out.println("Heap Memory Before: " + (memBefore / 1024 / 1024) + " MB, After: " + (memAfter / 1024 / 1024) + " MB");

            // Verify a subset received the fast-path response and stayed usable.
            int[] testIndices = {0, connectionCount / 4, connectionCount / 2, connectionCount - 1};
            for (int idx : testIndices) {
                SocketChannel channel = channels.get(idx);

                Socket socket = channel.socket();
                InputStream in = socket.getInputStream();

                byte[] buf = new byte[512];
                int read = in.read(buf);
                assertTrue(read > 0, "Connection " + idx + " failed to read response");
                String resp = new String(buf, 0, read, StandardCharsets.US_ASCII);
                assertTrue(resp.contains("HTTP/1.1 200 OK"), "Connection " + idx + " did not receive 200 OK");
                assertTrue(resp.contains("User details for ID: " + idx), "Connection " + idx + " payload mismatch");
            }

            System.out.println("Verified active request execution on idle connections successfully!");

        } finally {
            System.out.println("Closing " + channels.size() + " connections...");
            for (SocketChannel ch : channels) {
                try {
                    ch.close();
                } catch (Exception ignored) {}
            }
            awaitConnectionsClosed();
        }
    }

    private void awaitActiveConnections(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        int minimum = expected - Math.max(1, expected / 100);
        int active;
        do {
            active = servers.stream()
                .mapToInt(CardiganServer::activeConnectionCount)
                .sum();
            if (active >= minimum) {
                System.out.println("Cardigan accepted " + active + " / "
                    + expected + " keep-alive connections");
                return;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);

        fail("Only " + active + " of " + expected
            + " connections reached Cardigan before the timeout (minimum "
            + minimum + ")");
    }

    private void awaitConnectionsClosed() throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        int active;
        do {
            active = servers.stream()
                .mapToInt(CardiganServer::activeConnectionCount)
                .sum();
            if (active == 0) {
                return;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);

        fail(active + " keep-alive connections remained after client close");
    }
}
