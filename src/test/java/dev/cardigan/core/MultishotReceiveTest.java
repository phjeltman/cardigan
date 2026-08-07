// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.IsolatedRouteStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@Tag("integration")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class MultishotReceiveTest {
    private static final int PORT = 8101;

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
    void oneReceiveSubmissionProducesMultipleChunks() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(2_000);
                BufferedInputStream input = new BufferedInputStream(socket.getInputStream());

                for (int i = 0; i < 6; i++) {
                    boolean last = i == 5;
                    socket.getOutputStream().write((
                        "GET /users/" + i + " HTTP/1.1\r\n"
                            + "Host: localhost\r\n"
                            + "Connection: " + (last ? "close" : "keep-alive") + "\r\n\r\n"
                    ).getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();

                    String response = readResponse(input);
                    assertTrue(response.contains("User details for ID: " + i));
                }
            }
        });

        assertTrue(server.isMultishotReceiveObserved(),
            "receive CQEs never carried IORING_CQE_F_MORE");
        assertTrue(server.multishotReceiveCompletionCount() >= 2,
            "one receive submission did not produce multiple CQEs");
    }

    @Test
    void requestArrivingAfterSleepStartsRunsConcurrently() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(5_000);
                long started = System.nanoTime();

                socket.getOutputStream().write((
                    "GET /sleepy HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: keep-alive\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                Thread.sleep(250);
                socket.getOutputStream().write((
                    "GET /sleepy HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: close\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                String responses = new String(
                    socket.getInputStream().readAllBytes(),
                    StandardCharsets.US_ASCII
                );
                long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

                assertTrue(elapsedMillis < 3_500,
                    "delayed requests suffered connection-level HOL blocking for "
                        + elapsedMillis + "ms");
                assertTrue(count(responses, "Slept like a baby for 2000ms!") == 2);
            }
        });
    }

    @Test
    void writesExactStaticBodyOverHttp1() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(5_000);
                BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());
                socket.getOutputStream().write((
                    "GET /some/response/large HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: close\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                String response = readResponse(input);
                int bodyOffset = response.indexOf("\r\n\r\n") + 4;
                String body = response.substring(bodyOffset);
                assertEquals(65_536, body.length());
                assertTrue(body.chars().allMatch(value -> value == 'A'));
            }
        });
    }

    @Test
    void streamsFixedLengthBodiesAndPreservesPipelinedBytes() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(5_000);
                BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());
                byte[] upload = new byte[256 * 1024];
                socket.getOutputStream().write((
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Length: " + upload.length + "\r\n"
                        + "Connection: keep-alive\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(upload);
                socket.getOutputStream().write((
                    "GET /stream/32768 HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: close\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                String uploadResponse = readResponse(input);
                assertTrue(
                    uploadResponse.endsWith("Received 262144 bytes"),
                    uploadResponse);
                String downloadResponse = readResponse(input);
                int bodyOffset = downloadResponse.indexOf("\r\n\r\n") + 4;
                String body = downloadResponse.substring(bodyOffset);
                assertEquals(32_768, body.length());
                assertTrue(body.chars().allMatch(value -> value == 'A'));
            }
        });
    }

    @Test
    void isolatedStreamingUploadCrossesBoundedBridgeAndPreservesPipeline() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(5_000);
                BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());
                byte[] upload = new byte[256 * 1024];
                Arrays.fill(upload, (byte) 'I');
                socket.getOutputStream().write((
                    "POST /stream/upload-heavy HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Length: " + upload.length + "\r\n"
                        + "Connection: keep-alive\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(upload);
                socket.getOutputStream().write((
                    "GET /users/17 HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: close\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                String uploadResponse = readResponse(input);
                assertTrue(uploadResponse.contains(
                    "Heavy upload received 262144 bytes"), uploadResponse);
                String followingResponse = readResponse(input);
                assertTrue(followingResponse.contains("ID: 17"),
                    followingResponse);
            }
        });
    }

    @Test
    void isolatedStreamingUploadAcceptsChunkedFraming() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(5_000);
                BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());
                socket.getOutputStream().write((
                    "POST /stream/upload-heavy HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Connection: close\r\n\r\n"
                        + "4\r\nWiki\r\n"
                        + "5\r\npedia\r\n"
                        + "0\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                String response = readResponse(input);
                assertTrue(response.contains(
                    "Heavy upload received 9 bytes"), response);
            }
        });
    }

    @Test
    void chunksUnknownLengthBodiesAndPreservesPipelinedBytes() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(5_000);
                BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());
                socket.getOutputStream().write((
                    "GET /stream-unknown/32768 HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: keep-alive\r\n\r\n"
                        + "GET /users/7 HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: close\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                ChunkedResponse streamed = readChunkedResponse(input);
                assertTrue(streamed.headers.contains(
                    "Transfer-Encoding: chunked\r\n"));
                assertFalse(streamed.headers.contains("Content-Length:"));
                assertEquals(32_768, streamed.body.length);
                for (byte value : streamed.body) {
                    assertEquals((byte) 'A', value);
                }

                String following = readResponse(input);
                assertTrue(following.contains("ID: 7"), following);
            }
        });
    }

    @Test
    void slowStreamingUploadDoesNotBlockAnotherConnection() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket slow = new Socket("127.0.0.1", PORT)) {
                slow.getOutputStream().write((
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Length: 1048576\r\n"
                        + "Connection: close\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                slow.getOutputStream().write(new byte[1024]);
                slow.getOutputStream().flush();
                Thread.sleep(50);

                long started = System.nanoTime();
                try (Socket fast = new Socket("127.0.0.1", PORT)) {
                    fast.setSoTimeout(2_000);
                    BufferedInputStream input =
                        new BufferedInputStream(fast.getInputStream());
                    fast.getOutputStream().write((
                        "GET /users/7 HTTP/1.1\r\n"
                            + "Host: localhost\r\n"
                            + "Connection: close\r\n\r\n"
                    ).getBytes(StandardCharsets.US_ASCII));
                    fast.getOutputStream().flush();
                    String response = readResponse(input);
                    assertTrue(response.contains("ID: 7"));
                }
                long elapsedMillis =
                    (System.nanoTime() - started) / 1_000_000;
                assertTrue(
                    elapsedMillis < 1_000,
                    "slow upload occupied the io_uring carrier for "
                        + elapsedMillis + "ms");
            }
        });
    }

    @Test
    void slowIsolatedUploadDoesNotBlockAnotherConnection() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket slow = new Socket("127.0.0.1", PORT)) {
                slow.getOutputStream().write((
                    "POST /stream/upload-heavy HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Length: 1048576\r\n"
                        + "Connection: close\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                slow.getOutputStream().write(new byte[1024]);
                slow.getOutputStream().flush();
                Thread.sleep(50);

                long started = System.nanoTime();
                try (Socket fast = new Socket("127.0.0.1", PORT)) {
                    fast.setSoTimeout(2_000);
                    BufferedInputStream input =
                        new BufferedInputStream(fast.getInputStream());
                    fast.getOutputStream().write((
                        "GET /users/18 HTTP/1.1\r\n"
                            + "Host: localhost\r\n"
                            + "Connection: close\r\n\r\n"
                    ).getBytes(StandardCharsets.US_ASCII));
                    fast.getOutputStream().flush();
                    assertTrue(readResponse(input).contains("ID: 18"));
                }
                long elapsedMillis =
                    (System.nanoTime() - started) / 1_000_000;
                assertTrue(elapsedMillis < 1_000,
                    "isolated upload occupied the io_uring carrier for "
                        + elapsedMillis + "ms");
            }
        });
    }

    @Test
    void disconnectCancelsIsolatedStreamingUpload() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            int activeBefore = IsolatedRouteStats.snapshot().active();
            Socket socket = new Socket("127.0.0.1", PORT);
            try {
                socket.getOutputStream().write((
                    "POST /stream/upload-heavy HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Length: 1048576\r\n"
                        + "Connection: close\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(new byte[1024]);
                socket.getOutputStream().flush();

                long startedDeadline = System.nanoTime()
                    + 2_000_000_000L;
                while (IsolatedRouteStats.snapshot().active()
                        == activeBefore
                    && System.nanoTime() < startedDeadline) {
                    Thread.sleep(2);
                }
                assertEquals(activeBefore + 1,
                    IsolatedRouteStats.snapshot().active());
            } finally {
                socket.close();
            }

            long stoppedDeadline = System.nanoTime()
                + 2_000_000_000L;
            while (IsolatedRouteStats.snapshot().active() != activeBefore
                && System.nanoTime() < stoppedDeadline) {
                Thread.sleep(2);
            }
            assertEquals(activeBefore,
                IsolatedRouteStats.snapshot().active());
        });
    }

    @Test
    void slowChunkedUploadDoesNotBlockAnotherConnection() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket slow = new Socket("127.0.0.1", PORT)) {
                slow.getOutputStream().write((
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Connection: close\r\n\r\n"
                        + "100000\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                slow.getOutputStream().write(new byte[1024]);
                slow.getOutputStream().flush();
                Thread.sleep(50);

                long started = System.nanoTime();
                try (Socket fast = new Socket("127.0.0.1", PORT)) {
                    fast.setSoTimeout(2_000);
                    BufferedInputStream input =
                        new BufferedInputStream(fast.getInputStream());
                    fast.getOutputStream().write((
                        "GET /users/8 HTTP/1.1\r\n"
                            + "Host: localhost\r\n"
                            + "Connection: close\r\n\r\n"
                    ).getBytes(StandardCharsets.US_ASCII));
                    fast.getOutputStream().flush();
                    String response = readResponse(input);
                    assertTrue(response.contains("ID: 8"), response);
                }
                long elapsedMillis =
                    (System.nanoTime() - started) / 1_000_000;
                assertTrue(
                    elapsedMillis < 1_000,
                    "slow chunked upload occupied the io_uring carrier for "
                        + elapsedMillis + "ms");
            }
        });
    }

    @Test
    void truncatedChunkedUploadIsNotDispatchedAndReleasesConnection() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(2_000);
                socket.getOutputStream().write((
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Connection: close\r\n\r\n"
                        + "8\r\nabc"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                socket.shutdownOutput();

                String response = new String(
                    socket.getInputStream().readAllBytes(),
                    StandardCharsets.US_ASCII);
                assertFalse(
                    response.contains("HTTP/1.1 200"),
                    "truncated body reached the route: " + response);
            }

            long deadline = System.nanoTime() + 2_000_000_000L;
            while (server.activeConnectionCount() != 0
                    && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertEquals(0, server.activeConnectionCount());
        });
    }

    @Test
    void streamsChunkedUploadsAndPreservesPipelinedBytes() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(5_000);
                BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());
                socket.getOutputStream().write((
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Connection: keep-alive\r\n\r\n"
                        + "4;source=test\r\nWiki\r\n"
                        + "5\r\npedia\r\n"
                        + "0\r\nX-Checksum: accepted\r\n\r\n"
                        + "GET /users/7 HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: close\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                String uploadResponse = readResponse(input);
                assertTrue(
                    uploadResponse.endsWith("Received 9 bytes"),
                    uploadResponse);
                String followingResponse = readResponse(input);
                assertTrue(
                    followingResponse.contains("ID: 7"),
                    followingResponse);
            }
        });
    }

    @Test
    void continuesFixedAndChunkedStreamingUploadsBeforeReadingBodies() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(2_000);
                BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());
                socket.getOutputStream().write((
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Length: 9\r\n"
                        + "Expect: 100-continue\r\n"
                        + "Connection: keep-alive\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                assertEquals(
                    "HTTP/1.1 100 Continue\r\n\r\n",
                    readHeaderBlock(input));
                socket.getOutputStream().write(
                    "Wikipedia".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                assertTrue(
                    readResponse(input).endsWith("Received 9 bytes"));

                socket.getOutputStream().write((
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Expect: 100-Continue\r\n"
                        + "Connection: close\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                assertEquals(
                    "HTTP/1.1 100 Continue\r\n\r\n",
                    readHeaderBlock(input));
                socket.getOutputStream().write(
                    "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                assertTrue(
                    readResponse(input).endsWith("Received 9 bytes"));
                assertEquals(-1, input.read());
            }
        });
    }

    @Test
    void continuesMaterializedPostBeforeReadingBody() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            byte[] json = "{\"name\":\"Continue\",\"id\":73,\"active\":true}"
                .getBytes(StandardCharsets.US_ASCII);
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(2_000);
                BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());
                socket.getOutputStream().write((
                    "POST /users HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + json.length + "\r\n"
                        + "Expect: 100-continue\r\n"
                        + "Connection: close\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                assertEquals(
                    "HTTP/1.1 100 Continue\r\n\r\n",
                    readHeaderBlock(input));
                socket.getOutputStream().write(json);
                socket.getOutputStream().flush();
                String response = readResponse(input);
                assertTrue(response.startsWith("HTTP/1.1 200"), response);
                assertTrue(response.contains("Continue"), response);
            }
        });
    }

    @Test
    void rejectsUnsupportedOrOversizedExpectationsWithoutContinue() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(2_000);
                BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());
                socket.getOutputStream().write((
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Length: 4\r\n"
                        + "Expect: cardigan-magic\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                String response = readResponse(input);
                assertTrue(response.startsWith(
                    "HTTP/1.1 417 Expectation Failed"), response);
                assertEquals(-1, input.read());
            }

            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(2_000);
                BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());
                socket.getOutputStream().write((
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Length: 20000000\r\n"
                        + "Expect: 100-continue\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                String response = readResponse(input);
                assertTrue(response.startsWith(
                    "HTTP/1.1 413 Payload Too Large"), response);
                assertFalse(response.contains("100 Continue"), response);
                assertEquals(-1, input.read());
            }
        });
    }

    @Test
    void rejectsAmbiguousHttp1RequestFraming() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(2_000);
                BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());
                socket.getOutputStream().write((
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Length: 4\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Connection: keep-alive\r\n\r\n"
                        + "4\r\ntest\r\n0\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                String response = readResponse(input);
                assertTrue(response.startsWith("HTTP/1.1 400"), response);
                assertEquals(-1, input.read(),
                    "ambiguous framing must close the connection");
            }
        });
    }

    @Test
    void rejectsRequestSmugglingFramingCorpusAndClosesConnection() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            String[][] cases = {
                {
                    "duplicate Content-Length",
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Length: 4\r\n"
                        + "Content-Length: 4\r\n\r\n"
                        + "test",
                    "400"
                },
                {
                    "conflicting Content-Length",
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Length: 4\r\n"
                        + "Content-Length: 5\r\n\r\n"
                        + "test!",
                    "400"
                },
                {
                    "signed Content-Length",
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Length: +4\r\n\r\n"
                        + "test",
                    "400"
                },
                {
                    "comma-separated Content-Length",
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Length: 4, 4\r\n\r\n"
                        + "test",
                    "400"
                },
                {
                    "overflowing Content-Length",
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Length: 18446744073709551616\r\n\r\n",
                    "413"
                },
                {
                    "unsupported transfer coding",
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Transfer-Encoding: gzip\r\n\r\n",
                    "400"
                },
                {
                    "non-terminal transfer coding",
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Transfer-Encoding: gzip, chunked\r\n\r\n",
                    "400"
                },
                {
                    "duplicate Transfer-Encoding",
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\n"
                        + "0\r\n\r\n",
                    "400"
                },
                {
                    "HTTP/1.0 chunked body",
                    "POST /stream/upload HTTP/1.0\r\n"
                        + "Host: localhost\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\n"
                        + "0\r\n\r\n",
                    "400"
                },
                {
                    "chunked body on materialized route",
                    "POST /users HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\n"
                        + "0\r\n\r\n",
                    "400"
                }
            };

            for (String[] testCase : cases) {
                assertSmugglingFramingRejected(
                    testCase[0], testCase[1], testCase[2]);
            }
        });
    }

    @Test
    void mapsMalformedPicoChunkFramingToBadRequest() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(2_000);
                BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());
                socket.getOutputStream().write((
                    "POST /stream/upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Connection: keep-alive\r\n\r\n"
                        + "1x\r\na\r\n0\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                String response = readResponse(input);
                assertTrue(response.startsWith("HTTP/1.1 400"), response);
                assertEquals(-1, input.read(),
                    "malformed framing must close the connection");
            }
        });
    }

    @Test
    void boundedQueueCancelsAndRearmsReceiveWithoutLosingBytes() {
        System.setProperty("cardigan.receive.pause.chunks", "1");
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                try (Socket socket = new Socket("127.0.0.1", PORT)) {
                    socket.setSoTimeout(5_000);

                    byte[] json = new byte[512 * 1024];
                    Arrays.fill(json, (byte) ' ');
                    byte[] prefix = "{\"name\":\"Backpressure\",\"id\":91,\"active\":true}"
                        .getBytes(StandardCharsets.US_ASCII);
                    System.arraycopy(prefix, 0, json, 0, prefix.length);

                    socket.getOutputStream().write((
                        "POST /users HTTP/1.1\r\n"
                            + "Host: localhost\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + json.length + "\r\n"
                            + "Connection: close\r\n\r\n"
                    ).getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(json);
                    socket.getOutputStream().flush();

                    String response = new String(
                        socket.getInputStream().readAllBytes(),
                        StandardCharsets.US_ASCII
                    );
                    assertTrue(response.contains("HTTP/1.1 200 OK"), response);
                    assertTrue(response.contains("Backpressure"), response);
                }
            });
        } finally {
            System.clearProperty("cardigan.receive.pause.chunks");
        }

        assertTrue(server.receiveBackpressureEventCount() > 0,
            "large receive never crossed the configured queue high-water mark");
        assertFalse(server.isReceiveFallbackUsed(), "receive fell back under backpressure");
    }

    private static String readResponse(BufferedInputStream input) throws Exception {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        response.write(readHeaderBlock(input).getBytes(
            StandardCharsets.US_ASCII));

        String headers = response.toString(StandardCharsets.US_ASCII);
        int marker = headers.indexOf("Content-Length: ");
        int end = headers.indexOf("\r\n", marker);
        int contentLength = Integer.parseInt(headers.substring(marker + 16, end));
        response.write(input.readNBytes(contentLength));
        return response.toString(StandardCharsets.US_ASCII);
    }

    private static String readHeaderBlock(BufferedInputStream input)
        throws Exception {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int value = input.read();
            if (value < 0) {
                throw new IllegalStateException("Connection closed before response headers");
            }
            response.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> matched;
            };
        }
        return response.toString(StandardCharsets.US_ASCII);
    }

    private static void assertSmugglingFramingRejected(
        String description,
        String request,
        String expectedStatus
    ) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", PORT)) {
            socket.setSoTimeout(2_000);
            BufferedInputStream input =
                new BufferedInputStream(socket.getInputStream());
            String smuggled = "GET /users/999 HTTP/1.1\r\n"
                + "Host: localhost\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(
                (request + smuggled).getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            String response = readResponse(input);
            assertTrue(
                response.startsWith("HTTP/1.1 " + expectedStatus),
                description + ": " + response);
            assertFalse(
                response.contains("ID: 999"),
                description + " dispatched bytes after invalid framing");
            assertEquals(
                -1,
                input.read(),
                description + " did not close the connection");
        }
    }

    private static ChunkedResponse readChunkedResponse(
        BufferedInputStream input
    ) throws Exception {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int value = input.read();
            if (value < 0) {
                throw new IllegalStateException(
                    "Connection closed before response headers");
            }
            headerBytes.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> matched;
            };
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readAsciiLine(input);
            int extension = sizeLine.indexOf(';');
            if (extension >= 0) {
                sizeLine = sizeLine.substring(0, extension);
            }
            int length = Integer.parseUnsignedInt(sizeLine.trim(), 16);
            if (length == 0) {
                assertEquals("", readAsciiLine(input));
                return new ChunkedResponse(
                    headerBytes.toString(StandardCharsets.US_ASCII),
                    body.toByteArray()
                );
            }
            byte[] chunk = input.readNBytes(length);
            assertEquals(length, chunk.length);
            body.write(chunk);
            assertEquals('\r', input.read());
            assertEquals('\n', input.read());
        }
    }

    private static String readAsciiLine(BufferedInputStream input)
        throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int value = input.read();
            if (value < 0) {
                throw new IllegalStateException("Connection closed mid-line");
            }
            if (previous == '\r' && value == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(
                    bytes, 0, bytes.length - 1,
                    StandardCharsets.US_ASCII);
            }
            line.write(value);
            previous = value;
        }
    }

    private record ChunkedResponse(String headers, byte[] body) {
    }

    private static int count(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
