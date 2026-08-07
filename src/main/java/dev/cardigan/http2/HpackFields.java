// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http2;

/**
 * Reusable primitive metadata for one decoded HPACK field section. Compact
 * request decoding may retain indexed bytes in the static or dynamic table;
 * their public offset is {@code -1} until they are materialized.
 */
public final class HpackFields {
    static final byte SOURCE_OUTPUT = 0;
    static final byte SOURCE_STATIC = 1;
    static final byte SOURCE_DYNAMIC = 2;
    private static final int REFERENCE_STATIC = 0x8000_0000;
    private static final int REFERENCE_DYNAMIC = 0xc000_0000;
    private static final int REFERENCE_MASK = 0x3fff_ffff;

    public static final int NAME_LITERAL = 0;
    public static final int NAME_AUTHORITY = 1;
    public static final int NAME_METHOD = 2;
    public static final int NAME_PATH = 4;
    public static final int NAME_SCHEME = 6;
    public static final int NAME_STATUS = 8;
    public static final int NAME_CONTENT_LENGTH = 28;
    public static final int NAME_TRANSFER_ENCODING = 57;

    private final int[] nameOffsets;
    private final int[] nameLengths;
    private final int[] nameIndices;
    private final int[] valueOffsets;
    private final int[] valueLengths;
    private int count;
    private int headerListSize;

    public HpackFields(int maximumFields) {
        if (maximumFields <= 0) {
            throw new IllegalArgumentException("maximumFields must be positive");
        }
        this.nameOffsets = new int[maximumFields];
        this.nameLengths = new int[maximumFields];
        this.nameIndices = new int[maximumFields];
        this.valueOffsets = new int[maximumFields];
        this.valueLengths = new int[maximumFields];
    }

    public void reset() {
        count = 0;
        headerListSize = 0;
    }

    public int count() {
        return count;
    }

    public int headerListSize() {
        return headerListSize;
    }

    public int nameOffset(int index) {
        checkIndex(index);
        int offset = nameOffsets[index];
        return offset >= 0 ? offset : -1;
    }

    public int nameLength(int index) {
        checkIndex(index);
        return nameLengths[index];
    }

    /**
     * Returns the canonical HPACK static-table index for the name, or
     * {@link #NAME_LITERAL} when the name was encoded literally. Duplicate
     * static-table names use their first index, and dynamic entries retain the
     * identity of an indexed name from which they were created.
     */
    public int nameIndex(int index) {
        checkIndex(index);
        return nameIndices[index];
    }

    public int valueOffset(int index) {
        checkIndex(index);
        int offset = valueOffsets[index];
        return offset >= 0 ? offset : -1;
    }

    public int valueLength(int index) {
        checkIndex(index);
        return valueLengths[index];
    }

    boolean add(int nameOffset, int nameLength, int nameIndex,
                int valueOffset, int valueLength, int maximumHeaderListSize) {
        return store(
            nameOffset, nameLength, nameIndex,
            valueOffset, valueLength,
            maximumHeaderListSize
        );
    }

    boolean addReferenced(byte nameSource, int nameReference,
                          int nameLength, int nameIndex,
                          byte valueSource, int valueReference,
                          int valueLength, int maximumHeaderListSize) {
        return store(
            encodeReference(nameSource, nameReference),
            nameLength, nameIndex,
            encodeReference(valueSource, valueReference), valueLength,
            maximumHeaderListSize
        );
    }

    boolean addMixed(byte nameSource, int nameOffset, int nameReference,
                     int nameLength, int nameIndex,
                     byte valueSource, int valueOffset, int valueReference,
                     int valueLength, int maximumHeaderListSize) {
        return store(
            nameSource == SOURCE_OUTPUT
                ? nameOffset
                : encodeReference(nameSource, nameReference),
            nameLength, nameIndex,
            valueSource == SOURCE_OUTPUT
                ? valueOffset
                : encodeReference(valueSource, valueReference),
            valueLength,
            maximumHeaderListSize
        );
    }

    private boolean store(int nameOffset, int nameLength, int nameIndex,
                          int valueOffset, int valueLength,
                          int maximumHeaderListSize) {
        long fieldSize = (long) nameLength + valueLength + 32;
        if (count == nameOffsets.length
            || fieldSize > maximumHeaderListSize - (long) headerListSize) {
            return false;
        }
        nameOffsets[count] = nameOffset;
        nameLengths[count] = nameLength;
        nameIndices[count] = nameIndex;
        valueOffsets[count] = valueOffset;
        valueLengths[count] = valueLength;
        count++;
        headerListSize += (int) fieldSize;
        return true;
    }

    byte nameSource(int index) {
        return source(nameOffsets[index]);
    }

    int nameReference(int index) {
        return reference(nameOffsets[index]);
    }

    byte valueSource(int index) {
        return source(valueOffsets[index]);
    }

    int valueReference(int index) {
        return reference(valueOffsets[index]);
    }

    void materializeName(int index, int offset) {
        nameOffsets[index] = offset;
    }

    void materializeValue(int index, int offset) {
        valueOffsets[index] = offset;
    }

    private static int encodeReference(byte source, int reference) {
        return switch (source) {
            case SOURCE_STATIC -> REFERENCE_STATIC | reference;
            case SOURCE_DYNAMIC -> REFERENCE_DYNAMIC | reference;
            default -> reference;
        };
    }

    private static byte source(int encoded) {
        if (encoded >= 0) {
            return SOURCE_OUTPUT;
        }
        return (encoded & 0x4000_0000) == 0
            ? SOURCE_STATIC
            : SOURCE_DYNAMIC;
    }

    private static int reference(int encoded) {
        return encoded >= 0 ? encoded : encoded & REFERENCE_MASK;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(index);
        }
    }
}
