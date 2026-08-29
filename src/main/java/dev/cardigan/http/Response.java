// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.util.Objects;

public final class Response {

    public static final int CT_OTHER = 0;
    public static final int CT_TEXT = 1;
    public static final int CT_JSON = 2;
    private static final int MAX_METADATA_FIELDS = 64;
    private static final int MAX_METADATA_BYTES = 8 * 1024;
    private static final Response SERVICE_UNAVAILABLE =
        new Response(503, "text/plain", CT_TEXT, "Service Unavailable");

    private final int statusCode;
    private final String contentType;
    private final int contentTypeCode;
    private final Object body;
    private final Metadata metadata;

    public Response(
            int statusCode,
            String contentType,
            int contentTypeCode,
            Object body) {
        this(statusCode, contentType, contentTypeCode, body, null);
    }

    public Response(int statusCode, String contentType, Object body) {
        this(
            statusCode,
            contentType,
            "text/plain".equals(contentType)
                ? CT_TEXT
                : ("application/json".equals(contentType)
                    ? CT_JSON
                    : CT_OTHER),
            body,
            null);
    }

    private Response(
            int statusCode,
            String contentType,
            int contentTypeCode,
            Object body,
            Metadata metadata) {
        if (statusCode < 100 || statusCode > 999) {
            throw new IllegalArgumentException(
                "Invalid HTTP status code: " + statusCode);
        }
        this.statusCode = statusCode;
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        this.contentTypeCode = contentTypeCode;
        this.body = body;
        this.metadata = metadata;
    }

    public int statusCode() {
        return statusCode;
    }

    public String contentType() {
        return contentType;
    }

    public int contentTypeCode() {
        return contentTypeCode;
    }

    public Object body() {
        return body;
    }

    public ResponseHeaders headers() {
        return metadata == null ? ResponseHeaders.EMPTY : metadata.headers;
    }

    public ResponseHeaders trailers() {
        return metadata == null ? ResponseHeaders.EMPTY : metadata.trailers;
    }

    public boolean hasMetadata() {
        return metadata != null;
    }

    public Response withHeader(String name, String value) {
        ResponseHeaders next = headers().append(name, value);
        validateApplicationField(next.lastName(), false);
        return withMetadata(next, trailers());
    }

    public Response withHeaders(ResponseHeaders headers) {
        Objects.requireNonNull(headers, "headers");
        validateApplicationFields(headers, false);
        return withMetadata(headers, trailers());
    }

    public Response withTrailer(String name, String value) {
        ResponseHeaders next = trailers().append(name, value);
        validateApplicationField(next.lastName(), true);
        return withMetadata(headers(), next);
    }

    public Response withTrailers(ResponseHeaders trailers) {
        Objects.requireNonNull(trailers, "trailers");
        validateApplicationFields(trailers, true);
        return withMetadata(headers(), trailers);
    }

    private Response withMetadata(
            ResponseHeaders headers,
            ResponseHeaders trailers) {
        if (headers.size() + trailers.size() > MAX_METADATA_FIELDS) {
            throw new IllegalArgumentException(
                "Response metadata exceeds " + MAX_METADATA_FIELDS
                    + " fields");
        }
        if (headers.byteSize() + trailers.byteSize()
            > MAX_METADATA_BYTES) {
            throw new IllegalArgumentException(
                "Response metadata exceeds " + MAX_METADATA_BYTES
                    + " bytes");
        }
        Metadata next = headers.isEmpty() && trailers.isEmpty()
            ? null
            : new Metadata(headers, trailers);
        return new Response(
            statusCode, contentType, contentTypeCode, body, next);
    }

    private static void validateApplicationFields(
            ResponseHeaders fields,
            boolean trailers) {
        for (int index = 0; index < fields.size(); index++) {
            validateApplicationField(fields.name(index), trailers);
        }
    }

    private static void validateApplicationField(
            String name, boolean trailers) {
        if (isProtocolOwned(name)
            || trailers && (name.equals("host")
                || name.equals("content-encoding")
                || name.equals("content-range"))) {
            throw new IllegalArgumentException(
                "Response " + (trailers ? "trailer" : "header")
                    + " is managed by the protocol: " + name);
        }
    }

    private static boolean isProtocolOwned(String name) {
        return switch (name) {
            case "connection", "content-length", "content-type",
                 "keep-alive", "proxy-connection", "te", "trailer",
                 "transfer-encoding", "upgrade" -> true;
            default -> false;
        };
    }

    public static Response json(Object body) {
        return new Response(200, "application/json", CT_JSON, body);
    }

    public static Response text(String body) {
        return new Response(200, "text/plain", CT_TEXT, body);
    }

    public static Response text(StaticBody body) {
        return new Response(200, "text/plain", CT_TEXT, body);
    }

    /**
     * Responds with bytes that are already encoded for the supplied content
     * type. The array must not be modified after the response is returned.
     */
    public static Response bytes(String contentType, byte[] body) {
        Objects.requireNonNull(body, "body");
        return new Response(200, contentType, body);
    }

    public static Response encoded(String contentType, EncodedBody body) {
        return new Response(
            200, contentType, Objects.requireNonNull(body, "body"));
    }

    public static Response stream(String contentType, StreamingBody body) {
        return new Response(200, contentType, body);
    }

    public static Response stream(StreamingBody body) {
        return new Response(200, "application/octet-stream", CT_OTHER, body);
    }

    public static Response notFound() {
        return new Response(404, "text/plain", CT_TEXT, "Not Found");
    }

    public static Response error(String message) {
        return new Response(500, "text/plain", CT_TEXT, "Internal Error: " + message);
    }

    public static Response payloadTooLarge() {
        return new Response(413, "text/plain", CT_TEXT, "Payload Too Large");
    }

    public static Response headerFieldsTooLarge() {
        return new Response(431, "text/plain", CT_TEXT, "Request Header Fields Too Large");
    }

    public static Response expectationFailed() {
        return new Response(417, "text/plain", CT_TEXT, "Expectation Failed");
    }

    public static Response serviceUnavailable() {
        return SERVICE_UNAVAILABLE;
    }

    private record Metadata(
        ResponseHeaders headers,
        ResponseHeaders trailers
    ) {
    }
}
