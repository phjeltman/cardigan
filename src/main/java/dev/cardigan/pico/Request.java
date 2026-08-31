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

public class Request {
    public static final int FRAMING_CONTENT_LENGTH_SHIFT = 0;
    public static final int FRAMING_TRANSFER_ENCODING_SHIFT = 16;
    public static final int FRAMING_EXPECT_SHIFT = 32;
    public static final int FRAMING_CONNECTION_SHIFT = 48;
    public static final long FRAMING_INDEX_MASK = 0x7fffL;
    public static final long FRAMING_DUPLICATE_MASK = 0x8000L;

    public int methodCode = 0;
    public long methodOffset = -1;
    public long methodLen = 0;
    public long pathOffset = -1;
    public long pathLen = 0;
    public long targetLen = 0;
    public long queryOffset = -1;
    public long queryLen = 0;
    public int minorVersion = -1;
    public final Header[] headers;
    public int numHeaders = 0;
    public long framingHeaders = 0;

    public Request(int maxHeaders) {
        this.headers = new Header[maxHeaders];
        for (int i = 0; i < maxHeaders; i++) {
            this.headers[i] = new Header();
        }
    }

    public void reset() {
        methodCode = 0;
        methodOffset = -1;
        methodLen = 0;
        pathOffset = -1;
        pathLen = 0;
        targetLen = 0;
        queryOffset = -1;
        queryLen = 0;
        minorVersion = -1;
        numHeaders = 0;
        framingHeaders = 0;
    }
}
