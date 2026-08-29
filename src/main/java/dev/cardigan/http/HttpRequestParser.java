// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.lang.foreign.MemorySegment;
import dev.cardigan.pico.PicoHTTPParser;
import dev.cardigan.pico.Request;
import dev.cardigan.ffi.RawSegment;

public class HttpRequestParser {

    public static boolean parse(MemorySegment segment, int limit, HttpRequest request) {
        request.init(segment);

        long res = PicoHTTPParser.parseRequest(segment, 0, limit, request.picoRequest(), 0);
        if (res < 0) {
            return false;
        }
        request.splitQuery();

        long bodyStart = res;
        long bodyLen = 0;

        long framingHeaders = request.picoRequest().framingHeaders;
        int contentLengthIndex = (int) (framingHeaders
            & Request.FRAMING_INDEX_MASK) - 1;
        int connectionHeaderIndex = (int) ((framingHeaders
            >>> Request.FRAMING_CONNECTION_SHIFT)
            & Request.FRAMING_INDEX_MASK) - 1;
        request.cacheConnectionHeader(connectionHeaderIndex);
        if (contentLengthIndex >= 0) {
            long contentLength = parseDecimal(
                request.headerValue(contentLengthIndex));
            if (contentLength > 0) {
                bodyLen = contentLength;
                if (bodyStart + bodyLen > limit) {
                    return false;
                }
            }
        } else {
            bodyLen = 0;
        }

        request.setBody(bodyStart, bodyLen);
        return true;
    }

    public static long parseDecimal(Utf8Slice slice) {
        long val = 0;
        long ptr = slice.address() + slice.offset();
        for (int i = 0; i < slice.length(); i++) {
            byte b = RawSegment.getByte(ptr, i);
            if (b >= '0' && b <= '9') {
                int digit = b - '0';
                if (val > (Long.MAX_VALUE - digit) / 10) {
                    return Long.MAX_VALUE;
                }
                val = val * 10 + digit;
            } else {
                break;
            }
        }
        return val;
    }
}
