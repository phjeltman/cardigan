// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http2;

import dev.cardigan.ffi.RawSegment;
import dev.cardigan.http.HttpRequest;
import dev.cardigan.pico.Header;
import dev.cardigan.pico.Request;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Converts decoded HPACK field metadata into Cardigan's protocol-neutral
 * request representation. One parser is bound to one reusable decoded-header
 * slab and can prepare requests without allocation.
 */
public final class Http2RequestParser {
    public static final int ERROR = Integer.MIN_VALUE;

    private final long maximumRequestSize;
    private MemorySegment decodedHeaders;
    private MemorySegment decodedHeadersRaw;
    private HpackDecoder decoder;
    private HpackFields fields;
    private HttpRequest request;
    private int outputLength;
    private int methodFieldIndex;
    private boolean headersMaterialized;

    public Http2RequestParser(long maximumRequestSize) {
        if (maximumRequestSize < 0) {
            throw new IllegalArgumentException("maximumRequestSize must not be negative");
        }
        this.maximumRequestSize = maximumRequestSize;
    }

    public void bind(MemorySegment headers) {
        decodedHeaders = Objects.requireNonNull(headers, "headers");
        decodedHeadersRaw = RawSegment.raw(headers.address());
    }

    /**
     * Prepares {@code request} and returns its decoded Content-Length, or -1
     * when absent. {@link #ERROR} denotes an invalid HTTP/2 field section.
     */
    public int prepare(HpackDecoder decoder, HpackFields fields,
                       int decodedLength, HttpRequest request) {
        return prepare(decoder, fields, decodedLength, false, request);
    }

    public int prepare(HpackDecoder decoder, HpackFields fields,
                       int decodedLength, boolean materializeRegularHeaders,
                       HttpRequest request) {
        return prepare(
            decoder, fields, decodedLength,
            materializeRegularHeaders, false, request);
    }

    public int prepare(HpackDecoder decoder, HpackFields fields,
                       int decodedLength, boolean materializeRegularHeaders,
                       boolean deferKnownMethod, HttpRequest request) {
        if (decodedHeaders == null) {
            throw new IllegalStateException("No decoded-header slab is bound");
        }
        if (decodedLength < 0 || decodedLength > decodedHeaders.byteSize()) {
            throw new IllegalArgumentException(
                "Invalid decoded-header length: " + decodedLength);
        }

        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.fields = Objects.requireNonNull(fields, "fields");
        this.request = Objects.requireNonNull(request, "request");
        this.outputLength = decodedLength;
        this.methodFieldIndex = -1;
        this.headersMaterialized = materializeRegularHeaders;
        request.initHttp2(decodedHeaders);
        Request picoRequest = request.picoRequest();
        int pseudoFields = 0;
        boolean regularFieldsStarted = false;
        int contentLength = -1;

        for (int i = 0; i < fields.count(); i++) {
            int nameLength = fields.nameLength(i);
            int nameIndex = fields.nameIndex(i);
            int valueLength = fields.valueLength(i);

            if (nameIndex != HpackFields.NAME_LITERAL
                && nameIndex <= HpackFields.NAME_STATUS) {
                if (regularFieldsStarted || valueLength == 0) {
                    return ERROR;
                }
                switch (nameIndex) {
                    case HpackFields.NAME_AUTHORITY -> {
                        if ((pseudoFields & 8) != 0) return ERROR;
                        pseudoFields |= 8;
                    }
                    case HpackFields.NAME_METHOD -> {
                        if ((pseudoFields & 1) != 0) return ERROR;
                        pseudoFields |= 1;
                        methodFieldIndex = i;
                        picoRequest.methodCode =
                            requestMethodCode(i, valueLength);
                        picoRequest.methodOffset =
                            deferKnownMethod
                                && picoRequest.methodCode != 0
                                    ? -1
                                    : materializeValue(i);
                        picoRequest.methodLen = valueLength;
                    }
                    case HpackFields.NAME_PATH -> {
                        if ((pseudoFields & 4) != 0) return ERROR;
                        pseudoFields |= 4;
                        picoRequest.pathOffset = materializeValue(i);
                        picoRequest.pathLen = valueLength;
                    }
                    case HpackFields.NAME_SCHEME -> {
                        if ((pseudoFields & 2) != 0) return ERROR;
                        pseudoFields |= 2;
                    }
                    default -> {
                        return ERROR;
                    }
                }
                continue;
            }

            if (nameIndex == HpackFields.NAME_LITERAL) {
                if (nameLength == 0 || containsUppercase(i, nameLength)) {
                    return ERROR;
                }
                if (decoder.fieldNameByte(fields, i, 0, decodedHeaders) == ':') {
                    if (regularFieldsStarted || valueLength == 0) {
                        return ERROR;
                    }
                    if (nameEquals(i, nameLength, ":method")) {
                        if ((pseudoFields & 1) != 0) return ERROR;
                        pseudoFields |= 1;
                        methodFieldIndex = i;
                        picoRequest.methodCode =
                            requestMethodCode(i, valueLength);
                        picoRequest.methodOffset =
                            deferKnownMethod
                                && picoRequest.methodCode != 0
                                    ? -1
                                    : materializeValue(i);
                        picoRequest.methodLen = valueLength;
                    } else if (nameEquals(i, nameLength, ":scheme")) {
                        if ((pseudoFields & 2) != 0) return ERROR;
                        pseudoFields |= 2;
                    } else if (nameEquals(i, nameLength, ":path")) {
                        if ((pseudoFields & 4) != 0) return ERROR;
                        pseudoFields |= 4;
                        picoRequest.pathOffset = materializeValue(i);
                        picoRequest.pathLen = valueLength;
                    } else if (nameEquals(i, nameLength, ":authority")) {
                        if ((pseudoFields & 8) != 0) return ERROR;
                        pseudoFields |= 8;
                    } else {
                        return ERROR;
                    }
                    continue;
                }
            }

            regularFieldsStarted = true;
            if (nameIndex == HpackFields.NAME_TRANSFER_ENCODING
                || (nameIndex == HpackFields.NAME_LITERAL
                    && isConnectionSpecificHeader(i, nameLength))) {
                return ERROR;
            }
            if (nameIndex == HpackFields.NAME_LITERAL
                && nameEquals(i, nameLength, "te")
                && !valueEquals(i, valueLength, "trailers")) {
                return ERROR;
            }
            if (nameIndex == HpackFields.NAME_CONTENT_LENGTH
                || (nameIndex == HpackFields.NAME_LITERAL
                    && nameEquals(i, nameLength, "content-length"))) {
                int parsedLength = parseContentLength(i, valueLength);
                if (parsedLength < 0
                    || (contentLength >= 0 && contentLength != parsedLength)) {
                    return ERROR;
                }
                contentLength = parsedLength;
            }
            if (materializeRegularHeaders) {
                addHeader(picoRequest, i);
            }
        }

        if ((pseudoFields & 7) != 7) {
            return ERROR;
        }
        request.splitQuery();
        request.setBody(0, 0);
        return contentLength;
    }

    /**
     * Materializes ordinary headers for a handler that receives the request.
     * Routing-only requests keep their indexed fields in the HPACK tables.
     */
    public void materializeHeaders() {
        if (decoder == null || fields == null || request == null) {
            throw new IllegalStateException("No prepared request");
        }

        Request picoRequest = request.picoRequest();
        if (picoRequest.methodOffset < 0 && methodFieldIndex >= 0) {
            picoRequest.methodOffset = materializeValue(methodFieldIndex);
        }
        if (headersMaterialized) {
            return;
        }
        picoRequest.numHeaders = 0;
        for (int i = 0; i < fields.count(); i++) {
            int nameIndex = fields.nameIndex(i);
            if ((nameIndex != HpackFields.NAME_LITERAL
                    && nameIndex <= HpackFields.NAME_STATUS)
                || (nameIndex == HpackFields.NAME_LITERAL
                    && fields.nameLength(i) != 0
                    && decoder.fieldNameByte(fields, i, 0, decodedHeaders) == ':')) {
                continue;
            }
            addHeader(picoRequest, i);
        }
        headersMaterialized = true;
    }

    /**
     * Validates a trailing field section after HPACK decoding. Trailers may
     * contain ordinary fields, but never pseudo-headers or fields that affect
     * HTTP message framing or connection state.
     */
    public boolean validateTrailers(HpackDecoder decoder, HpackFields fields) {
        if (decodedHeaders == null) {
            throw new IllegalStateException("No decoded-header slab is bound");
        }
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.fields = Objects.requireNonNull(fields, "fields");

        for (int i = 0; i < fields.count(); i++) {
            int nameIndex = fields.nameIndex(i);
            int nameLength = fields.nameLength(i);
            int valueLength = fields.valueLength(i);
            if (nameIndex != HpackFields.NAME_LITERAL
                && nameIndex <= HpackFields.NAME_STATUS) {
                return false;
            }
            if (nameIndex == HpackFields.NAME_LITERAL) {
                if (nameLength == 0
                    || containsUppercase(i, nameLength)
                    || decoder.fieldNameByte(fields, i, 0, decodedHeaders) == ':'
                    || isConnectionSpecificHeader(i, nameLength)
                    || nameEquals(i, nameLength, "content-length")) {
                    return false;
                }
                if (nameEquals(i, nameLength, "te")
                    && !valueEquals(i, valueLength, "trailers")) {
                    return false;
                }
            } else if (nameIndex == HpackFields.NAME_TRANSFER_ENCODING
                       || nameIndex == HpackFields.NAME_CONTENT_LENGTH) {
                return false;
            }
        }
        return true;
    }

    private void addHeader(Request picoRequest, int fieldIndex) {
        if (picoRequest.numHeaders == picoRequest.headers.length) {
            throw new IllegalStateException("Too many decoded headers");
        }
        Header header = picoRequest.headers[picoRequest.numHeaders++];
        header.nameOffset = materializeName(fieldIndex);
        header.nameLen = fields.nameLength(fieldIndex);
        header.valueOffset = materializeValue(fieldIndex);
        header.valueLen = fields.valueLength(fieldIndex);
    }

    public int outputLength() {
        return outputLength;
    }

    private int materializeName(int fieldIndex) {
        if (fields.nameSource(fieldIndex) == HpackFields.SOURCE_OUTPUT) {
            return fields.nameReference(fieldIndex);
        }
        int length = fields.nameLength(fieldIndex);
        ensureOutputCapacity(length);
        int offset = outputLength;
        decoder.copyFieldName(fields, fieldIndex, decodedHeaders, offset);
        fields.materializeName(fieldIndex, offset);
        outputLength += length;
        return offset;
    }

    private int materializeValue(int fieldIndex) {
        if (fields.valueSource(fieldIndex) == HpackFields.SOURCE_OUTPUT) {
            return fields.valueReference(fieldIndex);
        }
        int length = fields.valueLength(fieldIndex);
        ensureOutputCapacity(length);
        int offset = outputLength;
        decoder.copyFieldValue(fields, fieldIndex, decodedHeaders, offset);
        fields.materializeValue(fieldIndex, offset);
        outputLength += length;
        return offset;
    }

    private int requestMethodCode(int fieldIndex, int length) {
        if (length == 3 && valueEquals(fieldIndex, length, "GET")) {
            return 1;
        }
        if (length == 4 && valueEquals(fieldIndex, length, "POST")) {
            return 2;
        }
        return 0;
    }

    private void ensureOutputCapacity(int length) {
        if (length < 0 || outputLength > decodedHeaders.byteSize() - length) {
            throw new IllegalStateException("Decoded headers exceed output slab");
        }
    }

    private int parseContentLength(int fieldIndex, int length) {
        if (length == 0) {
            return -1;
        }
        long value = 0;
        boolean inOutput =
            fields.valueSource(fieldIndex) == HpackFields.SOURCE_OUTPUT;
        int valueOffset = fields.valueReference(fieldIndex);
        for (int i = 0; i < length; i++) {
            int digit = (inOutput
                ? RawSegment.getByte(
                    decodedHeadersRaw, (long) valueOffset + i)
                : decoder.fieldValueByte(
                    fields, fieldIndex, i, decodedHeaders)) - '0';
            if (digit < 0 || digit > 9) {
                return -1;
            }
            value = value * 10 + digit;
            if (value > Integer.MAX_VALUE || value > maximumRequestSize) {
                return -1;
            }
        }
        return (int) value;
    }

    private boolean containsUppercase(int fieldIndex, int length) {
        for (int i = 0; i < length; i++) {
            byte value = decoder.fieldNameByte(
                fields, fieldIndex, i, decodedHeaders);
            if (value >= 'A' && value <= 'Z') {
                return true;
            }
        }
        return false;
    }

    private boolean isConnectionSpecificHeader(int fieldIndex, int length) {
        return nameEquals(fieldIndex, length, "connection")
            || nameEquals(fieldIndex, length, "proxy-connection")
            || nameEquals(fieldIndex, length, "keep-alive")
            || nameEquals(fieldIndex, length, "transfer-encoding")
            || nameEquals(fieldIndex, length, "upgrade");
    }

    private boolean nameEquals(int fieldIndex, int length, String expected) {
        if (length != expected.length()) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (decoder.fieldNameByte(fields, fieldIndex, i, decodedHeaders)
                != (byte) expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private boolean valueEquals(int fieldIndex, int length, String expected) {
        if (length != expected.length()) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (decoder.fieldValueByte(fields, fieldIndex, i, decodedHeaders)
                != (byte) expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
