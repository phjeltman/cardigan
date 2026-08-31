// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.Get;
import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.HttpRequestParser;
import dev.cardigan.http.Response;
import dev.cardigan.http.Router;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Http1ExchangeSequencerReservationTest {
    @Test
    void reservesCapacityOnceForTheSafeSubmissionPath() {
        DeferredExecutor executor = new DeferredExecutor();
        Router router = new Router();
        router.registerController(new Controller());
        HttpRequest request = request();
        Http1ExchangeSequencer sequencer =
            new Http1ExchangeSequencer(
                executor, 2,
                (response, keepAlive, keepAliveHeader) -> true);

        assertTrue(sequencer.submit(
            router, request, true, false, null));
        assertTrue(sequencer.submit(
            router, request, true, false, null));
        assertEquals(
            Http1ExchangeSequencer.RESERVATION_FULL,
            sequencer.tryReserveSubmission());

        executor.runAll();
        assertFalse(sequencer.hasInFlight());
        assertEquals(
            Http1ExchangeSequencer.RESERVATION_ACQUIRED,
            sequencer.tryReserveSubmission());
        assertTrue(sequencer.submitReservedSafe(
            router, request, true, false, null));
        executor.runAll();
        assertFalse(sequencer.hasInFlight());
    }

    private static HttpRequest request() {
        byte[] bytes = (
            "GET /value/42 HTTP/1.1\r\nHost: localhost\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        MemorySegment segment = Arena.ofAuto().allocate(bytes.length);
        MemorySegment.copy(
            bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
        HttpRequest request = new HttpRequest();
        assertTrue(HttpRequestParser.parse(
            segment, bytes.length, request));
        return request;
    }

    public static final class Controller {
        @Get("/value/{id}")
        public Response value(long id) {
            return Response.text(id);
        }
    }

    private static final class DeferredExecutor
            implements Http1ExchangeSequencer.TaskExecutor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public boolean submit(Runnable task) {
            tasks.addLast(task);
            return true;
        }

        private void runAll() {
            Runnable task;
            while ((task = tasks.pollFirst()) != null) {
                task.run();
            }
        }
    }
}
