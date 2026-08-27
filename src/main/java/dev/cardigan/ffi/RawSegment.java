// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.ffi;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

public final class RawSegment {
    /**
     * A process-wide, zero-based view of the native address space.
     *
     * <p>Hot native-memory loops use absolute addresses as offsets into this
     * segment, giving C2 a constant base, size, and global scope for every
     * buffer access.</p>
     */
    public static final MemorySegment ADDRESS_SPACE =
        MemorySegment.ofAddress(0).reinterpret(Long.MAX_VALUE);

    public static final VarHandle BYTE = ValueLayout.JAVA_BYTE.varHandle();
    public static final VarHandle INT_UNALIGNED = ValueLayout.JAVA_INT_UNALIGNED.varHandle();
    public static final VarHandle LONG_UNALIGNED = ValueLayout.JAVA_LONG_UNALIGNED.varHandle();

    public static MemorySegment raw(long address) {
        return MemorySegment.ofAddress(address).reinterpret(Long.MAX_VALUE);
    }

    public static byte getByte(MemorySegment segment, long offset) {
        return (byte) BYTE.get(segment, offset);
    }

    public static int getInt(MemorySegment segment, long offset) {
        return (int) INT_UNALIGNED.get(segment, offset);
    }

    public static long getLong(MemorySegment segment, long offset) {
        return (long) LONG_UNALIGNED.get(segment, offset);
    }

    public static byte getByte(long address, long offset) {
        return (byte) BYTE.get(raw(address), offset);
    }

    public static int getInt(long address, long offset) {
        return (int) INT_UNALIGNED.get(raw(address), offset);
    }

    public static long getLong(long address, long offset) {
        return (long) LONG_UNALIGNED.get(raw(address), offset);
    }

    public static void copy(byte[] src, int srcPos, long destAddr, int len) {
        MemorySegment.copy(src, srcPos, raw(destAddr), ValueLayout.JAVA_BYTE, 0, len);
    }

    public static void copy(long srcAddr, long destAddr, long len) {
        MemorySegment.copy(raw(srcAddr), 0, raw(destAddr), 0, len);
    }

    public static void putLong(long address, long offset, long value) {
        LONG_UNALIGNED.set(raw(address), offset, value);
    }

    public static void putInt(long address, long offset, int value) {
        INT_UNALIGNED.set(raw(address), offset, value);
    }
}
