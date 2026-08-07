// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

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

/** Shared route fixture for transport and integration tests. */
final class TestController {
    private static final StaticBody LARGE_PAYLOAD = StaticBody.utf8(
        "A".repeat(65_536));
    private final int sleepMillis;

    TestController() {
        this(2_000);
    }

    TestController(int sleepMillis) {
        this.sleepMillis = sleepMillis;
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
            StreamingBody.of(length, repeatedBytes(length)));
    }

    @Get("/stream-unknown/{bytes}")
    public Response streamUnknownBytes(long bytes) {
        if (bytes < 0 || bytes > 16 * 1024 * 1024L) {
            return Response.payloadTooLarge();
        }
        return Response.stream(
            "application/octet-stream",
            StreamingBody.unknownLength(repeatedBytes((int) bytes)));
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
        return receive(body, false);
    }

    @Post("/stream/upload-heavy")
    @Isolated
    public Response uploadHeavy(RequestBody body) {
        return receive(body, true);
    }

    private static Response receive(RequestBody body, boolean checksumBody) {
        long received = 0;
        long checksum = 0xcbf29ce484222325L;
        try (Arena arena = Arena.ofConfined(); body) {
            MemorySegment scratch = arena.allocate(UringEventLoop.BUFFER_SIZE);
            int count;
            while ((count = body.read(scratch)) >= 0) {
                received += count;
                if (checksumBody) {
                    for (int offset = 0; offset < count; offset++) {
                        checksum ^= scratch.get(
                            ValueLayout.JAVA_BYTE, offset) & 0xffL;
                        checksum *= 0x100000001b3L;
                    }
                }
            }
        }
        return checksumBody
            ? Response.text(
                "Heavy upload received " + received
                    + " bytes, checksum: " + checksum)
            : Response.text("Received " + received + " bytes");
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
        return Response.text("Heavy lifting complete!");
    }

    record User(String name, int id, boolean active) {
    }
}
