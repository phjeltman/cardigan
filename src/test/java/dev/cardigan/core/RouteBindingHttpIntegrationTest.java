// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.core.Http2TestSupport.Frame;
import dev.cardigan.http.Get;
import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.QueryParam;
import dev.cardigan.http.Response;
import dev.cardigan.http2.Http2Frames;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static dev.cardigan.core.Http2TestSupport.frame;
import static dev.cardigan.core.Http2TestSupport.readFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
final class RouteBindingHttpIntegrationTest {
    private CardiganServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        port = availablePort();
        server = CardiganServer.builder()
            .port(port)
            .eventLoops(1)
            .routes(new BindingController())
            .build();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void pipelinedHttp1RequestsInvokeGenericSignatures() {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            String requests = request("/bind/boxed/427", true)
                + request("/bind/name/cardigan", true)
                + request("/bind/pair/41/386", true)
                + request("/bind/query/cardigan?limit=19", true)
                + request("/bind/mixed/cardigan", false);
            String responses;
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5_000);
                socket.getOutputStream().write(
                    requests.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                responses = new String(
                    socket.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            }

            assertEquals(
                5,
                occurrences(responses, "HTTP/1.1 200 OK"),
                responses);
            assertTrue(responses.contains("name=cardigan"));
            assertTrue(responses.contains("boxed=427"));
            assertTrue(responses.contains("pair=427"));
            assertTrue(responses.contains("query=cardigan:19"));
            assertTrue(responses.contains("mixed=cardigan:wire"));
        });
    }

    @Test
    void multiplexedHttp2RequestsInvokeGenericSignatures() {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            try (Socket socket = connectHttp2()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                String[] paths = {
                    "/bind/boxed/427",
                    "/bind/name/cardigan",
                    "/bind/pair/41/386",
                    "/bind/mixed/cardigan"
                };
                for (int index = 0; index < paths.length; index++) {
                    output.write(frame(
                        Http2Frames.HEADERS,
                        Http2Frames.FLAG_END_HEADERS
                            | Http2Frames.FLAG_END_STREAM,
                        index * 2 + 1,
                        requestBlock(paths[index])));
                }
                output.flush();

                Map<Integer, ByteArrayOutputStream> bodies = new HashMap<>();
                int ended = 0;
                while (ended != paths.length) {
                    Frame response = readFrame(input);
                    if (response.type == Http2Frames.DATA) {
                        bodies.computeIfAbsent(
                            response.streamId,
                            ignored -> new ByteArrayOutputStream()
                        ).write(response.payload);
                    }
                    if ((response.flags & Http2Frames.FLAG_END_STREAM) != 0) {
                        ended++;
                    }
                }

                assertEquals("boxed=427", body(bodies, 1));
                assertEquals("name=cardigan", body(bodies, 3));
                assertEquals("pair=427", body(bodies, 5));
                assertEquals("mixed=cardigan:wire", body(bodies, 7));
            }
        });
    }

    private static String request(String path, boolean keepAlive) {
        return "GET " + path + " HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "X-Test: wire\r\n"
            + "Connection: "
            + (keepAlive ? "keep-alive" : "close")
            + "\r\n\r\n";
    }

    private Socket connectHttp2() throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(5_000);
        OutputStream output = socket.getOutputStream();
        output.write(Http2Frames.CLIENT_PREFACE);
        output.write(frame(Http2Frames.SETTINGS, 0, 0, new byte[0]));
        output.flush();

        assertEquals(Http2Frames.SETTINGS, readFrame(
            socket.getInputStream()).type);
        Frame acknowledgement = readFrame(socket.getInputStream());
        assertEquals(Http2Frames.SETTINGS, acknowledgement.type);
        assertEquals(Http2Frames.FLAG_ACK, acknowledgement.flags);
        return socket;
    }

    private static byte[] requestBlock(String path) {
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.write(0x82); // :method: GET
        block.write(0x86); // :scheme: http
        writeIndexedNameLiteral(block, 4, path); // :path
        writeLiteral(block, "x-test", "wire");
        return block.toByteArray();
    }

    private static void writeLiteral(
            ByteArrayOutputStream block, String name, String value) {
        block.write(0); // literal without indexing, new name
        writeAscii(block, name);
        writeAscii(block, value);
    }

    private static void writeIndexedNameLiteral(
            ByteArrayOutputStream block, int nameIndex, String value) {
        block.write(nameIndex);
        writeAscii(block, value);
    }

    private static void writeAscii(
            ByteArrayOutputStream block, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        block.write(bytes.length);
        block.writeBytes(bytes);
    }

    private static String body(
            Map<Integer, ByteArrayOutputStream> bodies, int streamId) {
        return bodies.get(streamId).toString(StandardCharsets.UTF_8);
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    public static final class BindingController {
        @Get("/bind/name/{name}")
        public Response name(String name) {
            return Response.text("name=" + name);
        }

        @Get("/bind/boxed/{id}")
        public Response boxed(Long id) {
            return Response.text("boxed=" + id);
        }

        @Get("/bind/pair/{left}/{right}")
        public Response pair(long left, long right) {
            return Response.text("pair=" + (left + right));
        }

        @Get("/bind/query/{name}")
        public Response query(
                String name, @QueryParam("limit") Integer limit) {
            return Response.text("query=" + name + ':' + limit);
        }

        @Get("/bind/mixed/{name}")
        public Response mixed(String name, HttpRequest request) {
            return Response.text(
                "mixed=" + name + ':' + request.getHeader("x-test"));
        }
    }
}
