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
    private final long[] decodeProgress;
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
        this.decodeProgress = null;
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
        this.decodeProgress = new long[2];
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
        if (complete) {
            return -1;
        }
        int capacity = Math.toIntExact(Math.min(
            destination.byteSize(), Integer.MAX_VALUE));
        if (capacity == 0) {
            return 0;
        }

        int written = 0;
        while (written < capacity) {
            if (complete) {
                return written == 0 ? -1 : written;
            }
            if (!ensureReadableChunk()) {
                RequestBodyException failure = truncated();
                if (written == 0) {
                    throw failure;
                }
                return written;
            }

            int result = PicoHTTPParser.decodeChunkedTo(
                decoder,
                chunk.segment(),
                offset,
                chunk.length() - offset,
                destination,
                written,
                capacity - written,
                maximumDecodedLength - decodedLength,
                maximumMetadataLength - metadataLength,
                decodeProgress
            );
            int consumed = Math.toIntExact(decodeProgress[0]);
            int produced = Math.toIntExact(decodeProgress[1]);
            offset += consumed;
            written += produced;
            decodedLength += produced;
            metadataLength += consumed - produced;

            RequestBodyException failure = switch (result) {
                case PicoHTTPParser.ERROR_PARSE -> fail(
                    400, "Invalid HTTP/1 chunked body");
                case PicoHTTPParser.ERROR_CHUNKED_PAYLOAD_LIMIT -> fail(
                    413,
                    "Decoded HTTP/1 request body exceeds the request limit");
                case PicoHTTPParser.ERROR_CHUNKED_METADATA_LIMIT -> fail(
                    431,
                    "HTTP/1 chunk metadata exceeds the header limit");
                default -> null;
            };
            if (failure != null) {
                if (written == 0) {
                    throw failure;
                }
                return written;
            }
            if (result == PicoHTTPParser.CHUNKED_COMPLETE) {
                complete = true;
                leftoverOffset = offset;
            }
            if (result == PicoHTTPParser.CHUNKED_OUTPUT_FULL) {
                return written;
            }
            if (offset == chunk.length() && !complete) {
                chunk.close();
                chunk = null;
                offset = 0;
            }
        }
        return written;
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
     * Advances Pico framing until one contiguous payload span is available.
     * Framing remains in place; only application reads and final pipelined
     * request recovery copy bytes.
     */
    private void decodeNextChunk() {
        while (decodedRemaining == 0 && !complete) {
            if (!ensureReadableChunk()) {
                throw truncated();
            }

            int inputOffset = offset;
            int inputLength = chunk.length() - inputOffset;
            int result = PicoHTTPParser.decodeChunkedSpan(
                decoder,
                chunk.segment(),
                inputOffset,
                inputLength,
                decodeProgress
            );
            int consumed = Math.toIntExact(decodeProgress[0]);
            int produced = Math.toIntExact(decodeProgress[1]);
            int nextOffset = inputOffset + consumed;

            metadataLength += consumed - produced;
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

            if (result == PicoHTTPParser.CHUNKED_SPAN_DATA) {
                decodedRemaining = produced;
                offset = nextOffset - produced;
            } else {
                offset = nextOffset;
            }
            if (result == PicoHTTPParser.CHUNKED_COMPLETE) {
                complete = true;
                leftoverOffset = nextOffset;
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
