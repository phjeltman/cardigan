// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResponseBodyTest {
    @Test
    void exposesAlreadyEncodedBytesWithoutCopying() {
        byte[] body = {1, 2, 3};

        Response response = Response.bytes("application/json", body);

        assertSame(body, response.body());
        assertEquals(Response.CT_JSON, response.contentTypeCode());
    }

    @Test
    void rejectsNullByteBody() {
        assertThrows(
            NullPointerException.class,
            () -> Response.bytes("application/octet-stream", null));
    }
}
