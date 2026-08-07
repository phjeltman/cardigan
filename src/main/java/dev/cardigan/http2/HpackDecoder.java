// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http2;

import dev.cardigan.ffi.RawSegment;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Stateful RFC 7541 decoder. General decoding writes names and values into a
 * caller-owned byte slab. Compact request decoding can instead retain indexed
 * fields as static/dynamic-table references described by {@link HpackFields};
 * the hot path creates no objects.
 */
public final class HpackDecoder {
    public static final int ERROR_COMPRESSION = -1;
    public static final int ERROR_HEADER_LIST_SIZE = -2;
    public static final int ERROR_OUTPUT_SIZE = -3;

    private static final String[] STATIC_NAMES = {
        "",
        ":authority", ":method", ":method", ":path", ":path", ":scheme", ":scheme",
        ":status", ":status", ":status", ":status", ":status", ":status", ":status",
        "accept-charset", "accept-encoding", "accept-language", "accept-ranges", "accept",
        "access-control-allow-origin", "age", "allow", "authorization", "cache-control",
        "content-disposition", "content-encoding", "content-language", "content-length",
        "content-location", "content-range", "content-type", "cookie", "date", "etag",
        "expect", "expires", "from", "host", "if-match", "if-modified-since",
        "if-none-match", "if-range", "if-unmodified-since", "last-modified", "link",
        "location", "max-forwards", "proxy-authenticate", "proxy-authorization", "range",
        "referer", "refresh", "retry-after", "server", "set-cookie",
        "strict-transport-security", "transfer-encoding", "user-agent", "vary", "via",
        "www-authenticate"
    };
    private static final String[] STATIC_VALUES = {
        "",
        "", "GET", "POST", "/", "/index.html", "http", "https",
        "200", "204", "206", "304", "400", "404", "500",
        "", "gzip, deflate", "", "", "", "", "", "", "", "", "", "", "", "", "",
        "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
        "", "", "", "", "", "", "", "", "", "", "", "", ""
    };
    private static final byte[] STATIC_BYTES;
    private static final MemorySegment STATIC_SEGMENT;
    private static final int[] STATIC_NAME_OFFSETS = new int[STATIC_NAMES.length];
    private static final int[] STATIC_NAME_LENGTHS = new int[STATIC_NAMES.length];
    private static final int[] STATIC_VALUE_OFFSETS = new int[STATIC_NAMES.length];
    private static final int[] STATIC_VALUE_LENGTHS = new int[STATIC_NAMES.length];

    static {
        int length = 0;
        for (int i = 1; i < STATIC_NAMES.length; i++) {
            length += STATIC_NAMES[i].length() + STATIC_VALUES[i].length();
        }
        STATIC_BYTES = new byte[length];
        int offset = 0;
        for (int i = 1; i < STATIC_NAMES.length; i++) {
            byte[] name = STATIC_NAMES[i].getBytes(StandardCharsets.US_ASCII);
            byte[] value = STATIC_VALUES[i].getBytes(StandardCharsets.US_ASCII);
            STATIC_NAME_OFFSETS[i] = offset;
            STATIC_NAME_LENGTHS[i] = name.length;
            System.arraycopy(name, 0, STATIC_BYTES, offset, name.length);
            offset += name.length;
            STATIC_VALUE_OFFSETS[i] = offset;
            STATIC_VALUE_LENGTHS[i] = value.length;
            System.arraycopy(value, 0, STATIC_BYTES, offset, value.length);
            offset += value.length;
        }
        STATIC_SEGMENT = MemorySegment.ofArray(STATIC_BYTES);
    }

    private final int allowedDynamicTableSize;
    private final int maximumHeaderListSize;
    private final byte[] dynamicBytes;
    private final MemorySegment dynamicSegment;
    private final int[] dynamicNameOffsets;
    private final int[] dynamicNameLengths;
    private final int[] dynamicNameIndices;
    private final int[] dynamicValueOffsets;
    private final int[] dynamicValueLengths;
    private final int[] dynamicEntrySizes;

    private int currentDynamicTableSize;
    private int dynamicTableSize;
    private int dynamicByteWrite;
    private int dynamicHead;
    private int dynamicCount;

    // Scratch results keep integer and string parsing allocation-free.
    private long nextInput;
    private int integerValue;
    private int stringOffset;
    private int stringLength;
    private int nextOutput;
    private byte resolvedSource;
    private int resolvedNameReference;
    private int resolvedValueReference;
    private int resolvedNameIndex;
    private int resolvedNameLength;
    private int resolvedValueLength;

    public HpackDecoder(int allowedDynamicTableSize, int maximumHeaderListSize) {
        if (allowedDynamicTableSize < 0) {
            throw new IllegalArgumentException("allowedDynamicTableSize must not be negative");
        }
        if (maximumHeaderListSize < 0) {
            throw new IllegalArgumentException("maximumHeaderListSize must not be negative");
        }
        this.allowedDynamicTableSize = allowedDynamicTableSize;
        this.maximumHeaderListSize = maximumHeaderListSize;
        this.currentDynamicTableSize = allowedDynamicTableSize;
        this.dynamicBytes = new byte[Math.max(1, allowedDynamicTableSize)];
        this.dynamicSegment = MemorySegment.ofArray(dynamicBytes);
        int entryCapacity = Math.max(1, allowedDynamicTableSize / 32 + 1);
        this.dynamicNameOffsets = new int[entryCapacity];
        this.dynamicNameLengths = new int[entryCapacity];
        this.dynamicNameIndices = new int[entryCapacity];
        this.dynamicValueOffsets = new int[entryCapacity];
        this.dynamicValueLengths = new int[entryCapacity];
        this.dynamicEntrySizes = new int[entryCapacity];
    }

    /**
     * Decodes one complete header block. Returns the number of bytes written to
     * {@code output}, or one of the negative error constants.
     */
    public int decode(MemorySegment source, long sourceOffset, int encodedLength,
                      MemorySegment output, HpackFields fields) {
        return decode(
            source, sourceOffset, encodedLength, output, fields, false);
    }

    /**
     * Decodes a header-only request block while retaining indexed bytes in the
     * HPACK tables. Dynamic references are copied to {@code output} only if an
     * insertion would evict their backing entry.
     */
    public int decodeRequest(MemorySegment source, long sourceOffset, int encodedLength,
                             MemorySegment output, HpackFields fields) {
        if (source.isNative() && output.isNative()) {
            return decodeNativeRequest(
                source, sourceOffset, encodedLength, output, fields);
        }
        return decode(
            source, sourceOffset, encodedLength, output, fields, true);
    }

    /**
     * Compact request decode for a stream whose headers must outlive the
     * current field section, such as a request waiting for DATA frames.
     */
    public int decodeRequestEager(MemorySegment source, long sourceOffset,
                                  int encodedLength, MemorySegment output,
                                  HpackFields fields) {
        fields.reset();
        if (encodedLength < 0 || sourceOffset < 0
            || sourceOffset > source.byteSize() - encodedLength) {
            return ERROR_COMPRESSION;
        }
        int outputLimit = (int) Math.min(
            output.byteSize(), Integer.MAX_VALUE);
        long input = sourceOffset;
        long inputEnd = sourceOffset + encodedLength;
        int outputOffset = 0;
        boolean sawField = false;

        while (input < inputEnd) {
            int first = source.get(ValueLayout.JAVA_BYTE, input) & 0xff;
            if ((first & 0x80) != 0) {
                if (!decodeInteger(source, first, input + 1, inputEnd, 7)
                    || integerValue == 0
                    || !resolveIndexed(integerValue)) {
                    return ERROR_COMPRESSION;
                }
                input = nextInput;
                boolean materializeName =
                    !isPseudoNameIndex(resolvedNameIndex);
                boolean materializeValue =
                    materializeName
                        || materializePseudoValue(resolvedNameIndex);
                int materializedLength =
                    (materializeName ? resolvedNameLength : 0)
                        + (materializeValue ? resolvedValueLength : 0);
                if (outputOffset > outputLimit - materializedLength) {
                    return ERROR_OUTPUT_SIZE;
                }

                int nameOffset = outputOffset;
                if (materializeName) {
                    copyResolvedName(output, outputOffset);
                    outputOffset += resolvedNameLength;
                }
                int valueOffset = outputOffset;
                if (materializeValue) {
                    copyResolvedValue(output, outputOffset);
                    outputOffset += resolvedValueLength;
                }
                if (!fields.add(
                        nameOffset, resolvedNameLength, resolvedNameIndex,
                        valueOffset, resolvedValueLength,
                        maximumHeaderListSize)) {
                    return ERROR_HEADER_LIST_SIZE;
                }
                sawField = true;
                continue;
            }

            if ((first & 0xe0) == 0x20) {
                if (sawField
                    || !decodeInteger(source, first, input + 1, inputEnd, 5)
                    || integerValue > allowedDynamicTableSize) {
                    return ERROR_COMPRESSION;
                }
                currentDynamicTableSize = integerValue;
                evictToLimit();
                input = nextInput;
                continue;
            }

            boolean addToDynamicTable = (first & 0x40) != 0;
            int prefix = addToDynamicTable ? 6 : 4;
            if (!decodeInteger(
                    source, first, input + 1, inputEnd, prefix)) {
                return ERROR_COMPRESSION;
            }
            input = nextInput;

            int nameOffset = outputOffset;
            int nameLength;
            int nameIndex = HpackFields.NAME_LITERAL;
            if (integerValue == 0) {
                if (!decodeString(
                        source, input, inputEnd, output,
                        outputOffset, outputLimit)) {
                    return stringFailure();
                }
                input = nextInput;
                nameOffset = stringOffset;
                nameLength = stringLength;
                outputOffset = nextOutput;
            } else {
                if (!resolveIndexed(integerValue)) {
                    return ERROR_COMPRESSION;
                }
                nameIndex = resolvedNameIndex;
                nameLength = resolvedNameLength;
                boolean materializeName =
                    addToDynamicTable || !isPseudoNameIndex(nameIndex);
                if (materializeName) {
                    if (outputOffset > outputLimit - nameLength) {
                        return ERROR_OUTPUT_SIZE;
                    }
                    copyResolvedName(output, outputOffset);
                    nameOffset = outputOffset;
                    outputOffset += nameLength;
                }
            }

            if (!decodeString(
                    source, input, inputEnd, output,
                    outputOffset, outputLimit)) {
                return stringFailure();
            }
            input = nextInput;
            int valueOffset = stringOffset;
            int valueLength = stringLength;
            outputOffset = nextOutput;
            if (!fields.add(
                    nameOffset, nameLength, nameIndex,
                    valueOffset, valueLength, maximumHeaderListSize)) {
                return ERROR_HEADER_LIST_SIZE;
            }
            if (addToDynamicTable) {
                insertMaterialized(
                    output, nameOffset, nameLength, nameIndex,
                    valueOffset, valueLength);
            }
            sawField = true;
        }
        return outputOffset;
    }

    /**
     * Production request decoding has already validated the native receive and
     * decoded-header slabs. Use absolute offsets into one constant, zero-based
     * native segment inside the byte-at-a-time HPACK loop. This lets C2 fold
     * the segment base, size, and global scope instead of loading that metadata
     * from a differently based segment for every prefix and Huffman transition.
     */
    private int decodeNativeRequest(
            MemorySegment boundedSource, long sourceOffset, int encodedLength,
            MemorySegment boundedOutput, HpackFields fields) {
        fields.reset();
        if (encodedLength < 0 || sourceOffset < 0
            || sourceOffset > boundedSource.byteSize() - encodedLength) {
            return ERROR_COMPRESSION;
        }

        int outputLimit = (int) Math.min(
            boundedOutput.byteSize(), Integer.MAX_VALUE);
        if (encodedLength == 0) {
            return 0;
        }
        MemorySegment addressSpace = RawSegment.ADDRESS_SPACE;
        long sourceAddress = boundedSource.address();
        long outputAddress = boundedOutput.address();
        long input = sourceAddress + sourceOffset;
        long inputEnd = input + encodedLength;
        int outputOffset = 0;
        boolean sawField = false;

        while (input < inputEnd) {
            int first = addressSpace.get(ValueLayout.JAVA_BYTE, input) & 0xff;
            if ((first & 0x80) != 0) {
                int index = first & 0x7f;
                if (index == 0x7f) {
                    if (!decodeIntegerAddress(
                            first, input + 1, inputEnd, 7)) {
                        return ERROR_COMPRESSION;
                    }
                    index = integerValue;
                    input = nextInput;
                } else {
                    input++;
                }
                if (index == 0 || !resolveIndexed(index)) {
                    return ERROR_COMPRESSION;
                }
                if (!fields.addReferenced(
                        resolvedSource, resolvedNameReference,
                        resolvedNameLength, resolvedNameIndex,
                        resolvedSource, resolvedValueReference,
                        resolvedValueLength, maximumHeaderListSize)) {
                    return ERROR_HEADER_LIST_SIZE;
                }
                sawField = true;
                continue;
            }

            if ((first & 0xe0) == 0x20) {
                if (sawField) {
                    return ERROR_COMPRESSION;
                }
                int tableSize = first & 0x1f;
                if (tableSize == 0x1f) {
                    if (!decodeIntegerAddress(
                            first, input + 1, inputEnd, 5)) {
                        return ERROR_COMPRESSION;
                    }
                    tableSize = integerValue;
                    input = nextInput;
                } else {
                    input++;
                }
                if (tableSize > allowedDynamicTableSize) {
                    return ERROR_COMPRESSION;
                }
                currentDynamicTableSize = tableSize;
                evictToLimit();
                continue;
            }

            boolean addToDynamicTable = (first & 0x40) != 0;
            int prefix = addToDynamicTable ? 6 : 4;
            int prefixMask = (1 << prefix) - 1;
            int indexedName = first & prefixMask;
            if (indexedName == prefixMask) {
                if (!decodeIntegerAddress(
                        first, input + 1, inputEnd, prefix)) {
                    return ERROR_COMPRESSION;
                }
                indexedName = integerValue;
                input = nextInput;
            } else {
                input++;
            }

            int nameOffset = outputOffset;
            int nameLength;
            int nameIndex = HpackFields.NAME_LITERAL;
            byte nameSource = HpackFields.SOURCE_OUTPUT;
            int nameReference = outputOffset;
            if (indexedName == 0) {
                if (!decodeStringAddress(
                        input, inputEnd, outputAddress,
                        outputOffset, outputLimit)) {
                    return stringFailure();
                }
                input = nextInput;
                nameOffset = stringOffset;
                nameLength = stringLength;
                outputOffset = nextOutput;
                nameReference = nameOffset;
            } else {
                if (!resolveIndexed(indexedName)) {
                    return ERROR_COMPRESSION;
                }
                nameIndex = resolvedNameIndex;
                nameLength = resolvedNameLength;
                nameSource = resolvedSource;
                nameReference = resolvedNameReference;
                nameOffset = -1;
            }

            if (!decodeStringAddress(
                    input, inputEnd, outputAddress,
                    outputOffset, outputLimit)) {
                return stringFailure();
            }
            input = nextInput;
            int valueOffset = stringOffset;
            int valueLength = stringLength;
            outputOffset = nextOutput;
            boolean added = nameSource == HpackFields.SOURCE_OUTPUT
                ? fields.add(
                    nameOffset, nameLength, nameIndex,
                    valueOffset, valueLength, maximumHeaderListSize)
                : fields.addMixed(
                    nameSource, nameOffset, nameReference,
                    nameLength, nameIndex,
                    HpackFields.SOURCE_OUTPUT, valueOffset, valueOffset,
                    valueLength, maximumHeaderListSize);
            if (!added) {
                return ERROR_HEADER_LIST_SIZE;
            }
            if (addToDynamicTable) {
                outputOffset = insert(
                    fields, fields.count() - 1, boundedOutput,
                    outputOffset, outputLimit, true);
                if (outputOffset < 0) {
                    return outputOffset;
                }
            }
            sawField = true;
        }
        return outputOffset;
    }

    private boolean decodeIntegerAddress(
            int first, long input, long inputEnd, int prefix) {
        int mask = (1 << prefix) - 1;
        int value = first & mask;
        if (value != mask) {
            integerValue = value;
            nextInput = input;
            return true;
        }

        MemorySegment addressSpace = RawSegment.ADDRESS_SPACE;
        long decoded = mask;
        int shift = 0;
        while (input < inputEnd) {
            int next =
                addressSpace.get(ValueLayout.JAVA_BYTE, input++) & 0xff;
            decoded += (long) (next & 0x7f) << shift;
            if (decoded > Integer.MAX_VALUE) {
                return false;
            }
            if ((next & 0x80) == 0) {
                integerValue = (int) decoded;
                nextInput = input;
                return true;
            }
            shift += 7;
            if (shift > 28) {
                return false;
            }
        }
        return false;
    }

    private boolean decodeStringAddress(
            long input, long inputEnd, long outputAddress,
            int outputOffset, int outputLimit) {
        if (input >= inputEnd) {
            integerValue = ERROR_COMPRESSION;
            return false;
        }
        MemorySegment addressSpace = RawSegment.ADDRESS_SPACE;
        int first = addressSpace.get(ValueLayout.JAVA_BYTE, input) & 0xff;
        boolean huffman = (first & 0x80) != 0;
        int encodedLength = first & 0x7f;
        if (encodedLength == 0x7f) {
            if (!decodeIntegerAddress(first, input + 1, inputEnd, 7)) {
                integerValue = ERROR_COMPRESSION;
                return false;
            }
            encodedLength = integerValue;
            input = nextInput;
        } else {
            input++;
        }
        if (encodedLength > inputEnd - input) {
            integerValue = ERROR_COMPRESSION;
            return false;
        }

        int decodedLength;
        if (huffman) {
            decodedLength = HpackHuffman.decodeAddress(
                input, encodedLength, outputAddress, outputOffset, outputLimit);
            if (decodedLength < 0) {
                integerValue = decodedLength == -2
                    ? ERROR_OUTPUT_SIZE : ERROR_COMPRESSION;
                return false;
            }
        } else {
            if (outputOffset > outputLimit - encodedLength) {
                integerValue = ERROR_OUTPUT_SIZE;
                return false;
            }
            MemorySegment.copy(
                addressSpace, input, addressSpace,
                outputAddress + outputOffset, encodedLength);
            decodedLength = encodedLength;
        }
        stringOffset = outputOffset;
        stringLength = decodedLength;
        nextOutput = outputOffset + decodedLength;
        nextInput = input + encodedLength;
        return true;
    }

    private int decode(MemorySegment source, long sourceOffset, int encodedLength,
                       MemorySegment output, HpackFields fields,
                       boolean deferIndexed) {
        fields.reset();
        if (encodedLength < 0 || sourceOffset < 0
            || sourceOffset > source.byteSize() - encodedLength) {
            return ERROR_COMPRESSION;
        }
        int outputLimit = (int) Math.min(output.byteSize(), Integer.MAX_VALUE);
        long input = sourceOffset;
        long inputEnd = sourceOffset + encodedLength;
        int outputOffset = 0;
        boolean sawField = false;

        while (input < inputEnd) {
            int first = source.get(ValueLayout.JAVA_BYTE, input) & 0xff;
            if ((first & 0x80) != 0) {
                if (!decodeInteger(
                        source, first, input + 1, inputEnd, 7)
                    || integerValue == 0) {
                    return ERROR_COMPRESSION;
                }
                input = nextInput;
                int index = integerValue;
                if (!resolveIndexed(index)) {
                    return ERROR_COMPRESSION;
                }
                if (deferIndexed) {
                    if (!fields.addReferenced(
                            resolvedSource, resolvedNameReference,
                            resolvedNameLength, resolvedNameIndex,
                            resolvedSource, resolvedValueReference,
                            resolvedValueLength, maximumHeaderListSize)) {
                        return ERROR_HEADER_LIST_SIZE;
                    }
                } else {
                    int materializedLength =
                        resolvedNameLength + resolvedValueLength;
                    if (outputOffset > outputLimit - materializedLength) {
                        return ERROR_OUTPUT_SIZE;
                    }
                    int nameOffset = outputOffset;
                    copyResolvedName(output, outputOffset);
                    outputOffset += resolvedNameLength;
                    int valueOffset = outputOffset;
                    copyResolvedValue(output, outputOffset);
                    outputOffset += resolvedValueLength;
                    if (!fields.add(
                            nameOffset, resolvedNameLength, resolvedNameIndex,
                            valueOffset, resolvedValueLength,
                            maximumHeaderListSize)) {
                        return ERROR_HEADER_LIST_SIZE;
                    }
                }
                sawField = true;
                continue;
            }

            if ((first & 0xe0) == 0x20) {
                if (sawField
                    || !decodeInteger(source, first, input + 1, inputEnd, 5)
                    || integerValue > allowedDynamicTableSize) {
                    return ERROR_COMPRESSION;
                }
                currentDynamicTableSize = integerValue;
                evictToLimit();
                input = nextInput;
                continue;
            }

            boolean addToDynamicTable = (first & 0x40) != 0;
            int prefix = addToDynamicTable ? 6 : 4;
            if (!decodeInteger(
                    source, first, input + 1, inputEnd, prefix)) {
                return ERROR_COMPRESSION;
            }
            input = nextInput;

            int nameOffset = outputOffset;
            int nameLength;
            int nameIndex = 0;
            byte nameSource = HpackFields.SOURCE_OUTPUT;
            int nameReference = outputOffset;
            if (integerValue == 0) {
                if (!decodeString(source, input, inputEnd, output, outputOffset, outputLimit)) {
                    return stringFailure();
                }
                input = nextInput;
                nameOffset = stringOffset;
                nameLength = stringLength;
                outputOffset = nextOutput;
                nameReference = nameOffset;
            } else {
                if (!resolveIndexed(integerValue)) {
                    return ERROR_COMPRESSION;
                }
                nameIndex = resolvedNameIndex;
                nameLength = resolvedNameLength;
                nameSource = resolvedSource;
                nameReference = resolvedNameReference;
                boolean materializeName = !deferIndexed;
                if (materializeName && outputOffset > outputLimit - nameLength) {
                    return ERROR_OUTPUT_SIZE;
                }
                if (materializeName) {
                    copyResolvedName(output, outputOffset);
                    nameSource = HpackFields.SOURCE_OUTPUT;
                    nameReference = outputOffset;
                    nameOffset = outputOffset;
                    outputOffset += nameLength;
                } else {
                    nameOffset = -1;
                }
            }

            if (!decodeString(source, input, inputEnd, output, outputOffset, outputLimit)) {
                return stringFailure();
            }
            input = nextInput;
            int valueOffset = stringOffset;
            int valueLength = stringLength;
            outputOffset = nextOutput;
            boolean added = nameSource == HpackFields.SOURCE_OUTPUT
                ? fields.add(
                    nameOffset, nameLength, nameIndex,
                    valueOffset, valueLength, maximumHeaderListSize)
                : fields.addMixed(
                    nameSource, nameOffset, nameReference,
                    nameLength, nameIndex,
                    HpackFields.SOURCE_OUTPUT, valueOffset, valueOffset,
                    valueLength, maximumHeaderListSize);
            if (!added) {
                return ERROR_HEADER_LIST_SIZE;
            }
            if (addToDynamicTable) {
                outputOffset = insert(
                    fields, fields.count() - 1, output,
                    outputOffset, outputLimit, deferIndexed);
                if (outputOffset < 0) {
                    return outputOffset;
                }
            }
            sawField = true;
        }
        return outputOffset;
    }

    private static boolean isPseudoNameIndex(int nameIndex) {
        return nameIndex > HpackFields.NAME_LITERAL
            && nameIndex <= HpackFields.NAME_STATUS;
    }

    private static boolean materializePseudoValue(int nameIndex) {
        return nameIndex == HpackFields.NAME_METHOD
            || nameIndex == HpackFields.NAME_PATH;
    }

    public int dynamicTableSize() {
        return dynamicTableSize;
    }

    public int dynamicTableEntryCount() {
        return dynamicCount;
    }

    private boolean decodeInteger(
            MemorySegment source, int first, long input, long inputEnd,
            int prefix) {
        int mask = (1 << prefix) - 1;
        int value = first & mask;
        if (value != mask) {
            integerValue = value;
            nextInput = input;
            return true;
        }

        long decoded = mask;
        int shift = 0;
        while (input < inputEnd) {
            int next = source.get(ValueLayout.JAVA_BYTE, input++) & 0xff;
            decoded += (long) (next & 0x7f) << shift;
            if (decoded > Integer.MAX_VALUE) {
                return false;
            }
            if ((next & 0x80) == 0) {
                integerValue = (int) decoded;
                nextInput = input;
                return true;
            }
            shift += 7;
            if (shift > 28) {
                return false;
            }
        }
        return false;
    }

    private boolean decodeString(MemorySegment source, long input, long inputEnd,
                                 MemorySegment output, int outputOffset, int outputLimit) {
        if (input >= inputEnd) {
            integerValue = ERROR_COMPRESSION;
            return false;
        }
        int first = source.get(ValueLayout.JAVA_BYTE, input) & 0xff;
        boolean huffman = (first & 0x80) != 0;
        int encodedLength = first & 0x7f;
        if (encodedLength == 0x7f) {
            if (!decodeInteger(source, first, input + 1, inputEnd, 7)) {
                integerValue = ERROR_COMPRESSION;
                return false;
            }
            encodedLength = integerValue;
            input = nextInput;
        } else {
            input++;
        }
        if (encodedLength > inputEnd - input) {
            integerValue = ERROR_COMPRESSION;
            return false;
        }

        int decodedLength;
        if (huffman) {
            decodedLength = HpackHuffman.decode(source, input, encodedLength,
                                               output, outputOffset, outputLimit);
            if (decodedLength < 0) {
                integerValue = decodedLength == -2 ? ERROR_OUTPUT_SIZE : ERROR_COMPRESSION;
                return false;
            }
        } else {
            if (outputOffset > outputLimit - encodedLength) {
                integerValue = ERROR_OUTPUT_SIZE;
                return false;
            }
            MemorySegment.copy(source, input, output, outputOffset, encodedLength);
            decodedLength = encodedLength;
        }
        stringOffset = outputOffset;
        stringLength = decodedLength;
        nextOutput = outputOffset + decodedLength;
        nextInput = input + encodedLength;
        return true;
    }

    private int stringFailure() {
        return integerValue == ERROR_OUTPUT_SIZE ? ERROR_OUTPUT_SIZE : ERROR_COMPRESSION;
    }

    private boolean resolveIndexed(int index) {
        if (index < STATIC_NAMES.length) {
            if (index <= 0) {
                return false;
            }
            resolvedSource = HpackFields.SOURCE_STATIC;
            resolvedNameReference = STATIC_NAME_OFFSETS[index];
            resolvedValueReference = STATIC_VALUE_OFFSETS[index];
            resolvedNameLength = STATIC_NAME_LENGTHS[index];
            resolvedValueLength = STATIC_VALUE_LENGTHS[index];
            resolvedNameIndex = canonicalNameIndex(index);
            return true;
        }

        int metadataIndex = dynamicMetadataIndex(index);
        if (metadataIndex < 0) {
            return false;
        }
        resolvedSource = HpackFields.SOURCE_DYNAMIC;
        resolvedNameReference = metadataIndex;
        resolvedValueReference = metadataIndex;
        resolvedNameLength = dynamicNameLengths[metadataIndex];
        resolvedValueLength = dynamicValueLengths[metadataIndex];
        resolvedNameIndex = dynamicNameIndices[metadataIndex];
        return true;
    }

    private static int canonicalNameIndex(int index) {
        return switch (index) {
            case 3 -> HpackFields.NAME_METHOD;
            case 5 -> HpackFields.NAME_PATH;
            case 7 -> HpackFields.NAME_SCHEME;
            case 9, 10, 11, 12, 13, 14 -> HpackFields.NAME_STATUS;
            default -> index;
        };
    }

    private void copyResolvedName(MemorySegment output, int outputOffset) {
        if (resolvedSource == HpackFields.SOURCE_STATIC) {
            MemorySegment.copy(
                STATIC_SEGMENT, resolvedNameReference,
                output, outputOffset, resolvedNameLength);
        } else {
            copyFromDynamic(
                dynamicNameOffsets[resolvedNameReference], resolvedNameLength,
                output, outputOffset);
        }
    }

    private void copyResolvedValue(MemorySegment output, int outputOffset) {
        if (resolvedSource == HpackFields.SOURCE_STATIC) {
            MemorySegment.copy(
                STATIC_SEGMENT, resolvedValueReference,
                output, outputOffset, resolvedValueLength);
        } else {
            copyFromDynamic(
                dynamicValueOffsets[resolvedValueReference], resolvedValueLength,
                output, outputOffset);
        }
    }

    private int dynamicMetadataIndex(int hpackIndex) {
        int rank = hpackIndex - STATIC_NAMES.length;
        if (rank < 0 || rank >= dynamicCount) {
            return -1;
        }
        int index = dynamicHead + rank;
        return index < dynamicEntrySizes.length ? index : index - dynamicEntrySizes.length;
    }

    byte fieldNameByte(HpackFields fields, int fieldIndex, int byteIndex,
                       MemorySegment output) {
        return fieldByte(
            fields.nameSource(fieldIndex), fields.nameReference(fieldIndex),
            fields.nameLength(fieldIndex), byteIndex, true, output);
    }

    byte fieldValueByte(HpackFields fields, int fieldIndex, int byteIndex,
                        MemorySegment output) {
        return fieldByte(
            fields.valueSource(fieldIndex), fields.valueReference(fieldIndex),
            fields.valueLength(fieldIndex), byteIndex, false, output);
    }

    private byte fieldByte(byte source, int reference, int length, int byteIndex,
                           boolean name, MemorySegment output) {
        if (byteIndex < 0 || byteIndex >= length) {
            throw new IndexOutOfBoundsException(byteIndex);
        }
        if (source == HpackFields.SOURCE_OUTPUT) {
            return output.get(ValueLayout.JAVA_BYTE, (long) reference + byteIndex);
        }
        if (source == HpackFields.SOURCE_STATIC) {
            return STATIC_SEGMENT.get(
                ValueLayout.JAVA_BYTE, (long) reference + byteIndex);
        }
        int offset = name
            ? dynamicNameOffsets[reference]
            : dynamicValueOffsets[reference];
        int position = offset + byteIndex;
        if (position >= dynamicBytes.length) {
            position -= dynamicBytes.length;
        }
        return dynamicSegment.get(ValueLayout.JAVA_BYTE, position);
    }

    void copyFieldName(HpackFields fields, int fieldIndex,
                       MemorySegment output, int outputOffset) {
        copyFieldPart(
            fields.nameSource(fieldIndex), fields.nameReference(fieldIndex),
            fields.nameLength(fieldIndex), true, output, outputOffset);
    }

    void copyFieldValue(HpackFields fields, int fieldIndex,
                        MemorySegment output, int outputOffset) {
        copyFieldPart(
            fields.valueSource(fieldIndex), fields.valueReference(fieldIndex),
            fields.valueLength(fieldIndex), false, output, outputOffset);
    }

    private void copyFieldPart(byte source, int reference, int length,
                               boolean name, MemorySegment output,
                               int outputOffset) {
        if (source == HpackFields.SOURCE_OUTPUT) {
            MemorySegment.copy(output, reference, output, outputOffset, length);
        } else if (source == HpackFields.SOURCE_STATIC) {
            MemorySegment.copy(
                STATIC_SEGMENT, reference, output, outputOffset, length);
        } else {
            int sourceOffset = name
                ? dynamicNameOffsets[reference]
                : dynamicValueOffsets[reference];
            copyFromDynamic(sourceOffset, length, output, outputOffset);
        }
    }

    private void insertMaterialized(
            MemorySegment source, int nameOffset, int nameLength,
            int nameIndex, int valueOffset, int valueLength) {
        int entrySize = nameLength + valueLength + 32;
        if (entrySize > currentDynamicTableSize) {
            clearDynamicTable();
            return;
        }
        while (dynamicCount != 0
            && dynamicTableSize + entrySize > currentDynamicTableSize) {
            evictOldest();
        }

        int metadataIndex = dynamicHead == 0
            ? dynamicEntrySizes.length - 1
            : dynamicHead - 1;
        int storedNameOffset = dynamicByteWrite;
        copyToDynamic(source, nameOffset, nameLength);
        int storedValueOffset = dynamicByteWrite;
        copyToDynamic(source, valueOffset, valueLength);

        dynamicNameOffsets[metadataIndex] = storedNameOffset;
        dynamicNameLengths[metadataIndex] = nameLength;
        dynamicNameIndices[metadataIndex] = nameIndex;
        dynamicValueOffsets[metadataIndex] = storedValueOffset;
        dynamicValueLengths[metadataIndex] = valueLength;
        dynamicEntrySizes[metadataIndex] = entrySize;
        dynamicHead = metadataIndex;
        dynamicCount++;
        dynamicTableSize += entrySize;
    }

    private int insert(HpackFields fields, int fieldIndex,
                       MemorySegment output, int outputOffset, int outputLimit,
                       boolean preserveReferences) {
        int nameLength = fields.nameLength(fieldIndex);
        int valueLength = fields.valueLength(fieldIndex);
        int nameIndex = fields.nameIndex(fieldIndex);
        int entrySize = nameLength + valueLength + 32;
        if (entrySize > currentDynamicTableSize) {
            if (preserveReferences) {
                outputOffset = spillAllDynamicReferences(
                    fields, output, outputOffset, outputLimit);
                if (outputOffset < 0) {
                    return outputOffset;
                }
            }
            clearDynamicTable();
            return outputOffset;
        }
        while (dynamicCount != 0
               && dynamicTableSize + entrySize > currentDynamicTableSize) {
            int oldest = oldestMetadataIndex();
            if (preserveReferences) {
                outputOffset = spillDynamicReferences(
                    fields, oldest, output, outputOffset, outputLimit);
                if (outputOffset < 0) {
                    return outputOffset;
                }
            }
            evictOldest();
        }

        int metadataIndex = dynamicHead == 0
            ? dynamicEntrySizes.length - 1
            : dynamicHead - 1;
        int storedNameOffset = dynamicByteWrite;
        copyFieldPartToDynamic(
            fields.nameSource(fieldIndex), fields.nameReference(fieldIndex),
            nameLength, true, output);
        int storedValueOffset = dynamicByteWrite;
        copyFieldPartToDynamic(
            fields.valueSource(fieldIndex), fields.valueReference(fieldIndex),
            valueLength, false, output);

        dynamicNameOffsets[metadataIndex] = storedNameOffset;
        dynamicNameLengths[metadataIndex] = nameLength;
        dynamicNameIndices[metadataIndex] = nameIndex;
        dynamicValueOffsets[metadataIndex] = storedValueOffset;
        dynamicValueLengths[metadataIndex] = valueLength;
        dynamicEntrySizes[metadataIndex] = entrySize;
        dynamicHead = metadataIndex;
        dynamicCount++;
        dynamicTableSize += entrySize;
        return outputOffset;
    }

    private void copyFieldPartToDynamic(byte source, int reference, int length,
                                        boolean name, MemorySegment output) {
        if (source == HpackFields.SOURCE_OUTPUT) {
            copyToDynamic(output, reference, length);
        } else if (source == HpackFields.SOURCE_STATIC) {
            copyToDynamic(STATIC_SEGMENT, reference, length);
        } else {
            int sourceOffset = name
                ? dynamicNameOffsets[reference]
                : dynamicValueOffsets[reference];
            int firstLength = Math.min(
                length, dynamicBytes.length - sourceOffset);
            copyToDynamic(dynamicSegment, sourceOffset, firstLength);
            if (firstLength != length) {
                copyToDynamic(dynamicSegment, 0, length - firstLength);
            }
        }
    }

    private int spillAllDynamicReferences(
            HpackFields fields, MemorySegment output,
            int outputOffset, int outputLimit) {
        for (int i = 0; i < fields.count(); i++) {
            if (fields.nameSource(i) == HpackFields.SOURCE_DYNAMIC) {
                int length = fields.nameLength(i);
                if (outputOffset > outputLimit - length) {
                    return ERROR_OUTPUT_SIZE;
                }
                copyFieldName(fields, i, output, outputOffset);
                fields.materializeName(i, outputOffset);
                outputOffset += length;
            }
            if (fields.valueSource(i) == HpackFields.SOURCE_DYNAMIC) {
                int length = fields.valueLength(i);
                if (outputOffset > outputLimit - length) {
                    return ERROR_OUTPUT_SIZE;
                }
                copyFieldValue(fields, i, output, outputOffset);
                fields.materializeValue(i, outputOffset);
                outputOffset += length;
            }
        }
        return outputOffset;
    }

    private int spillDynamicReferences(
            HpackFields fields, int metadataIndex, MemorySegment output,
            int outputOffset, int outputLimit) {
        for (int i = 0; i < fields.count(); i++) {
            if (fields.nameSource(i) == HpackFields.SOURCE_DYNAMIC
                && fields.nameReference(i) == metadataIndex) {
                int length = fields.nameLength(i);
                if (outputOffset > outputLimit - length) {
                    return ERROR_OUTPUT_SIZE;
                }
                copyFieldName(fields, i, output, outputOffset);
                fields.materializeName(i, outputOffset);
                outputOffset += length;
            }
            if (fields.valueSource(i) == HpackFields.SOURCE_DYNAMIC
                && fields.valueReference(i) == metadataIndex) {
                int length = fields.valueLength(i);
                if (outputOffset > outputLimit - length) {
                    return ERROR_OUTPUT_SIZE;
                }
                copyFieldValue(fields, i, output, outputOffset);
                fields.materializeValue(i, outputOffset);
                outputOffset += length;
            }
        }
        return outputOffset;
    }

    private int oldestMetadataIndex() {
        int oldest = dynamicHead + dynamicCount - 1;
        return oldest < dynamicEntrySizes.length
            ? oldest
            : oldest - dynamicEntrySizes.length;
    }

    private void evictToLimit() {
        while (dynamicCount != 0 && dynamicTableSize > currentDynamicTableSize) {
            evictOldest();
        }
    }

    private void evictOldest() {
        int oldest = oldestMetadataIndex();
        dynamicTableSize -= dynamicEntrySizes[oldest];
        dynamicCount--;
        if (dynamicCount == 0) {
            dynamicHead = 0;
            dynamicByteWrite = 0;
        }
    }

    private void clearDynamicTable() {
        dynamicTableSize = 0;
        dynamicByteWrite = 0;
        dynamicHead = 0;
        dynamicCount = 0;
    }

    private void copyToDynamic(MemorySegment source, int sourceOffset, int length) {
        int firstLength = Math.min(length, dynamicBytes.length - dynamicByteWrite);
        MemorySegment.copy(source, sourceOffset, dynamicSegment, dynamicByteWrite, firstLength);
        int remaining = length - firstLength;
        if (remaining != 0) {
            MemorySegment.copy(source, sourceOffset + firstLength, dynamicSegment, 0, remaining);
        }
        dynamicByteWrite += length;
        if (dynamicByteWrite >= dynamicBytes.length) {
            dynamicByteWrite -= dynamicBytes.length;
        }
    }

    private void copyFromDynamic(int sourceOffset, int length,
                                 MemorySegment output, int outputOffset) {
        int firstLength = Math.min(length, dynamicBytes.length - sourceOffset);
        MemorySegment.copy(dynamicSegment, sourceOffset, output, outputOffset, firstLength);
        int remaining = length - firstLength;
        if (remaining != 0) {
            MemorySegment.copy(dynamicSegment, 0, output, outputOffset + firstLength, remaining);
        }
    }
}
