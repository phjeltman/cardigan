// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.serdes;

import dev.cardigan.http.Utf8Slice;
import dev.cardigan.json.JsonWriter;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/**
 * On-Demand SIMD Format Serializer Driver.
 * Writes JSON output directly into MemorySegment with zero intermediate allocations.
 */
public final class SimdJsonSerializer {

    private MemorySegment segment;
    private long offset;

    public SimdJsonSerializer(MemorySegment segment) {
        this.segment = segment;
        this.offset = 0;
    }

    public SimdJsonSerializer(MemorySegment segment, long startOffset) {
        this.segment = segment;
        this.offset = startOffset;
    }

    public void reset(MemorySegment segment, long startOffset) {
        this.segment = segment;
        this.offset = startOffset;
    }

    public MemorySegment getSegment() {
        return segment;
    }

    public long getOffset() {
        return offset;
    }

    public long getBytesWritten() {
        return offset;
    }

    public void writeInt(int val) {
        offset = JsonWriter.writeInt(segment, offset, val);
    }

    public void writeLong(long val) {
        offset = JsonWriter.writeLong(segment, offset, val);
    }

    public void writeFloat(float val) {
        writeDouble(val);
    }

    public void writeDouble(double val) {
        offset = JsonWriter.writeDouble(segment, offset, val);
    }

    public void writeBoolean(boolean val) {
        offset = JsonWriter.writeBoolean(segment, offset, val);
    }

    public void writeNull() {
        segment.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, offset, 0x6c6c756e);
        offset += 4;
    }

    public void writeString(String str) {
        if (str == null) {
            writeNull();
        } else {
            offset = JsonWriter.writeUtf8String(segment, offset, str);
        }
    }

    public void writeSlice(Utf8Slice slice) {
        if (slice == null) {
            writeNull();
        } else {
            offset = JsonWriter.writeSlice(segment, offset, slice);
        }
    }

    public void writeKey(String key) {
        offset = JsonWriter.writeUtf8String(segment, offset, key);
        segment.set(java.lang.foreign.ValueLayout.JAVA_BYTE, offset++, (byte) ':');
    }

    public void writePreEncodedKey(MemorySegment preEncodedKeySegment) {
        long len = preEncodedKeySegment.byteSize();
        MemorySegment.copy(preEncodedKeySegment, 0, segment, offset, len);
        offset += len;
    }

    public void startObject() {
        segment.set(java.lang.foreign.ValueLayout.JAVA_BYTE, offset++, (byte) '{');
    }

    public void endObject() {
        segment.set(java.lang.foreign.ValueLayout.JAVA_BYTE, offset++, (byte) '}');
    }

    public void startArray() {
        segment.set(java.lang.foreign.ValueLayout.JAVA_BYTE, offset++, (byte) '[');
    }

    public void endArray() {
        segment.set(java.lang.foreign.ValueLayout.JAVA_BYTE, offset++, (byte) ']');
    }

    public void writeComma() {
        segment.set(java.lang.foreign.ValueLayout.JAVA_BYTE, offset++, (byte) ',');
    }

    public <T extends Record> void writeRecord(T record) {
        if (record == null) {
            writeNull();
        } else {
            offset += JsonWriter.writeRecord(segment, offset, record);
        }
    }

    public <T> void write(T value, Serialize<T> serializer) {
        serializer.serialize(value, this);
    }

    public byte[] toByteArray() {
        byte[] bytes = new byte[(int) offset];
        MemorySegment.copy(segment, 0, MemorySegment.ofArray(bytes), 0, (int) offset);
        return bytes;
    }

    @Override
    public String toString() {
        return new String(toByteArray(), StandardCharsets.UTF_8);
    }
}
