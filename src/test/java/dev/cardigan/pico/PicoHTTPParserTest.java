/*
 * Copyright (c) 2009-2014 Kazuho Oku, Tokuhiro Matsuno, Daisuke Murase,
 *                         Shigeo Mitsunari
 * Copyright (c) 2026 dev.cardigan contributors
 *
 * The software is licensed under either the MIT License (below) or the Perl
 * license.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to
 * deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */

package dev.cardigan.pico;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class PicoHTTPParserTest {

    private static void assertBufIs(MemorySegment ms, long offset, long len, String expected) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(expectedBytes.length, len, () -> {
            byte[] actualBytes = new byte[(int) len];
            for (int i = 0; i < len; i++) {
                actualBytes[i] = ms.get(ValueLayout.JAVA_BYTE, offset + i);
            }
            return "Length mismatch. Expected: " + expected + ", got: " + new String(actualBytes, StandardCharsets.ISO_8859_1);
        });
        for (int i = 0; i < len; i++) {
            assertEquals(expectedBytes[i], ms.get(ValueLayout.JAVA_BYTE, offset + i), "Mismatch at index " + i + " inside: " + expected);
        }
    }

    @Test
    public void testRequest() {
        Request req = new Request(4);

        {
            String s = "GET / HTTP/1.0\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(ms.byteSize(), res);
            assertEquals(0, req.numHeaders);
            assertBufIs(ms, req.methodOffset, req.methodLen, "GET");
            assertBufIs(ms, req.pathOffset, req.pathLen, "/");
            assertEquals(0, req.minorVersion);
        }

        {
            String s = "GET / HTTP/1.0\r\n\r";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-2, res);
        }

        {
            String s = "GET /hoge HTTP/1.1\r\nHost: example.com\r\nCookie: \r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(ms.byteSize(), res);
            assertEquals(2, req.numHeaders);
            assertBufIs(ms, req.methodOffset, req.methodLen, "GET");
            assertBufIs(ms, req.pathOffset, req.pathLen, "/hoge");
            assertEquals(1, req.minorVersion);
            assertBufIs(ms, req.headers[0].nameOffset, req.headers[0].nameLen, "Host");
            assertBufIs(ms, req.headers[0].valueOffset, req.headers[0].valueLen, "example.com");
            assertBufIs(ms, req.headers[1].nameOffset, req.headers[1].nameLen, "Cookie");
            assertBufIs(ms, req.headers[1].valueOffset, req.headers[1].valueLen, "");
        }

        {
            String s = "GET /hoge HTTP/1.1\r\nHost: example.com\r\nUser-Agent: \u00e3\u0081\u00b2\u00e3/1.0\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(ms.byteSize(), res);
            assertEquals(2, req.numHeaders);
            assertBufIs(ms, req.headers[0].nameOffset, req.headers[0].nameLen, "Host");
            assertBufIs(ms, req.headers[0].valueOffset, req.headers[0].valueLen, "example.com");
            assertBufIs(ms, req.headers[1].nameOffset, req.headers[1].nameLen, "User-Agent");
            assertBufIs(ms, req.headers[1].valueOffset, req.headers[1].valueLen, "\u00e3\u0081\u00b2\u00e3/1.0");
        }

        {
            String s = "GET / HTTP/1.0\r\nfoo: \r\nfoo: b\r\n  \tc\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(ms.byteSize(), res);
            assertEquals(3, req.numHeaders);
            assertBufIs(ms, req.headers[0].nameOffset, req.headers[0].nameLen, "foo");
            assertBufIs(ms, req.headers[0].valueOffset, req.headers[0].valueLen, "");
            assertBufIs(ms, req.headers[1].nameOffset, req.headers[1].nameLen, "foo");
            assertBufIs(ms, req.headers[1].valueOffset, req.headers[1].valueLen, "b");
            assertEquals(-1, req.headers[2].nameOffset);
            assertEquals(0, req.headers[2].nameLen);
            assertBufIs(ms, req.headers[2].valueOffset, req.headers[2].valueLen, "  \tc");
        }

        {
            String s = "GET / HTTP/1.0\r\nfoo : ab\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-1, res);
        }

        {
            String s = "GET";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-2, res);
            assertEquals(-1, req.methodOffset);
        }

        {
            String s = "GET ";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-2, res);
            assertBufIs(ms, req.methodOffset, req.methodLen, "GET");
        }

        {
            String s = "GET /";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-2, res);
            assertEquals(-1, req.pathOffset);
        }

        {
            String s = "GET / ";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-2, res);
            assertBufIs(ms, req.pathOffset, req.pathLen, "/");
        }

        {
            String s = "GET / H";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-2, res);
        }

        {
            String s = "GET / HTTP/1.";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-2, res);
        }

        {
            String s = "GET / HTTP/1.0";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-2, res);
            assertEquals(-1, req.minorVersion);
        }

        {
            String s = "GET / HTTP/1.0\r";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-2, res);
            assertEquals(0, req.minorVersion);
        }

        {
            String s = "GET /hoge HTTP/1.0\r\n\r";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, ms.byteSize() - 1);
            assertEquals(-2, res);
        }

        {
            String s = "GET /hoge HTTP/1.0\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, ms.byteSize() - 1);
            assertEquals(ms.byteSize(), res);
        }

        {
            String s = " / HTTP/1.0\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-1, res);
        }

        {
            String s = "GET  HTTP/1.0\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-1, res);
        }

        {
            String s = "GET / HTTP/1.0\r\n:a\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-1, res);
        }

        {
            String s = "GET / HTTP/1.0\r\n :a\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-1, res);
        }

        {
            String s = "G\u0000T / HTTP/1.0\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-1, res);
        }

        {
            String s = "G\tT / HTTP/1.0\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-1, res);
        }

        {
            String s = ":GET / HTTP/1.0\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-1, res);
        }

        {
            String s = "GET /\u007fhello HTTP/1.0\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-1, res);
        }

        {
            String s = "GET / HTTP/1.0\r\na\u0000b: c\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-1, res);
        }

        {
            String s = "GET / HTTP/1.0\r\nab: c\u0000d\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-1, res);
        }

        {
            String s = "GET / HTTP/1.0\r\na\u001bb: c\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-1, res);
        }

        {
            String s = "GET / HTTP/1.0\r\nab: c\u001b\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-1, res);
        }

        {
            String s = "GET / HTTP/1.0\r\n/: 1\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-1, res);
        }

        {
            String s = "GET /\u00a0 HTTP/1.0\r\nh: c\u00a2y\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(ms.byteSize(), res);
            assertEquals(1, req.numHeaders);
            assertBufIs(ms, req.methodOffset, req.methodLen, "GET");
            assertBufIs(ms, req.pathOffset, req.pathLen, "/\u00a0");
            assertEquals(0, req.minorVersion);
            assertBufIs(ms, req.headers[0].nameOffset, req.headers[0].nameLen, "h");
            assertBufIs(ms, req.headers[0].valueOffset, req.headers[0].valueLen, "c\u00a2y");
        }

        {
            String s = "GET / HTTP/1.0\r\n|~: 1\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(ms.byteSize(), res);
            assertEquals(1, req.numHeaders);
            assertBufIs(ms, req.headers[0].nameOffset, req.headers[0].nameLen, "|~");
            assertBufIs(ms, req.headers[0].valueOffset, req.headers[0].valueLen, "1");
        }

        {
            String s = "GET / HTTP/1.0\r\n{: 1\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(-1, res);
        }

        {
            String s = "GET / HTTP/1.0\r\nfoo: a \t \r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(ms.byteSize(), res);
            assertBufIs(ms, req.headers[0].valueOffset, req.headers[0].valueLen, "a");
        }

        {
            String s = "GET   /   HTTP/1.0\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(ms.byteSize(), res);
        }

        // Check generic HTTP methods (POST, PUT, etc.)
        {
            String s = "POST /submit HTTP/1.1\r\nHost: example.com\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(ms.byteSize(), res);
            assertBufIs(ms, req.methodOffset, req.methodLen, "POST");
            assertBufIs(ms, req.pathOffset, req.pathLen, "/submit");
            assertEquals(1, req.minorVersion);
        }

        {
            String s = "PUT /items/1 HTTP/1.1\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long res = PicoHTTPParser.parseRequest(ms, 0, ms.byteSize(), req, 0);
            assertEquals(ms.byteSize(), res);
            assertBufIs(ms, req.methodOffset, req.methodLen, "PUT");
            assertBufIs(ms, req.pathOffset, req.pathLen, "/items/1");
        }
    }

    @Test
    public void testResponse() {
        Response res = new Response(4);

        {
            String s = "HTTP/1.0 200 OK\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(ms.byteSize(), ret);
            assertEquals(0, res.numHeaders);
            assertEquals(200, res.status);
            assertEquals(0, res.minorVersion);
            assertBufIs(ms, res.msgOffset, res.msgLen, "OK");
        }

        {
            String s = "HTTP/1.0 200 OK\r\n\r";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-2, ret);
        }

        {
            String s = "HTTP/1.1 200 OK\r\nHost: example.com\r\nCookie: \r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(ms.byteSize(), ret);
            assertEquals(2, res.numHeaders);
            assertEquals(1, res.minorVersion);
            assertEquals(200, res.status);
            assertBufIs(ms, res.msgOffset, res.msgLen, "OK");
            assertBufIs(ms, res.headers[0].nameOffset, res.headers[0].nameLen, "Host");
            assertBufIs(ms, res.headers[0].valueOffset, res.headers[0].valueLen, "example.com");
            assertBufIs(ms, res.headers[1].nameOffset, res.headers[1].nameLen, "Cookie");
            assertBufIs(ms, res.headers[1].valueOffset, res.headers[1].valueLen, "");
        }

        {
            String s = "HTTP/1.0 200 OK\r\nfoo: \r\nfoo: b\r\n  \tc\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(ms.byteSize(), ret);
            assertEquals(3, res.numHeaders);
            assertEquals(0, res.minorVersion);
            assertEquals(200, res.status);
            assertBufIs(ms, res.msgOffset, res.msgLen, "OK");
            assertBufIs(ms, res.headers[0].nameOffset, res.headers[0].nameLen, "foo");
            assertBufIs(ms, res.headers[0].valueOffset, res.headers[0].valueLen, "");
            assertBufIs(ms, res.headers[1].nameOffset, res.headers[1].nameLen, "foo");
            assertBufIs(ms, res.headers[1].valueOffset, res.headers[1].valueLen, "b");
            assertEquals(-1, res.headers[2].nameOffset);
            assertBufIs(ms, res.headers[2].valueOffset, res.headers[2].valueLen, "  \tc");
        }

        {
            String s = "HTTP/1.0 500 Internal Server Error\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(ms.byteSize(), ret);
            assertEquals(0, res.numHeaders);
            assertEquals(0, res.minorVersion);
            assertEquals(500, res.status);
            assertBufIs(ms, res.msgOffset, res.msgLen, "Internal Server Error");
        }

        {
            String s = "H";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-2, ret);
        }

        {
            String s = "HTTP/1.";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-2, ret);
        }

        {
            String s = "HTTP/1.1";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-2, ret);
            assertEquals(-1, res.minorVersion);
        }

        {
            String s = "HTTP/1.1 ";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-2, ret);
            assertEquals(1, res.minorVersion);
        }

        {
            String s = "HTTP/1.1 2";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-2, ret);
        }

        {
            String s = "HTTP/1.1 200";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-2, ret);
            assertEquals(0, res.status);
        }

        {
            String s = "HTTP/1.1 200 ";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-2, ret);
            assertEquals(200, res.status);
        }

        {
            String s = "HTTP/1.1 200 O";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-2, ret);
        }

        {
            String s = "HTTP/1.1 200 OK\r";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-2, ret);
            assertEquals(-1, res.msgOffset);
        }

        {
            String s = "HTTP/1.1 200 OK\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-2, ret);
            assertBufIs(ms, res.msgOffset, res.msgLen, "OK");
        }

        {
            String s = "HTTP/1.1 200 OK\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-2, ret);
            assertBufIs(ms, res.msgOffset, res.msgLen, "OK");
        }

        {
            String s = "HTTP/1.1 200 OK\r\nA: 1\r";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-2, ret);
            assertEquals(0, res.numHeaders);
        }

        {
            String s = "HTTP/1.1 200 OK\r\nA: 1\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-2, ret);
            assertEquals(1, res.numHeaders);
            assertBufIs(ms, res.headers[0].nameOffset, res.headers[0].nameLen, "A");
            assertBufIs(ms, res.headers[0].valueOffset, res.headers[0].valueLen, "1");
        }

        {
            String s = "HTTP/1.0 200 OK\r\n\r";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, ms.byteSize() - 1);
            assertEquals(-2, ret);
        }

        {
            String s = "HTTP/1.0 200 OK\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, ms.byteSize() - 1);
            assertEquals(ms.byteSize(), ret);
        }

        {
            String s = "HTTP/1. 200 OK\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-1, ret);
        }

        {
            String s = "HTTP/1.2z 200 OK\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-1, ret);
        }

        {
            String s = "HTTP/1.1  OK\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-1, ret);
        }

        {
            String s = "HTTP/1.1 200\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(ms.byteSize(), ret);
            assertBufIs(ms, res.msgOffset, res.msgLen, "");
        }

        {
            String s = "HTTP/1.1 200X\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-1, ret);
        }

        {
            String s = "HTTP/1.1 200X \r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-1, ret);
        }

        {
            String s = "HTTP/1.1 200X OK\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(-1, ret);
        }

        {
            String s = "HTTP/1.1 200 OK\r\nbar: \t b\t \t\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(ms.byteSize(), ret);
            assertBufIs(ms, res.headers[0].valueOffset, res.headers[0].valueLen, "b");
        }

        {
            String s = "HTTP/1.1   200   OK\r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseResponse(ms, 0, ms.byteSize(), res, 0);
            assertEquals(ms.byteSize(), ret);
        }
    }

    @Test
    public void testHeaders() {
        Header[] headers = new Header[4];
        for (int i = 0; i < 4; i++) {
            headers[i] = new Header();
        }
        int[] outNumHeaders = new int[1];

        {
            String s = "Host: example.com\r\nCookie: \r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseHeaders(ms, 0, ms.byteSize(), headers, outNumHeaders, 0);
            assertEquals(ms.byteSize(), ret);
            assertEquals(2, outNumHeaders[0]);
            assertBufIs(ms, headers[0].nameOffset, headers[0].nameLen, "Host");
            assertBufIs(ms, headers[0].valueOffset, headers[0].valueLen, "example.com");
            assertBufIs(ms, headers[1].nameOffset, headers[1].nameLen, "Cookie");
            assertBufIs(ms, headers[1].valueOffset, headers[1].valueLen, "");
        }

        {
            String s = "Host: example.com\r\nCookie: \r\n\r\n";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseHeaders(ms, 0, ms.byteSize(), headers, outNumHeaders, 1);
            assertEquals(ms.byteSize(), ret);
            assertEquals(2, outNumHeaders[0]);
            assertBufIs(ms, headers[0].nameOffset, headers[0].nameLen, "Host");
            assertBufIs(ms, headers[0].valueOffset, headers[0].valueLen, "example.com");
            assertBufIs(ms, headers[1].nameOffset, headers[1].nameLen, "Cookie");
            assertBufIs(ms, headers[1].valueOffset, headers[1].valueLen, "");
        }

        {
            String s = "Host: example.com\r\nCookie: \r\n\r";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseHeaders(ms, 0, ms.byteSize(), headers, outNumHeaders, 0);
            assertEquals(-2, ret);
        }

        {
            String s = "Host: e\u007fample.com\r\nCookie: \r\n\r";
            byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long ret = PicoHTTPParser.parseHeaders(ms, 0, ms.byteSize(), headers, outNumHeaders, 0);
            assertEquals(-1, ret);
        }
    }

    private void testChunkedAtOnce(int line, boolean consumeTrailer, String encoded, String decoded, int expected) {
        ChunkedDecoder dec = new ChunkedDecoder();
        dec.consumeTrailer = consumeTrailer;

        byte[] buf = encoded.getBytes(StandardCharsets.ISO_8859_1);
        MemorySegment ms = MemorySegment.ofArray(buf);
        long[] bufsz = new long[]{ms.byteSize()};
        long ret = PicoHTTPParser.decodeChunked(dec, ms, 0, bufsz);

        assertEquals(expected, (int) ret, "At line " + line + ": expected ret " + expected + ", got " + ret);
        byte[] decodedBytes = decoded.getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(decodedBytes.length, bufsz[0], "At line " + line + ": expected decoded size " + decodedBytes.length + ", got " + bufsz[0]);
        for (int i = 0; i < bufsz[0]; i++) {
            assertEquals(decodedBytes[i], ms.get(ValueLayout.JAVA_BYTE, i), "At line " + line + ": decoded byte mismatch at index " + i);
        }
        if (expected >= 0) {
            byte[] expectedUndecoded = encoded.substring(encoded.length() - expected).getBytes(StandardCharsets.ISO_8859_1);
            for (int i = 0; i < expected; i++) {
                assertEquals(expectedUndecoded[i], ms.get(ValueLayout.JAVA_BYTE, bufsz[0] + i), "At line " + line + ": undecoded mismatch at index " + i);
            }
        }
    }

    private void testChunkedPerByte(int line, boolean consumeTrailer, String encoded, String decoded, int expected) {
        ChunkedDecoder dec = new ChunkedDecoder();
        dec.consumeTrailer = consumeTrailer;

        byte[] encodedBytes = encoded.getBytes(StandardCharsets.ISO_8859_1);
        long bytesToConsume = encodedBytes.length - (expected >= 0 ? expected : 0);

        byte[] buf = new byte[encodedBytes.length];
        MemorySegment ms = MemorySegment.ofArray(buf);
        long bytesReady = 0;
        long[] bufsz = new long[1];

        for (int i = 0; i < bytesToConsume - 1; i++) {
            ms.set(ValueLayout.JAVA_BYTE, bytesReady, encodedBytes[i]);
            bufsz[0] = 1;
            long ret = PicoHTTPParser.decodeChunked(dec, ms, bytesReady, bufsz);
            assertEquals(-2, (int) ret, "At line " + line + ": expected -2 intermediate ret at idx " + i);
            bytesReady += bufsz[0];
        }

        // Feed the rest
        int remaining = encodedBytes.length - (int) (bytesToConsume - 1);
        for (int i = 0; i < remaining; i++) {
            ms.set(ValueLayout.JAVA_BYTE, bytesReady + i, encodedBytes[(int) (bytesToConsume - 1) + i]);
        }
        bufsz[0] = remaining;
        long ret = PicoHTTPParser.decodeChunked(dec, ms, bytesReady, bufsz);
        assertEquals(expected, (int) ret, "At line " + line + ": expected ret " + expected);
        bytesReady += bufsz[0];

        byte[] decodedBytes = decoded.getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(decodedBytes.length, bytesReady, "At line " + line + ": expected decoded size " + decodedBytes.length);
        for (int i = 0; i < bytesReady; i++) {
            assertEquals(decodedBytes[i], ms.get(ValueLayout.JAVA_BYTE, i), "At line " + line + ": decoded byte mismatch at index " + i);
        }
        if (expected >= 0) {
            byte[] expectedUndecoded = encoded.substring(encoded.length() - expected).getBytes(StandardCharsets.ISO_8859_1);
            for (int i = 0; i < expected; i++) {
                assertEquals(expectedUndecoded[i], ms.get(ValueLayout.JAVA_BYTE, bytesReady + i), "At line " + line + ": undecoded mismatch");
            }
        }
    }

    private void testChunkedFailure(int line, String encoded, int expected) {
        {
            ChunkedDecoder dec = new ChunkedDecoder();
            byte[] buf = encoded.getBytes(StandardCharsets.ISO_8859_1);
            MemorySegment ms = MemorySegment.ofArray(buf);
            long[] bufsz = new long[]{ms.byteSize()};
            long ret = PicoHTTPParser.decodeChunked(dec, ms, 0, bufsz);
            assertEquals(expected, (int) ret, "At line " + line + " (at-once): expected " + expected + ", got " + ret);
        }

        {
            ChunkedDecoder dec = new ChunkedDecoder();
            byte[] encodedBytes = encoded.getBytes(StandardCharsets.ISO_8859_1);
            byte[] buf = new byte[encodedBytes.length];
            MemorySegment ms = MemorySegment.ofArray(buf);
            long bytesReady = 0;
            long ret = -2;
            for (int i = 0; i < encodedBytes.length; i++) {
                ms.set(ValueLayout.JAVA_BYTE, bytesReady, encodedBytes[i]);
                long[] bufsz = new long[]{1};
                ret = PicoHTTPParser.decodeChunked(dec, ms, bytesReady, bufsz);
                if (ret == -1) {
                    assertEquals(expected, (int) ret, "At line " + line + " (per-byte at idx " + i + ")");
                    return;
                } else if (ret == -2) {
                    bytesReady += bufsz[0];
                } else {
                    fail("At line " + line + ": expected -1 or -2, got " + ret);
                }
            }
            assertEquals(expected, (int) ret, "At line " + line + " (per-byte final): expected " + expected + ", got " + ret);
        }
    }

    @Test
    public void testChunked() {
        int[] lines = new int[]{1, 2};
        for (int runner : lines) {
            runChunked(runner, false, "b\r\nhello world\r\n0\r\n", "hello world", 0);
            runChunked(runner, false, "6\r\nhello \r\n5\r\nworld\r\n0\r\n", "hello world", 0);
            runChunked(runner, false, "6;comment=hi\r\nhello \r\n5\r\nworld\r\n0\r\n", "hello world", 0);
            runChunked(runner, false, "6 ; comment\r\nhello \r\n5\r\nworld\r\n0\r\n", "hello world", 0);
            runChunked(runner, false, "6\r\nhello \r\n5\r\nworld\r\n0\r\na: b\r\nc: d\r\n\r\n", "hello world",
                    "a: b\r\nc: d\r\n\r\n".length());
            runChunked(runner, false, "b\r\nhello world\r\n0\r\n", "hello world", 0);
        }

        testChunkedFailure(420, "z\r\nabcdefg", -1);
        testChunkedFailure(422, "6\r\nhello \r\nffffffffffffffff\r\nabcdefg", -2);
        testChunkedFailure(423, "6\r\nhello \r\nfffffffffffffffff\r\nabcdefg", -1);
        testChunkedFailure(425, "1x\r\na\r\n0\r\n", -1);

        testChunkedFailure(428, "6\nhello \r\n5\r\nworld\r\n0\r\n", -1);
        testChunkedFailure(429, "6\r\nhello \n5\r\nworld\r\n0\r\n", -1);
        testChunkedFailure(430, "6\r\nhello \r\n5\r\nworld\n0\r\n", -1);
        testChunkedFailure(431, "6\r\nhello \r\n5\r\nworld\n0\r\n", -1);
        testChunkedFailure(432, "6\r\nhello \r\n5\r\nworld\r\n0\n", -1);
        testChunkedFailure(433, "6\rX\nhello \n5\r\nworld\r\n0\r\n", -1);
    }

    private void runChunked(int runner, boolean consumeTrailer, String encoded, String decoded, int expected) {
        if (runner == 1) {
            testChunkedAtOnce(runner, consumeTrailer, encoded, decoded, expected);
        } else {
            testChunkedPerByte(runner, consumeTrailer, encoded, decoded, expected);
        }
    }

    @Test
    public void testChunkedConsumeTrailer() {
        int[] lines = new int[]{1, 2};
        for (int runner : lines) {
            runChunked(runner, true, "b\r\nhello world\r\n0\r\n", "hello world", -2);
            runChunked(runner, true, "6\r\nhello \r\n5\r\nworld\r\n0\r\n", "hello world", -2);
            runChunked(runner, true, "6;comment=hi\r\nhello \r\n5\r\nworld\r\n0\r\n", "hello world", -2);
            runChunked(runner, true, "b\r\nhello world\r\n0\r\n\r\n", "hello world", 0);
            runChunked(runner, true, "6\r\nhello \r\n5\r\nworld\r\n0\r\na: b\r\nc: d\r\n\r\n", "hello world", 0);
            runChunked(runner, true, "b\r\nhello world\r\n0\r\n\n", "hello world", 0);
            runChunked(runner, true, "6\r\nhello \r\n5\r\nworld\r\n0\r\na: b\nc: d\n\n", "hello world", 0);
        }
    }

    @Test
    public void testChunkedDelimiterScanAcrossSwarAlignments() {
        for (int padding = 0; padding < 32; padding++) {
            String extension = "x".repeat(padding);
            testChunkedAtOnce(
                padding,
                true,
                "1;" + extension + "\r\na\r\n0\r\n\r\n",
                "a",
                0
            );
            testChunkedFailure(
                padding,
                "1;" + extension + "\na\r\n0\r\n\r\n",
                -1
            );

            String trailerValue = "v".repeat(padding);
            testChunkedAtOnce(
                padding,
                true,
                "1\r\na\r\n0\r\nX-Test: " + trailerValue + "\r\n\r\n",
                "a",
                0
            );
        }
    }

    @Test
    public void testChunkedLeftData() {
        String nextReq = "GET / HTTP/1.1\r\n\r\n";
        ChunkedDecoder dec = new ChunkedDecoder();
        dec.consumeTrailer = true;
        String s = "5\r\nabcde\r\n0\r\n\r\n" + nextReq;
        byte[] buf = s.getBytes(StandardCharsets.ISO_8859_1);
        MemorySegment ms = MemorySegment.ofArray(buf);
        long[] bufsz = new long[]{ms.byteSize()};

        long ret = PicoHTTPParser.decodeChunked(dec, ms, 0, bufsz);
        assertTrue(ret >= 0);
        assertEquals(5, bufsz[0]);
        assertBufIs(ms, 0, 5, "abcde");
        assertEquals(nextReq.length(), ret);
        assertBufIs(ms, bufsz[0], ret, nextReq);
    }

    private int doTestChunkedOverhead(int chunkLen, int chunkCount, String extra) {
        ChunkedDecoder dec = new ChunkedDecoder();
        byte[] buf = new byte[1024];
        MemorySegment ms = MemorySegment.ofArray(buf);
        long[] bufsz = new long[1];
        long ret = -2;

        for (int i = 0; i < chunkCount; i++) {
            String header = Integer.toHexString(chunkLen) + extra + "\r\n";
            byte[] headerBytes = header.getBytes(StandardCharsets.ISO_8859_1);
            for (int j = 0; j < headerBytes.length; j++) {
                ms.set(ValueLayout.JAVA_BYTE, j, headerBytes[j]);
            }
            bufsz[0] = headerBytes.length;
            ret = PicoHTTPParser.decodeChunked(dec, ms, 0, bufsz);
            if (ret != -2) {
                return (int) ret;
            }
            assertEquals(0, bufsz[0]);

            for (int j = 0; j < chunkLen; j++) {
                ms.set(ValueLayout.JAVA_BYTE, j, (byte) 'A');
            }
            bufsz[0] = chunkLen;
            ret = PicoHTTPParser.decodeChunked(dec, ms, 0, bufsz);
            if (ret != -2) {
                return (int) ret;
            }
            assertEquals(chunkLen, bufsz[0]);

            ms.set(ValueLayout.JAVA_BYTE, 0, (byte) '\r');
            ms.set(ValueLayout.JAVA_BYTE, 1, (byte) '\n');
            bufsz[0] = 2;
            ret = PicoHTTPParser.decodeChunked(dec, ms, 0, bufsz);
            if (ret != -2) {
                return (int) ret;
            }
            assertEquals(0, bufsz[0]);
        }

        ms.set(ValueLayout.JAVA_BYTE, 0, (byte) '0');
        ms.set(ValueLayout.JAVA_BYTE, 1, (byte) '\r');
        ms.set(ValueLayout.JAVA_BYTE, 2, (byte) '\n');
        ms.set(ValueLayout.JAVA_BYTE, 3, (byte) '\r');
        ms.set(ValueLayout.JAVA_BYTE, 4, (byte) '\n');
        bufsz[0] = 5;
        ret = PicoHTTPParser.decodeChunked(dec, ms, 0, bufsz);
        assertEquals(0, bufsz[0]);

        return (int) ret;
    }

    @Test
    public void testChunkedOverhead() {
        assertEquals(2, doTestChunkedOverhead(100, 10000, ""));
        assertEquals(2, doTestChunkedOverhead(10, 100000, ""));
        assertEquals(-1, doTestChunkedOverhead(1, 1000000, ""));

        assertEquals(2, doTestChunkedOverhead(10, 100000, "; tiny=1"));
        assertEquals(-1, doTestChunkedOverhead(10, 100000, "; large=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    }
}
