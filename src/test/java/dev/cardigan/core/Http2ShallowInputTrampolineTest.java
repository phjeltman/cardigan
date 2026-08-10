// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.core.Http2TestSupport.Frame;
import dev.cardigan.http2.Http2Frames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import static dev.cardigan.core.Http2TestSupport.frame;
import static dev.cardigan.core.Http2TestSupport.readFrame;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@Tag("integration")
class Http2ShallowInputTrampolineTest {
    private static final int PORT = 8178;

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
    void steadyReceiveParkOmitsTheFrameProcessingMethod() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                sendGet(socket.getOutputStream(), 1, "/users/423");
                Frame headers = readFrame(socket.getInputStream());
                Frame data = readFrame(socket.getInputStream());
                assertEquals(Http2Frames.HEADERS, headers.type());
                assertEquals(1, headers.streamId());
                assertEquals(Http2Frames.DATA, data.type());
                assertEquals(1, data.streamId());

                StackTraceElement[] parked = awaitReceivePark(server);
                assertTrue(hasFrame(
                        parked, Http2Connection.class.getName(), "run"),
                    "the connection owner was not parked in the H2 driver");
                assertFalse(hasFrame(
                        parked, Http2Connection.class.getName(),
                        "drainAvailableFrames"),
                    "the H2 frame-processing method remained captured at receive park");
            }
        });
    }

    @Test
    void splitPayloadFallbackReturnsToTheShallowDriver() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                byte[] opaque = {8, 7, 6, 5, 4, 3, 2, 1};
                byte[] ping = frame(Http2Frames.PING, 0, 0, opaque);
                OutputStream output = socket.getOutputStream();

                int firstLength = Http2Frames.HEADER_SIZE + 3;
                output.write(ping, 0, firstLength);
                output.flush();

                StackTraceElement[] fragmented = awaitOwnerFrame(
                    server, Http2Connection.class.getName(), "readFully");
                assertTrue(hasFrame(
                        fragmented, Http2Connection.class.getName(),
                        "drainAvailableFrames"),
                    "the split-payload fallback did not retain its parser frame");

                output.write(ping, firstLength, ping.length - firstLength);
                output.flush();

                Frame ack = readFrame(socket.getInputStream());
                assertEquals(Http2Frames.PING, ack.type());
                assertEquals(Http2Frames.FLAG_ACK, ack.flags());
                assertArrayEquals(opaque, ack.payload());

                StackTraceElement[] parked = awaitReceivePark(server);
                assertFalse(hasFrame(
                        parked, Http2Connection.class.getName(),
                        "drainAvailableFrames"),
                    "the fragmented-payload fallback did not unwind");
                assertFalse(hasFrame(
                        parked, Http2Connection.class.getName(), "readFully"),
                    "the fragmented-payload read remained captured at idle");
            }
        });
    }

    @Test
    void continuationStateSurvivesAFrameBoundaryUnwind() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = connect()) {
                byte[] block = getBlock("/users/423");
                int split = 4;
                OutputStream output = socket.getOutputStream();
                output.write(frame(
                    Http2Frames.HEADERS,
                    Http2Frames.FLAG_END_STREAM,
                    1,
                    java.util.Arrays.copyOfRange(block, 0, split)
                ));
                output.flush();

                StackTraceElement[] parked = awaitContinuationPark(server, 1);
                assertFalse(hasFrame(
                        parked, Http2Connection.class.getName(),
                        "drainAvailableFrames"),
                    "the incomplete header block retained the frame parser");

                output.write(frame(
                    Http2Frames.CONTINUATION,
                    Http2Frames.FLAG_END_HEADERS,
                    1,
                    java.util.Arrays.copyOfRange(block, split, block.length)
                ));
                output.flush();

                Frame headers = readFrame(socket.getInputStream());
                Frame data = readFrame(socket.getInputStream());
                assertEquals(Http2Frames.HEADERS, headers.type());
                assertEquals(1, headers.streamId());
                assertEquals(Http2Frames.DATA, data.type());
                assertEquals(1, data.streamId());
            }
        });
    }

    private static Socket connect() throws Exception {
        Socket socket = new Socket("127.0.0.1", PORT);
        socket.setSoTimeout(3_000);
        OutputStream output = socket.getOutputStream();
        output.write(Http2Frames.CLIENT_PREFACE);
        output.write(frame(
            Http2Frames.SETTINGS, 0, 0, new byte[0]));
        output.flush();

        Frame settings = readFrame(socket.getInputStream());
        assertEquals(Http2Frames.SETTINGS, settings.type());
        Frame ack = readFrame(socket.getInputStream());
        assertEquals(Http2Frames.SETTINGS, ack.type());
        assertEquals(Http2Frames.FLAG_ACK, ack.flags());
        return socket;
    }

    private static void sendGet(
            OutputStream output, int streamId, String path) throws Exception {
        output.write(frame(
            Http2Frames.HEADERS,
            Http2Frames.FLAG_END_HEADERS | Http2Frames.FLAG_END_STREAM,
            streamId,
            getBlock(path)
        ));
        output.flush();
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

    private static StackTraceElement[] awaitReceivePark(
            CardiganServer server) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        StackTraceElement[] last = new StackTraceElement[0];
        while (System.nanoTime() < deadline) {
            Thread owner = connectionOwner(server);
            if (owner != null) {
                last = owner.getStackTrace();
                if (hasFrame(
                        last, MultishotReceiver.class.getName(), "receive")) {
                    return last;
                }
            }
            Thread.sleep(2);
        }
        throw new AssertionError(
            "connection owner did not park in MultishotReceiver.receive: "
                + java.util.Arrays.toString(last));
    }

    private static StackTraceElement[] awaitOwnerFrame(
            CardiganServer server, String className, String methodName)
            throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        StackTraceElement[] last = new StackTraceElement[0];
        while (System.nanoTime() < deadline) {
            Thread owner = connectionOwner(server);
            if (owner != null) {
                last = owner.getStackTrace();
                if (hasFrame(last, className, methodName)) {
                    return last;
                }
            }
            Thread.sleep(2);
        }
        throw new AssertionError(
            "connection owner did not enter " + className + "."
                + methodName + ": " + java.util.Arrays.toString(last));
    }

    private static StackTraceElement[] awaitContinuationPark(
            CardiganServer server, int streamId) throws Exception {
        Field connectionField =
            Http2Connection.class.getDeclaredField("continuationStreamId");
        connectionField.setAccessible(true);
        long deadline = System.nanoTime() + 2_000_000_000L;
        StackTraceElement[] last = new StackTraceElement[0];
        int lastContinuation = 0;
        while (System.nanoTime() < deadline) {
            Object control = connectionControl(server);
            if (control != null) {
                Field http2Field = control.getClass().getDeclaredField("http2");
                http2Field.setAccessible(true);
                Object connection = http2Field.get(control);
                Field ownerField = control.getClass().getDeclaredField("owner");
                ownerField.setAccessible(true);
                Thread owner = (Thread) ownerField.get(control);
                if (connection != null) {
                    lastContinuation = connectionField.getInt(connection);
                }
                if (owner != null) {
                    last = owner.getStackTrace();
                }
                if (lastContinuation == streamId
                        && hasFrame(
                            last, MultishotReceiver.class.getName(),
                            "receive")) {
                    return last;
                }
            }
            Thread.sleep(2);
        }
        throw new AssertionError(
            "connection did not park after retaining continuation stream "
                + streamId + " (last=" + lastContinuation + "): "
                + java.util.Arrays.toString(last));
    }

    private static Thread connectionOwner(CardiganServer server)
            throws Exception {
        Object connection = connectionControl(server);
        if (connection == null) {
            return null;
        }
        Field ownerField = connection.getClass().getDeclaredField("owner");
        ownerField.setAccessible(true);
        return (Thread) ownerField.get(connection);
    }

    private static Object connectionControl(CardiganServer server)
            throws Exception {
        Field connectionsField =
            CardiganServer.class.getDeclaredField("activeConnections");
        connectionsField.setAccessible(true);
        Set<?> connections = (Set<?>) connectionsField.get(server);
        return connections.stream().findFirst().orElse(null);
    }

    private static boolean hasFrame(
            StackTraceElement[] stack, String className, String methodName) {
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().equals(className)
                    && frame.getMethodName().equals(methodName)) {
                return true;
            }
        }
        return false;
    }
}
