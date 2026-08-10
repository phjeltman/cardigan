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

    private static final int METADATA_STRIDE = 5;
    private static final int NAME_REFERENCE = 0;
    private static final int NAME_LENGTH = 1;
    private static final int NAME_INDEX = 2;
    private static final int VALUE_REFERENCE = 3;
    private static final int VALUE_LENGTH = 4;

    /**
     * Field metadata is consumed together by both the decoder and request
     * parser, so keep one field's five integers adjacent instead of touching
     * five independently allocated array cache lines.
     */
    private final int[] metadata;
    private final int maximumFields;
    private int count;
    private int headerListSize;

    public HpackFields(int maximumFields) {
        if (maximumFields <= 0) {
            throw new IllegalArgumentException("maximumFields must be positive");
        }
        if (maximumFields > Integer.MAX_VALUE / METADATA_STRIDE) {
            throw new IllegalArgumentException("maximumFields is too large");
        }
        this.metadata = new int[maximumFields * METADATA_STRIDE];
        this.maximumFields = maximumFields;
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
        int offset = metadata[metadataOffset(index) + NAME_REFERENCE];
        return offset >= 0 ? offset : -1;
    }

    public int nameLength(int index) {
        checkIndex(index);
        return metadata[metadataOffset(index) + NAME_LENGTH];
    }

    /**
     * Returns the canonical HPACK static-table index for the name, or
     * {@link #NAME_LITERAL} when the name was encoded literally. Duplicate
     * static-table names use their first index, and dynamic entries retain the
     * identity of an indexed name from which they were created.
     */
    public int nameIndex(int index) {
        checkIndex(index);
        return metadata[metadataOffset(index) + NAME_INDEX];
    }

    public int valueOffset(int index) {
        checkIndex(index);
        int offset = metadata[metadataOffset(index) + VALUE_REFERENCE];
        return offset >= 0 ? offset : -1;
    }

    public int valueLength(int index) {
        checkIndex(index);
        return metadata[metadataOffset(index) + VALUE_LENGTH];
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
        if (count == maximumFields
            || fieldSize > maximumHeaderListSize - (long) headerListSize) {
            return false;
        }
        int offset = metadataOffset(count);
        metadata[offset + NAME_REFERENCE] = nameOffset;
        metadata[offset + NAME_LENGTH] = nameLength;
        metadata[offset + NAME_INDEX] = nameIndex;
        metadata[offset + VALUE_REFERENCE] = valueOffset;
        metadata[offset + VALUE_LENGTH] = valueLength;
        count++;
        headerListSize += (int) fieldSize;
        return true;
    }

    byte nameSource(int index) {
        return source(metadata[metadataOffset(index) + NAME_REFERENCE]);
    }

    int nameReference(int index) {
        return reference(metadata[metadataOffset(index) + NAME_REFERENCE]);
    }

    byte valueSource(int index) {
        return source(metadata[metadataOffset(index) + VALUE_REFERENCE]);
    }

    int valueReference(int index) {
        return reference(metadata[metadataOffset(index) + VALUE_REFERENCE]);
    }

    void materializeName(int index, int offset) {
        metadata[metadataOffset(index) + NAME_REFERENCE] = offset;
    }

    void materializeValue(int index, int offset) {
        metadata[metadataOffset(index) + VALUE_REFERENCE] = offset;
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

    private static int metadataOffset(int index) {
        return index * METADATA_STRIDE;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(index);
        }
    }
}
