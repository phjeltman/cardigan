// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.RequestBody;
import dev.cardigan.http.RequestBodyException;
import dev.cardigan.pico.ChunkedDecoder;
import dev.cardigan.pico.PicoHTTPParser;
import java.lang.foreign.MemorySegment;

/** Pulls a fixed-length or Pico-decoded chunked body from receive buffers. */
final class Http1RequestBody implements RequestBody {
    private final InboundChunkStream inbound;
    private final long length;
    private final boolean chunked;
    private final long maximumDecodedLength;
    private final int maximumMetadataLength;
    private final ChunkedDecoder decoder;
    private final long[] decodeLength;
    private InboundChunk chunk;
    private int offset;
    private long remaining;
    private long decodedLength;
    private long metadataLength;
    private int decodedRemaining;
    private int leftoverOffset;
    private boolean complete;
    private boolean failed;
    private int failureStatus;
    private String failureMessage;

    Http1RequestBody(
        InboundChunkStream inbound,
        InboundChunk initialChunk,
        int bodyOffset,
        long length
    ) {
        if (length < 0) {
            throw new IllegalArgumentException("Negative body length");
        }
        this.inbound = inbound;
        this.chunk = initialChunk;
        this.offset = bodyOffset;
        this.length = length;
        this.remaining = length;
        this.chunked = false;
        this.maximumDecodedLength = length;
        this.maximumMetadataLength = 0;
        this.decoder = null;
        this.decodeLength = null;
    }

    private Http1RequestBody(
        InboundChunkStream inbound,
        InboundChunk initialChunk,
        int bodyOffset,
        long maximumDecodedLength,
        int maximumMetadataLength
    ) {
        if (maximumDecodedLength < 0 || maximumMetadataLength <= 0) {
            throw new IllegalArgumentException("Invalid chunked body limit");
        }
        this.inbound = inbound;
        this.chunk = initialChunk;
        this.offset = bodyOffset;
        this.length = -1;
        this.remaining = -1;
        this.chunked = true;
        this.maximumDecodedLength = maximumDecodedLength;
        this.maximumMetadataLength = maximumMetadataLength;
        this.decoder = new ChunkedDecoder();
        this.decoder.consumeTrailer = true;
        this.decodeLength = new long[1];
    }

    static Http1RequestBody chunked(
        InboundChunkStream inbound,
        InboundChunk initialChunk,
        int bodyOffset,
        long maximumDecodedLength,
        int maximumMetadataLength
    ) {
        return new Http1RequestBody(
            inbound,
            initialChunk,
            bodyOffset,
            maximumDecodedLength,
            maximumMetadataLength
        );
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public long remaining() {
        return remaining;
    }

    @Override
    public int read(MemorySegment destination) {
        if (failed) {
            throw failure();
        }
        if (chunked) {
            return readChunked(destination);
        }
        if (remaining == 0) {
            return -1;
        }
        int capacity = Math.toIntExact(Math.min(
            destination.byteSize(), Integer.MAX_VALUE));
        if (capacity == 0) {
            return 0;
        }
        if (!ensureReadableChunk()) {
            throw truncated();
        }

        int count = (int) Math.min(
            Math.min((long) capacity, remaining),
            chunk.length() - offset);
        MemorySegment.copy(chunk.segment(), offset, destination, 0, count);
        offset += count;
        remaining -= count;
        return count;
    }

    @Override
    public void close() {
        discardRemaining();
    }

    boolean discardRemaining() {
        if (failed) {
            return false;
        }
        if (chunked) {
            return discardChunked();
        }
        while (remaining != 0) {
            if (!ensureReadableChunk()) {
                fail(0, "Request body was truncated");
                return false;
            }
            int count = (int) Math.min(
                remaining, chunk.length() - offset);
            offset += count;
            remaining -= count;
        }
        compactLeftover(offset);
        return true;
    }

    int failureStatus() {
        return failureStatus;
    }

    InboundChunk takeChunk() {
        InboundChunk result = chunk;
        chunk = null;
        offset = 0;
        return result;
    }

    int leftoverLength() {
        return chunk == null ? 0 : chunk.length();
    }

    void release() {
        if (chunk != null) {
            chunk.close();
            chunk = null;
        }
    }

    private boolean ensureReadableChunk() {
        while (chunk == null || offset == chunk.length()) {
            if (chunk != null) {
                chunk.close();
            }
            chunk = inbound.nextChunk();
            offset = 0;
            if (chunk == null) {
                return false;
            }
        }
        return true;
    }

    private int readChunked(MemorySegment destination) {
        if (complete && decodedRemaining == 0) {
            return -1;
        }
        int capacity = Math.toIntExact(Math.min(
            destination.byteSize(), Integer.MAX_VALUE));
        if (capacity == 0) {
            return 0;
        }

        while (decodedRemaining == 0) {
            if (complete) {
                return -1;
            }
            decodeNextChunk();
        }

        int count = Math.min(capacity, decodedRemaining);
        MemorySegment.copy(chunk.segment(), offset, destination, 0, count);
        offset += count;
        decodedRemaining -= count;
        return count;
    }

    private boolean discardChunked() {
        try {
            while (!complete) {
                offset += decodedRemaining;
                decodedRemaining = 0;
                decodeNextChunk();
            }
            offset += decodedRemaining;
            decodedRemaining = 0;
            compactLeftover(leftoverOffset);
            leftoverOffset = 0;
            return true;
        } catch (RequestBodyException ignored) {
            return false;
        }
    }

    /**
     * Decodes one owned receive buffer with phr_decode_chunked. Pico compacts
     * decoded bytes to {@code offset}; if the terminal chunk is present, it
     * places pipelined bytes immediately after them.
     */
    private void decodeNextChunk() {
        while (decodedRemaining == 0 && !complete) {
            if (!ensureReadableChunk()) {
                throw truncated();
            }

            int inputLength = chunk.length() - offset;
            decodeLength[0] = inputLength;
            long result = PicoHTTPParser.decodeChunked(
                decoder, chunk.segment(), offset, decodeLength);
            int produced = Math.toIntExact(decodeLength[0]);
            int leftover = result >= 0 ? Math.toIntExact(result) : 0;

            metadataLength += inputLength - produced - leftover;
            if (metadataLength > maximumMetadataLength) {
                throw fail(
                    431, "HTTP/1 chunk metadata exceeds the header limit");
            }
            if (produced > maximumDecodedLength - decodedLength) {
                throw fail(
                    413,
                    "Decoded HTTP/1 request body exceeds the request limit");
            }
            decodedLength += produced;
            if (Long.compareUnsigned(
                decoder.bytesLeftInChunk,
                maximumDecodedLength - decodedLength) > 0) {
                throw fail(
                    413,
                    "HTTP/1 chunk size exceeds the request limit");
            }
            if (result == PicoHTTPParser.ERROR_PARSE) {
                throw fail(400, "Invalid HTTP/1 chunked body");
            }

            decodedRemaining = produced;
            if (result >= 0) {
                complete = true;
                leftoverOffset = offset + produced;
                chunk.length(leftoverOffset + leftover);
            } else {
                // The framing bytes have been removed in place. Once this
                // decoded prefix is consumed, the buffer can be released.
                chunk.length(offset + produced);
            }

            if (decodedRemaining == 0 && !complete) {
                chunk.close();
                chunk = null;
                offset = 0;
            }
        }
    }

    private RequestBodyException truncated() {
        return fail(0, "Request body was truncated");
    }

    private RequestBodyException fail(int status, String message) {
        failed = true;
        failureStatus = status;
        failureMessage = message;
        return new RequestBodyException(message);
    }

    private RequestBodyException failure() {
        return new RequestBodyException(failureMessage != null
            ? failureMessage
            : "Request body failed");
    }

    private void compactLeftover(int start) {
        if (chunk == null) {
            return;
        }
        int leftover = chunk.length() - start;
        if (leftover == 0) {
            chunk.close();
            chunk = null;
            offset = 0;
            return;
        }
        if (start != 0) {
            MemorySegment.copy(
                chunk.segment(), start, chunk.segment(), 0, leftover);
        }
        chunk.length(leftover);
        offset = 0;
    }
}
