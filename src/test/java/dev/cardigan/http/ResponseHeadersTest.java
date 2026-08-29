// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseHeadersTest {
    @Test
    void storesNormalizedFieldsInInsertionOrder() {
        ResponseHeaders headers = ResponseHeaders.builder()
            .add("Cache-Control", "no-cache")
            .add("Set-Cookie", "a=1")
            .add("Set-Cookie", "b=2")
            .build();

        assertEquals(3, headers.size());
        assertEquals("cache-control", headers.name(0));
        assertEquals("set-cookie", headers.name(1));
        assertEquals("a=1", headers.value(1));
        assertEquals("b=2", headers.value(2));
    }

    @Test
    void metadataRemainsAbsentOnOrdinaryResponse() {
        Response response = Response.text("hello");

        assertFalse(response.hasMetadata());
        assertTrue(response.headers().isEmpty());
        assertTrue(response.trailers().isEmpty());
    }

    @Test
    void addsHeadersAndTrailersWithoutMutatingSource() {
        Response original = Response.text("hello");
        Response response = original
            .withHeader("cache-control", "no-cache")
            .withTrailer("grpc-status", "0");

        assertFalse(original.hasMetadata());
        assertEquals("no-cache", response.headers().value(0));
        assertEquals("grpc-status", response.trailers().name(0));
        assertEquals("0", response.trailers().value(0));
    }

    @Test
    void chainedHeadersMaterializeOnceInInsertionOrder() {
        Response original = Response.text("hello");
        Response response = original;
        for (int index = 0; index < 32; index++) {
            response = response.withHeader(
                "X-Sequence-" + index, Integer.toString(index));
        }

        assertTrue(original.headers().isEmpty());
        assertEquals(32, response.headers().size());
        for (int index = 0; index < 32; index++) {
            assertEquals("x-sequence-" + index,
                response.headers().name(index));
            assertEquals(Integer.toString(index),
                response.headers().value(index));
        }
    }

    @Test
    void rejectsInjectionAndProtocolOwnedFields() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ResponseHeaders.of("x-test", "yes\r\nInjected: true"));
        assertThrows(
            IllegalArgumentException.class,
            () -> ResponseHeaders.of("bad name", "value"));
        assertThrows(
            IllegalArgumentException.class,
            () -> Response.text("hello")
                .withHeader("content-length", "99"));
        assertThrows(
            IllegalArgumentException.class,
            () -> Response.text("hello")
                .withTrailer("content-encoding", "gzip"));
    }
}
