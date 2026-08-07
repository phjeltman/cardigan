// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.example;

import dev.cardigan.core.UringEventLoop;
import dev.cardigan.http.Get;
import dev.cardigan.http.Isolated;
import dev.cardigan.http.Post;
import dev.cardigan.http.RequestBody;
import dev.cardigan.http.Response;
import dev.cardigan.http.StaticBody;
import dev.cardigan.http.StreamingBody;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Routes used by Cardigan's development server and benchmarks. */
public final class ExampleController {
    private static final StaticBody LARGE_PAYLOAD = StaticBody.utf8(
        "A".repeat(
            Integer.getInteger(
                "cardigan.benchmark.payloadSize", 65_536)));

    private final int sleepMillis;
    private final int heavyIterations;

    public ExampleController() {
        this(
            Math.max(
                0,
                Integer.getInteger(
                    "cardigan.benchmark.sleepMillis", 2_000)),
            Math.max(
                1,
                Integer.getInteger(
                    "cardigan.benchmark.heavyIterations", 5_000_000))
        );
    }

    public ExampleController(int sleepMillis, int heavyIterations) {
        this.sleepMillis = Math.max(0, sleepMillis);
        this.heavyIterations = Math.max(1, heavyIterations);
    }

    @Get("/users/{id}")
    public Response getUser(long id) {
        return Response.text(
            "User details for ID: " + id + " parsed directly off-heap!");
    }

    @Post("/users")
    public Response createUser(User user) {
        return Response.json(user);
    }

    @Get("/some/response/large")
    public Response getLargeResponse() {
        return Response.text(LARGE_PAYLOAD);
    }

    @Get("/stream/{bytes}")
    public Response streamBytes(long bytes) {
        if (bytes < 0 || bytes > 16 * 1024 * 1024L) {
            return Response.payloadTooLarge();
        }
        int length = (int) bytes;
        return Response.stream(
            "application/octet-stream",
            StreamingBody.of(length, repeatedBytes(length))
        );
    }

    @Get("/stream-unknown/{bytes}")
    public Response streamUnknownBytes(long bytes) {
        if (bytes < 0 || bytes > 16 * 1024 * 1024L) {
            return Response.payloadTooLarge();
        }
        return Response.stream(
            "application/octet-stream",
            StreamingBody.unknownLength(repeatedBytes((int) bytes))
        );
    }

    private static StreamingBody.Reader repeatedBytes(int length) {
        return new StreamingBody.Reader() {
            private int offset;

            @Override
            public int read(MemorySegment destination) {
                if (offset == length) {
                    return -1;
                }
                int count = Math.min(
                    length - offset,
                    Math.toIntExact(destination.byteSize()));
                destination.asSlice(0, count).fill((byte) 'A');
                offset += count;
                return count;
            }
        };
    }

    @Post("/stream/upload")
    public Response uploadStream(RequestBody body) {
        long received = 0;
        try (Arena arena = Arena.ofConfined(); body) {
            MemorySegment scratch = arena.allocate(
                UringEventLoop.BUFFER_SIZE);
            int count;
            while ((count = body.read(scratch)) >= 0) {
                received += count;
            }
        }
        return Response.text("Received " + received + " bytes");
    }

    @Post("/stream/upload-heavy")
    @Isolated
    public Response uploadHeavy(RequestBody body) {
        long received = 0;
        long checksum = 0xcbf29ce484222325L;
        try (Arena arena = Arena.ofConfined(); body) {
            MemorySegment scratch = arena.allocate(
                UringEventLoop.BUFFER_SIZE);
            int count;
            while ((count = body.read(scratch)) >= 0) {
                received += count;
                for (int offset = 0; offset < count; offset++) {
                    checksum ^= scratch.get(
                        ValueLayout.JAVA_BYTE, offset) & 0xffL;
                    checksum *= 0x100000001b3L;
                }
            }
        }
        checksum ^= heavyChecksum(heavyIterations);
        return Response.text(
            "Heavy upload received " + received
                + " bytes, checksum: " + checksum);
    }

    @Get("/sleepy")
    public Response getSleepy() {
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Response.text(
            "Slept like a baby for " + sleepMillis + "ms!");
    }

    @Get("/heavy")
    @Isolated
    public Response heavyTask() {
        long checksum = heavyChecksum(heavyIterations);
        return Response.text(
            "Heavy lifting complete! Iterations: " + heavyIterations
                + ", checksum: " + checksum);
    }

    public static long heavyChecksum(int iterations) {
        long value = 0x9e3779b97f4a7c15L;
        for (int i = 0; i < iterations; i++) {
            value ^= value << 13;
            value ^= value >>> 7;
            value ^= value << 17;
            value += i;
        }
        return value;
    }

    public record User(String name, int id, boolean active) {
    }
}
