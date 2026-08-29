// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicReference;
import dev.cardigan.json.JsonWriter;

public class RouterTest {
    public record User(String name, int id, boolean active) {}

    public static class TestController {
        @Get("/users/{id}")
        public Response getUser(long id) {
            return Response.text("User details for ID: " + id);
        }

        @Post("/users")
        public Response createUser(dev.cardigan.simdjson.ondemand.Value userVal) {
            String name = userVal.get("name").getString();
            int id = (int) userVal.get("id").getLong();
            boolean active = userVal.get("active").getBoolean();
            return Response.json(new User(name, id, active));
        }

        @Get("/request")
        public Response inspectRequest(HttpRequest request) {
            return Response.text(request.getHeader("X-Test").toString());
        }
    }

    public static class StreamingController {
        @Post("/stream/{id}")
        public Response stream(long id, RequestBody body) {
            return Response.text(id + ":" + body.length());
        }
    }

    public static class TrieController {
        @Get("/catalog/known/detail")
        public Response known() {
            return Response.text("known");
        }

        @Post("/catalog/known/detail")
        public Response knownPost() {
            return Response.text("post");
        }

        @Get("/catalog/static/other")
        public Response staticBranch() {
            return Response.text("static");
        }

        @Get("/catalog/{name}/fallback")
        public Response parameterFallback() {
            return Response.text("fallback");
        }
    }

    public static class InvalidStreamingController {
        @Get("/streaming-get")
        public Response streamingGet(RequestBody body) {
            return Response.text("unused");
        }
    }

    public static final class BodyLengthDecoder
            implements LongBodyDecoder {
        @Override
        public long decode(
            MemorySegment source,
            long offset,
            long length
        ) {
            return length;
        }
    }

    public static class DecodedBodyController {
        @Post("/decoded")
        public Response decoded(
            @DecodedBody(BodyLengthDecoder.class) long length
        ) {
            return Response.text(Long.toString(length));
        }
    }

    public static class InvalidDecodedBodyController {
        @Get("/decoded")
        public Response decoded(
            @DecodedBody(BodyLengthDecoder.class) long length
        ) {
            return Response.text(Long.toString(length));
        }
    }

    public static class IsolatedStreamingController {
        static final AtomicReference<Thread> thread = new AtomicReference<>();

        @Post("/isolated-stream")
        @Isolated
        public Response stream(RequestBody body) {
            thread.set(Thread.currentThread());
            try (Arena arena = Arena.ofConfined(); body) {
                MemorySegment scratch = arena.allocate(16);
                int received = 0;
                int count;
                while ((count = body.read(scratch)) >= 0) {
                    received += count;
                }
                return Response.text("received:" + received);
            }
        }
    }

    @Test
    public void testRouterGet() {
        Router router = new Router();
        router.registerController(new TestController());

        try (Arena arena = Arena.ofConfined()) {
            String rawRequest = "GET /users/427 HTTP/1.1\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(rawRequest);
            HttpRequest request = new HttpRequest();

            boolean parsed = HttpRequestParser.parse(segment, (int) segment.byteSize() - 1, request);
            assertTrue(parsed, "Parser should succeed");

            Response response = router.dispatch("GET", request);
            assertEquals(200, response.statusCode(), "GET Status should be 200");
            assertEquals("User details for ID: 427", response.body(), "GET Response body mismatch");
        }
    }

    @Test
    public void testRouterPostJson() {
        Router router = new Router();
        router.registerController(new TestController());

        try (Arena arena = Arena.ofConfined()) {
            User user = new User("John Doe", 888, true);
            MemorySegment jsonBuf = arena.allocate(1024);
            int jsonLen = JsonWriter.writeRecord(jsonBuf, 0, user);
            byte[] jsonBytes = jsonBuf.asSlice(0, jsonLen).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            String jsonStr = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8);

            String rawRequest = "POST /users HTTP/1.1\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: " + jsonLen + "\r\n\r\n" +
                                jsonStr;

            MemorySegment segment = arena.allocateFrom(rawRequest);
            HttpRequest request = new HttpRequest();

            boolean parsed = HttpRequestParser.parse(segment, (int) segment.byteSize() - 1, request);
            assertTrue(parsed, "Parser should succeed");

            Response response = router.dispatch("POST", request);
            assertEquals(200, response.statusCode(), "POST Status should be 200");
            assertInstanceOf(User.class, response.body(), "POST Response body should be User record");

            User returnedUser = (User) response.body();
            assertEquals("John Doe", returnedUser.name(), "Name mismatch");
            assertEquals(888, returnedUser.id(), "ID mismatch");
            assertTrue(returnedUser.active(), "Active flag mismatch");
        }
    }

    @Test
    public void preparedInvocationMaterializesOnlyRequestAwareRoutes() {
        Router router = new Router();
        router.registerController(new TestController());

        try (Arena arena = Arena.ofConfined()) {
            HttpRequest ordinary = new HttpRequest();
            MemorySegment ordinarySegment = arena.allocateFrom(
                "GET /users/427 HTTP/1.1\r\n\r\n");
            assertTrue(HttpRequestParser.parse(
                ordinarySegment, (int) ordinarySegment.byteSize() - 1,
                ordinary));
            int[] materializations = {0};
            router.prepare(
                ordinary, new PreparedInvocation(),
                () -> materializations[0]++);
            assertEquals(0, materializations[0]);

            HttpRequest requestAware = new HttpRequest();
            MemorySegment requestSegment = arena.allocateFrom(
                "GET /request HTTP/1.1\r\nX-Test: present\r\n\r\n");
            assertTrue(HttpRequestParser.parse(
                requestSegment, (int) requestSegment.byteSize() - 1,
                requestAware));
            PreparedInvocation invocation = router.prepare(
                requestAware, new PreparedInvocation(),
                () -> materializations[0]++);
            assertEquals(1, materializations[0]);
            assertEquals("present", invocation.invoke().body());
        }
    }

    @Test
    void compiledRouteTreeMatchesStaticMethodsAndParameterFallbacks() {
        Router router = new Router();
        router.registerController(new TrieController());

        assertEquals("known", dispatch(router,
            "GET /catalog/known/detail HTTP/1.1\r\n\r\n").body());
        assertEquals("post", dispatch(router,
            "POST /catalog/known/detail HTTP/1.1\r\n"
                + "Content-Length: 0\r\n\r\n").body());
        assertEquals("fallback", dispatch(router,
            "GET /catalog/static/fallback HTTP/1.1\r\n\r\n").body());
        assertEquals(404, dispatch(router,
            "GET /catalog/absent/detail HTTP/1.1\r\n\r\n").statusCode());
    }

    private static Response dispatch(Router router, String encoded) {
        byte[] bytes = encoded.getBytes(
            java.nio.charset.StandardCharsets.US_ASCII);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(bytes.length);
            MemorySegment.copy(
                bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
            HttpRequest request = new HttpRequest();
            assertTrue(HttpRequestParser.parse(
                segment, bytes.length, request));
            return router.dispatch(request);
        }
    }

    @Test
    void cachesStreamingRouteAndRetainsPathArguments() {
        Router router = new Router();
        router.registerController(new StreamingController());

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocateFrom(
                "POST /stream/42 HTTP/1.1\r\nContent-Length: 0\r\n\r\n");
            HttpRequest request = new HttpRequest();
            assertTrue(HttpRequestParser.parse(
                segment, (int) segment.byteSize() - 1, request));
            request.setBodyStream(new RequestBody() {
                @Override
                public long length() {
                    return 0;
                }

                @Override
                public long remaining() {
                    return 0;
                }

                @Override
                public int read(MemorySegment destination) {
                    return -1;
                }

                @Override
                public void close() {
                }
            });

            assertTrue(router.acceptsStreamingBody(request));
            assertEquals(
                Router.BODY_STREAMING,
                router.streamingBodyMode(request));
            assertEquals("42:0", router.dispatch(request).body());
        }
    }

    @Test
    void rejectsStreamingBodiesOnGetRoutes() {
        Router router = new Router();
        assertThrows(
            IllegalArgumentException.class,
            () -> router.registerController(
                new InvalidStreamingController()));
    }

    @Test
    void decodesBufferedBodiesBeforeHandoverWithoutMaterializingRequest() {
        Router router = new Router();
        router.registerController(new DecodedBodyController());

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocateFrom(
                "POST /decoded HTTP/1.1\r\n"
                    + "Content-Length: 3\r\n\r\nabc");
            HttpRequest request = new HttpRequest();
            assertTrue(HttpRequestParser.parse(
                segment, (int) segment.byteSize() - 1, request));
            int[] materializations = {0};
            PreparedInvocation invocation = router.prepare(
                request,
                new PreparedInvocation(),
                () -> materializations[0]++
            );

            assertEquals(0, materializations[0]);
            assertEquals("3", invocation.invoke().body());
        }
    }

    @Test
    void rejectsDecodedBodiesOnSafeRoutes() {
        Router router = new Router();
        assertThrows(
            IllegalArgumentException.class,
            () -> router.registerController(
                new InvalidDecodedBodyController()));
    }

    @Test
    void preparesStreamingBodiesForAnIsolatedHandler() {
        Router router = new Router();
        router.registerController(new IsolatedStreamingController());
        IsolatedStreamingController.thread.set(null);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocateFrom(
                "POST /isolated-stream HTTP/1.1\r\n"
                    + "Content-Length: 3\r\n\r\nabc");
            HttpRequest request = new HttpRequest();
            assertTrue(HttpRequestParser.parse(
                segment, (int) segment.byteSize() - 1, request));
            request.setBodyStream(new RequestBody() {
                private boolean read;

                @Override
                public long length() {
                    return 3;
                }

                @Override
                public long remaining() {
                    return read ? 0 : 3;
                }

                @Override
                public int read(MemorySegment destination) {
                    if (read) {
                        return -1;
                    }
                    destination.set(ValueLayout.JAVA_BYTE, 0, (byte) 'a');
                    destination.set(ValueLayout.JAVA_BYTE, 1, (byte) 'b');
                    destination.set(ValueLayout.JAVA_BYTE, 2, (byte) 'c');
                    read = true;
                    return 3;
                }

                @Override
                public void close() {
                    read = true;
                }
            });

            assertTrue(router.acceptsStreamingBody(request));
            assertTrue(router.isIsolatedRoute(request));
            assertEquals(
                Router.BODY_STREAMING_ISOLATED,
                router.streamingBodyMode(request));
            Response response = router.prepare(request).invoke();
            assertEquals("received:3", response.body());
            Thread handlerThread = IsolatedStreamingController.thread.get();
            assertNotNull(handlerThread);
            assertTrue(handlerThread.isVirtual());
            assertTrue(handlerThread.getName().startsWith(
                "cardigan-isolated-vt-"));
        }
    }
}
