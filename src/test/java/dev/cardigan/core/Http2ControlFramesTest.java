// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.core.Http2TestSupport.Frame;
import dev.cardigan.http2.Http2Frames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.net.Socket;
import java.nio.ByteOrder;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static dev.cardigan.core.Http2TestSupport.frame;
import static dev.cardigan.core.Http2TestSupport.readFrame;

@Tag("integration")
class Http2ControlFramesTest {
    private static final int PORT = 8103;

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
    void fragmentedPriorKnowledgePrefaceNegotiatesSettingsAndPing() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(2_000);
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();

                output.write(Http2Frames.CLIENT_PREFACE, 0, 7);
                output.flush();
                Thread.sleep(10);
                output.write(Http2Frames.CLIENT_PREFACE, 7,
                             Http2Frames.CLIENT_PREFACE.length - 7);
                output.write(setting(Http2Frames.SETTINGS_MAX_FRAME_SIZE, 32_768));
                output.flush();

                Frame serverSettings = readFrame(input);
                assertEquals(Http2Frames.SETTINGS, serverSettings.type);
                assertEquals(0, serverSettings.flags);
                assertEquals(0, serverSettings.streamId);
                assertEquals(6, serverSettings.payload.length);

                Frame settingsAck = readFrame(input);
                assertEquals(Http2Frames.SETTINGS, settingsAck.type);
                assertEquals(Http2Frames.FLAG_ACK, settingsAck.flags);
                assertEquals(0, settingsAck.payload.length);

                byte[] opaque = {1, 2, 3, 4, 5, 6, 7, 8};
                output.write(frame(Http2Frames.PING, 0, 0, opaque));
                output.flush();

                Frame pingAck = readFrame(input);
                assertEquals(Http2Frames.PING, pingAck.type);
                assertEquals(Http2Frames.FLAG_ACK, pingAck.flags);
                assertEquals(0, pingAck.streamId);
                assertArrayEquals(opaque, pingAck.payload);

                output.write(frame(Http2Frames.GOAWAY, 0, 0, new byte[8]));
                output.flush();
            }
        });
    }

    @Test
    void invalidFirstFrameProducesGoAway() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("127.0.0.1", PORT)) {
                socket.setSoTimeout(2_000);
                OutputStream output = socket.getOutputStream();
                output.write(Http2Frames.CLIENT_PREFACE);
                output.write(frame(Http2Frames.PING, 0, 0, new byte[8]));
                output.flush();

                Frame serverSettings = readFrame(socket.getInputStream());
                assertEquals(Http2Frames.SETTINGS, serverSettings.type);

                Frame goAway = readFrame(socket.getInputStream());
                assertEquals(Http2Frames.GOAWAY, goAway.type);
                assertEquals(Http2Frames.PROTOCOL_ERROR,
                    MemorySegment.ofArray(goAway.payload).get(
                        java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED
                            .withOrder(ByteOrder.BIG_ENDIAN), 4));
            }
        });
    }

    private static byte[] setting(int identifier, int value) {
        byte[] bytes = new byte[15];
        Http2Frames.writeSetting(MemorySegment.ofArray(bytes), 0, identifier, value);
        return bytes;
    }

}
