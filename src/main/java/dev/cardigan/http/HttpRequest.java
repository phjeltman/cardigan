// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import dev.cardigan.pico.Request;
import dev.cardigan.ffi.RawSegment;

public class HttpRequest {
    private static final long QUESTION_MARKS = 0x3f3f_3f3f_3f3f_3f3fL;
    private static final long BYTE_ONES = 0x0101_0101_0101_0101L;
    private static final long BYTE_HIGH_BITS = 0x8080_8080_8080_8080L;
    private MemorySegment segment;
    private long address;
    private long messageOffset;
    private final Request picoRequest = new Request(64);
    private final long[] segPacked = new long[16];

    private long bodyOffset;
    private long bodyLength;
    private RequestBody bodyStream;
    private Route resolvedRoute;
    private int resolvedSegmentCount = -1;
    private boolean routeResolved;

    private int keepAliveState = -1;

    public void init(MemorySegment segment) {
        init(segment, 0);
    }

    public void init(MemorySegment segment, long messageOffset) {
        this.segment = segment;
        this.address = segment != null ? segment.address() : 0;
        this.messageOffset = messageOffset;
        this.picoRequest.reset();
        this.bodyOffset = 0;
        this.bodyLength = 0;
        this.bodyStream = null;
        this.resolvedRoute = null;
        this.resolvedSegmentCount = -1;
        this.routeResolved = false;
        this.keepAliveState = -1;
    }

    public void initHttp2(MemorySegment segment) {
        init(segment);
        this.picoRequest.minorVersion = 2;
        this.keepAliveState = 1;
    }

    public MemorySegment segment() { return segment; }
    public long address() { return address; }
    
    public Request picoRequest() { return picoRequest; }
    public long[] segPacked() { return segPacked; }
    public long bodyOffset() { return bodyOffset; }
    public long bodyLength() { return bodyLength; }
    public RequestBody bodyStream() { return bodyStream; }

    public <T> T bodyAs(Class<T> clazz) {
        Utf8Slice b = body();
        if (b == null || b.length() == 0) {
            return null;
        }
        return dev.cardigan.serdes.Serdes.fromJson(b.segment(), b.offset(), b.length(), clazz);
    }

    public dev.cardigan.simdjson.ondemand.Value bodyJson() {
        Utf8Slice b = body();
        if (b == null || b.length() == 0) {
            return null;
        }
        return dev.cardigan.serdes.Serdes.parseOnDemand(b.segment(), b.offset(), b.length());
    }
    
    public Utf8Slice method() {
        return new Utf8Slice(segment, picoRequest.methodOffset, picoRequest.methodLen);
    }

    public Utf8Slice path() {
        return new Utf8Slice(segment, picoRequest.pathOffset, picoRequest.pathLen);
    }

    /** Returns the complete request target, including its query component. */
    public Utf8Slice requestTarget() {
        long length = picoRequest.targetLen != 0
            ? picoRequest.targetLen : picoRequest.pathLen;
        return new Utf8Slice(segment, picoRequest.pathOffset, length);
    }

    /** Returns the query without the leading {@code ?}, or {@code null}. */
    public Utf8Slice query() {
        return picoRequest.queryOffset < 0
            ? null
            : new Utf8Slice(
                segment, picoRequest.queryOffset, picoRequest.queryLen);
    }

    /** Parses a named decimal query parameter without materializing a String. */
    public int queryInt(String name, int defaultValue) {
        if (picoRequest.queryOffset < 0 || name.isEmpty()) {
            return defaultValue;
        }
        long index = picoRequest.queryOffset;
        long end = index + picoRequest.queryLen;
        while (index < end) {
            long keyStart = index;
            while (index < end) {
                byte current = segment.get(ValueLayout.JAVA_BYTE, index);
                if (current == '=' || current == '&') {
                    break;
                }
                index++;
            }
            boolean matches = index - keyStart == name.length();
            if (matches) {
                for (int character = 0; character < name.length(); character++) {
                    if ((segment.get(
                            ValueLayout.JAVA_BYTE, keyStart + character) & 0xff)
                            != name.charAt(character)) {
                        matches = false;
                        break;
                    }
                }
            }
            if (index < end
                    && segment.get(ValueLayout.JAVA_BYTE, index) == '=') {
                index++;
                int value = 0;
                boolean negative = false;
                if (index < end
                        && segment.get(ValueLayout.JAVA_BYTE, index) == '-') {
                    negative = true;
                    index++;
                }
                long valueStart = index;
                while (index < end) {
                    byte current = segment.get(ValueLayout.JAVA_BYTE, index);
                    if (current == '&') {
                        break;
                    }
                    if (current < '0' || current > '9') {
                        matches = false;
                    } else {
                        value = value * 10 + current - '0';
                    }
                    index++;
                }
                if (matches && index != valueStart) {
                    return negative ? -value : value;
                }
            }
            while (index < end
                    && segment.get(ValueLayout.JAVA_BYTE, index) != '&') {
                index++;
            }
            index++;
        }
        return defaultValue;
    }

    /**
     * Splits the parser's raw request target in place. Both HTTP/1 and HTTP/2
     * call this once after their protocol parser has materialized the target.
     */
    public void splitQuery() {
        long offset = picoRequest.pathOffset;
        long length = picoRequest.pathLen;
        picoRequest.targetLen = length;
        picoRequest.queryOffset = -1;
        picoRequest.queryLen = 0;
        if (offset < 0 || length == 0) {
            return;
        }
        long end = offset + length;
        long index = offset;
        while (index + Long.BYTES <= end) {
            long difference = segment.get(
                ValueLayout.JAVA_LONG_UNALIGNED, index) ^ QUESTION_MARKS;
            long matches = (difference - BYTE_ONES)
                & ~difference & BYTE_HIGH_BITS;
            if (matches != 0) {
                long query = index
                    + (Long.numberOfTrailingZeros(matches) >>> 3);
                splitQueryAt(offset, end, query);
                return;
            }
            index += Long.BYTES;
        }
        for (; index < end; index++) {
            if (segment.get(ValueLayout.JAVA_BYTE, index) == '?') {
                splitQueryAt(offset, end, index);
                return;
            }
        }
    }

    private void splitQueryAt(long offset, long end, long query) {
        picoRequest.pathLen = query - offset;
        picoRequest.queryOffset = query + 1;
        picoRequest.queryLen = end - query - 1;
    }

    public int headerCount() {
        return picoRequest.numHeaders;
    }

    public Utf8Slice headerName(int index) {
        if (index < 0 || index >= picoRequest.numHeaders) {
            throw new IndexOutOfBoundsException();
        }
        return new Utf8Slice(segment, picoRequest.headers[index].nameOffset, picoRequest.headers[index].nameLen);
    }

    public Utf8Slice headerValue(int index) {
        if (index < 0 || index >= picoRequest.numHeaders) {
            throw new IndexOutOfBoundsException();
        }
        return new Utf8Slice(segment, picoRequest.headers[index].valueOffset, picoRequest.headers[index].valueLen);
    }

    public Utf8Slice body() {
        return new Utf8Slice(segment, bodyOffset, bodyLength);
    }

    public Utf8Slice getHeader(String name) {
        int targetLen = name.length();
        for (int i = 0; i < picoRequest.numHeaders; i++) {
            if (picoRequest.headers[i].nameLen == targetLen) {
                if (headerNameEquals(i, name)) {
                    return headerValue(i);
                }
            }
        }
        return null;
    }

    public int version() {
        return picoRequest.minorVersion;
    }

    public boolean isKeepAlive() {
        if (keepAliveState != -1) {
            return keepAliveState == 1;
        }
        boolean ka = computeKeepAlive();
        keepAliveState = ka ? 1 : 0;
        return ka;
    }

    private boolean computeKeepAlive() {
        Utf8Slice connectionHeader = getHeader("Connection");
        if (connectionHeader == null) {
            return picoRequest.minorVersion == 1;
        }
        if (connectionHeader.equalsIgnoreCaseString("close")) {
            return false;
        }
        if (connectionHeader.equalsIgnoreCaseString("keep-alive")) {
            return true;
        }
        return picoRequest.minorVersion == 1;
    }

    private boolean headerNameEquals(int index, String str) {
        long len = picoRequest.headers[index].nameLen;
        int intLen = (int) len;
        if (str.length() != intLen) return false;
        long offset = picoRequest.headers[index].nameOffset;
        long ptr = address + offset;
        for (int i = 0; i < intLen; i++) {
            byte b = RawSegment.getByte(ptr, i);
            int c2 = str.charAt(i);
            if (b != c2) {
                int c1 = (b >= 'A' && b <= 'Z') ? (b + 32) : b;
                int c2Fold = (c2 >= 'A' && c2 <= 'Z') ? (c2 + 32) : c2;
                if (c1 != c2Fold) {
                    return false;
                }
            }
        }
        return true;
    }

    public void setSegment(MemorySegment segment) {
        this.segment = segment;
        this.address = segment != null ? segment.address() : 0;
    }

    public void setBody(long offset, long len) {
        this.bodyOffset = offset;
        this.bodyLength = len;
    }

    public void setBodyStream(RequestBody bodyStream) {
        this.bodyStream = bodyStream;
    }

    Route resolvedRoute() {
        return resolvedRoute;
    }

    int resolvedSegmentCount() {
        return resolvedSegmentCount;
    }

    boolean routeResolved() {
        return routeResolved;
    }

    Route cacheRoute(Route route, int segmentCount) {
        resolvedRoute = route;
        resolvedSegmentCount = segmentCount;
        routeResolved = true;
        return route;
    }

    public void copyMetadataFrom(HttpRequest source, MemorySegment copiedSegment) {
        this.segment = copiedSegment;
        this.address = copiedSegment.address();
        this.messageOffset = 0;
        this.bodyOffset = source.bodyOffset;
        this.bodyLength = source.bodyLength;
        this.bodyStream = source.bodyStream;
        this.resolvedRoute = source.resolvedRoute;
        this.resolvedSegmentCount = source.resolvedSegmentCount;
        this.routeResolved = source.routeResolved;
        if (source.resolvedSegmentCount > 0) {
            System.arraycopy(
                source.segPacked, 0,
                this.segPacked, 0,
                source.resolvedSegmentCount);
        }
        this.keepAliveState = source.keepAliveState;
        copyRequestMetadata(source.picoRequest, this.picoRequest);
    }

    HttpRequest detachedCopy() {
        long requestEnd = bodyOffset + bodyLength;
        requestEnd = Math.max(
            requestEnd,
            picoRequest.methodOffset >= 0
                ? picoRequest.methodOffset + picoRequest.methodLen
                : 0
        );
        requestEnd = Math.max(
            requestEnd,
            picoRequest.pathOffset >= 0
                ? picoRequest.pathOffset
                    + (picoRequest.targetLen != 0
                        ? picoRequest.targetLen : picoRequest.pathLen)
                : 0
        );
        for (int i = 0; i < picoRequest.numHeaders; i++) {
            requestEnd = Math.max(
                requestEnd,
                picoRequest.headers[i].nameOffset + picoRequest.headers[i].nameLen
            );
            requestEnd = Math.max(
                requestEnd,
                picoRequest.headers[i].valueOffset + picoRequest.headers[i].valueLen
            );
        }
        long requestLength = requestEnd - messageOffset;
        MemorySegment detachedSegment = Arena.ofAuto().allocate(requestLength);
        MemorySegment.copy(
            segment, messageOffset, detachedSegment, 0, requestLength);

        HttpRequest detached = new HttpRequest();
        detached.segment = detachedSegment;
        detached.address = detachedSegment.address();
        detached.messageOffset = messageOffset;
        detached.bodyOffset = bodyOffset;
        detached.bodyLength = bodyLength;
        detached.bodyStream = bodyStream;
        detached.resolvedRoute = resolvedRoute;
        detached.resolvedSegmentCount = resolvedSegmentCount;
        detached.routeResolved = routeResolved;
        if (resolvedSegmentCount > 0) {
            System.arraycopy(
                segPacked, 0,
                detached.segPacked, 0,
                resolvedSegmentCount);
        }
        detached.keepAliveState = keepAliveState;
        copyRequestMetadata(picoRequest, detached.picoRequest);
        detached.rebase(-messageOffset);
        return detached;
    }

    private void rebase(long delta) {
        messageOffset += delta;
        bodyOffset += delta;
        if (picoRequest.methodOffset >= 0) {
            picoRequest.methodOffset += delta;
        }
        if (picoRequest.pathOffset >= 0) {
            picoRequest.pathOffset += delta;
        }
        if (picoRequest.queryOffset >= 0) {
            picoRequest.queryOffset += delta;
        }
        for (int i = 0; i < picoRequest.numHeaders; i++) {
            picoRequest.headers[i].nameOffset += delta;
            picoRequest.headers[i].valueOffset += delta;
        }
        for (int i = 0; i < resolvedSegmentCount; i++) {
            long packed = segPacked[i];
            long offset = (packed >>> 32) + delta;
            segPacked[i] = (offset << 32) | (packed & 0xffff_ffffL);
        }
    }

    private static void copyRequestMetadata(Request source, Request target) {
        target.methodCode = source.methodCode;
        target.methodOffset = source.methodOffset;
        target.methodLen = source.methodLen;
        target.pathOffset = source.pathOffset;
        target.pathLen = source.pathLen;
        target.targetLen = source.targetLen;
        target.queryOffset = source.queryOffset;
        target.queryLen = source.queryLen;
        target.minorVersion = source.minorVersion;
        target.numHeaders = source.numHeaders;
        for (int i = 0; i < source.numHeaders; i++) {
            target.headers[i].nameOffset = source.headers[i].nameOffset;
            target.headers[i].nameLen = source.headers[i].nameLen;
            target.headers[i].valueOffset = source.headers[i].valueOffset;
            target.headers[i].valueLen = source.headers[i].valueLen;
        }
    }

}
