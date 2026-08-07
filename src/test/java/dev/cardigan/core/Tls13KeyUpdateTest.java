// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.tls.TlsConfig;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.LINUX)
@Tag("integration")
@Tag("tls")
@Tag("advanced-tls")
class Tls13KeyUpdateTest {
    private static final int PORT = 8115;
    private static final String INBOUND_KEY_UPDATE =
        "<<< TLS 1.3, Handshake [length 0005], KeyUpdate";
    private static CardiganServer server;
    private static OpenSslCommand openssl;

    @BeforeAll
    static void setUp() throws Exception {
        Path certificate = Path.of(Tls13KeyUpdateTest.class.getResource(
            "/tls/localhost-cert.pem").toURI());
        Path privateKey = Path.of(Tls13KeyUpdateTest.class.getResource(
            "/tls/localhost-key.pem").toURI());
        server = TestServers.example(
            PORT, 1, new TlsConfig(certificate, privateKey));
        server.start();
        Thread.sleep(100);
        openssl = OpenSslCommand.resolve();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    @Timeout(15)
    void handlesRepeatedRequestedKeyUpdates() throws Exception {
        try (Client client = Client.start(openssl, false)) {
            client.send(request("/users/423", true));
            client.await("ID: 423", 1);

            client.send("K\n");
            client.await("KEYUPDATE", 1);
            client.send(request("/users/423", true));
            client.await("ID: 423", 2);
            client.await(INBOUND_KEY_UPDATE, 1);

            client.send("K\n");
            client.await("KEYUPDATE", 2);
            client.send(request("/users/423", false));
            client.await("ID: 423", 3);
            client.await(INBOUND_KEY_UPDATE, 2);
        }
    }

    @Test
    @Timeout(15)
    void ordersKeyUpdateWithQueuedDirectResponse() throws Exception {
        assumeTrue(openssl.advancedCommands());
        try (Client client = Client.start(openssl, true)) {
            client.send(
                request("/some/response/large", true)
                    + "{keyup:req}"
                    + request("/users/423", true)
                    + "{keyup:req}"
                    + request("/users/423", false)
            );
            client.await("HTTP/1.1 200 OK", 3);
            client.await("Content-Length: 65536", 1);
            client.await("ID: 423", 2);
            client.await(INBOUND_KEY_UPDATE, 2);
        }
    }

    private static String request(String path, boolean keepAlive) {
        return "GET " + path + " HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Connection: "
            + (keepAlive ? "keep-alive" : "close")
            + "\r\n\r\n";
    }

    private record OpenSslCommand(boolean advancedCommands) {
        private static OpenSslCommand resolve() throws Exception {
            ProcessBuilder helpBuilder = new ProcessBuilder(
                "openssl", "s_client", "-help");
            Process help = helpBuilder.redirectErrorStream(true).start();
            String output;
            try (InputStream input = help.getInputStream()) {
                output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            help.waitFor();
            return new OpenSslCommand(output.contains(" -adv "));
        }
    }

    private static final class Client implements AutoCloseable {
        private final Process process;
        private final OutputStream input;
        private final OutputCapture output;

        private Client(
                Process process, OutputStream input, OutputCapture output) {
            this.process = process;
            this.input = input;
            this.output = output;
        }

        private static Client start(
                OpenSslCommand command, boolean advanced) throws Exception {
            List<String> arguments = new ArrayList<>(List.of(
                "openssl",
                "s_client",
                "-connect", "localhost:" + PORT,
                "-tls1_3",
                "-alpn", "http/1.1",
                "-msg",
                "-verify_quiet"
            ));
            if (advanced) {
                arguments.add("-adv");
            }
            ProcessBuilder builder = new ProcessBuilder(arguments)
                .redirectErrorStream(true);
            Process process = builder.start();
            OutputCapture output = new OutputCapture(process.getInputStream());
            return new Client(process, process.getOutputStream(), output);
        }

        private void send(String text) throws Exception {
            input.write(text.getBytes(StandardCharsets.US_ASCII));
            input.flush();
        }

        private void await(String needle, int occurrences) throws Exception {
            output.await(needle, occurrences, process);
        }

        @Override
        public void close() {
            try {
                input.close();
            } catch (Exception ignored) {
            }
            process.destroy();
            try {
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            output.close();
        }
    }

    private static final class OutputCapture implements AutoCloseable {
        private final Object lock = new Object();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final Thread reader;

        private OutputCapture(InputStream input) {
            reader = Thread.ofPlatform()
                .daemon()
                .name("openssl-keyupdate-output")
                .start(() -> copy(input));
        }

        private void copy(InputStream input) {
            byte[] buffer = new byte[8_192];
            try (input) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    synchronized (lock) {
                        bytes.write(buffer, 0, count);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        private void await(
                String needle, int occurrences, Process process)
                throws Exception {
            long deadline = System.nanoTime()
                + Duration.ofSeconds(8).toNanos();
            while (true) {
                String snapshot = snapshot();
                if (occurrences(snapshot, needle) >= occurrences) {
                    return;
                }
                if (!process.isAlive()) {
                    throw new AssertionError(
                        "OpenSSL exited before '" + needle + "':\n" + snapshot);
                }
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError(
                        "Timed out waiting for '" + needle + "':\n" + snapshot);
                }
                Thread.sleep(10);
            }
        }

        private String snapshot() {
            synchronized (lock) {
                return bytes.toString(StandardCharsets.UTF_8);
            }
        }

        private static int occurrences(String value, String needle) {
            int count = 0;
            int offset = 0;
            while ((offset = value.indexOf(needle, offset)) >= 0) {
                count++;
                offset += needle.length();
            }
            return count;
        }

        @Override
        public void close() {
            try {
                reader.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
