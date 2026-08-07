// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.core.Http2TestSupport.Frame;
import dev.cardigan.http2.Http2Frames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
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
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class Http2FlowControlAdmissionTest {
    private static final int PORT = 8105;
    private static final String LIMIT_PROPERTY =
        "cardigan.http2.max.parked.senders.per.loop";

    private CardiganServer server;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty(LIMIT_PROPERTY, "8");
        server = TestServers.example(PORT, 1);
        server.start();
        Thread.sleep(100);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        System.clearProperty(LIMIT_PROPERTY);
    }

    @Test
    void rejectsOnlyExcessWaitersAndPreservesFastStreamCapacity() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (Socket socket = connect()) {
                DataInputStream input = new DataInputStream(
                    socket.getInputStream());
                OutputStream output = socket.getOutputStream();
                Set<Integer> stalled = new HashSet<>();

                int nextStreamId = 1;
                for (int i = 0; i < 16; i++) {
                    stalled.add(nextStreamId);
                    request(output, nextStreamId, "/some/response/large");
                    nextStreamId += 2;
                }
                int fastStreamId = nextStreamId;
                nextStreamId += 2;
                request(output, fastStreamId, "/users/423");
                output.write(windowUpdate(fastStreamId, 65_535));
                output.flush();

                ResponseRead fast = readResponse(
                    input, fastStreamId, stalled);
                assertEquals(8, fast.overloadResets());
                assertTrue(
                    new String(fast.body(), StandardCharsets.UTF_8)
                        .contains("ID: 423")
                );
                assertEquals(8, server.http2ParkedSenderCount());

                for (int streamId : stalled) {
                    output.write(rstStream(streamId, Http2Frames.CANCEL));
                }
                int recoveryStreamId = nextStreamId;
                request(output, recoveryStreamId, "/users/7");
                output.write(windowUpdate(recoveryStreamId, 65_535));
                output.flush();

                ResponseRead recovery = readResponse(
                    input, recoveryStreamId, Set.of());
                assertTrue(
                    new String(recovery.body(), StandardCharsets.UTF_8)
                        .contains("ID: 7")
                );

                Thread.sleep(100);
                assertEquals(0, server.http2ParkedSenderCount());
                assertTrue(server.exchangeWorkerCount() <= 64);
            }
        });
    }

    private static Socket connect() throws Exception {
        Socket socket = new Socket("127.0.0.1", PORT);
        socket.setSoTimeout(5_000);
        OutputStream output = socket.getOutputStream();
        output.write(Http2Frames.CLIENT_PREFACE);
        output.write(setting(
            Http2Frames.SETTINGS_INITIAL_WINDOW_SIZE, 0));
        output.flush();

        Frame serverSettings = readFrame(
            new DataInputStream(socket.getInputStream()));
        assertEquals(Http2Frames.SETTINGS, serverSettings.type());
        Frame clientSettingsAck = readFrame(
            new DataInputStream(socket.getInputStream()));
        assertEquals(Http2Frames.SETTINGS, clientSettingsAck.type());
        assertEquals(Http2Frames.FLAG_ACK, clientSettingsAck.flags());
        return socket;
    }

    private static void request(OutputStream output, int streamId, String path)
            throws Exception {
        byte[] pathBytes = path.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.write(0x82); // :method: GET
        block.write(0x86); // :scheme: http
        block.write(0x04); // literal without indexing, indexed :path name
        block.write(pathBytes.length);
        block.write(pathBytes);
        output.write(frame(
            Http2Frames.HEADERS,
            Http2Frames.FLAG_END_HEADERS | Http2Frames.FLAG_END_STREAM,
            streamId,
            block.toByteArray()
        ));
    }

    private static ResponseRead readResponse(
            DataInputStream input, int streamId, Set<Integer> stalled)
            throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        boolean sawHeaders = false;
        int overloadResets = 0;
        while (true) {
            Frame frame = readFrame(input);
            if (frame.type() == Http2Frames.DATA
                && stalled.contains(frame.streamId())) {
                throw new AssertionError(
                    "DATA escaped zero-window stream " + frame.streamId());
            }
            if (frame.type() == Http2Frames.RST_STREAM
                && stalled.contains(frame.streamId())) {
                assertEquals(
                    Http2Frames.ENHANCE_YOUR_CALM,
                    intValue(frame.payload())
                );
                overloadResets++;
            }
            if (frame.streamId() != streamId) {
                continue;
            }
            if (frame.type() == Http2Frames.RST_STREAM) {
                throw new AssertionError("fast stream was reset");
            }
            if (frame.type() == Http2Frames.HEADERS) {
                sawHeaders = true;
            } else if (frame.type() == Http2Frames.DATA) {
                body.write(frame.payload());
            }
            if ((frame.flags() & Http2Frames.FLAG_END_STREAM) != 0) {
                assertTrue(sawHeaders);
                return new ResponseRead(body.toByteArray(), overloadResets);
            }
        }
    }

    private static byte[] setting(int identifier, int value) {
        byte[] bytes = new byte[Http2Frames.HEADER_SIZE + 6];
        Http2Frames.writeSetting(
            MemorySegment.ofArray(bytes), 0, identifier, value);
        return bytes;
    }

    private static byte[] windowUpdate(int streamId, int increment) {
        byte[] bytes = new byte[Http2Frames.HEADER_SIZE + 4];
        Http2Frames.writeWindowUpdate(
            MemorySegment.ofArray(bytes), 0, streamId, increment);
        return bytes;
    }

    private static byte[] rstStream(int streamId, int error) {
        byte[] bytes = new byte[Http2Frames.HEADER_SIZE + 4];
        Http2Frames.writeRstStream(
            MemorySegment.ofArray(bytes), 0, streamId, error);
        return bytes;
    }

    private static int intValue(byte[] bytes) {
        return ((bytes[0] & 0xff) << 24)
            | ((bytes[1] & 0xff) << 16)
            | ((bytes[2] & 0xff) << 8)
            | (bytes[3] & 0xff);
    }

    private record ResponseRead(byte[] body, int overloadResets) {
    }
}
