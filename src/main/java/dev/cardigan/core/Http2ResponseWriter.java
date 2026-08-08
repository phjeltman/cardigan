// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.Response;
import dev.cardigan.http.ResponseHeaders;
import dev.cardigan.http.StaticBody;
import dev.cardigan.http.StreamingBody;
import dev.cardigan.http2.HpackEncoder;
import dev.cardigan.http2.Http2Frames;
import dev.cardigan.ffi.RawSegment;
import dev.cardigan.json.JsonWriter;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Emits one response as an END_HEADERS block followed by zero or more DATA
 * frames. Each queued buffer is self-contained, allowing streams to complete
 * in any order without a connection-wide response sequencer.
 */
final class Http2ResponseWriter {
    interface FlowControl {
        int reserve(int desiredBytes);

        void refund(int bytes);

        boolean cancelled();
    }

    private static final int RECORD_BODY_OFFSET = 256;
    private static final int FIRST_BYTE_FRAME_PAYLOAD =
        Http2Frames.DEFAULT_MAX_FRAME_SIZE;
    private static final int MAX_INLINE_CONTENT_TYPE_LENGTH = 192;
    private static final int MAX_RECORD_BUFFER_SIZE = 2 * 1024 * 1024;
    private static final int STREAM_QUEUE_FLUSH_BYTES = 64 * 1024;
    private static final int COMPACT_NOT_APPLICABLE = 0;
    private static final int COMPACT_SENT = 1;
    private static final int COMPACT_FAILED = -1;
    private static final long STREAM_EOF = 1L << 32;
    private static final long STREAM_FAILURE = Long.MIN_VALUE;
    private static final long ASCII_HIGH_BITS = 0x8080_8080_8080_8080L;
    private static final VarHandle STRING_VALUE_HANDLE;
    private static final VarHandle BYTE_ARRAY_LONG_HANDLE =
        MethodHandles.byteArrayViewVarHandle(
            long[].class, ByteOrder.nativeOrder());

    static {
        VarHandle handle;
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                String.class, MethodHandles.lookup());
            handle = lookup.findVarHandle(String.class, "value", byte[].class);
        } catch (Throwable ignored) {
            handle = null;
        }
        STRING_VALUE_HANDLE = handle;
    }

    private final ConnectionWriter writer;
    private int peerMaxFrameSize = Http2Frames.DEFAULT_MAX_FRAME_SIZE;

    Http2ResponseWriter(ConnectionWriter writer) {
        this.writer = writer;
    }

    void peerMaxFrameSize(int value) {
        peerMaxFrameSize = value;
    }

    boolean send(int streamId, Response response, FlowControl flowControl) {
        if (response.hasMetadata()) {
            return sendMetadataResponse(
                streamId, response, flowControl);
        }
        Object body = response.body();
        if (body == null) {
            return sendFrames(
                streamId, response, MemorySegment.NULL, 0, flowControl);
        }
        if (body instanceof StaticBody staticBody) {
            return sendStaticFrames(
                streamId, response, staticBody, flowControl);
        }
        if (body instanceof StreamingBody streamingBody) {
            return sendStreamingFrames(
                streamId, response, streamingBody, flowControl);
        }
        if (body instanceof String text) {
            byte[] bytes = asciiBytes(text);
            if (bytes != null) {
                return sendByteFrames(
                    streamId, response, bytes, bytes.length, flowControl);
            }
            bytes = text.getBytes(StandardCharsets.UTF_8);
            return sendByteFrames(
                streamId, response, bytes, bytes.length, flowControl);
        }
        if (body instanceof Record record) {
            return sendRecord(streamId, response, record, flowControl);
        }

        byte[] bytes = String.valueOf(body).getBytes(StandardCharsets.UTF_8);
        return sendByteFrames(
            streamId, response, bytes, bytes.length, flowControl);
    }

    private boolean sendMetadataResponse(
            int streamId,
            Response response,
            FlowControl flowControl) {
        Object body = response.body();
        if (body instanceof StreamingBody streamingBody) {
            return sendMetadataStreamingResponse(
                streamId, response, streamingBody, flowControl);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes;
            int length;
            if (body == null) {
                bytes = MemorySegment.NULL;
                length = 0;
            } else if (body instanceof StaticBody staticBody) {
                bytes = staticBody.segment();
                length = staticBody.length();
            } else if (body instanceof String text) {
                byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
                bytes = MemorySegment.ofArray(encoded);
                length = encoded.length;
            } else if (body instanceof Record record) {
                bytes = arena.allocate(MAX_RECORD_BUFFER_SIZE);
                try {
                    length = JsonWriter.writeRecord(bytes, 0, record);
                } catch (Throwable failure) {
                    return sendSerializationError(streamId, flowControl);
                }
            } else {
                byte[] encoded = String.valueOf(body)
                    .getBytes(StandardCharsets.UTF_8);
                bytes = MemorySegment.ofArray(encoded);
                length = encoded.length;
            }
            return sendMetadataFrames(
                streamId, response, bytes, length, flowControl);
        }
    }

    private boolean sendMetadataFrames(
            int streamId,
            Response response,
            MemorySegment body,
            int bodyLength,
            FlowControl flowControl) {
        ResponseHeaders trailers = response.trailers();
        if (!sendMetadataHeaders(
                streamId, response, bodyLength, bodyLength == 0
                    && trailers.isEmpty())) {
            return false;
        }

        int offset = 0;
        int maximumPayload = Math.min(
            peerMaxFrameSize,
            UringEventLoop.EGRESS_FRAME_SIZE - Http2Frames.HEADER_SIZE);
        while (offset < bodyLength) {
            int desired = Math.min(maximumPayload, bodyLength - offset);
            int length = flowControl.reserve(desired);
            if (length <= 0) {
                return false;
            }
            int flags = offset + length == bodyLength && trailers.isEmpty()
                ? Http2Frames.FLAG_END_STREAM
                : 0;
            if (!sendMetadataDataFrame(
                    streamId, body, offset, length, flags)) {
                return false;
            }
            offset += length;
        }
        return trailers.isEmpty()
            || sendMetadataTrailers(streamId, trailers);
    }

    private boolean sendMetadataStreamingResponse(
            int streamId,
            Response response,
            StreamingBody body,
            FlowControl flowControl) {
        ResponseHeaders trailers = response.trailers();
        int bodyLength = body.length();
        try {
            int compactResult = trySendCompactMetadataStreamingResponse(
                streamId, response, body, bodyLength, trailers, flowControl);
            if (compactResult != COMPACT_NOT_APPLICABLE) {
                return compactResult == COMPACT_SENT;
            }
            if (!sendMetadataHeaders(
                    streamId, response, bodyLength,
                    bodyLength == 0 && trailers.isEmpty())) {
                return false;
            }
            if (bodyLength == 0) {
                return trailers.isEmpty()
                    || sendMetadataTrailers(streamId, trailers);
            }
            if (!body.hasKnownLength()) {
                return sendMetadataUnknownStreaming(
                    streamId, body, trailers, flowControl);
            }

            int remaining = bodyLength;
            int queuedBytes = 0;
            int maximumPayload = Math.min(
                peerMaxFrameSize,
                UringEventLoop.EGRESS_FRAME_SIZE
                    - Http2Frames.HEADER_SIZE);
            while (remaining != 0) {
                int desired = Math.min(maximumPayload, remaining);
                int reserved = flowControl.reserve(desired);
                if (reserved <= 0) {
                    return false;
                }
                int produced = sendMetadataStreamingDataFrame(
                    streamId,
                    body,
                    reserved,
                    remaining == reserved && trailers.isEmpty()
                        ? Http2Frames.FLAG_END_STREAM
                        : 0,
                    flowControl
                );
                if (produced <= 0) {
                    return false;
                }
                remaining -= produced;
                queuedBytes += Http2Frames.HEADER_SIZE + produced;
                if (remaining != 0
                    && queuedBytes >= STREAM_QUEUE_FLUSH_BYTES) {
                    if (!writer.awaitDrained()) {
                        return false;
                    }
                    queuedBytes = 0;
                }
            }
            return trailers.isEmpty()
                || sendMetadataTrailers(streamId, trailers);
        } finally {
            body.close();
        }
    }

    private int trySendCompactMetadataStreamingResponse(
            int streamId,
            Response response,
            StreamingBody body,
            int bodyLength,
            ResponseHeaders trailers,
            FlowControl flowControl) {
        if (!body.hasKnownLength() || bodyLength <= 0 || trailers.isEmpty()
            || bodyLength > peerMaxFrameSize
            || response.contentType().length()
                > MAX_INLINE_CONTENT_TYPE_LENGTH) {
            return COMPACT_NOT_APPLICABLE;
        }

        long maximumLength = (long) bodyLength
            + 3L * Http2Frames.HEADER_SIZE
            + response.contentType().length()
            + response.headers().byteSize()
            + trailers.byteSize()
            + 7L * (response.headers().size() + trailers.size())
            + 32;
        if (maximumLength > UringEventLoop.EGRESS_FRAME_SIZE) {
            return COMPACT_NOT_APPLICABLE;
        }

        int reserved = flowControl.reserve(bodyLength);
        if (reserved != bodyLength) {
            flowControl.refund(reserved);
            return reserved <= 0 && flowControl.cancelled()
                ? COMPACT_FAILED
                : COMPACT_NOT_APPLICABLE;
        }

        UringEventLoop loop = writer.eventLoop();
        int egressId = loop.acquireEgressBuffer();
        if (egressId < 0) {
            flowControl.refund(bodyLength);
            return COMPACT_NOT_APPLICABLE;
        }

        MemorySegment output = loop.getEgressBufferSegment(egressId);
        int dataFrameOffset;
        int dataOffset;
        int trailerFrameOffset;
        int totalLength;
        try {
            int headerBlockLength = HpackEncoder.writeResponseHeaders(
                output,
                Http2Frames.HEADER_SIZE,
                response.statusCode(),
                response.contentType(),
                bodyLength,
                response.headers());
            dataFrameOffset = Http2Frames.HEADER_SIZE + headerBlockLength;
            dataOffset = dataFrameOffset + Http2Frames.HEADER_SIZE;
            trailerFrameOffset = dataOffset + bodyLength;
            int trailerBlockLength = HpackEncoder.writeFields(
                output,
                trailerFrameOffset + Http2Frames.HEADER_SIZE,
                trailers);
            totalLength = trailerFrameOffset
                + Http2Frames.HEADER_SIZE + trailerBlockLength;

            Http2Frames.writeHeader(
                output, 0, headerBlockLength, Http2Frames.HEADERS,
                Http2Frames.FLAG_END_HEADERS, streamId);
            Http2Frames.writeHeader(
                output, dataFrameOffset, bodyLength, Http2Frames.DATA,
                0, streamId);
            Http2Frames.writeHeader(
                output, trailerFrameOffset, trailerBlockLength,
                Http2Frames.HEADERS,
                Http2Frames.FLAG_END_HEADERS | Http2Frames.FLAG_END_STREAM,
                streamId);

            if (flowControl.cancelled()
                || !fillStreamingBody(
                    body, output.asSlice(dataOffset, bodyLength))
                || flowControl.cancelled()) {
                loop.releaseEgressBuffer(egressId);
                flowControl.refund(bodyLength);
                return COMPACT_FAILED;
            }
        } catch (Throwable failure) {
            loop.releaseEgressBuffer(egressId);
            flowControl.refund(bodyLength);
            throw failure;
        }

        if (!writer.enqueue(egressId, totalLength)) {
            flowControl.refund(bodyLength);
            return COMPACT_FAILED;
        }
        return COMPACT_SENT;
    }

    private boolean sendMetadataUnknownStreaming(
            int streamId,
            StreamingBody body,
            ResponseHeaders trailers,
            FlowControl flowControl) {
        int queuedBytes = 0;
        int maximumPayload = Math.min(
            peerMaxFrameSize,
            UringEventLoop.EGRESS_FRAME_SIZE - Http2Frames.HEADER_SIZE);
        try (Arena probeArena = Arena.ofConfined()) {
            MemorySegment probe = probeArena.allocate(1);
            while (!flowControl.cancelled() && !body.isClosed()) {
                int first = body.read(probe);
                if (first < 0) {
                    return trailers.isEmpty()
                        ? sendEmptyEndStream(streamId, flowControl)
                        : sendMetadataTrailers(streamId, trailers);
                }
                int reserved = flowControl.reserve(maximumPayload);
                if (reserved <= 0) {
                    return false;
                }
                long result = sendMetadataUnknownDataFrame(
                    streamId,
                    body,
                    reserved,
                    probe.get(ValueLayout.JAVA_BYTE, 0) & 0xff,
                    trailers.isEmpty(),
                    flowControl
                );
                if (result == STREAM_FAILURE) {
                    return false;
                }
                int produced = (int) result;
                boolean ended = (result & STREAM_EOF) != 0;
                queuedBytes += Http2Frames.HEADER_SIZE + produced;
                if (ended) {
                    return trailers.isEmpty()
                        || sendMetadataTrailers(streamId, trailers);
                }
                if (queuedBytes >= STREAM_QUEUE_FLUSH_BYTES) {
                    if (!writer.awaitDrained()) {
                        return false;
                    }
                    queuedBytes = 0;
                }
            }
            return false;
        }
    }

    private int sendMetadataStreamingDataFrame(
            int streamId,
            StreamingBody body,
            int reserved,
            int flags,
            FlowControl flowControl) {
        UringEventLoop loop = writer.eventLoop();
        int egressId = loop.acquireEgressBuffer();
        if (egressId >= 0) {
            MemorySegment output = loop.getEgressBufferSegment(egressId);
            int produced = body.read(
                output.asSlice(Http2Frames.HEADER_SIZE, reserved));
            if (produced <= 0) {
                flowControl.refund(reserved);
                loop.releaseEgressBuffer(egressId);
                return -1;
            }
            flowControl.refund(reserved - produced);
            Http2Frames.writeHeader(
                output, 0, produced, Http2Frames.DATA,
                produced == reserved ? flags : 0, streamId);
            if (!writer.enqueue(
                    egressId, Http2Frames.HEADER_SIZE + produced)) {
                flowControl.refund(produced);
                return -1;
            }
            return produced;
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(
                Http2Frames.HEADER_SIZE + reserved);
            int produced = body.read(
                output.asSlice(Http2Frames.HEADER_SIZE, reserved));
            if (produced <= 0) {
                flowControl.refund(reserved);
                return -1;
            }
            flowControl.refund(reserved - produced);
            Http2Frames.writeHeader(
                output, 0, produced, Http2Frames.DATA,
                produced == reserved ? flags : 0, streamId);
            if (writer.writeFully(
                    output, Http2Frames.HEADER_SIZE + produced) <= 0) {
                flowControl.refund(produced);
                return -1;
            }
            return produced;
        }
    }

    private long sendMetadataUnknownDataFrame(
            int streamId,
            StreamingBody body,
            int reserved,
            int pendingByte,
            boolean endOnEof,
            FlowControl flowControl) {
        UringEventLoop loop = writer.eventLoop();
        int egressId = loop.acquireEgressBuffer();
        if (egressId >= 0) {
            MemorySegment output = loop.getEgressBufferSegment(egressId);
            long result = fillUnknownStreamingBody(
                body,
                output.asSlice(Http2Frames.HEADER_SIZE, reserved),
                pendingByte);
            int produced = (int) result;
            flowControl.refund(reserved - produced);
            int flags = endOnEof && (result & STREAM_EOF) != 0
                ? Http2Frames.FLAG_END_STREAM
                : 0;
            Http2Frames.writeHeader(
                output, 0, produced, Http2Frames.DATA, flags, streamId);
            if (!writer.enqueue(
                    egressId, Http2Frames.HEADER_SIZE + produced)) {
                flowControl.refund(produced);
                return STREAM_FAILURE;
            }
            return result;
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(
                Http2Frames.HEADER_SIZE + reserved);
            long result = fillUnknownStreamingBody(
                body,
                output.asSlice(Http2Frames.HEADER_SIZE, reserved),
                pendingByte);
            int produced = (int) result;
            flowControl.refund(reserved - produced);
            int flags = endOnEof && (result & STREAM_EOF) != 0
                ? Http2Frames.FLAG_END_STREAM
                : 0;
            Http2Frames.writeHeader(
                output, 0, produced, Http2Frames.DATA, flags, streamId);
            if (writer.writeFully(
                    output, Http2Frames.HEADER_SIZE + produced) <= 0) {
                flowControl.refund(produced);
                return STREAM_FAILURE;
            }
            return result;
        }
    }

    private boolean sendMetadataHeaders(
            int streamId,
            Response response,
            int bodyLength,
            boolean endStream) {
        UringEventLoop loop = writer.eventLoop();
        int egressId = loop.acquireEgressBuffer();
        if (egressId >= 0) {
            MemorySegment output = loop.getEgressBufferSegment(egressId);
            int blockLength = HpackEncoder.writeResponseHeaders(
                output,
                Http2Frames.HEADER_SIZE,
                response.statusCode(),
                response.contentType(),
                bodyLength,
                response.headers());
            int flags = Http2Frames.FLAG_END_HEADERS
                | (endStream ? Http2Frames.FLAG_END_STREAM : 0);
            Http2Frames.writeHeader(
                output, 0, blockLength, Http2Frames.HEADERS,
                flags, streamId);
            return writer.enqueue(
                egressId, Http2Frames.HEADER_SIZE + blockLength);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(
                UringEventLoop.EGRESS_FRAME_SIZE);
            int blockLength = HpackEncoder.writeResponseHeaders(
                output,
                Http2Frames.HEADER_SIZE,
                response.statusCode(),
                response.contentType(),
                bodyLength,
                response.headers());
            int flags = Http2Frames.FLAG_END_HEADERS
                | (endStream ? Http2Frames.FLAG_END_STREAM : 0);
            Http2Frames.writeHeader(
                output, 0, blockLength, Http2Frames.HEADERS,
                flags, streamId);
            return writer.writeFully(
                output, Http2Frames.HEADER_SIZE + blockLength) > 0;
        }
    }

    private boolean sendMetadataTrailers(
            int streamId,
            ResponseHeaders trailers) {
        UringEventLoop loop = writer.eventLoop();
        int egressId = loop.acquireEgressBuffer();
        if (egressId >= 0) {
            MemorySegment output = loop.getEgressBufferSegment(egressId);
            int blockLength = HpackEncoder.writeFields(
                output, Http2Frames.HEADER_SIZE, trailers);
            Http2Frames.writeHeader(
                output, 0, blockLength, Http2Frames.HEADERS,
                Http2Frames.FLAG_END_HEADERS | Http2Frames.FLAG_END_STREAM,
                streamId);
            return writer.enqueue(
                egressId, Http2Frames.HEADER_SIZE + blockLength);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(
                UringEventLoop.EGRESS_FRAME_SIZE);
            int blockLength = HpackEncoder.writeFields(
                output, Http2Frames.HEADER_SIZE, trailers);
            Http2Frames.writeHeader(
                output, 0, blockLength, Http2Frames.HEADERS,
                Http2Frames.FLAG_END_HEADERS | Http2Frames.FLAG_END_STREAM,
                streamId);
            return writer.writeFully(
                output, Http2Frames.HEADER_SIZE + blockLength) > 0;
        }
    }

    private boolean sendMetadataDataFrame(
            int streamId,
            MemorySegment body,
            int bodyOffset,
            int bodyLength,
            int flags) {
        UringEventLoop loop = writer.eventLoop();
        int egressId = loop.acquireEgressBuffer();
        if (egressId >= 0) {
            MemorySegment output = loop.getEgressBufferSegment(egressId);
            Http2Frames.writeHeader(
                output, 0, bodyLength, Http2Frames.DATA, flags, streamId);
            MemorySegment.copy(
                body, bodyOffset,
                output, Http2Frames.HEADER_SIZE, bodyLength);
            return writer.enqueue(
                egressId, Http2Frames.HEADER_SIZE + bodyLength);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(
                Http2Frames.HEADER_SIZE + bodyLength);
            Http2Frames.writeHeader(
                output, 0, bodyLength, Http2Frames.DATA, flags, streamId);
            MemorySegment.copy(
                body, bodyOffset,
                output, Http2Frames.HEADER_SIZE, bodyLength);
            return writer.writeFully(
                output, Http2Frames.HEADER_SIZE + bodyLength) > 0;
        }
    }

    private boolean sendStreamingFrames(
        int streamId,
        Response response,
        StreamingBody body,
        FlowControl flowControl
    ) {
        try {
            if (!sendStreamingHeaders(streamId, response, body.length())) {
                return false;
            }
            if (!body.hasKnownLength()) {
                return sendUnknownStreamingFrames(
                    streamId, body, flowControl);
            }
            int remaining = body.length();
            int queuedBytes = 0;
            int maximumPayload = Math.min(
                peerMaxFrameSize,
                UringEventLoop.EGRESS_FRAME_SIZE - Http2Frames.HEADER_SIZE);
            while (remaining != 0) {
                int desired = Math.min(maximumPayload, remaining);
                int chunkLength = flowControl.reserve(desired);
                if (chunkLength <= 0) {
                    return false;
                }
                int flags = chunkLength == remaining
                    ? Http2Frames.FLAG_END_STREAM
                    : 0;
                if (!sendStreamingDataFrame(
                        streamId, body, chunkLength, flags)) {
                    return false;
                }
                remaining -= chunkLength;
                queuedBytes += Http2Frames.HEADER_SIZE + chunkLength;
                if (remaining != 0
                    && queuedBytes >= STREAM_QUEUE_FLUSH_BYTES) {
                    if (!writer.awaitDrained()) {
                        return false;
                    }
                    queuedBytes = 0;
                }
            }
            return true;
        } finally {
            body.close();
        }
    }

    private boolean sendUnknownStreamingFrames(
        int streamId,
        StreamingBody body,
        FlowControl flowControl
    ) {
        int queuedBytes = 0;
        int maximumPayload = Math.min(
            peerMaxFrameSize,
            UringEventLoop.EGRESS_FRAME_SIZE - Http2Frames.HEADER_SIZE);
        int pendingByte = -1;
        // Probe before reserving credit so an empty body can terminate even
        // when the peer's stream window is zero.
        boolean probeForEnd = true;

        try (Arena probeArena = Arena.ofConfined()) {
            MemorySegment probe = probeArena.allocate(1);
            while (!flowControl.cancelled() && !body.isClosed()) {
                if (probeForEnd) {
                    int produced = body.read(probe);
                    if (produced < 0) {
                        return sendEmptyEndStream(streamId, flowControl);
                    }
                    pendingByte = probe.get(
                        ValueLayout.JAVA_BYTE, 0) & 0xff;
                    probeForEnd = false;
                }

                int reserved = flowControl.reserve(maximumPayload);
                if (reserved <= 0) {
                    return false;
                }
                long result = sendUnknownStreamingDataFrame(
                    streamId,
                    body,
                    reserved,
                    pendingByte,
                    flowControl
                );
                pendingByte = -1;
                if (result == STREAM_FAILURE) {
                    return false;
                }

                int produced = (int) result;
                boolean ended = (result & STREAM_EOF) != 0;
                queuedBytes += Http2Frames.HEADER_SIZE + produced;
                if (ended) {
                    return true;
                }
                probeForEnd = true;

                if (queuedBytes >= STREAM_QUEUE_FLUSH_BYTES) {
                    if (!writer.awaitDrained()) {
                        return false;
                    }
                    queuedBytes = 0;
                }
            }
            return false;
        }
    }

    private long sendUnknownStreamingDataFrame(
        int streamId,
        StreamingBody body,
        int reserved,
        int pendingByte,
        FlowControl flowControl
    ) {
        UringEventLoop loop = writer.eventLoop();
        int egressId = loop.acquireEgressBuffer();
        if (egressId >= 0) {
            MemorySegment output = loop.getEgressBufferSegment(egressId);
            long result = fillUnknownStreamingBody(
                body,
                output.asSlice(Http2Frames.HEADER_SIZE, reserved),
                pendingByte
            );
            int produced = (int) result;
            int unused = reserved - produced;
            if (unused != 0) {
                flowControl.refund(unused);
            }
            if (flowControl.cancelled() || body.isClosed()) {
                if (produced != 0) {
                    flowControl.refund(produced);
                }
                loop.releaseEgressBuffer(egressId);
                return STREAM_FAILURE;
            }
            int flags = (result & STREAM_EOF) != 0
                ? Http2Frames.FLAG_END_STREAM
                : 0;
            Http2Frames.writeHeader(
                output, 0, produced, Http2Frames.DATA, flags, streamId);
            if (!writer.enqueue(
                    egressId, Http2Frames.HEADER_SIZE + produced)) {
                if (produced != 0) {
                    flowControl.refund(produced);
                }
                return STREAM_FAILURE;
            }
            return result;
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(
                Http2Frames.HEADER_SIZE + reserved);
            long result = fillUnknownStreamingBody(
                body,
                output.asSlice(Http2Frames.HEADER_SIZE, reserved),
                pendingByte
            );
            int produced = (int) result;
            int unused = reserved - produced;
            if (unused != 0) {
                flowControl.refund(unused);
            }
            if (flowControl.cancelled() || body.isClosed()) {
                if (produced != 0) {
                    flowControl.refund(produced);
                }
                return STREAM_FAILURE;
            }
            int flags = (result & STREAM_EOF) != 0
                ? Http2Frames.FLAG_END_STREAM
                : 0;
            Http2Frames.writeHeader(
                output, 0, produced, Http2Frames.DATA, flags, streamId);
            if (writer.writeFully(
                    output, Http2Frames.HEADER_SIZE + produced) <= 0) {
                if (produced != 0) {
                    flowControl.refund(produced);
                }
                return STREAM_FAILURE;
            }
            return result;
        }
    }

    private static long fillUnknownStreamingBody(
        StreamingBody body,
        MemorySegment destination,
        int pendingByte
    ) {
        int offset = 0;
        int length = Math.toIntExact(destination.byteSize());
        if (pendingByte >= 0) {
            destination.set(
                ValueLayout.JAVA_BYTE,
                0,
                (byte) pendingByte
            );
            offset = 1;
        }
        while (offset < length) {
            int produced = body.read(destination.asSlice(offset));
            if (produced < 0) {
                return STREAM_EOF | (offset & 0xffff_ffffL);
            }
            offset += produced;
        }
        return offset & 0xffff_ffffL;
    }

    private boolean sendEmptyEndStream(
        int streamId,
        FlowControl flowControl
    ) {
        if (flowControl.cancelled()) {
            return false;
        }
        UringEventLoop loop = writer.eventLoop();
        int egressId = loop.acquireEgressBuffer();
        if (egressId >= 0) {
            MemorySegment output = loop.getEgressBufferSegment(egressId);
            Http2Frames.writeHeader(
                output,
                0,
                0,
                Http2Frames.DATA,
                Http2Frames.FLAG_END_STREAM,
                streamId
            );
            return writer.enqueue(egressId, Http2Frames.HEADER_SIZE);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(Http2Frames.HEADER_SIZE);
            Http2Frames.writeHeader(
                output,
                0,
                0,
                Http2Frames.DATA,
                Http2Frames.FLAG_END_STREAM,
                streamId
            );
            return writer.writeFully(output, Http2Frames.HEADER_SIZE) > 0;
        }
    }

    private boolean sendStreamingHeaders(
        int streamId,
        Response response,
        int bodyLength
    ) {
        UringEventLoop loop = writer.eventLoop();
        int egressId = loop.acquireEgressBuffer();
        if (egressId >= 0) {
            MemorySegment output = loop.getEgressBufferSegment(egressId);
            int headerBlockLength = HpackEncoder.writeResponseHeaders(
                output,
                Http2Frames.HEADER_SIZE,
                response.statusCode(),
                response.contentType(),
                bodyLength
            );
            int flags = Http2Frames.FLAG_END_HEADERS;
            if (bodyLength == 0) {
                flags |= Http2Frames.FLAG_END_STREAM;
            }
            Http2Frames.writeHeader(
                output, 0, headerBlockLength, Http2Frames.HEADERS,
                flags, streamId);
            return writer.enqueue(
                egressId, Http2Frames.HEADER_SIZE + headerBlockLength);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(512);
            int headerBlockLength = HpackEncoder.writeResponseHeaders(
                output,
                Http2Frames.HEADER_SIZE,
                response.statusCode(),
                response.contentType(),
                bodyLength
            );
            int flags = Http2Frames.FLAG_END_HEADERS;
            if (bodyLength == 0) {
                flags |= Http2Frames.FLAG_END_STREAM;
            }
            Http2Frames.writeHeader(
                output, 0, headerBlockLength, Http2Frames.HEADERS,
                flags, streamId);
            return writer.writeFully(
                output, Http2Frames.HEADER_SIZE + headerBlockLength) > 0;
        }
    }

    private boolean sendStreamingDataFrame(
        int streamId,
        StreamingBody body,
        int chunkLength,
        int flags
    ) {
        UringEventLoop loop = writer.eventLoop();
        int egressId = loop.acquireEgressBuffer();
        if (egressId >= 0) {
            MemorySegment output = loop.getEgressBufferSegment(egressId);
            Http2Frames.writeHeader(
                output, 0, chunkLength, Http2Frames.DATA, flags, streamId);
            if (!fillStreamingBody(
                    body,
                    output.asSlice(Http2Frames.HEADER_SIZE, chunkLength))) {
                loop.releaseEgressBuffer(egressId);
                return false;
            }
            return writer.enqueue(
                egressId, Http2Frames.HEADER_SIZE + chunkLength);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(
                Http2Frames.HEADER_SIZE + chunkLength);
            Http2Frames.writeHeader(
                output, 0, chunkLength, Http2Frames.DATA, flags, streamId);
            if (!fillStreamingBody(
                    body,
                    output.asSlice(Http2Frames.HEADER_SIZE, chunkLength))) {
                return false;
            }
            return writer.writeFully(
                output, Http2Frames.HEADER_SIZE + chunkLength) > 0;
        }
    }

    private static boolean fillStreamingBody(
        StreamingBody body,
        MemorySegment destination
    ) {
        int offset = 0;
        int length = Math.toIntExact(destination.byteSize());
        while (offset < length) {
            int produced = body.read(destination.asSlice(offset));
            if (produced <= 0) {
                return false;
            }
            offset += produced;
        }
        return true;
    }

    private boolean sendStaticFrames(
            int streamId, Response response, StaticBody body,
            FlowControl flowControl) {
        int bodyLength = body.length();
        int chunkLength = 0;
        if (bodyLength != 0) {
            int desired = Math.min(bodyLength, peerMaxFrameSize);
            chunkLength = flowControl.reserve(desired);
            if (chunkLength <= 0) {
                return false;
            }
        }

        UringEventLoop loop = writer.eventLoop();
        int egressId = loop.acquireEgressBuffer();
        if (egressId < 0) {
            return sendStaticHeaderFallback(
                streamId, response, body, chunkLength, flowControl);
        }

        MemorySegment output = loop.getEgressBufferSegment(egressId);
        int headerBlockLength = HpackEncoder.writeResponseHeaders(
            output,
            Http2Frames.HEADER_SIZE,
            response.statusCode(),
            response.contentType(),
            bodyLength
        );
        int flags = Http2Frames.FLAG_END_HEADERS;
        if (bodyLength == 0) {
            flags |= Http2Frames.FLAG_END_STREAM;
        }
        Http2Frames.writeHeader(
            output, 0, headerBlockLength, Http2Frames.HEADERS, flags, streamId);
        int outputLength = Http2Frames.HEADER_SIZE + headerBlockLength;
        int bodyOffset = 0;

        if (chunkLength != 0) {
            int dataFlags = chunkLength == bodyLength
                ? Http2Frames.FLAG_END_STREAM
                : 0;
            Http2Frames.writeHeader(
                output,
                outputLength,
                chunkLength,
                Http2Frames.DATA,
                dataFlags,
                streamId
            );
            outputLength += Http2Frames.HEADER_SIZE;
        }

        if (!writer.enqueue(egressId, outputLength)) {
            return false;
        }
        if (chunkLength != 0) {
            if (!writer.enqueueBorrowed(
                    body.segment(), bodyOffset, chunkLength)) {
                return false;
            }
            bodyOffset += chunkLength;
        }
        return sendRemainingStatic(
            streamId, body, bodyOffset, flowControl);
    }

    private boolean sendRemainingStatic(
            int streamId, StaticBody body, int bodyOffset,
            FlowControl flowControl) {
        int bodyLength = body.length();
        while (bodyOffset < bodyLength) {
            int desired = Math.min(
                peerMaxFrameSize, bodyLength - bodyOffset);
            int chunkLength = flowControl.reserve(desired);
            if (chunkLength <= 0) {
                return false;
            }
            int flags = bodyOffset + chunkLength == bodyLength
                ? Http2Frames.FLAG_END_STREAM
                : 0;
            if (!sendStaticDataFrame(
                    streamId, body, bodyOffset, chunkLength, flags)) {
                return false;
            }
            bodyOffset += chunkLength;
        }
        return true;
    }

    private boolean sendStaticHeaderFallback(
            int streamId, Response response, StaticBody body,
            int firstChunkLength,
            FlowControl flowControl) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(512);
            int headerBlockLength = HpackEncoder.writeResponseHeaders(
                output,
                Http2Frames.HEADER_SIZE,
                response.statusCode(),
                response.contentType(),
                body.length()
            );
            int flags = Http2Frames.FLAG_END_HEADERS;
            if (body.length() == 0) {
                flags |= Http2Frames.FLAG_END_STREAM;
            }
            Http2Frames.writeHeader(
                output,
                0,
                headerBlockLength,
                Http2Frames.HEADERS,
                flags,
                streamId
            );
            if (writer.writeFully(
                    output,
                    Http2Frames.HEADER_SIZE + headerBlockLength) <= 0) {
                return false;
            }
        }
        if (body.length() == 0) {
            return true;
        }
        int bodyOffset = 0;
        if (firstChunkLength != 0) {
            int flags = firstChunkLength == body.length()
                ? Http2Frames.FLAG_END_STREAM
                : 0;
            if (!sendStaticDataFrame(
                    streamId, body, 0, firstChunkLength, flags)) {
                return false;
            }
            bodyOffset = firstChunkLength;
        }
        return sendRemainingStatic(
            streamId, body, bodyOffset, flowControl);
    }

    private boolean sendStaticDataFrame(
            int streamId, StaticBody body, int bodyOffset,
            int chunkLength, int flags) {
        UringEventLoop loop = writer.eventLoop();
        int egressId = loop.acquireEgressBuffer();
        if (egressId < 0) {
            return sendStaticFrameFallback(
                streamId, body, bodyOffset, chunkLength, flags);
        }

        MemorySegment output = loop.getEgressBufferSegment(egressId);
        Http2Frames.writeHeader(
            output,
            0,
            chunkLength,
            Http2Frames.DATA,
            flags,
            streamId
        );
        if (!writer.enqueue(egressId, Http2Frames.HEADER_SIZE)) {
            return false;
        }
        return writer.enqueueBorrowed(
            body.segment(), bodyOffset, chunkLength);
    }

    private boolean sendStaticFrameFallback(
            int streamId, StaticBody body, int bodyOffset,
            int chunkLength, int flags) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(
                Http2Frames.HEADER_SIZE + chunkLength);
            Http2Frames.writeHeader(
                output,
                0,
                chunkLength,
                Http2Frames.DATA,
                flags,
                streamId
            );
            MemorySegment.copy(
                body.segment(),
                bodyOffset,
                output,
                Http2Frames.HEADER_SIZE,
                chunkLength
            );
            return writer.writeFully(
                output,
                Http2Frames.HEADER_SIZE + chunkLength) > 0;
        }
    }

    private boolean sendRecord(int streamId, Response response, Record record,
                               FlowControl flowControl) {
        UringEventLoop loop = writer.eventLoop();
        String contentType = response.contentType();
        int egressId = contentType == null
            || contentType.length() <= MAX_INLINE_CONTENT_TYPE_LENGTH
            ? loop.acquireEgressBuffer()
            : -1;

        if (egressId >= 0) {
            MemorySegment output = loop.getEgressBufferSegment(egressId);
            int bodyLength;
            try {
                bodyLength = JsonWriter.writeRecord(
                    output, RECORD_BODY_OFFSET, record);
            } catch (Throwable t) {
                loop.releaseEgressBuffer(egressId);
                if (!isBufferOverflow(t)) {
                    return sendSerializationError(
                        streamId, flowControl);
                }
                return sendRecordFallback(
                    streamId,
                    response,
                    record,
                    UringEventLoop.EGRESS_FRAME_SIZE << 1,
                    flowControl
                );
            }
            return sendInlineRecord(
                streamId, response, output, egressId, bodyLength,
                flowControl);
        }

        return sendRecordFallback(
            streamId, response, record, UringEventLoop.EGRESS_FRAME_SIZE,
            flowControl);
    }

    private boolean sendInlineRecord(int streamId, Response response,
                                     MemorySegment output, int egressId,
                                     int bodyLength,
                                     FlowControl flowControl) {
        int headerBlockLength = HpackEncoder.writeResponseHeaders(
            output,
            Http2Frames.HEADER_SIZE,
            response.statusCode(),
            response.contentType(),
            bodyLength
        );
        Http2Frames.writeHeader(
            output, 0, headerBlockLength, Http2Frames.HEADERS,
            Http2Frames.FLAG_END_HEADERS, streamId);
        int outputLength = Http2Frames.HEADER_SIZE + headerBlockLength;

        if (bodyLength == 0) {
            Http2Frames.writeHeader(
                output, 0, headerBlockLength, Http2Frames.HEADERS,
                Http2Frames.FLAG_END_HEADERS | Http2Frames.FLAG_END_STREAM,
                streamId);
            return writer.enqueue(egressId, outputLength);
        }

        int maximumPayload = Math.min(
            peerMaxFrameSize,
            UringEventLoop.EGRESS_FRAME_SIZE
                - outputLength
                - Http2Frames.HEADER_SIZE
        );
        int desired = Math.min(bodyLength, maximumPayload);
        int chunkLength = flowControl.reserve(desired);
        if (chunkLength <= 0) {
            writer.eventLoop().releaseEgressBuffer(egressId);
            return false;
        }

        int flags = chunkLength == bodyLength ? Http2Frames.FLAG_END_STREAM : 0;
        Http2Frames.writeHeader(
            output, outputLength, chunkLength, Http2Frames.DATA, flags, streamId);
        int dataOffset = outputLength + Http2Frames.HEADER_SIZE;

        if (chunkLength == bodyLength) {
            MemorySegment.copy(
                output, RECORD_BODY_OFFSET, output, dataOffset, chunkLength);
            return writer.enqueue(egressId, dataOffset + chunkLength);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment retainedBody = arena.allocate(bodyLength);
            MemorySegment.copy(
                output, RECORD_BODY_OFFSET, retainedBody, 0, bodyLength);
            MemorySegment.copy(
                retainedBody, 0, output, dataOffset, chunkLength);
            if (!writer.enqueue(egressId, dataOffset + chunkLength)) {
                return false;
            }
            return sendRemainingData(
                streamId, retainedBody, chunkLength, bodyLength,
                flowControl);
        }
    }

    private boolean sendRecordFallback(int streamId, Response response,
                                       Record record, int initialCapacity,
                                       FlowControl flowControl) {
        try (Arena arena = Arena.ofConfined()) {
            for (int capacity = initialCapacity;
                 capacity <= MAX_RECORD_BUFFER_SIZE;
                 capacity <<= 1) {
                MemorySegment bytes = arena.allocate(capacity);
                try {
                    int length = JsonWriter.writeRecord(
                        bytes, 0, record);
                    return sendFrames(
                        streamId, response, bytes, length, flowControl);
                } catch (Throwable t) {
                    if (!isBufferOverflow(t)) {
                        return sendSerializationError(
                            streamId, flowControl);
                    }
                }
            }
        } catch (Throwable ignored) {
            return sendSerializationError(streamId, flowControl);
        }
        return sendSerializationError(streamId, flowControl);
    }

    private boolean sendSerializationError(int streamId,
                                           FlowControl flowControl) {
        return send(
            streamId, Response.error("Response serialization failed"),
            flowControl);
    }

    private static boolean isBufferOverflow(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof IndexOutOfBoundsException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    static byte[] asciiBytes(String value) {
        VarHandle handle = STRING_VALUE_HANDLE;
        if (handle == null) {
            return null;
        }
        try {
            byte[] bytes = (byte[]) handle.get(value);
            if (bytes == null || bytes.length != value.length()) {
                return null;
            }
            int index = 0;
            int wordLimit = bytes.length & -Long.BYTES;
            while (index < wordLimit) {
                long word = (long) BYTE_ARRAY_LONG_HANDLE.get(
                    bytes, index);
                if ((word & ASCII_HIGH_BITS) != 0) {
                    return null;
                }
                index += Long.BYTES;
            }
            while (index < bytes.length) {
                if (bytes[index++] < 0) {
                    return null;
                }
            }
            return bytes;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean sendByteFrames(
            int streamId, Response response, byte[] body, int bodyLength,
            FlowControl flowControl) {
        int chunkLength = 0;
        if (bodyLength != 0) {
            int desired = Math.min(
                bodyLength,
                Math.min(peerMaxFrameSize, FIRST_BYTE_FRAME_PAYLOAD)
            );
            chunkLength = flowControl.reserve(desired);
            if (chunkLength <= 0) {
                return false;
            }
        }

        UringEventLoop loop = writer.eventLoop();
        int egressId = loop.acquireEgressBuffer();
        if (egressId < 0) {
            return sendByteHeaderFallback(
                streamId, response, body, bodyLength, chunkLength,
                flowControl);
        }

        MemorySegment output = loop.getEgressBufferSegment(egressId);
        int headerBlockLength = HpackEncoder.writeResponseHeaders(
            output,
            Http2Frames.HEADER_SIZE,
            response.statusCode(),
            response.contentType(),
            bodyLength
        );
        int flags = Http2Frames.FLAG_END_HEADERS;
        if (bodyLength == 0) {
            flags |= Http2Frames.FLAG_END_STREAM;
        }
        Http2Frames.writeHeader(
            output, 0, headerBlockLength, Http2Frames.HEADERS, flags, streamId);
        int outputLength = Http2Frames.HEADER_SIZE + headerBlockLength;
        int bodyOffset = 0;

        if (chunkLength != 0) {
            int available = UringEventLoop.EGRESS_FRAME_SIZE
                - outputLength
                - Http2Frames.HEADER_SIZE;
            if (chunkLength > available) {
                loop.releaseEgressBuffer(egressId);
                return sendByteHeaderFallback(
                    streamId, response, body, bodyLength, chunkLength,
                    flowControl);
            }
            int dataFlags = chunkLength == bodyLength
                ? Http2Frames.FLAG_END_STREAM
                : 0;
            Http2Frames.writeHeader(
                output, outputLength, chunkLength, Http2Frames.DATA,
                dataFlags, streamId);
            RawSegment.copy(
                body, 0,
                output.address() + outputLength + Http2Frames.HEADER_SIZE,
                chunkLength);
            outputLength += Http2Frames.HEADER_SIZE + chunkLength;
            bodyOffset = chunkLength;
        }

        if (!writer.enqueue(egressId, outputLength)) {
            return false;
        }
        return sendRemainingBytes(
            streamId, body, bodyOffset, bodyLength, flowControl);
    }

    private boolean sendRemainingBytes(
            int streamId, byte[] body, int bodyOffset, int bodyLength,
            FlowControl flowControl) {
        int maximumPayload = Math.min(
            peerMaxFrameSize,
            UringEventLoop.EGRESS_FRAME_SIZE - Http2Frames.HEADER_SIZE
        );
        while (bodyOffset < bodyLength) {
            int desired = Math.min(maximumPayload, bodyLength - bodyOffset);
            int chunkLength = flowControl.reserve(desired);
            if (chunkLength <= 0) {
                return false;
            }
            int flags = bodyOffset + chunkLength == bodyLength
                ? Http2Frames.FLAG_END_STREAM
                : 0;
            if (!sendByteDataFrame(
                    streamId, body, bodyOffset, chunkLength, flags)) {
                return false;
            }
            bodyOffset += chunkLength;
        }
        return true;
    }

    private boolean sendByteHeaderFallback(
            int streamId, Response response, byte[] body, int bodyLength,
            int firstChunkLength,
            FlowControl flowControl) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(512);
            int headerBlockLength = HpackEncoder.writeResponseHeaders(
                output,
                Http2Frames.HEADER_SIZE,
                response.statusCode(),
                response.contentType(),
                bodyLength
            );
            int flags = Http2Frames.FLAG_END_HEADERS;
            if (bodyLength == 0) {
                flags |= Http2Frames.FLAG_END_STREAM;
            }
            Http2Frames.writeHeader(
                output, 0, headerBlockLength, Http2Frames.HEADERS,
                flags, streamId);
            if (writer.writeFully(
                    output,
                    Http2Frames.HEADER_SIZE + headerBlockLength) <= 0) {
                return false;
            }
        }
        if (bodyLength == 0) {
            return true;
        }
        int bodyOffset = 0;
        if (firstChunkLength != 0) {
            int flags = firstChunkLength == bodyLength
                ? Http2Frames.FLAG_END_STREAM
                : 0;
            if (!sendByteDataFrame(
                    streamId, body, 0, firstChunkLength, flags)) {
                return false;
            }
            bodyOffset = firstChunkLength;
        }
        return sendRemainingBytes(
            streamId, body, bodyOffset, bodyLength, flowControl);
    }

    private boolean sendByteDataFrame(
            int streamId, byte[] body, int bodyOffset,
            int chunkLength, int flags) {
        UringEventLoop loop = writer.eventLoop();
        int egressId = loop.acquireEgressBuffer();
        if (egressId < 0) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment output = arena.allocate(
                    Http2Frames.HEADER_SIZE + chunkLength);
                Http2Frames.writeHeader(
                    output, 0, chunkLength, Http2Frames.DATA,
                    flags, streamId);
                RawSegment.copy(
                    body, bodyOffset,
                    output.address() + Http2Frames.HEADER_SIZE,
                    chunkLength);
                return writer.writeFully(
                    output,
                    Http2Frames.HEADER_SIZE + chunkLength) > 0;
            }
        }

        MemorySegment output = loop.getEgressBufferSegment(egressId);
        Http2Frames.writeHeader(
            output, 0, chunkLength, Http2Frames.DATA, flags, streamId);
        RawSegment.copy(
            body, bodyOffset,
            output.address() + Http2Frames.HEADER_SIZE,
            chunkLength);
        return writer.enqueue(
            egressId, Http2Frames.HEADER_SIZE + chunkLength);
    }

    private boolean sendFrames(int streamId, Response response,
                               MemorySegment body, int bodyLength,
                               FlowControl flowControl) {
        UringEventLoop loop = writer.eventLoop();
        int egressId = loop.acquireEgressBuffer();
        if (egressId < 0) {
            return sendHeaderFallback(
                streamId, response, body, bodyLength, flowControl);
        }

        MemorySegment output = loop.getEgressBufferSegment(egressId);
        int headerBlockLength = HpackEncoder.writeResponseHeaders(
            output,
            Http2Frames.HEADER_SIZE,
            response.statusCode(),
            response.contentType(),
            bodyLength
        );
        int flags = Http2Frames.FLAG_END_HEADERS;
        if (bodyLength == 0) {
            flags |= Http2Frames.FLAG_END_STREAM;
        }
        Http2Frames.writeHeader(
            output, 0, headerBlockLength, Http2Frames.HEADERS, flags, streamId);
        int outputLength = Http2Frames.HEADER_SIZE + headerBlockLength;
        int bodyOffset = 0;

        if (bodyLength != 0) {
            int available = UringEventLoop.EGRESS_FRAME_SIZE
                - outputLength
                - Http2Frames.HEADER_SIZE;
            int desired = Math.min(bodyLength, Math.min(peerMaxFrameSize, available));
            int chunkLength = flowControl.reserve(desired);
            if (chunkLength <= 0) {
                loop.releaseEgressBuffer(egressId);
                return false;
            }
            int dataFlags = chunkLength == bodyLength ? Http2Frames.FLAG_END_STREAM : 0;
            Http2Frames.writeHeader(
                output, outputLength, chunkLength, Http2Frames.DATA,
                dataFlags, streamId);
            MemorySegment.copy(
                body, 0, output, outputLength + Http2Frames.HEADER_SIZE, chunkLength);
            outputLength += Http2Frames.HEADER_SIZE + chunkLength;
            bodyOffset = chunkLength;
        }

        if (!writer.enqueue(egressId, outputLength)) {
            return false;
        }
        return sendRemainingData(
            streamId, body, bodyOffset, bodyLength, flowControl);
    }

    private boolean sendRemainingData(int streamId, MemorySegment body,
                                      int bodyOffset, int bodyLength,
                                      FlowControl flowControl) {
        UringEventLoop loop = writer.eventLoop();
        int maximumPayload = Math.min(
            peerMaxFrameSize,
            UringEventLoop.EGRESS_FRAME_SIZE - Http2Frames.HEADER_SIZE
        );
        while (bodyOffset < bodyLength) {
            int desired = Math.min(maximumPayload, bodyLength - bodyOffset);
            int chunkLength = flowControl.reserve(desired);
            if (chunkLength <= 0) {
                return false;
            }
            int flags = bodyOffset + chunkLength == bodyLength
                ? Http2Frames.FLAG_END_STREAM
                : 0;
            int egressId = loop.acquireEgressBuffer();
            if (egressId < 0) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment output = arena.allocate(
                        Http2Frames.HEADER_SIZE + chunkLength);
                    Http2Frames.writeHeader(
                        output, 0, chunkLength, Http2Frames.DATA, flags, streamId);
                    MemorySegment.copy(
                        body, bodyOffset, output, Http2Frames.HEADER_SIZE, chunkLength);
                    if (writer.writeFully(
                        output, Http2Frames.HEADER_SIZE + chunkLength) <= 0) {
                        return false;
                    }
                }
            } else {
                MemorySegment output = loop.getEgressBufferSegment(egressId);
                Http2Frames.writeHeader(
                    output, 0, chunkLength, Http2Frames.DATA, flags, streamId);
                MemorySegment.copy(
                    body, bodyOffset, output, Http2Frames.HEADER_SIZE, chunkLength);
                if (!writer.enqueue(
                    egressId, Http2Frames.HEADER_SIZE + chunkLength)) {
                    return false;
                }
            }
            bodyOffset += chunkLength;
        }
        return true;
    }

    private boolean sendHeaderFallback(int streamId, Response response,
                                       MemorySegment body, int bodyLength,
                                       FlowControl flowControl) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(512);
            int headerBlockLength = HpackEncoder.writeResponseHeaders(
                output,
                Http2Frames.HEADER_SIZE,
                response.statusCode(),
                response.contentType(),
                bodyLength
            );
            int flags = Http2Frames.FLAG_END_HEADERS;
            if (bodyLength == 0) {
                flags |= Http2Frames.FLAG_END_STREAM;
            }
            Http2Frames.writeHeader(
                output, 0, headerBlockLength, Http2Frames.HEADERS, flags, streamId);
            if (writer.writeFully(
                output, Http2Frames.HEADER_SIZE + headerBlockLength) <= 0) {
                return false;
            }
        }
        return bodyLength == 0
            || sendRemainingData(
                streamId, body, 0, bodyLength, flowControl);
    }
}
