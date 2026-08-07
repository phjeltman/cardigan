// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

/** A response body produced incrementally into native buffers. */
public final class StreamingBody implements AutoCloseable {
    public static final int UNKNOWN_LENGTH = -1;
    private static final VarHandle CLOSED;

    static {
        try {
            CLOSED = MethodHandles.lookup().findVarHandle(
                StreamingBody.class, "closed", boolean.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @FunctionalInterface
    public interface Reader {
        /**
         * Returns a positive byte count, or {@code -1} after the final byte.
         * Returning zero is invalid.
         */
        int read(MemorySegment destination);
    }

    private final int length;
    private final Reader reader;
    private final Runnable closeAction;
    private volatile boolean closed;

    private StreamingBody(int length, Reader reader, Runnable closeAction) {
        if (length < UNKNOWN_LENGTH) {
            throw new IllegalArgumentException("Negative body length");
        }
        this.length = length;
        this.reader = Objects.requireNonNull(reader, "reader");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
    }

    public static StreamingBody of(int length, Reader reader) {
        requireKnownLength(length);
        return new StreamingBody(length, reader, () -> { });
    }

    public static StreamingBody of(
        int length,
        Reader reader,
        Runnable closeAction
    ) {
        requireKnownLength(length);
        return new StreamingBody(length, reader, closeAction);
    }

    public static StreamingBody unknownLength(Reader reader) {
        return new StreamingBody(UNKNOWN_LENGTH, reader, () -> { });
    }

    public static StreamingBody unknownLength(
        Reader reader,
        Runnable closeAction
    ) {
        return new StreamingBody(UNKNOWN_LENGTH, reader, closeAction);
    }

    public int length() {
        return length;
    }

    public boolean hasKnownLength() {
        return length != UNKNOWN_LENGTH;
    }

    public boolean isClosed() {
        return closed;
    }

    private static void requireKnownLength(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Negative body length");
        }
    }

    public int read(MemorySegment destination) {
        if (closed) {
            return -1;
        }
        if (destination.byteSize() == 0) {
            return 0;
        }
        int produced = reader.read(destination);
        if (closed) {
            return -1;
        }
        if (produced < -1 || produced == 0
            || produced > destination.byteSize()) {
            throw new IllegalStateException(
                "Streaming body produced an invalid byte count: " + produced);
        }
        return produced;
    }

    @Override
    public void close() {
        if (!(boolean) CLOSED.compareAndSet(this, false, true)) {
            return;
        }
        closeAction.run();
    }
}
