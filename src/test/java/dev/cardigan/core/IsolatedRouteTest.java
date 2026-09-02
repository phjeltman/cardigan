// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.Get;
import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.HttpRequestParser;
import dev.cardigan.http.Isolated;
import dev.cardigan.http.Response;
import dev.cardigan.http.Router;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class IsolatedRouteTest {

    public static class IsolationTestController {
        public static final AtomicReference<Thread> heavyThreadRef = new AtomicReference<>();
        public static final AtomicReference<Thread> sleepyThreadRef = new AtomicReference<>();
        public static final AtomicReference<String> carrierRef = new AtomicReference<>();

        @Get("/heavy-test")
        @Isolated
        public Response heavyEndpoint() {
            heavyThreadRef.set(Thread.currentThread());
            carrierRef.set(Thread.currentThread().toString());
            return Response.text("Heavy done");
        }

        @Get("/sleepy-test")
        @Isolated
        public Response sleepyEndpoint() {
            sleepyThreadRef.set(Thread.currentThread());
            return Response.text("Sleepy done");
        }
    }

    @Test
    public void isolatedEndpointsUseFreshVirtualThreads() throws Throwable {
        Router router = new Router();
        router.registerController(new TestController());
        router.registerController(new IsolationTestController());
        IsolationTestController.heavyThreadRef.set(null);
        IsolationTestController.sleepyThreadRef.set(null);

        try (Arena arena = Arena.ofShared()) {
            // 1. Test /heavy endpoint
            String heavyReqStr = "GET /heavy-test HTTP/1.1\r\nHost: localhost\r\n\r\n";
            MemorySegment heavySeg = arena.allocateFrom(heavyReqStr);
            HttpRequest heavyReq = new HttpRequest();
            assertTrue(HttpRequestParser.parse(heavySeg, (int) heavySeg.byteSize(), heavyReq));

            Thread connectionThread = Thread.currentThread();
            Response heavyResp = router.dispatch(heavyReq);

            assertEquals(200, heavyResp.statusCode());
            Thread heavyExecutedThread = IsolationTestController.heavyThreadRef.get();
            assertNotNull(heavyExecutedThread, "Heavy endpoint should have recorded its execution thread");
            assertTrue(heavyExecutedThread.isVirtual(), "/heavy endpoint MUST run inside a Virtual Thread");
            assertTrue(heavyExecutedThread.getName().startsWith("cardigan-isolated-vt-"), "Heavy endpoint thread name MUST identify an isolated virtual thread");
            assertNotEquals(connectionThread, heavyExecutedThread, "/heavy endpoint MUST NOT share the connection thread");

            // 2. Test /sleepy endpoint
            String sleepyReqStr = "GET /sleepy-test HTTP/1.1\r\nHost: localhost\r\n\r\n";
            MemorySegment sleepySeg = arena.allocateFrom(sleepyReqStr);
            HttpRequest sleepyReq = new HttpRequest();
            assertTrue(HttpRequestParser.parse(sleepySeg, (int) sleepySeg.byteSize(), sleepyReq));

            Response sleepyResp = router.dispatch(sleepyReq);

            assertEquals(200, sleepyResp.statusCode());
            Thread sleepyExecutedThread = IsolationTestController.sleepyThreadRef.get();
            assertNotNull(sleepyExecutedThread, "Sleepy endpoint should have recorded its execution thread");
            assertTrue(sleepyExecutedThread.isVirtual(), "/sleepy endpoint MUST run inside a Virtual Thread");
            assertTrue(sleepyExecutedThread.getName().startsWith("cardigan-isolated-vt-"), "Sleepy endpoint thread name MUST identify an isolated virtual thread");
            assertNotEquals(connectionThread, sleepyExecutedThread, "/sleepy endpoint MUST NOT share the connection thread");
            assertNotEquals(heavyExecutedThread, sleepyExecutedThread, "Different isolated endpoints MUST execute in separate virtual threads");
        }
    }

    @Test
    public void isolatedEndpointDoesNotRunOnUringCarrier() throws Exception {
        Router router = new Router();
        router.registerController(new IsolationTestController());
        IsolationTestController.carrierRef.set(null);

        try (UringEventLoop loop = new UringEventLoop(0, 64);
             Arena arena = Arena.ofShared()) {
            MemorySegment segment = arena.allocateFrom(
                "GET /heavy-test HTTP/1.1\r\nHost: localhost\r\n\r\n"
            );
            HttpRequest request = new HttpRequest();
            assertTrue(
                HttpRequestParser.parse(segment, (int) segment.byteSize(), request)
            );

            AtomicReference<Response> response = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            loop.applicationRuntime().startTask(() -> {
                try {
                    response.set(router.dispatch(request));
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    completed.countDown();
                }
            });

            assertTrue(
                completed.await(5, TimeUnit.SECONDS),
                "Route dispatch did not complete"
            );
            assertNull(failure.get(), "Route dispatch failed");
            Response routeResponse = response.get();
            assertNotNull(routeResponse, "Route did not return a response");
            assertEquals(200, routeResponse.statusCode());

            String executionThread = IsolationTestController.carrierRef.get();
            assertNotNull(executionThread, "Route did not record its carrier");
            assertTrue(
                executionThread.contains("cardigan-isolated-carrier-"),
                "Isolated route used the wrong carrier: " + executionThread
            );
            assertFalse(
                executionThread.contains("cardigan-loop-"),
                "Isolated route leaked onto the io_uring carrier: " +
                    executionThread
            );
        }
    }
}
