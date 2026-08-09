// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.PreparedInvocation;
import dev.cardigan.http.Response;
import dev.cardigan.http.Router;
import dev.cardigan.http.StreamingBody;
import dev.cardigan.http2.HpackDecoder;
import dev.cardigan.http2.HpackFields;
import dev.cardigan.http2.Http2Frames;
import dev.cardigan.http2.Http2RequestParser;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.concurrent.locks.LockSupport;

/**
 * Connection-scoped HTTP/2 frame pump. Stream dispatch is deliberately kept
 * separate from the connection control plane.
 */
final class Http2Connection {
    private static final int MAX_CONCURRENT_STREAMS = Math.max(
        1, Integer.getInteger("cardigan.http2.max.concurrent.streams", 128));
    private static final int MAX_HEADER_LIST_SIZE = Math.max(
        1024, Integer.getInteger("cardigan.http2.max.header.list.size", 16 * 1024));
    private static final int MAX_COMPRESSED_HEADER_BLOCK = Math.max(
        MAX_HEADER_LIST_SIZE,
        Integer.getInteger("cardigan.http2.max.compressed.header.block", 64 * 1024));
    private static final int MAX_RETAINED_PENDING_CAPACITY = Math.max(
        UringEventLoop.BUFFER_SIZE,
        Integer.getInteger("cardigan.http2.max.retained.request.size", 64 * 1024));
    private static final int INITIAL_WINDOW_SIZE = 65_535;
    private static final int MAX_STREAMING_BODIES = Math.max(
        1,
        Integer.getInteger(
            "cardigan.http2.max.streaming.bodies.per.connection", 16));
    private static final int RECEIVE_WINDOW_UPDATE_THRESHOLD =
        (INITIAL_WINDOW_SIZE + 1) >>> 1;
    private static final VarHandle IN_FLIGHT;
    private static final VarHandle FREE_TASK_COUNT;
    private static final VarHandle TASK_ACTIVE;
    private static final VarHandle TASK_CANCELLED;
    private static final VarHandle TASK_SEND_WAITER;
    private static final VarHandle TASK_RESPONSE_BODY;
    private static final VarHandle TASK_ARRAY =
        MethodHandles.arrayElementVarHandle(Http2Task[].class);

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            IN_FLIGHT = lookup.findVarHandle(Http2Connection.class, "inFlight", int.class);
            FREE_TASK_COUNT =
                lookup.findVarHandle(Http2Connection.class, "freeTaskCount", int.class);
            TASK_ACTIVE =
                lookup.findVarHandle(Http2Task.class, "active", boolean.class);
            TASK_CANCELLED =
                lookup.findVarHandle(Http2Task.class, "cancelled", boolean.class);
            TASK_SEND_WAITER =
                lookup.findVarHandle(Http2Task.class, "sendWaiter", Thread.class);
            TASK_RESPONSE_BODY = lookup.findVarHandle(
                Http2Task.class, "responseBody", StreamingBody.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final ValueLayout.OfShort SHORT_BE =
        ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfInt INT_BE =
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong LONG_BE =
        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private final InboundChunkStream inbound;
    private final ConnectionWriter writer;
    private final Router router;
    private final ExchangeExecutor executor;
    private final Http2ResponseWriter responseWriter;
    private final HpackDecoder hpackDecoder = new HpackDecoder(4096, MAX_HEADER_LIST_SIZE);
    private final HpackFields hpackFields = new HpackFields(64);
    private final Http2RequestParser requestParser =
        new Http2RequestParser(CardiganServer.MAX_REQUEST_SIZE);
    private final Runnable requestMaterializer = requestParser::materializeHeaders;
    private final HttpRequest request = new HttpRequest();
    private final Http2Task[] freeTasks = new Http2Task[MAX_CONCURRENT_STREAMS];
    private final Http2Task[] allTasks = new Http2Task[MAX_CONCURRENT_STREAMS];
    private final PendingStream[] pendingStreams = new PendingStream[MAX_CONCURRENT_STREAMS];
    private final PendingStream[] freePendingStreams =
        new PendingStream[MAX_CONCURRENT_STREAMS];
    private final PendingStream[] allPendingStreams =
        new PendingStream[MAX_CONCURRENT_STREAMS];
    private InboundChunk currentChunk;
    private int currentOffset;
    private int peerMaxFrameSize = Http2Frames.DEFAULT_MAX_FRAME_SIZE;
    private int peerInitialWindowSize = INITIAL_WINDOW_SIZE;
    private int connectionSendWindow = INITIAL_WINDOW_SIZE;
    private int connectionReceiveWindow = INITIAL_WINDOW_SIZE;
    private int connectionConsumedBytes;
    private int lastClientStreamId;
    private int lastAdmittedStreamId;
    private int drainLastStreamId;
    private int continuationStreamId;
    private int continuationFlags;
    private int continuationLength;
    private boolean continuationTrailers;
    private int pendingStreamCount;
    private int decodedContentLength = -1;
    private int taskCount;
    private int freeTaskCount;
    private int pendingSlotCount;
    private int freePendingStreamCount;
    private int streamingBodyCount;
    private int inFlight;
    private boolean receivedInitialSettings;
    private volatile boolean open = true;
    private volatile boolean failed;
    private volatile boolean draining;
    private volatile Thread waiter;
    private boolean drainInputScheduled;
    private Runnable drainInputShutdown;
    private MemorySegment decodedHeaders;
    private MemorySegment continuationHeaders;

    Http2Connection(InboundChunkStream inbound, ConnectionWriter writer,
                    InboundChunk initialChunk, int initialOffset,
                    Router router, ExchangeExecutor executor) {
        this.inbound = inbound;
        this.writer = writer;
        this.router = router;
        this.executor = executor;
        this.responseWriter = new Http2ResponseWriter(writer);
        this.currentChunk = initialChunk;
        this.currentOffset = initialOffset;
    }

    void run() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment headerScratch = arena.allocate(Http2Frames.HEADER_SIZE);
            MemorySegment payloadScratch = arena.allocate(Http2Frames.DEFAULT_MAX_FRAME_SIZE);
            decodedHeaders = arena.allocate(MAX_HEADER_LIST_SIZE);
            requestParser.bind(decodedHeaders);
            continuationHeaders = arena.allocate(MAX_COMPRESSED_HEADER_BLOCK);

            if (!sendSettings()) {
                return;
            }

            while (open) {
                MemorySegment header;
                long headerOffset;
                if (remaining() >= Http2Frames.HEADER_SIZE) {
                    header = currentChunk.segment();
                    headerOffset = currentOffset;
                    currentOffset += Http2Frames.HEADER_SIZE;
                } else {
                    if (!readFully(headerScratch, 0, Http2Frames.HEADER_SIZE)) {
                        break;
                    }
                    header = headerScratch;
                    headerOffset = 0;
                }

                long headerWord = Http2Frames.readHeaderWord(header, headerOffset);
                int payloadLength = Http2Frames.payloadLength(headerWord);
                int type = Http2Frames.type(headerWord);
                int flags = Http2Frames.flags(headerWord);
                int streamId = Http2Frames.streamId(header, headerOffset, headerWord);

                if (payloadLength > Http2Frames.DEFAULT_MAX_FRAME_SIZE) {
                    connectionError(Http2Frames.FRAME_SIZE_ERROR);
                    break;
                }
                if (!receivedInitialSettings
                    && (type != Http2Frames.SETTINGS
                        || streamId != 0
                        || (flags & Http2Frames.FLAG_ACK) != 0)) {
                    connectionError(Http2Frames.PROTOCOL_ERROR);
                    break;
                }

                if (payloadLength == 0) {
                    if (!processFrame(type, flags, streamId, MemorySegment.NULL, 0, 0)) {
                        break;
                    }
                    releaseExhaustedChunk();
                    continue;
                }

                if (remaining() >= payloadLength) {
                    MemorySegment payload = currentChunk.segment();
                    int payloadOffset = currentOffset;
                    if (!processFrame(type, flags, streamId,
                                      payload, payloadOffset, payloadLength)) {
                        break;
                    }
                    currentOffset += payloadLength;
                    releaseExhaustedChunk();
                } else {
                    if (!readFully(payloadScratch, 0, payloadLength)) {
                        break;
                    }
                    if (!processFrame(type, flags, streamId,
                                      payloadScratch, 0, payloadLength)) {
                        break;
                    }
                }
            }
            cancelAllStreams();
            awaitExchanges();
        } finally {
            decodedHeaders = null;
            continuationHeaders = null;
            closePendingStreams();
            disposePendingStreams();
            releaseCurrentChunk();
        }
    }

    int peerMaxFrameSize() {
        return peerMaxFrameSize;
    }

    void beginDrain(Runnable inputShutdown) {
        if (draining || failed) {
            return;
        }
        drainInputShutdown = inputShutdown;
        drainLastStreamId = lastAdmittedStreamId;
        draining = true;
        closePendingStreams();
        if (!sendControl(
                Http2Frames.GOAWAY, 0, drainLastStreamId,
                0, Http2Frames.NO_ERROR)) {
            forceClose();
            return;
        }
        finishDrainIfIdle();
    }

    void forceClose() {
        failed = true;
        open = false;
        cancelAllStreams();
        signalAllSendWaiters();
        scheduleDrainInputShutdown();
    }

    private boolean processFrame(int type, int flags, int streamId,
                                 MemorySegment payload, int payloadOffset, int payloadLength) {
        if (continuationStreamId != 0 && type != Http2Frames.CONTINUATION) {
            return connectionError(Http2Frames.PROTOCOL_ERROR);
        }
        return switch (type) {
            case Http2Frames.SETTINGS ->
                processSettings(flags, streamId, payload, payloadOffset, payloadLength);
            case Http2Frames.PING ->
                processPing(flags, streamId, payload, payloadOffset, payloadLength);
            case Http2Frames.WINDOW_UPDATE ->
                processWindowUpdate(streamId, payload, payloadOffset, payloadLength);
            case Http2Frames.GOAWAY ->
                processGoAway(streamId, payloadLength);
            case Http2Frames.RST_STREAM ->
                processRstStream(streamId, payloadLength);
            case Http2Frames.PRIORITY ->
                processPriority(streamId, payload, payloadOffset, payloadLength);
            case Http2Frames.PUSH_PROMISE ->
                connectionError(Http2Frames.PROTOCOL_ERROR);
            case Http2Frames.CONTINUATION ->
                processContinuation(flags, streamId, payload, payloadOffset, payloadLength);
            case Http2Frames.HEADERS ->
                processHeaders(flags, streamId, payload, payloadOffset, payloadLength);
            case Http2Frames.DATA ->
                processData(flags, streamId, payload, payloadOffset, payloadLength);
            default -> true;
        };
    }

    private boolean processSettings(int flags, int streamId, MemorySegment payload,
                                    int payloadOffset, int payloadLength) {
        if (streamId != 0) {
            return connectionError(Http2Frames.PROTOCOL_ERROR);
        }
        if ((flags & Http2Frames.FLAG_ACK) != 0) {
            if (payloadLength != 0) {
                return connectionError(Http2Frames.FRAME_SIZE_ERROR);
            }
            receivedInitialSettings = true;
            return true;
        }
        if (payloadLength % 6 != 0) {
            return connectionError(Http2Frames.FRAME_SIZE_ERROR);
        }

        for (int offset = 0; offset < payloadLength; offset += 6) {
            int identifier = payload.get(SHORT_BE, (long) payloadOffset + offset) & 0xffff;
            int value = payload.get(INT_BE, (long) payloadOffset + offset + 2);
            switch (identifier) {
                case Http2Frames.SETTINGS_ENABLE_PUSH -> {
                    if (value != 0 && value != 1) {
                        return connectionError(Http2Frames.PROTOCOL_ERROR);
                    }
                }
                case Http2Frames.SETTINGS_INITIAL_WINDOW_SIZE -> {
                    if (value < 0) {
                        return connectionError(Http2Frames.FLOW_CONTROL_ERROR);
                    }
                    int delta = value - peerInitialWindowSize;
                    for (int i = 0; i < taskCount; i++) {
                        Http2Task task = allTasks[i];
                        if (taskActive(task)) {
                            long updated = (long) task.sendWindow + delta;
                            if (updated > Integer.MAX_VALUE || updated < Integer.MIN_VALUE) {
                                return connectionError(Http2Frames.FLOW_CONTROL_ERROR);
                            }
                            task.sendWindow = (int) updated;
                            signalSendWaiter(task);
                        }
                    }
                    for (int i = 0; i < pendingStreamCount; i++) {
                        PendingStream pending = pendingStreams[i];
                        long updated = (long) pending.sendWindow + delta;
                        if (updated > Integer.MAX_VALUE || updated < Integer.MIN_VALUE) {
                            return connectionError(Http2Frames.FLOW_CONTROL_ERROR);
                        }
                        pending.sendWindow = (int) updated;
                    }
                    peerInitialWindowSize = value;
                }
                case Http2Frames.SETTINGS_MAX_FRAME_SIZE -> {
                    if (value < Http2Frames.DEFAULT_MAX_FRAME_SIZE
                        || value > Http2Frames.MAX_FRAME_SIZE) {
                        return connectionError(Http2Frames.PROTOCOL_ERROR);
                    }
                    peerMaxFrameSize = value;
                    responseWriter.peerMaxFrameSize(value);
                }
                default -> {
                    // Unknown settings and settings not used by the decoder are ignored.
                }
            }
        }
        receivedInitialSettings = true;
        return sendSettingsAck();
    }

    private boolean processPing(int flags, int streamId, MemorySegment payload,
                                int payloadOffset, int payloadLength) {
        if (streamId != 0) {
            return connectionError(Http2Frames.PROTOCOL_ERROR);
        }
        if (payloadLength != 8) {
            return connectionError(Http2Frames.FRAME_SIZE_ERROR);
        }
        if ((flags & Http2Frames.FLAG_ACK) != 0) {
            return true;
        }
        return sendPing(payload.get(LONG_BE, payloadOffset));
    }

    private boolean processWindowUpdate(int streamId, MemorySegment payload,
                                        int payloadOffset, int payloadLength) {
        if (payloadLength != 4) {
            return connectionError(Http2Frames.FRAME_SIZE_ERROR);
        }
        int increment = payload.get(INT_BE, payloadOffset) & Http2Frames.MAX_STREAM_ID;
        if (increment == 0) {
            if (streamId == 0) {
                return connectionError(Http2Frames.PROTOCOL_ERROR);
            }
            return sendRstStream(streamId, Http2Frames.PROTOCOL_ERROR);
        }

        if (streamId == 0) {
            long updated = (long) connectionSendWindow + increment;
            if (updated > Integer.MAX_VALUE) {
                return connectionError(Http2Frames.FLOW_CONTROL_ERROR);
            }
            connectionSendWindow = (int) updated;
            signalAllSendWaiters();
            return true;
        }

        Http2Task task = findActiveTask(streamId);
        if (task != null) {
            long updated = (long) task.sendWindow + increment;
            if (updated > Integer.MAX_VALUE) {
                return sendRstStream(streamId, Http2Frames.FLOW_CONTROL_ERROR);
            }
            task.sendWindow = (int) updated;
            signalSendWaiter(task);
            return true;
        }
        PendingStream pending = findPendingStream(streamId);
        if (pending != null) {
            long updated = (long) pending.sendWindow + increment;
            if (updated > Integer.MAX_VALUE) {
                removePendingStream(pending);
                return sendRstStream(streamId, Http2Frames.FLOW_CONTROL_ERROR);
            }
            pending.sendWindow = (int) updated;
            return true;
        }
        if (streamId > lastClientStreamId) {
            return connectionError(Http2Frames.PROTOCOL_ERROR);
        }
        return true;
    }

    private boolean processGoAway(int streamId, int payloadLength) {
        if (streamId != 0) {
            return connectionError(Http2Frames.PROTOCOL_ERROR);
        }
        if (payloadLength < 8) {
            return connectionError(Http2Frames.FRAME_SIZE_ERROR);
        }
        // A peer GOAWAY starts graceful shutdown; it does not make subsequent
        // connection-level frames invalid. In particular, PING still requires
        // an acknowledgement while this connection remains open.
        return true;
    }

    private boolean validateStreamFrame(int streamId, int payloadLength, int requiredLength) {
        if (streamId == 0) {
            return connectionError(Http2Frames.PROTOCOL_ERROR);
        }
        if (payloadLength != requiredLength) {
            return connectionError(Http2Frames.FRAME_SIZE_ERROR);
        }
        return true;
    }

    private boolean processRstStream(int streamId, int payloadLength) {
        if (!validateStreamFrame(streamId, payloadLength, 4)) {
            return false;
        }
        if (streamId > lastClientStreamId) {
            return connectionError(Http2Frames.PROTOCOL_ERROR);
        }
        PendingStream pending = findPendingStream(streamId);
        if (pending != null) {
            removePendingStream(pending);
        }
        Http2Task task = findActiveTask(streamId);
        if (task != null) {
            setTaskCancelled(task, true);
            task.invocation.cancel();
            closeTaskResponseBody(task);
            signalSendWaiter(task);
        }
        return true;
    }

    private boolean processPriority(int streamId, MemorySegment payload,
                                    int payloadOffset, int payloadLength) {
        if (!validateStreamFrame(streamId, payloadLength, 5)) {
            return false;
        }
        int dependency =
            payload.get(INT_BE, payloadOffset) & Http2Frames.MAX_STREAM_ID;
        if (dependency == streamId) {
            return sendRstStream(streamId, Http2Frames.PROTOCOL_ERROR);
        }
        return true;
    }

    private boolean processHeaders(int flags, int streamId, MemorySegment payload,
                                   int payloadOffset, int payloadLength) {
        if (streamId == 0 || (streamId & 1) == 0) {
            return connectionError(Http2Frames.PROTOCOL_ERROR);
        }
        boolean trailers = false;
        if (streamId <= lastClientStreamId) {
            trailers = findPendingStream(streamId) != null;
            if (!trailers) {
                return connectionError(Http2Frames.PROTOCOL_ERROR);
            }
        } else {
            lastClientStreamId = streamId;
        }

        int blockOffset = payloadOffset;
        int blockLength = payloadLength;
        if ((flags & Http2Frames.FLAG_PADDED) != 0) {
            if (blockLength == 0) {
                return connectionError(Http2Frames.PROTOCOL_ERROR);
            }
            int padding = payload.get(ValueLayout.JAVA_BYTE, blockOffset) & 0xff;
            blockOffset++;
            blockLength--;
            if (padding > blockLength) {
                return connectionError(Http2Frames.PROTOCOL_ERROR);
            }
            blockLength -= padding;
        }
        if ((flags & Http2Frames.FLAG_PRIORITY) != 0) {
            if (blockLength < 5) {
                return connectionError(Http2Frames.FRAME_SIZE_ERROR);
            }
            int dependency = payload.get(INT_BE, blockOffset) & Http2Frames.MAX_STREAM_ID;
            if (dependency == streamId) {
                return sendRstStream(streamId, Http2Frames.PROTOCOL_ERROR);
            }
            blockOffset += 5;
            blockLength -= 5;
        }

        if ((flags & Http2Frames.FLAG_END_HEADERS) == 0) {
            if (blockLength > MAX_COMPRESSED_HEADER_BLOCK) {
                return connectionError(Http2Frames.ENHANCE_YOUR_CALM);
            }
            MemorySegment.copy(
                payload, blockOffset, continuationHeaders, 0, blockLength);
            continuationStreamId = streamId;
            continuationFlags = flags;
            continuationLength = blockLength;
            continuationTrailers = trailers;
            return true;
        }
        return processHeaderBlock(
            streamId, flags, payload, blockOffset, blockLength, trailers);
    }

    private boolean processContinuation(int flags, int streamId, MemorySegment payload,
                                        int payloadOffset, int payloadLength) {
        if (continuationStreamId == 0 || streamId != continuationStreamId) {
            return connectionError(Http2Frames.PROTOCOL_ERROR);
        }
        if (payloadLength > MAX_COMPRESSED_HEADER_BLOCK - continuationLength) {
            return connectionError(Http2Frames.ENHANCE_YOUR_CALM);
        }
        MemorySegment.copy(
            payload, payloadOffset, continuationHeaders, continuationLength, payloadLength);
        continuationLength += payloadLength;
        if ((flags & Http2Frames.FLAG_END_HEADERS) == 0) {
            return true;
        }

        int completedStreamId = continuationStreamId;
        int completedFlags = continuationFlags | Http2Frames.FLAG_END_HEADERS;
        int completedLength = continuationLength;
        boolean completedTrailers = continuationTrailers;
        continuationStreamId = 0;
        continuationFlags = 0;
        continuationLength = 0;
        continuationTrailers = false;
        return processHeaderBlock(
            completedStreamId, completedFlags, continuationHeaders, 0,
            completedLength, completedTrailers);
    }

    private boolean processHeaderBlock(int streamId, int flags, MemorySegment block,
                                       int blockOffset, int blockLength,
                                       boolean trailers) {
        if (trailers) {
            PendingStream pending = findPendingStream(streamId);
            return processTrailerBlock(
                pending, flags, block, blockOffset, blockLength);
        }
        boolean headerOnly = (flags & Http2Frames.FLAG_END_STREAM) != 0;
        int decodedLength = headerOnly
            ? hpackDecoder.decodeRequest(
                block, blockOffset, blockLength, decodedHeaders, hpackFields)
            : hpackDecoder.decodeRequestEager(
                block, blockOffset, blockLength, decodedHeaders, hpackFields);
        if (decodedLength == HpackDecoder.ERROR_COMPRESSION) {
            return connectionError(Http2Frames.COMPRESSION_ERROR);
        }
        if (decodedLength < 0) {
            return connectionError(Http2Frames.ENHANCE_YOUR_CALM);
        }
        if (!prepareRequest(decodedLength, !headerOnly)) {
            return sendRstStream(streamId, Http2Frames.PROTOCOL_ERROR);
        }
        int streamingBodyMode = router.streamingBodyMode(request);
        if (!headerOnly) {
            return beginPendingStream(
                streamId, requestParser.outputLength(), streamingBodyMode);
        }
        if (decodedContentLength > 0) {
            return sendRstStream(streamId, Http2Frames.PROTOCOL_ERROR);
        }
        if (streamingBodyMode != Router.BODY_BUFFERED) {
            if (!beginPendingStream(
                    streamId, requestParser.outputLength(),
                    streamingBodyMode)) {
                return false;
            }
            PendingStream pending = findPendingStream(streamId);
            if (pending != null) {
                finishStreamingInput(pending);
            }
            return true;
        }
        return submit(streamId, request, peerInitialWindowSize);
    }

    private boolean processTrailerBlock(PendingStream pending, int flags,
                                        MemorySegment block, int blockOffset,
                                        int blockLength) {
        int decodedLength = hpackDecoder.decodeRequestEager(
            block, blockOffset, blockLength, decodedHeaders, hpackFields);
        if (decodedLength == HpackDecoder.ERROR_COMPRESSION) {
            return connectionError(Http2Frames.COMPRESSION_ERROR);
        }
        if (decodedLength < 0) {
            return connectionError(Http2Frames.ENHANCE_YOUR_CALM);
        }
        if ((flags & Http2Frames.FLAG_END_STREAM) == 0
            || !requestParser.validateTrailers(hpackDecoder, hpackFields)
            || (pending.expectedContentLength >= 0
                && pending.bodyLength != pending.expectedContentLength)) {
            int streamId = pending.streamId;
            removePendingStream(pending);
            return sendRstStream(streamId, Http2Frames.PROTOCOL_ERROR);
        }

        if (pending.streaming) {
            finishStreamingInput(pending);
            return true;
        }

        int streamId = pending.streamId;
        int sendWindow = pending.sendWindow;
        HttpRequest pendingRequest = pending.request;
        detachPendingStream(pending);
        try {
            return submit(streamId, pendingRequest, sendWindow);
        } finally {
            releasePendingStream(pending);
        }
    }

    private boolean processData(int flags, int streamId, MemorySegment payload,
                                int payloadOffset, int payloadLength) {
        if (streamId == 0) {
            return connectionError(Http2Frames.PROTOCOL_ERROR);
        }
        if (payloadLength > connectionReceiveWindow) {
            return connectionError(Http2Frames.FLOW_CONTROL_ERROR);
        }
        connectionReceiveWindow -= payloadLength;

        int dataOffset = payloadOffset;
        int dataLength = payloadLength;
        if ((flags & Http2Frames.FLAG_PADDED) != 0) {
            if (dataLength == 0) {
                return connectionError(Http2Frames.PROTOCOL_ERROR);
            }
            int padding = payload.get(ValueLayout.JAVA_BYTE, dataOffset) & 0xff;
            dataOffset++;
            dataLength--;
            if (padding > dataLength) {
                return connectionError(Http2Frames.PROTOCOL_ERROR);
            }
            dataLength -= padding;
        }

        PendingStream pending = findPendingStream(streamId);
        if (pending == null) {
            if (!replenishConnectionWindow(payloadLength)) {
                return false;
            }
            if (streamId > lastClientStreamId) {
                return connectionError(Http2Frames.PROTOCOL_ERROR);
            }
            return sendRstStream(streamId, Http2Frames.STREAM_CLOSED);
        }
        if (payloadLength > pending.receiveWindow) {
            removePendingStream(pending);
            replenishConnectionWindow(payloadLength);
            return sendRstStream(streamId, Http2Frames.FLOW_CONTROL_ERROR);
        }
        pending.receiveWindow -= payloadLength;

        if (pending.expectedContentLength >= 0
            && dataLength > pending.expectedContentLength - pending.bodyLength) {
            removePendingStream(pending);
            replenishConnectionWindow(payloadLength);
            return sendRstStream(streamId, Http2Frames.PROTOCOL_ERROR);
        }

        if (pending.streaming) {
            pending.bodyLength += dataLength;
            if (!pending.streamingBody.offer(
                    payload, dataOffset, dataLength)) {
                removePendingStream(pending);
                replenishConnectionWindow(payloadLength);
                return sendRstStream(
                    streamId, Http2Frames.ENHANCE_YOUR_CALM);
            }
            int framingBytes = payloadLength - dataLength;
            if (framingBytes != 0
                && !replenishReceiveWindows(
                    streamId, pending, framingBytes)) {
                return false;
            }
            boolean endStream =
                (flags & Http2Frames.FLAG_END_STREAM) != 0;
            if (!endStream) {
                return true;
            }
            if (pending.expectedContentLength >= 0
                && pending.bodyLength != pending.expectedContentLength) {
                removePendingStream(pending);
                return sendRstStream(
                    streamId, Http2Frames.PROTOCOL_ERROR);
            }
            finishStreamingInput(pending);
            return true;
        }

        if (!pending.append(payload, dataOffset, dataLength)) {
            removePendingStream(pending);
            replenishConnectionWindow(payloadLength);
            return sendRstStream(streamId, Http2Frames.ENHANCE_YOUR_CALM);
        }
        boolean endStream = (flags & Http2Frames.FLAG_END_STREAM) != 0;
        if (endStream) {
            if (!replenishConnectionWindow(payloadLength)) {
                return false;
            }
        } else if (!replenishReceiveWindows(streamId, pending, payloadLength)) {
            return false;
        }
        if (!endStream) {
            return true;
        }
        if (pending.expectedContentLength >= 0
            && pending.bodyLength != pending.expectedContentLength) {
            removePendingStream(pending);
            return sendRstStream(streamId, Http2Frames.PROTOCOL_ERROR);
        }

        detachPendingStream(pending);
        try {
            return submit(streamId, pending.request, pending.sendWindow);
        } finally {
            releasePendingStream(pending);
        }
    }

    private boolean prepareRequest(int decodedLength,
                                   boolean materializeRegularHeaders) {
        decodedContentLength = requestParser.prepare(
            hpackDecoder, hpackFields, decodedLength,
            materializeRegularHeaders, !materializeRegularHeaders, request);
        return decodedContentLength != Http2RequestParser.ERROR;
    }

    private boolean beginPendingStream(
        int streamId,
        int headerLength,
        int streamingBodyMode
    ) {
        if ((draining && streamId > drainLastStreamId)
            || inFlight() + pendingStreamCount >= MAX_CONCURRENT_STREAMS
            || pendingStreamCount >= pendingStreams.length
            || headerLength > CardiganServer.MAX_REQUEST_SIZE
            || (decodedContentLength >= 0
                && (long) headerLength + decodedContentLength
                    > CardiganServer.MAX_REQUEST_SIZE)) {
            return sendRstStream(streamId, Http2Frames.REFUSED_STREAM);
        }
        boolean streaming = streamingBodyMode != Router.BODY_BUFFERED;
        boolean isolatedStreaming = streamingBodyMode
            == Router.BODY_STREAMING_ISOLATED;
        if (streaming
            && (streamingBodyCount >= MAX_STREAMING_BODIES
                || !Http2StreamingAdmission.tryAcquire(
                    INITIAL_WINDOW_SIZE))) {
            return sendRstStream(streamId, Http2Frames.REFUSED_STREAM);
        }
        PendingStream pending = acquirePendingStream();
        if (streaming) {
            streamingBodyCount++;
            pending.streamingReservation = true;
        }
        try {
            pending.init(
                streamId,
                headerLength,
                decodedContentLength,
                peerInitialWindowSize,
                streaming,
                isolatedStreaming
            );
        } catch (Throwable failure) {
            releasePendingStream(pending);
            return sendRstStream(
                streamId, Http2Frames.REFUSED_STREAM);
        }
        pendingStreams[pendingStreamCount++] = pending;
        if (streaming) {
            return submit(
                streamId, pending.request, peerInitialWindowSize, pending);
        }
        return true;
    }

    private boolean replenishConnectionWindow(int consumed) {
        if (consumed == 0) {
            return true;
        }
        connectionConsumedBytes += consumed;
        if (connectionConsumedBytes < RECEIVE_WINDOW_UPDATE_THRESHOLD) {
            return true;
        }
        int increment = connectionConsumedBytes;
        if (!sendWindowUpdate(0, increment)) {
            return false;
        }
        connectionReceiveWindow += increment;
        connectionConsumedBytes = 0;
        return true;
    }

    private boolean replenishReceiveWindows(int streamId, PendingStream pending, int consumed) {
        if (consumed == 0) {
            return true;
        }
        if (!replenishConnectionWindow(consumed)) {
            return false;
        }
        pending.consumedBytes += consumed;
        if (pending.consumedBytes < RECEIVE_WINDOW_UPDATE_THRESHOLD) {
            return true;
        }
        int increment = pending.consumedBytes;
        if (!sendWindowUpdate(streamId, increment)) {
            return false;
        }
        pending.receiveWindow += increment;
        pending.consumedBytes = 0;
        return true;
    }

    private void streamingBytesConsumed(
        PendingStream pending,
        int consumed
    ) {
        if (failed || pending.streamingBody == null) {
            return;
        }
        boolean replenished = pending.inputEnded
            ? replenishConnectionWindow(consumed)
            : replenishReceiveWindows(
                pending.streamId, pending, consumed);
        if (!replenished) {
            forceClose();
        }
    }

    private void isolatedStreamingBytesConsumed(
        PendingStream pending,
        int streamId,
        int consumed
    ) {
        writer.eventLoop().execute(
            () -> {
                if (failed) {
                    return;
                }
                if (pending.streamId == streamId
                    && pending.streamingBody != null) {
                    streamingBytesConsumed(pending, consumed);
                } else if (!replenishConnectionWindow(consumed)) {
                    forceClose();
                }
            });
    }

    private void finishStreamingInput(PendingStream pending) {
        detachPendingStream(pending);
        pending.inputEnded = true;
        pending.streamingBody.end();
        if (pending.exchangeEnded) {
            releasePendingStream(pending);
        }
    }

    private void streamingExchangeCompleted(PendingStream pending) {
        pending.exchangeEnded = true;
        if (pending.inputEnded) {
            releasePendingStream(pending);
        }
    }

    private PendingStream findPendingStream(int streamId) {
        for (int i = 0; i < pendingStreamCount; i++) {
            PendingStream pending = pendingStreams[i];
            if (pending.streamId == streamId) {
                return pending;
            }
        }
        return null;
    }

    private void detachPendingStream(PendingStream pending) {
        for (int i = 0; i < pendingStreamCount; i++) {
            if (pendingStreams[i] == pending) {
                int last = --pendingStreamCount;
                pendingStreams[i] = pendingStreams[last];
                pendingStreams[last] = null;
                return;
            }
        }
    }

    private void removePendingStream(PendingStream pending) {
        detachPendingStream(pending);
        if (!pending.streaming) {
            releasePendingStream(pending);
            return;
        }
        pending.inputEnded = true;
        pending.streamingBody.fail();
        if (pending.exchangeEnded) {
            releasePendingStream(pending);
        }
    }

    private void closePendingStreams() {
        while (pendingStreamCount != 0) {
            removePendingStream(pendingStreams[pendingStreamCount - 1]);
        }
    }

    private PendingStream acquirePendingStream() {
        if (freePendingStreamCount != 0) {
            int index = --freePendingStreamCount;
            PendingStream pending = freePendingStreams[index];
            freePendingStreams[index] = null;
            return pending;
        }
        if (pendingSlotCount < allPendingStreams.length) {
            PendingStream pending = new PendingStream();
            allPendingStreams[pendingSlotCount++] = pending;
            return pending;
        }
        throw new IllegalStateException("No HTTP/2 request slot below stream limit");
    }

    private void releasePendingStream(PendingStream pending) {
        pending.release();
        freePendingStreams[freePendingStreamCount++] = pending;
    }

    private void disposePendingStreams() {
        for (int i = 0; i < pendingSlotCount; i++) {
            allPendingStreams[i].dispose();
        }
    }

    private boolean submit(int streamId, HttpRequest streamRequest, int initialSendWindow) {
        return submit(streamId, streamRequest, initialSendWindow, null);
    }

    private boolean submit(
        int streamId,
        HttpRequest streamRequest,
        int initialSendWindow,
        PendingStream requestBodyOwner
    ) {
        int pendingAdjustment = requestBodyOwner == null ? 0 : 1;
        if (failed || (draining && streamId > drainLastStreamId)
            || inFlight() + pendingStreamCount - pendingAdjustment
                >= MAX_CONCURRENT_STREAMS) {
            if (requestBodyOwner != null) {
                requestBodyOwner.exchangeEnded = true;
                removePendingStream(requestBodyOwner);
            }
            return sendRstStream(streamId, Http2Frames.REFUSED_STREAM);
        }

        Http2Task task = acquireTask();
        task.streamId = streamId;
        task.sendWindow = initialSendWindow;
        task.requestBodyOwner = requestBodyOwner;
        setTaskActive(task, true);
        setTaskSendWaiter(task, null);
        if (streamRequest == request) {
            router.prepare(
                streamRequest, task.invocation, requestMaterializer);
        } else {
            router.prepare(streamRequest, task.invocation);
        }
        setInFlight(inFlight() + 1);
        if (Http2ResourceStats.ENABLED) {
            Http2ResourceStats.streamStarted();
        }
        if (!executor.submit(task)) {
            setInFlight(inFlight() - 1);
            if (Http2ResourceStats.ENABLED) {
                Http2ResourceStats.streamCompleted();
            }
            setTaskActive(task, false);
            task.requestBodyOwner = null;
            releaseTask(task);
            if (requestBodyOwner != null) {
                requestBodyOwner.exchangeEnded = true;
                removePendingStream(requestBodyOwner);
            }
            return sendRstStream(streamId, Http2Frames.REFUSED_STREAM);
        }
        if (streamId > lastAdmittedStreamId) {
            lastAdmittedStreamId = streamId;
        }
        return true;
    }

    private Http2Task acquireTask() {
        int available = freeTaskCount();
        if (available != 0) {
            int index = available - 1;
            Http2Task task = (Http2Task) TASK_ARRAY.getAcquire(freeTasks, index);
            TASK_ARRAY.setRelease(freeTasks, index, null);
            setFreeTaskCount(index);
            return task;
        }
        if (taskCount < freeTasks.length) {
            Http2Task task = new Http2Task();
            allTasks[taskCount++] = task;
            return task;
        }
        throw new IllegalStateException("No HTTP/2 exchange slot below stream limit");
    }

    private void releaseTask(Http2Task task) {
        int available = freeTaskCount();
        TASK_ARRAY.setRelease(freeTasks, available, task);
        setFreeTaskCount(available + 1);
    }

    private void complete(Http2Task task, Response response) {
        boolean sent = taskCancelled(task);
        if (!sent && !failed) {
            StreamingBody streamingBody = response.body()
                instanceof StreamingBody body ? body : null;
            setTaskResponseBody(task, streamingBody);
            try {
                sent = responseWriter.send(task.streamId, response, task);
            } catch (Throwable ignored) {
                sent = false;
            } finally {
                setTaskResponseBody(task, null);
            }
        } else if (response != null
            && response.body() instanceof StreamingBody streamingBody) {
            streamingBody.close();
        }
        if (!sent && taskCancelled(task)) {
            sent = true;
        }
        if (!sent) {
            failed = true;
            open = false;
        }
        setTaskActive(task, false);
        setInFlight(inFlight() - 1);
        if (Http2ResourceStats.ENABLED) {
            Http2ResourceStats.streamCompleted();
        }
        signalWaiter();
        finishDrainIfIdle();
    }

    private void finishDrainIfIdle() {
        if (draining && inFlight() == 0 && pendingStreamCount == 0) {
            open = false;
            scheduleDrainInputShutdown();
        }
    }

    private void scheduleDrainInputShutdown() {
        if (drainInputScheduled) {
            return;
        }
        drainInputScheduled = true;
        Runnable shutdown = drainInputShutdown;
        if (shutdown != null) {
            shutdown.run();
        }
    }

    private Http2Task findActiveTask(int streamId) {
        for (int i = 0; i < taskCount; i++) {
            Http2Task task = allTasks[i];
            if (taskActive(task) && task.streamId == streamId) {
                return task;
            }
        }
        return null;
    }

    private void cancelAllStreams() {
        closePendingStreams();
        for (int i = 0; i < taskCount; i++) {
            Http2Task task = allTasks[i];
            if (taskActive(task)) {
                setTaskCancelled(task, true);
                task.invocation.cancel();
                closeTaskResponseBody(task);
                signalSendWaiter(task);
            }
        }
    }

    private int reserveSendWindow(Http2Task task, int desiredBytes) {
        if (!taskActive(task) || desiredBytes <= 0) {
            return 0;
        }

        if (!failed && !taskCancelled(task)) {
            int available = Math.min(connectionSendWindow, task.sendWindow);
            if (available > 0) {
                int reserved = Math.min(desiredBytes, available);
                connectionSendWindow -= reserved;
                task.sendWindow -= reserved;
                return reserved;
            }
        }
        return awaitSendWindow(task, desiredBytes);
    }

    private void refundSendWindow(Http2Task task, int bytes) {
        if (bytes <= 0) {
            return;
        }
        connectionSendWindow = (int) Math.min(
            Integer.MAX_VALUE,
            (long) connectionSendWindow + bytes
        );
        task.sendWindow = (int) Math.min(
            Integer.MAX_VALUE,
            (long) task.sendWindow + bytes
        );
        signalAllSendWaiters();
    }

    private int awaitSendWindow(Http2Task task, int desiredBytes) {
        Thread current = Thread.currentThread();
        boolean interrupted = false;
        boolean parked = !failed && !taskCancelled(task)
            && (connectionSendWindow <= 0 || task.sendWindow <= 0);
        UringEventLoop loop = writer.eventLoop();
        if (parked && !loop.tryAcquireHttp2ParkedSender()) {
            setTaskCancelled(task, true);
            if (Http2ResourceStats.ENABLED) {
                Http2ResourceStats.senderRejected();
            }
            if (!sendRstStream(
                    task.streamId, Http2Frames.ENHANCE_YOUR_CALM)) {
                failed = true;
                open = false;
            }
            return 0;
        }
        setTaskSendWaiter(task, current);
        if (parked && Http2ResourceStats.ENABLED) {
            Http2ResourceStats.senderParked();
        }
        try {
            while (!failed && !taskCancelled(task)
                   && (connectionSendWindow <= 0 || task.sendWindow <= 0)) {
                LockSupport.park(this);
                if (!failed && !taskCancelled(task)
                    && (connectionSendWindow <= 0 || task.sendWindow <= 0)
                    && Thread.interrupted()) {
                    interrupted = true;
                }
            }
        } finally {
            if (parked && Http2ResourceStats.ENABLED) {
                Http2ResourceStats.senderResumed();
            }
            if (parked) {
                loop.releaseHttp2ParkedSender();
            }
        }
        setTaskSendWaiter(task, null);
        if (interrupted) {
            current.interrupt();
        }
        if (failed || taskCancelled(task)) {
            return 0;
        }

        int reserved = Math.min(
            desiredBytes, Math.min(connectionSendWindow, task.sendWindow));
        connectionSendWindow -= reserved;
        task.sendWindow -= reserved;
        return reserved;
    }

    private void signalAllSendWaiters() {
        for (int i = 0; i < taskCount; i++) {
            signalSendWaiter(allTasks[i]);
        }
    }

    private static void signalSendWaiter(Http2Task task) {
        Thread waiting = taskSendWaiter(task);
        if (waiting != null) {
            LockSupport.unpark(waiting);
        }
    }

    private void awaitExchanges() {
        if (inFlight() == 0) {
            return;
        }
        Thread current = Thread.currentThread();
        boolean interrupted = false;
        waiter = current;
        while (inFlight() != 0) {
            LockSupport.park(this);
            if (inFlight() != 0 && Thread.interrupted()) {
                interrupted = true;
            }
        }
        waiter = null;
        if (interrupted) {
            current.interrupt();
        }
    }

    private void signalWaiter() {
        Thread waiting = waiter;
        if (waiting != null) {
            LockSupport.unpark(waiting);
        }
    }

    private int inFlight() {
        return (int) IN_FLIGHT.getAcquire(this);
    }

    private void setInFlight(int value) {
        IN_FLIGHT.setRelease(this, value);
    }

    private int freeTaskCount() {
        return (int) FREE_TASK_COUNT.getAcquire(this);
    }

    private void setFreeTaskCount(int value) {
        FREE_TASK_COUNT.setRelease(this, value);
    }

    private static boolean taskActive(Http2Task task) {
        return (boolean) TASK_ACTIVE.getAcquire(task);
    }

    private static void setTaskActive(Http2Task task, boolean active) {
        TASK_ACTIVE.setRelease(task, active);
    }

    private static boolean taskCancelled(Http2Task task) {
        return (boolean) TASK_CANCELLED.getAcquire(task);
    }

    private static void setTaskCancelled(Http2Task task, boolean cancelled) {
        TASK_CANCELLED.setRelease(task, cancelled);
    }

    private static Thread taskSendWaiter(Http2Task task) {
        return (Thread) TASK_SEND_WAITER.getAcquire(task);
    }

    private static void setTaskSendWaiter(Http2Task task, Thread waiter) {
        TASK_SEND_WAITER.setRelease(task, waiter);
    }

    private static StreamingBody taskResponseBody(Http2Task task) {
        return (StreamingBody) TASK_RESPONSE_BODY.getAcquire(task);
    }

    private static void setTaskResponseBody(
        Http2Task task,
        StreamingBody body
    ) {
        TASK_RESPONSE_BODY.setRelease(task, body);
    }

    private static void closeTaskResponseBody(Http2Task task) {
        StreamingBody body = taskResponseBody(task);
        if (body != null) {
            try {
                body.close();
            } catch (Throwable ignored) {
                // Cancellation must continue even if application cleanup fails.
            }
        }
    }

    private boolean sendSettings() {
        return sendControl(
            Http2Frames.SETTINGS, 0, 0, 0, MAX_CONCURRENT_STREAMS);
    }

    private boolean sendSettingsAck() {
        return sendControl(Http2Frames.SETTINGS, Http2Frames.FLAG_ACK, 0, 0, 0);
    }

    private boolean sendPing(long opaqueData) {
        return sendControl(Http2Frames.PING, Http2Frames.FLAG_ACK, 0, opaqueData, 0);
    }

    private boolean sendRstStream(int streamId, int error) {
        return sendControl(Http2Frames.RST_STREAM, 0, streamId, 0, error);
    }

    private boolean sendWindowUpdate(int streamId, int increment) {
        return sendControl(Http2Frames.WINDOW_UPDATE, 0, streamId, 0, increment);
    }

    private boolean connectionError(int error) {
        sendControl(Http2Frames.GOAWAY, 0, lastClientStreamId, 0, error);
        failed = true;
        open = false;
        cancelAllStreams();
        signalAllSendWaiters();
        return false;
    }

    private boolean sendControl(int type, int flags, int streamId, long opaqueData, int error) {
        UringEventLoop loop = writer.eventLoop();
        int egressId = loop.acquireEgressBuffer();
        if (egressId >= 0) {
            MemorySegment output = loop.getEgressBufferSegment(egressId);
            int length = writeControl(output, type, flags, streamId, opaqueData, error);
            if (writer.enqueue(egressId, length)) {
                return true;
            }
            return false;
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(32);
            int length = writeControl(output, type, flags, streamId, opaqueData, error);
            return writer.writeFully(output, length) > 0;
        }
    }

    private static int writeControl(MemorySegment output, int type, int flags, int streamId,
                                    long opaqueData, int error) {
        return switch (type) {
            case Http2Frames.SETTINGS -> {
                if ((flags & Http2Frames.FLAG_ACK) != 0) {
                    yield Http2Frames.writeSettingsAck(output, 0);
                }
                yield Http2Frames.writeSetting(
                    output, 0, Http2Frames.SETTINGS_MAX_CONCURRENT_STREAMS, error);
            }
            case Http2Frames.PING ->
                Http2Frames.writePing(output, 0, opaqueData, true);
            case Http2Frames.RST_STREAM ->
                Http2Frames.writeRstStream(output, 0, streamId, error);
            case Http2Frames.WINDOW_UPDATE ->
                Http2Frames.writeWindowUpdate(output, 0, streamId, error);
            case Http2Frames.GOAWAY ->
                Http2Frames.writeGoAway(output, 0, streamId, error);
            default -> throw new IllegalArgumentException("Unsupported control frame: " + type);
        };
    }

    private int remaining() {
        return currentChunk == null ? 0 : currentChunk.length() - currentOffset;
    }

    private boolean readFully(MemorySegment destination, int destinationOffset, int length) {
        int copied = 0;
        while (copied < length) {
            if (!ensureCurrentChunk()) {
                return false;
            }
            int count = Math.min(length - copied, remaining());
            MemorySegment.copy(currentChunk.segment(), currentOffset,
                               destination, destinationOffset + copied, count);
            copied += count;
            currentOffset += count;
            releaseExhaustedChunk();
        }
        return true;
    }

    private boolean ensureCurrentChunk() {
        releaseExhaustedChunk();
        if (currentChunk == null) {
            currentChunk = inbound.nextChunk();
            currentOffset = 0;
        }
        return currentChunk != null;
    }

    private void releaseExhaustedChunk() {
        if (currentChunk != null && currentOffset == currentChunk.length()) {
            currentChunk.close();
            currentChunk = null;
            currentOffset = 0;
        }
    }

    private void releaseCurrentChunk() {
        if (currentChunk != null) {
            currentChunk.close();
            currentChunk = null;
            currentOffset = 0;
        }
    }

    private final class Http2Task
            implements Runnable, Http2ResponseWriter.FlowControl {
        private final PreparedInvocation invocation =
            new PreparedInvocation();
        private int streamId;
        private int sendWindow;
        private PendingStream requestBodyOwner;
        // Access only through the acquire/release helpers above. These fields
        // cross between the connection and exchange virtual threads, but do
        // not require the StoreLoad ordering of sequentially consistent
        // volatile writes.
        private boolean active;
        private boolean cancelled;
        private Thread sendWaiter;
        private StreamingBody responseBody;

        @Override
        public int reserve(int desiredBytes) {
            return reserveSendWindow(this, desiredBytes);
        }

        @Override
        public void refund(int bytes) {
            refundSendWindow(this, bytes);
        }

        @Override
        public boolean cancelled() {
            return failed || taskCancelled(this);
        }

        @Override
        public void run() {
            PendingStream bodyOwner = requestBodyOwner;
            try {
                if (failed || taskCancelled(this)) {
                    complete(this, null);
                } else {
                    Response response = bodyOwner == null
                        ? invocation.invoke()
                        : invokeStreaming(bodyOwner);
                    complete(this, response);
                }
            } finally {
                // Clean invocations never need a reset. Cancellation marks
                // both objects, so retire the exceptional state together and
                // keep the normal submission path free of volatile stores.
                if (taskCancelled(this)) {
                    setTaskCancelled(this, false);
                    invocation.resetCancellation();
                }
                requestBodyOwner = null;
                if (bodyOwner != null) {
                    streamingExchangeCompleted(bodyOwner);
                }
                releaseTask(this);
            }
        }

        private Response invokeStreaming(PendingStream bodyOwner) {
            Http2RequestBody body = bodyOwner.streamingBody;
            try {
                if (!bodyOwner.isolatedStreaming) {
                    return invocation.invoke();
                }
                Runnable completion = body::handlerComplete;
                body.retainHandler();
                return invocation.invokeWithCompletion(completion);
            } finally {
                body.close();
            }
        }
    }

    private final class PendingStream {
        private final HttpRequest request = new HttpRequest();
        private int streamId;
        private int headerLength;
        private int expectedContentLength;
        private Arena arena;
        private MemorySegment storage;
        private int capacity;
        private int bodyLength;
        private int receiveWindow;
        private int consumedBytes;
        private int sendWindow;
        private Http2RequestBody streamingBody;
        private boolean streaming;
        private boolean inputEnded;
        private boolean exchangeEnded;
        private boolean streamingReservation;
        private boolean isolatedStreaming;

        private void init(int streamId, int headerLength,
                          int expectedContentLength, int sendWindow,
                          boolean streaming,
                          boolean isolatedStreaming) {
            this.streamId = streamId;
            this.headerLength = headerLength;
            this.expectedContentLength = expectedContentLength;
            this.sendWindow = sendWindow;
            this.bodyLength = 0;
            this.receiveWindow = INITIAL_WINDOW_SIZE;
            this.consumedBytes = 0;
            this.streaming = streaming;
            this.isolatedStreaming = isolatedStreaming;
            this.inputEnded = false;
            this.exchangeEnded = !streaming;

            int initialBodyCapacity = streaming
                ? 0
                : expectedContentLength >= 0
                    ? expectedContentLength
                    : Math.min(
                        UringEventLoop.BUFFER_SIZE, maximumBodyLength());
            ensureInitialCapacity(Math.max(1, headerLength + initialBodyCapacity));
            MemorySegment.copy(decodedHeaders, 0, storage, 0, headerLength);
            request.copyMetadataFrom(Http2Connection.this.request, storage);
            request.setBody(headerLength, 0);
            if (streaming) {
                streamingBody = new Http2RequestBody(
                    expectedContentLength,
                    INITIAL_WINDOW_SIZE,
                    isolatedStreaming
                        ? consumed -> isolatedStreamingBytesConsumed(
                            this, streamId, consumed)
                        : consumed -> streamingBytesConsumed(this, consumed)
                );
                request.setBodyStream(streamingBody);
            }
        }

        private boolean append(MemorySegment source, int sourceOffset, int length) {
            if (length > maximumBodyLength() - bodyLength) {
                return false;
            }
            int required = headerLength + bodyLength + length;
            if (required > capacity) {
                int newCapacity = capacity;
                while (newCapacity < required) {
                    int grown = newCapacity << 1;
                    if (grown <= newCapacity || grown > headerLength + maximumBodyLength()) {
                        newCapacity = headerLength + maximumBodyLength();
                        break;
                    }
                    newCapacity = grown;
                }
                ensureArena();
                MemorySegment replacement = arena.allocate(newCapacity);
                MemorySegment.copy(storage, 0, replacement, 0, headerLength + bodyLength);
                storage = replacement;
                capacity = newCapacity;
                request.setSegment(storage);
            }
            MemorySegment.copy(
                source, sourceOffset, storage, headerLength + bodyLength, length);
            bodyLength += length;
            request.setBody(headerLength, bodyLength);
            return true;
        }

        private int maximumBodyLength() {
            return (int) Math.min(
                Integer.MAX_VALUE - (long) headerLength,
                CardiganServer.MAX_REQUEST_SIZE - headerLength
            );
        }

        private void ensureInitialCapacity(int required) {
            if (required <= capacity) {
                return;
            }
            int newCapacity = 256;
            while (newCapacity < required) {
                int grown = newCapacity << 1;
                if (grown <= newCapacity) {
                    newCapacity = required;
                    break;
                }
                newCapacity = grown;
            }
            ensureArena();
            storage = arena.allocate(newCapacity);
            capacity = newCapacity;
        }

        private void ensureArena() {
            if (arena == null) {
                arena = Arena.ofConfined();
            }
        }

        private void release() {
            if (streamingBody != null) {
                streamingBody.dispose();
                streamingBody = null;
            }
            if (streamingReservation) {
                Http2StreamingAdmission.release(INITIAL_WINDOW_SIZE);
                streamingBodyCount--;
                streamingReservation = false;
            }
            request.init(null);
            streamId = 0;
            headerLength = 0;
            expectedContentLength = -1;
            bodyLength = 0;
            receiveWindow = INITIAL_WINDOW_SIZE;
            consumedBytes = 0;
            sendWindow = 0;
            streaming = false;
            isolatedStreaming = false;
            inputEnded = false;
            exchangeEnded = false;
            if (capacity > MAX_RETAINED_PENDING_CAPACITY) {
                arena.close();
                arena = null;
                storage = null;
                capacity = 0;
            }
        }

        private void dispose() {
            if (streamingBody != null) {
                streamingBody.fail();
                streamingBody.dispose();
                streamingBody = null;
            }
            if (streamingReservation) {
                Http2StreamingAdmission.release(INITIAL_WINDOW_SIZE);
                streamingBodyCount--;
                streamingReservation = false;
            }
            isolatedStreaming = false;
            if (arena != null) {
                arena.close();
                arena = null;
                storage = null;
                capacity = 0;
            }
        }
    }
}
