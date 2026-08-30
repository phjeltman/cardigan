// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.Get;
import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.HttpRequestParser;
import dev.cardigan.http.Response;
import dev.cardigan.http.Router;
import dev.cardigan.http.StreamingBody;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
final class Http1ExchangeSequencerCleanupTest {
    @Test
    void throwingStreamingCloseCannotStrandFailedAccounting() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            ThrowingStreamController controller =
                new ThrowingStreamController();
            try (UringEventLoop loop = new UringEventLoop(
                    0, 64, 512, false)) {
                Router router = new Router();
                router.registerController(controller);
                Http1ExchangeSequencer sequencer =
                    new Http1ExchangeSequencer(
                        loop.exchangeExecutor(), 2,
                        (response, keepAlive, keepAliveHeader) -> true);

                AtomicReference<Throwable> submitFailure =
                    runOnLoop(loop, () -> {
                        assertTrue(sequencer.submit(
                            router, request("/throw-slow"),
                            true, false, null));
                        assertTrue(sequencer.submit(
                            router, request("/throw-fast"),
                            true, false, null));
                    });
                assertNull(submitFailure.get());
                assertTrue(controller.slowStarted.await(
                    2, TimeUnit.SECONDS));
                assertTrue(controller.fastReturned.await(
                    2, TimeUnit.SECONDS));

                AtomicReference<Throwable> cancelFailure =
                    runOnLoop(loop, sequencer::cancelAll);
                assertNull(cancelFailure.get(),
                    "throwing response cleanup escaped cancellation");
                assertEquals(1, controller.closeAttempts.get(),
                    "out-of-order response was not discarded");

                controller.releaseSlow.countDown();
                assertTrue(controller.slowReturned.await(
                    2, TimeUnit.SECONDS));
                AtomicReference<Throwable> barrierFailure =
                    runOnLoop(loop, () -> { });
                assertNull(barrierFailure.get());

                assertEquals(2, controller.closeAttempts.get(),
                    "failed predecessor completion skipped cleanup");
                assertFalse(sequencer.hasInFlight(),
                    "throwing cleanup stranded in-flight accounting");
            } finally {
                controller.releaseSlow.countDown();
            }
        });
    }

    private static AtomicReference<Throwable> runOnLoop(
            UringEventLoop loop, Runnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        loop.execute(() -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                completed.countDown();
            }
        });
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        return failure;
    }

    private static HttpRequest request(String path) {
        byte[] bytes = ("GET " + path + " HTTP/1.1\r\n"
            + "Host: localhost\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        MemorySegment segment = Arena.ofAuto().allocate(bytes.length);
        MemorySegment.copy(
            bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
        HttpRequest request = new HttpRequest();
        assertTrue(HttpRequestParser.parse(
            segment, bytes.length, request));
        return request;
    }

    public static final class ThrowingStreamController {
        private final CountDownLatch slowStarted = new CountDownLatch(1);
        private final CountDownLatch releaseSlow = new CountDownLatch(1);
        private final CountDownLatch slowReturned = new CountDownLatch(1);
        private final CountDownLatch fastReturned = new CountDownLatch(1);
        private final AtomicInteger closeAttempts = new AtomicInteger();

        @Get("/throw-slow")
        public Response slow() {
            slowStarted.countDown();
            try {
                releaseSlow.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            slowReturned.countDown();
            return throwingResponse();
        }

        @Get("/throw-fast")
        public Response fast() {
            fastReturned.countDown();
            return throwingResponse();
        }

        private Response throwingResponse() {
            return Response.stream(StreamingBody.of(
                0,
                ignored -> -1,
                () -> {
                    closeAttempts.incrementAndGet();
                    throw new IllegalStateException("close failed");
                }
            ));
        }
    }
}
