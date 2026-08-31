// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRequestHeaderConcurrencyTest {
    @Test
    void buildsHeaderIndexSafelyAcrossConcurrentReaders() throws Exception {
        StringBuilder encoded = new StringBuilder(
            "GET / HTTP/1.1\r\nHost: localhost\r\n");
        for (int index = 0; index < 62; index++) {
            encoded.append("X-Test-").append(index)
                .append(": value-").append(index).append("\r\n");
        }
        byte[] bytes = encoded.append("\r\n").toString()
            .getBytes(StandardCharsets.US_ASCII);

        try (Arena arena = Arena.ofShared()) {
            MemorySegment segment = arena.allocate(bytes.length);
            MemorySegment.copy(
                bytes, 0, segment,
                java.lang.foreign.ValueLayout.JAVA_BYTE, 0, bytes.length);
            for (int iteration = 0; iteration < 100; iteration++) {
                HttpRequest request = new HttpRequest();
                assertTrue(HttpRequestParser.parse(
                    segment, bytes.length, request));
                CountDownLatch start = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread first = reader(
                    request, "X-Test-17", "value-17", start, failure);
                Thread second = reader(
                    request, "X-Test-53", "value-53", start, failure);
                start.countDown();
                first.join();
                second.join();
                if (failure.get() != null) {
                    throw new AssertionError(
                        "Concurrent header lookup failed", failure.get());
                }
            }
        }
    }

    private static Thread reader(
            HttpRequest request,
            String name,
            String expected,
            CountDownLatch start,
            AtomicReference<Throwable> failure) {
        return Thread.ofVirtual().start(() -> {
            try {
                start.await();
                for (int lookup = 0; lookup < 4; lookup++) {
                    assertEquals(expected, request.getHeader(name).toString());
                }
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
            }
        });
    }
}
