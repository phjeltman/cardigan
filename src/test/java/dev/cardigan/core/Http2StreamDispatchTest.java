// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.core.Http2TestSupport.Frame;
import dev.cardigan.http.IsolatedRouteStats;
import dev.cardigan.http2.HpackDecoder;
import dev.cardigan.http2.HpackFields;
import dev.cardigan.http2.Http2Frames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static dev.cardigan.core.Http2TestSupport.frame;
import static dev.cardigan.core.Http2TestSupport.readFrame;

@Tag("integration")
class Http2StreamDispatchTest {
    private static final int PORT = 8104;

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
    void routesRequestAndFramesResponse() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                sendGet(socket.getOutputStream(), 1, "/users/423");

                Frame headers = readFrame(input);
                assertEquals(Http2Frames.HEADERS, headers.type);
                assertEquals(1, headers.streamId);
                assertTrue((headers.flags & Http2Frames.FLAG_END_HEADERS) != 0);
                assertResponseHeaders(headers.payload, "200", "text/plain");

                Frame data = readFrame(input);
                assertEquals(Http2Frames.DATA, data.type);
                assertEquals(1, data.streamId);
                assertTrue((data.flags & Http2Frames.FLAG_END_STREAM) != 0);
                assertEquals(
                    "User details for ID: 423 parsed directly off-heap!",
                    new String(data.payload, StandardCharsets.UTF_8)
                );
            }
        });
    }

    @Test
    void laterStreamCompletesWhileEarlierStreamSleeps() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                long start = System.nanoTime();

                sendGet(output, 1, "/sleepy");
                sendGet(output, 3, "/users/3");

                Frame fastHeaders = readFrame(input);
                Frame fastData = readFrame(input);
                long fastMillis = (System.nanoTime() - start) / 1_000_000;
                assertEquals(3, fastHeaders.streamId);
                assertEquals(3, fastData.streamId);
                assertTrue(fastMillis < 1_000,
                    "stream 3 waited behind /sleepy for " + fastMillis + "ms");

                Frame sleepyHeaders = readFrame(input);
                Frame sleepyData = readFrame(input);
                assertEquals(1, sleepyHeaders.streamId);
                assertEquals(1, sleepyData.streamId);
                assertTrue(new String(sleepyData.payload, StandardCharsets.UTF_8)
                    .contains("Slept like a baby"));
            }
        });
    }

    @Test
    void assemblesRequestBodyAcrossDataFrames() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                byte[] json = "{\"name\":\"H2\",\"id\":7,\"active\":true}"
                    .getBytes(StandardCharsets.UTF_8);

                sendPostHeaders(output, 1, "/users", json.length);
                output.write(frame(
                    Http2Frames.DATA, 0, 1,
                    java.util.Arrays.copyOfRange(json, 0, 11)));
                output.write(frame(
                    Http2Frames.DATA, Http2Frames.FLAG_END_STREAM, 1,
                    java.util.Arrays.copyOfRange(json, 11, json.length)));
                output.flush();

                Frame headers = readFrame(input);
                Frame data = readFrame(input);
                assertEquals(Http2Frames.HEADERS, headers.type);
                assertEquals(Http2Frames.DATA, data.type);
                assertEquals(1, headers.streamId);
                assertEquals(1, data.streamId);
                String response = new String(data.payload, StandardCharsets.UTF_8);
                assertTrue(response.contains("\"name\":\"H2\""), response);
                assertTrue(response.contains("\"id\":7"), response);
            }
        });
    }

    @Test
    void streamsRequestBodyWhileDataFramesArrive() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                byte[] chunk = new byte[16 * 1024];

                sendPostHeaders(
                    output, 1, "/stream/upload", chunk.length * 5);
                output.write(frame(Http2Frames.DATA, 0, 1, chunk));
                output.write(frame(Http2Frames.DATA, 0, 1, chunk));
                output.flush();

                boolean connectionCredit = false;
                boolean streamCredit = false;
                while (!connectionCredit || !streamCredit) {
                    Frame update = readFrame(input);
                    if (update.type == Http2Frames.WINDOW_UPDATE) {
                        connectionCredit |= update.streamId == 0;
                        streamCredit |= update.streamId == 1;
                    }
                }

                output.write(frame(Http2Frames.DATA, 0, 1, chunk));
                output.write(frame(Http2Frames.DATA, 0, 1, chunk));
                output.write(frame(
                    Http2Frames.DATA,
                    Http2Frames.FLAG_END_STREAM,
                    1,
                    chunk));
                output.flush();

                byte[] response = readResponse(input, 1, Set.of());
                assertEquals(
                    "Received 81920 bytes",
                    new String(response, StandardCharsets.UTF_8));
            }
        });
    }

    @Test
    void isolatedStreamingBodyReturnsFlowCreditOnOwnerLoop() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                byte[] chunk = new byte[16 * 1024];

                sendPostHeaders(
                    output, 1, "/stream/upload-heavy", chunk.length * 5);
                output.write(frame(Http2Frames.DATA, 0, 1, chunk));
                output.write(frame(Http2Frames.DATA, 0, 1, chunk));
                output.flush();

                boolean connectionCredit = false;
                boolean streamCredit = false;
                while (!connectionCredit || !streamCredit) {
                    Frame update = readFrame(input);
                    if (update.type == Http2Frames.WINDOW_UPDATE) {
                        connectionCredit |= update.streamId == 0;
                        streamCredit |= update.streamId == 1;
                    }
                }

                output.write(frame(Http2Frames.DATA, 0, 1, chunk));
                output.write(frame(Http2Frames.DATA, 0, 1, chunk));
                output.write(frame(
                    Http2Frames.DATA,
                    Http2Frames.FLAG_END_STREAM,
                    1,
                    chunk));
                output.flush();

                String response = new String(
                    readResponse(input, 1, Set.of()),
                    StandardCharsets.UTF_8);
                assertTrue(response.contains(
                    "Heavy upload received 81920 bytes"), response);
            }
            awaitStreamingReservations();
        });
    }

    @Test
    void waitingStreamingUploadDoesNotBlockSiblingStream() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                sendPostHeaders(output, 1, "/stream/upload", 1);
                sendGet(output, 3, "/users/11", false);
                output.flush();

                byte[] response = readResponse(input, 3, Set.of());
                assertTrue(
                    new String(response, StandardCharsets.UTF_8)
                        .contains("ID: 11"));
                output.write(rstStream(1, Http2Frames.CANCEL));
                output.flush();
            }
            awaitStreamingReservations();
        });
    }

    @Test
    void concurrentStreamingUploadsCompleteCancelAndReuseConnection() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                int[] uploadStreams = {1, 3, 5, 7};
                byte[] firstChunk = new byte[16_383];
                byte[] fullChunk = new byte[16_384];

                // Four partial bodies consume almost the complete connection
                // window. None has consumed enough to earn stream-level
                // credit, while their aggregate consumption must replenish
                // the connection and leave an unrelated GET runnable.
                for (int streamId : uploadStreams) {
                    sendPostHeaders(
                        output, streamId, "/stream/upload", 32_769);
                    output.write(frame(
                        Http2Frames.DATA, 0, streamId, firstChunk));
                }
                sendGet(output, 9, "/users/9", false);
                output.flush();

                ByteArrayOutputStream fastBody = new ByteArrayOutputStream();
                boolean fastEnded = false;
                int connectionCredit = 0;
                Set<Integer> earlyStreamCredits = new HashSet<>();
                while (!fastEnded || connectionCredit == 0) {
                    Frame frame = readFrame(input);
                    if (frame.type == Http2Frames.WINDOW_UPDATE) {
                        if (frame.streamId == 0) {
                            connectionCredit += windowIncrement(frame);
                        } else {
                            earlyStreamCredits.add(frame.streamId);
                        }
                    } else if (frame.streamId == 9
                        && frame.type == Http2Frames.DATA) {
                        fastBody.write(frame.payload);
                        fastEnded = (frame.flags
                            & Http2Frames.FLAG_END_STREAM) != 0;
                    }
                }
                assertTrue(new String(
                    fastBody.toByteArray(), StandardCharsets.UTF_8
                ).contains("ID: 9"));
                assertTrue(earlyStreamCredits.isEmpty(),
                    "stream credit preceded consumption threshold: "
                        + earlyStreamCredits);

                // Cancel one partial upload, complete two in turn, and leave
                // the fourth stalled until after both successful responses.
                output.write(rstStream(5, Http2Frames.CANCEL));
                completeStreamingUpload(
                    input, output, 1, fullChunk, 32_769);
                completeStreamingUpload(
                    input, output, 3, fullChunk, 32_769);

                output.write(rstStream(7, Http2Frames.CANCEL));
                sendGet(output, 11, "/users/11", false);
                output.flush();
                assertTrue(new String(
                    readResponse(input, 11, Set.of()),
                    StandardCharsets.UTF_8
                ).contains("ID: 11"));

                // Completion and cancellation must both release their native
                // request-body reservations without closing the connection.
                awaitStreamingReservations();
            }
        });
    }

    @Test
    void simultaneousUploadDownloadAndCancellationPreserveConnection() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (Socket socket = connect(32_768)) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                byte[] uploadChunk = new byte[16_384];
                byte[] cancelledChunk = new byte[8_192];

                sendPostHeaders(
                    output, 1, "/stream/upload", uploadChunk.length * 4);
                output.write(frame(
                    Http2Frames.DATA, 0, 1, uploadChunk));
                sendGet(output, 3, "/stream/1048576", false);
                sendGet(output, 5, "/users/5", false);
                sendPostHeaders(
                    output, 7, "/stream/upload", cancelledChunk.length * 4);
                output.write(frame(
                    Http2Frames.DATA, 0, 7, cancelledChunk));
                output.flush();

                ByteArrayOutputStream fastBody = new ByteArrayOutputStream();
                boolean fastEnded = false;
                int downloadBytes = 0;
                while (!fastEnded || downloadBytes < 32_768) {
                    Frame frame = readFrame(input);
                    assertTrue(frame.streamId != 1 && frame.streamId != 7,
                        "upload responded before END_STREAM");
                    if (frame.streamId == 3
                        && frame.type == Http2Frames.DATA) {
                        downloadBytes += frame.payload.length;
                    } else if (frame.streamId == 5
                        && frame.type == Http2Frames.DATA) {
                        fastBody.write(frame.payload);
                        fastEnded = (frame.flags
                            & Http2Frames.FLAG_END_STREAM) != 0;
                    }
                }
                assertEquals(32_768, downloadBytes);
                assertTrue(new String(
                    fastBody.toByteArray(), StandardCharsets.UTF_8
                ).contains("ID: 5"));

                // Abort one response producer and one request consumer while
                // the surviving upload continues to return receive credit.
                output.write(rstStream(3, Http2Frames.CANCEL));
                output.write(rstStream(7, Http2Frames.CANCEL));
                output.write(frame(
                    Http2Frames.DATA, 0, 1, uploadChunk));
                output.flush();

                int connectionCredit = 0;
                int streamCredit = 0;
                while (connectionCredit == 0 || streamCredit < 32_768) {
                    Frame frame = readFrame(input);
                    assertTrue(frame.streamId != 1
                            || frame.type == Http2Frames.WINDOW_UPDATE,
                        "upload responded before END_STREAM");
                    if (frame.type != Http2Frames.WINDOW_UPDATE) {
                        continue;
                    }
                    if (frame.streamId == 0) {
                        connectionCredit += windowIncrement(frame);
                    } else if (frame.streamId == 1) {
                        streamCredit += windowIncrement(frame);
                    }
                }
                assertEquals(32_768, streamCredit);

                output.write(frame(
                    Http2Frames.DATA, 0, 1, uploadChunk));
                output.write(frame(
                    Http2Frames.DATA,
                    Http2Frames.FLAG_END_STREAM,
                    1,
                    uploadChunk
                ));
                output.flush();
                assertEquals(
                    "Received 65536 bytes",
                    new String(
                        readResponse(input, 1, Set.of()),
                        StandardCharsets.UTF_8));

                sendGet(output, 9, "/users/9", false);
                output.flush();
                assertTrue(new String(
                    readResponse(input, 9, Set.of()),
                    StandardCharsets.UTF_8
                ).contains("ID: 9"));
                awaitStreamingReservations();
            }
        });
    }

    @Test
    void streamsFixedLengthResponseAcrossDataFrames() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                sendGet(socket.getOutputStream(), 1, "/stream/32768");

                byte[] response = readResponse(input, 1, Set.of());
                assertEquals(32_768, response.length);
                for (byte value : response) {
                    assertEquals((byte) 'A', value);
                }
            }
        });
    }

    @Test
    void streamsUnknownLengthResponseWithoutContentLength() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                sendGet(
                    socket.getOutputStream(),
                    1,
                    "/stream-unknown/65535"
                );

                Frame headers = readFrame(input);
                assertEquals(Http2Frames.HEADERS, headers.type);
                assertUnknownResponseHeaders(headers.payload);

                int received = 0;
                Frame terminal;
                do {
                    terminal = readFrame(input);
                    assertEquals(Http2Frames.DATA, terminal.type);
                    assertEquals(1, terminal.streamId);
                    for (byte value : terminal.payload) {
                        assertEquals((byte) 'A', value);
                    }
                    received += terminal.payload.length;
                } while ((terminal.flags
                    & Http2Frames.FLAG_END_STREAM) == 0);

                assertEquals(65_535, received);
                assertEquals(0, terminal.payload.length,
                    "an exact flow-window boundary needs an empty END_STREAM");
            }
        });
    }

    @Test
    void unknownLengthResponseRefundsUnusedFlowCredit() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                sendGet(output, 1, "/stream-unknown/60000");
                assertEquals(60_000, readResponse(input, 1, Set.of()).length);

                // The producer reached EOF after reserving a larger DATA
                // frame. Stream 3 can only complete if that unused portion
                // was returned to the connection window.
                sendGet(output, 3, "/users/7");
                byte[] response = readResponse(input, 3, Set.of());
                assertTrue(new String(response, StandardCharsets.UTF_8)
                    .contains("ID: 7"));
            }
        });
    }

    @Test
    void emptyUnknownLengthResponseEndsWithZeroPeerWindow() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect(0)) {
                InputStream input = socket.getInputStream();
                sendGet(
                    socket.getOutputStream(),
                    1,
                    "/stream-unknown/0"
                );

                Frame headers = readFrame(input);
                assertEquals(Http2Frames.HEADERS, headers.type);
                assertUnknownResponseHeaders(headers.payload);
                Frame end = readFrame(input);
                assertEquals(Http2Frames.DATA, end.type);
                assertEquals(0, end.payload.length);
                assertTrue(
                    (end.flags & Http2Frames.FLAG_END_STREAM) != 0);
            }
        });
    }

    @Test
    void streamingResponseHonorsHttp2FlowControl() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                sendGet(output, 1, "/stream/65536");

                Frame headers = readFrame(input);
                assertEquals(Http2Frames.HEADERS, headers.type);
                int received = 0;
                boolean ended = false;
                while (received < 65_535) {
                    Frame data = readFrame(input);
                    assertEquals(Http2Frames.DATA, data.type);
                    received += data.payload.length;
                    ended |= (data.flags & Http2Frames.FLAG_END_STREAM) != 0;
                }
                assertEquals(65_535, received);
                assertTrue(!ended);

                output.write(windowUpdate(0, 1));
                output.write(windowUpdate(1, 1));
                output.flush();
                Frame last = readFrame(input);
                assertEquals(Http2Frames.DATA, last.type);
                assertEquals(1, last.payload.length);
                assertEquals((byte) 'A', last.payload[0]);
                assertTrue(
                    (last.flags & Http2Frames.FLAG_END_STREAM) != 0);
            }
        });
    }

    @Test
    void concurrentStreamingResponsesAllResumeAfterWriterDrain() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                int streamCount = 8;
                int[] received = new int[streamCount];
                boolean[] sawHeaders = new boolean[streamCount];

                // Let every stream consume its complete initial stream
                // window without exhausting the connection window. Each
                // producer consequently reaches ConnectionWriter.awaitDrained
                // before it needs its final byte of stream credit.
                output.write(windowUpdate(0, 1_000_000));
                for (int index = 0; index < streamCount; index++) {
                    sendGet(
                        output,
                        index * 2 + 1,
                        "/stream/65536",
                        false
                    );
                }
                output.flush();

                int streamsAtWindow = 0;
                while (streamsAtWindow != streamCount) {
                    Frame frame = readFrame(input);
                    int index = (frame.streamId - 1) >>> 1;
                    assertTrue(index >= 0 && index < streamCount,
                        "unexpected stream " + frame.streamId);
                    if (frame.type == Http2Frames.HEADERS) {
                        sawHeaders[index] = true;
                    } else if (frame.type == Http2Frames.DATA) {
                        int before = received[index];
                        received[index] += frame.payload.length;
                        if (before < 65_535 && received[index] == 65_535) {
                            streamsAtWindow++;
                        }
                    }
                }

                for (int index = 0; index < streamCount; index++) {
                    assertTrue(sawHeaders[index]);
                    assertEquals(65_535, received[index]);
                    output.write(windowUpdate(index * 2 + 1, 1));
                }
                output.flush();

                int completed = 0;
                while (completed != streamCount) {
                    Frame frame = readFrame(input);
                    int index = (frame.streamId - 1) >>> 1;
                    assertTrue(index >= 0 && index < streamCount,
                        "unexpected stream " + frame.streamId);
                    if (frame.type == Http2Frames.RST_STREAM) {
                        throw new AssertionError(
                            "server reset stream " + frame.streamId);
                    }
                    if (frame.type == Http2Frames.DATA) {
                        received[index] += frame.payload.length;
                        if ((frame.flags & Http2Frames.FLAG_END_STREAM) != 0) {
                            completed++;
                        }
                    }
                }
                for (int bytes : received) {
                    assertEquals(65_536, bytes);
                }

                sendGet(output, 17, "/users/17");
                assertTrue(new String(
                    readResponse(input, 17, Set.of()),
                    StandardCharsets.UTF_8
                ).contains("ID: 17"));
            }
        });
    }

    @Test
    void concurrentStreamingResponsesCrossRepeatedDrainCycles() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                int streamCount = 8;
                int[] received = new int[streamCount];
                int completed = 0;

                output.write(windowUpdate(0, 1_000_000));
                for (int index = 0; index < streamCount; index++) {
                    sendGet(
                        output,
                        index * 2 + 1,
                        "/stream/1048576",
                        false
                    );
                }
                output.flush();

                while (completed != streamCount) {
                    Frame frame;
                    try {
                        frame = readFrame(input);
                    } catch (java.net.SocketTimeoutException timeout) {
                        throw new AssertionError(
                            "timed out after " + completed
                                + " completions; received="
                                + java.util.Arrays.toString(received),
                            timeout
                        );
                    }
                    int index = (frame.streamId - 1) >>> 1;
                    assertTrue(index >= 0 && index < streamCount,
                        "unexpected stream " + frame.streamId);
                    if (frame.type == Http2Frames.RST_STREAM) {
                        throw new AssertionError(
                            "server reset stream " + frame.streamId);
                    }
                    if (frame.type != Http2Frames.DATA) {
                        continue;
                    }
                    received[index] += frame.payload.length;
                    output.write(windowUpdate(0, frame.payload.length));
                    if ((frame.flags & Http2Frames.FLAG_END_STREAM) == 0) {
                        output.write(windowUpdate(
                            frame.streamId, frame.payload.length));
                    } else {
                        completed++;
                    }
                    output.flush();
                }

                for (int bytes : received) {
                    assertEquals(1_048_576, bytes);
                }
            }
        });
    }

    @Test
    void cancellingQueuedStreamingResponseHandsDrainToNextProducer() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                int streamCount = 8;
                int[] received = new int[streamCount];

                output.write(windowUpdate(0, 1_000_000));
                for (int index = 0; index < streamCount; index++) {
                    sendGet(
                        output,
                        index * 2 + 1,
                        "/stream/65536",
                        false
                    );
                }
                output.flush();

                int streamsAtWindow = 0;
                while (streamsAtWindow != streamCount) {
                    Frame frame = readFrame(input);
                    if (frame.type != Http2Frames.DATA) {
                        continue;
                    }
                    int index = (frame.streamId - 1) >>> 1;
                    assertTrue(index >= 0 && index < streamCount,
                        "unexpected stream " + frame.streamId);
                    int before = received[index];
                    received[index] += frame.payload.length;
                    if (before < 65_535 && received[index] == 65_535) {
                        streamsAtWindow++;
                    }
                }

                // Stream 1 is first in the drain FIFO. Cancelling it must
                // hand an already-idle writer to the next response producer.
                output.write(rstStream(1, Http2Frames.CANCEL));
                for (int index = 1; index < streamCount; index++) {
                    output.write(windowUpdate(index * 2 + 1, 1));
                }
                output.flush();

                int completed = 0;
                while (completed != streamCount - 1) {
                    Frame frame = readFrame(input);
                    if (frame.type != Http2Frames.DATA || frame.streamId == 1) {
                        continue;
                    }
                    int index = (frame.streamId - 1) >>> 1;
                    received[index] += frame.payload.length;
                    if ((frame.flags & Http2Frames.FLAG_END_STREAM) != 0) {
                        completed++;
                    }
                }
                for (int index = 1; index < streamCount; index++) {
                    assertEquals(65_536, received[index]);
                }

                sendGet(output, 17, "/users/17");
                assertTrue(new String(
                    readResponse(input, 17, Set.of()),
                    StandardCharsets.UTF_8
                ).contains("ID: 17"));
            }
        });
    }

    @Test
    void cancellingStreamingUploadDoesNotPoisonConnection() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                byte[] chunk = new byte[16 * 1024];

                sendPostHeaders(
                    output, 1, "/stream/upload", chunk.length * 3);
                output.write(frame(Http2Frames.DATA, 0, 1, chunk));
                output.write(rstStream(1, Http2Frames.CANCEL));
                sendGet(output, 3, "/users/9", false);
                output.flush();

                byte[] response = readResponse(input, 3, Set.of());
                assertTrue(
                    new String(response, StandardCharsets.UTF_8)
                        .contains("ID: 9"));
            }
            awaitStreamingReservations();
        });
    }

    @Test
    void cancellingIsolatedStreamingUploadReleasesActualHandler() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            int activeBefore = IsolatedRouteStats.snapshot().active();
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                byte[] chunk = new byte[16 * 1024];

                sendPostHeaders(
                    output, 1, "/stream/upload-heavy", chunk.length * 3);
                output.write(frame(Http2Frames.DATA, 0, 1, chunk));
                output.flush();
                awaitIsolatedTasks(activeBefore + 1);

                output.write(rstStream(1, Http2Frames.CANCEL));
                sendGet(output, 3, "/users/19", false);
                output.flush();

                byte[] response = readResponse(input, 3, Set.of());
                assertTrue(new String(response, StandardCharsets.UTF_8)
                    .contains("ID: 19"));
            }
            awaitStreamingReservations();
            awaitIsolatedTasks(activeBefore);
        });
    }

    @Test
    void cancellingUnknownLengthResponseDoesNotPoisonConnection() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect(0)) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                sendGet(output, 1, "/stream-unknown/65536");

                Frame headers = readFrame(input);
                assertEquals(Http2Frames.HEADERS, headers.type);
                assertEquals(1, headers.streamId);

                output.write(rstStream(1, Http2Frames.CANCEL));
                sendGet(output, 3, "/users/9", false);
                output.write(windowUpdate(3, 1024));
                output.flush();

                byte[] response = readResponse(input, 3, Set.of());
                assertTrue(new String(response, StandardCharsets.UTF_8)
                    .contains("ID: 9"));
            }
        });
    }

    @Test
    void boundsConcurrentStreamingRequestBuffers() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                for (int streamId = 1; streamId <= 33; streamId += 2) {
                    sendPostHeaders(
                        output, streamId, "/stream/upload", 1);
                }
                output.flush();

                Frame refused;
                do {
                    refused = readFrame(input);
                } while (refused.type != Http2Frames.RST_STREAM);
                assertEquals(33, refused.streamId);

                for (int streamId = 1; streamId < 33; streamId += 2) {
                    output.write(rstStream(streamId, Http2Frames.CANCEL));
                }
                output.flush();
            }
            awaitStreamingReservations();
        });
    }

    @Test
    void batchesRequestBodyWindowUpdates() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                byte[] chunk = new byte[Http2Frames.DEFAULT_MAX_FRAME_SIZE];

                sendPostHeaders(output, 1, "/missing", chunk.length * 2 + 1);
                output.write(frame(Http2Frames.DATA, 0, 1, chunk));
                output.write(frame(Http2Frames.DATA, 0, 1, chunk));
                output.flush();

                Frame connectionUpdate = readFrame(input);
                Frame streamUpdate = readFrame(input);
                assertEquals(Http2Frames.WINDOW_UPDATE, connectionUpdate.type);
                assertEquals(0, connectionUpdate.streamId);
                assertEquals(chunk.length * 2, windowIncrement(connectionUpdate));
                assertEquals(Http2Frames.WINDOW_UPDATE, streamUpdate.type);
                assertEquals(1, streamUpdate.streamId);
                assertEquals(chunk.length * 2, windowIncrement(streamUpdate));

                output.write(frame(
                    Http2Frames.DATA,
                    Http2Frames.FLAG_END_STREAM,
                    1,
                    new byte[1]
                ));
                output.flush();

                Frame headers = readFrame(input);
                Frame data = readFrame(input);
                assertEquals(Http2Frames.HEADERS, headers.type);
                assertEquals(Http2Frames.DATA, data.type);
                assertEquals(1, headers.streamId);
                assertEquals(1, data.streamId);
            }
        });
    }

    @Test
    void acceptsHeaderBlocksSplitAcrossContinuationFrames() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                byte[] block = getBlock("/users/9");

                output.write(frame(
                    Http2Frames.HEADERS, Http2Frames.FLAG_END_STREAM, 1,
                    java.util.Arrays.copyOfRange(block, 0, 3)));
                output.write(frame(
                    Http2Frames.CONTINUATION, Http2Frames.FLAG_END_HEADERS, 1,
                    java.util.Arrays.copyOfRange(block, 3, block.length)));
                output.flush();

                Frame headers = readFrame(input);
                Frame data = readFrame(input);
                assertEquals(1, headers.streamId);
                assertEquals(1, data.streamId);
                assertTrue(new String(data.payload, StandardCharsets.UTF_8)
                    .contains("ID: 9"));
            }
        });
    }

    @Test
    void largeResponseWaitsForConnectionAndStreamWindowUpdates() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                sendGet(output, 1, "/some/response/large");

                Frame headers = readFrame(input);
                assertEquals(Http2Frames.HEADERS, headers.type);

                int bodyBytes = 0;
                int dataFrames = 0;
                boolean ended = false;
                while (bodyBytes < 65_535) {
                    Frame data = readFrame(input);
                    assertEquals(Http2Frames.DATA, data.type);
                    dataFrames++;
                    for (byte value : data.payload) {
                        assertEquals((byte) 'A', value);
                    }
                    bodyBytes += data.payload.length;
                    ended |= (data.flags & Http2Frames.FLAG_END_STREAM) != 0;
                }
                assertEquals(65_535, bodyBytes);
                assertEquals(4, dataFrames);
                assertTrue(!ended, "server exceeded the initial peer flow-control window");

                output.write(windowUpdate(0, 1));
                output.write(windowUpdate(1, 1));
                output.flush();

                Frame finalData = readFrame(input);
                assertEquals(Http2Frames.DATA, finalData.type);
                assertEquals(1, finalData.payload.length);
                assertEquals((byte) 'A', finalData.payload[0]);
                assertTrue((finalData.flags & Http2Frames.FLAG_END_STREAM) != 0);
            }
        });
    }

    @Test
    void zeroWindowStreamsDoNotBlockProgressAndRecoverAfterCancellation() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (Socket socket = connect(0)) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                Set<Integer> stalled = new HashSet<>();
                int nextStreamId = 1;

                for (int cycle = 0; cycle < 2; cycle++) {
                    for (int i = 0; i < 96; i++) {
                        int streamId = nextStreamId;
                        nextStreamId += 2;
                        stalled.add(streamId);
                        sendGet(output, streamId, "/some/response/large", false);
                    }

                    int fastStreamId = nextStreamId;
                    nextStreamId += 2;
                    sendGet(output, fastStreamId, "/users/423", false);
                    output.write(windowUpdate(fastStreamId, 65_535));
                    output.flush();

                    byte[] fastBody = readResponse(input, fastStreamId, stalled);
                    assertTrue(
                        new String(fastBody, StandardCharsets.UTF_8)
                            .contains("ID: 423"),
                        "fast stream returned the wrong response"
                    );
                    output.write(windowUpdate(0, fastBody.length));

                    for (int streamId : stalled) {
                        output.write(rstStream(streamId, Http2Frames.CANCEL));
                    }
                    output.flush();
                    stalled.clear();

                    int recoveryStreamId = nextStreamId;
                    nextStreamId += 2;
                    sendGet(output, recoveryStreamId, "/users/7", false);
                    output.write(windowUpdate(recoveryStreamId, 65_535));
                    output.flush();

                    byte[] recoveryBody = readResponse(
                        input, recoveryStreamId, Set.of());
                    assertTrue(
                        new String(recoveryBody, StandardCharsets.UTF_8)
                            .contains("ID: 7"),
                        "connection did not recover after cancelling stalled streams"
                    );
                    output.write(windowUpdate(0, recoveryBody.length));
                    output.flush();
                }

                Thread.sleep(100);
                assertTrue(
                    server.exchangeWorkerCount() <= 64,
                    "flow-control burst retained "
                        + server.exchangeWorkerCount()
                        + " idle exchange workers"
                );
            }
        });
    }

    private static Socket connect() throws Exception {
        return connect(-1);
    }

    private static Socket connect(int initialWindowSize) throws Exception {
        Socket socket = new Socket("127.0.0.1", PORT);
        socket.setSoTimeout(4_000);
        OutputStream output = socket.getOutputStream();
        output.write(Http2Frames.CLIENT_PREFACE);
        if (initialWindowSize < 0) {
            output.write(frame(Http2Frames.SETTINGS, 0, 0, new byte[0]));
        } else {
            output.write(setting(
                Http2Frames.SETTINGS_INITIAL_WINDOW_SIZE,
                initialWindowSize
            ));
        }
        output.flush();

        Frame settings = readFrame(socket.getInputStream());
        assertEquals(Http2Frames.SETTINGS, settings.type);
        Frame ack = readFrame(socket.getInputStream());
        assertEquals(Http2Frames.SETTINGS, ack.type);
        assertEquals(Http2Frames.FLAG_ACK, ack.flags);
        return socket;
    }

    private static void sendGet(OutputStream output, int streamId, String path) throws Exception {
        sendGet(output, streamId, path, true);
    }

    private static void sendGet(OutputStream output, int streamId, String path,
                                boolean flush) throws Exception {
        output.write(frame(
            Http2Frames.HEADERS,
            Http2Frames.FLAG_END_HEADERS | Http2Frames.FLAG_END_STREAM,
            streamId,
            getBlock(path)
        ));
        if (flush) {
            output.flush();
        }
    }

    private static byte[] getBlock(String path) throws Exception {
        byte[] pathBytes = path.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.write(0x82); // :method: GET
        block.write(0x86); // :scheme: http
        block.write(0x04); // literal without indexing, indexed name :path
        block.write(pathBytes.length);
        block.write(pathBytes);
        return block.toByteArray();
    }

    private static void sendPostHeaders(OutputStream output, int streamId,
                                        String path, int contentLength) throws Exception {
        byte[] pathBytes = path.getBytes(StandardCharsets.US_ASCII);
        byte[] type = "application/json".getBytes(StandardCharsets.US_ASCII);
        byte[] length = Integer.toString(contentLength).getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.write(0x83); // :method: POST
        block.write(0x86); // :scheme: http
        block.write(0x04); // :path
        block.write(pathBytes.length);
        block.write(pathBytes);
        block.write(0x0f); // content-type static name index 31
        block.write(0x10);
        block.write(type.length);
        block.write(type);
        block.write(0x0f); // content-length static name index 28
        block.write(0x0d);
        block.write(length.length);
        block.write(length);
        output.write(frame(
            Http2Frames.HEADERS,
            Http2Frames.FLAG_END_HEADERS,
            streamId,
            block.toByteArray()
        ));
        output.flush();
    }

    private static byte[] windowUpdate(int streamId, int increment) {
        byte[] frame = new byte[Http2Frames.HEADER_SIZE + 4];
        MemorySegment segment = MemorySegment.ofArray(frame);
        Http2Frames.writeWindowUpdate(segment, 0, streamId, increment);
        return frame;
    }

    private static byte[] rstStream(int streamId, int error) {
        byte[] frame = new byte[Http2Frames.HEADER_SIZE + 4];
        Http2Frames.writeRstStream(
            MemorySegment.ofArray(frame), 0, streamId, error);
        return frame;
    }

    private static byte[] setting(int identifier, int value) {
        byte[] frame = new byte[Http2Frames.HEADER_SIZE + 6];
        Http2Frames.writeSetting(
            MemorySegment.ofArray(frame), 0, identifier, value);
        return frame;
    }

    private static byte[] readResponse(InputStream input, int streamId,
                                       Set<Integer> stalledStreams) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        boolean sawHeaders = false;
        while (true) {
            Frame frame = readFrame(input);
            if (frame.type == Http2Frames.DATA
                && stalledStreams.contains(frame.streamId)) {
                throw new AssertionError(
                    "server sent DATA on zero-window stream " + frame.streamId);
            }
            if (frame.streamId != streamId) {
                continue;
            }
            if (frame.type == Http2Frames.RST_STREAM) {
                throw new AssertionError(
                    "server reset probe stream " + streamId);
            }
            if (frame.type == Http2Frames.HEADERS) {
                sawHeaders = true;
            } else if (frame.type == Http2Frames.DATA) {
                body.write(frame.payload);
            }
            if ((frame.flags & Http2Frames.FLAG_END_STREAM) != 0) {
                assertTrue(sawHeaders, "response DATA arrived without HEADERS");
                return body.toByteArray();
            }
        }
    }

    private static int windowIncrement(Frame frame) {
        return MemorySegment.ofArray(frame.payload).get(
            java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED
                .withOrder(java.nio.ByteOrder.BIG_ENDIAN),
            0
        ) & Http2Frames.MAX_STREAM_ID;
    }

    private static void completeStreamingUpload(
        InputStream input,
        OutputStream output,
        int streamId,
        byte[] fullChunk,
        int expectedBytes
    ) throws Exception {
        output.write(frame(Http2Frames.DATA, 0, streamId, fullChunk));
        output.write(frame(Http2Frames.DATA, 0, streamId, new byte[1]));
        output.flush();

        int streamCredit = 0;
        while (streamCredit < 32_768) {
            Frame frame = readFrame(input);
            if (frame.type == Http2Frames.WINDOW_UPDATE) {
                if (frame.streamId == streamId) {
                    streamCredit += windowIncrement(frame);
                }
                continue;
            }
            assertTrue(frame.streamId != streamId,
                "upload responded before END_STREAM");
        }
        assertEquals(32_768, streamCredit);

        output.write(frame(
            Http2Frames.DATA,
            Http2Frames.FLAG_END_STREAM,
            streamId,
            new byte[1]
        ));
        output.flush();
        assertEquals(
            "Received " + expectedBytes + " bytes",
            new String(
                readResponse(input, streamId, Set.of()),
                StandardCharsets.UTF_8));
    }

    private static void awaitStreamingReservations() throws Exception {
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (Http2StreamingAdmission.reservedBytes() != 0
            && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, Http2StreamingAdmission.reservedBytes());
    }

    private static void awaitIsolatedTasks(int expected) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (IsolatedRouteStats.snapshot().active() != expected
            && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertEquals(expected, IsolatedRouteStats.snapshot().active());
    }

    private static void assertResponseHeaders(byte[] block, String status, String contentType) {
        byte[] decoded = new byte[1024];
        HpackFields fields = new HpackFields(8);
        int result = new HpackDecoder(0, 4096).decode(
            MemorySegment.ofArray(block), 0, block.length,
            MemorySegment.ofArray(decoded), fields);
        assertTrue(result > 0);
        assertEquals(status,
            text(decoded, fields.valueOffset(0), fields.valueLength(0)));
        assertEquals(contentType,
            text(decoded, fields.valueOffset(1), fields.valueLength(1)));
    }

    private static void assertUnknownResponseHeaders(byte[] block) {
        byte[] decoded = new byte[1024];
        HpackFields fields = new HpackFields(8);
        int result = new HpackDecoder(0, 4096).decode(
            MemorySegment.ofArray(block), 0, block.length,
            MemorySegment.ofArray(decoded), fields);
        assertTrue(result > 0);
        assertEquals(2, fields.count());
        assertEquals(":status",
            text(decoded, fields.nameOffset(0), fields.nameLength(0)));
        assertEquals("200",
            text(decoded, fields.valueOffset(0), fields.valueLength(0)));
        assertEquals("content-type",
            text(decoded, fields.nameOffset(1), fields.nameLength(1)));
        assertEquals("application/octet-stream",
            text(decoded, fields.valueOffset(1), fields.valueLength(1)));
    }

    private static String text(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

}
