// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import dev.cardigan.ffi.RawSegment;

public final class Utf8Slice {
    private final MemorySegment segment;
    private final long address;
    private final long offset;
    private final long length;

    public Utf8Slice(MemorySegment segment, long offset, long length) {
        this.segment = segment;
        this.address = segment != null ? segment.address() : 0;
        this.offset = offset;
        this.length = length;
    }

    public MemorySegment segment() { return segment; }
    public long address() { return address; }
    public long offset() { return offset; }
    public long length() { return length; }

    @Override
    public String toString() {
        if (length <= 0 || segment == null) return "";
        return new String(segment.asSlice(offset, length).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    public boolean equalsString(String str) {
        if (str.length() != length) return false;
        long ptr = address + offset;
        for (int i = 0; i < length; i++) {
            if (RawSegment.getByte(ptr, i) != (byte) str.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    public boolean equalsIgnoreCaseString(String str) {
        if (str.length() != length) return false;
        long ptr = address + offset;
        for (int i = 0; i < length; i++) {
            byte b = RawSegment.getByte(ptr, i);
            int c1 = b >= 'A' && b <= 'Z' ? b + 32 : b;
            int c2 = str.charAt(i);
            c2 = c2 >= 'A' && c2 <= 'Z' ? c2 + 32 : c2;
            if (c1 != c2) {
                return false;
            }
        }
        return true;
    }
}
