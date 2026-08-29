// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.json;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import dev.cardigan.ffi.RawSegment;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

public class RecordCache {
    private static final int CACHE_SIZE = 128;
    private static final int MASK = CACHE_SIZE - 1;
    private static final RecordMetadata[] FAST_CACHE = new RecordMetadata[CACHE_SIZE];

    private static final ClassValue<RecordMetadata> CLASS_VALUE_CACHE = new ClassValue<>() {
        @Override
        protected RecordMetadata computeValue(Class<?> type) {
            return new RecordMetadata(type);
        }
    };

    public static RecordMetadata getMetadata(Class<?> recordClass) {
        int slot = System.identityHashCode(recordClass) & MASK;
        RecordMetadata meta = FAST_CACHE[slot];
        if (meta != null && meta.recordClass == recordClass) {
            return meta;
        }
        meta = CLASS_VALUE_CACHE.get(recordClass);
        FAST_CACHE[slot] = meta;
        return meta;
    }

    public static class RecordMetadata {
        public final Class<?> recordClass;
        public final Constructor<?> constructor;
        public final java.lang.invoke.MethodHandle constructorHandle;
        public final java.lang.invoke.MethodHandle constructorSpreader;
        public final String[] componentNames;
        public final Class<?>[] componentTypes;
        final java.lang.invoke.MethodHandle[] accessorHandles;
        public final byte[][] preEncodedKeyBytes;
        public final FieldWriter[] fieldWriters;
        public final int[] componentLengths;
        public final long[] componentMasks;
        public final long[] componentLongs;
        final int hashMask;
        final long[] hashTableLongs;
        final int[] hashLengths;
        final byte[] hashIndices;

        public static final byte TYPE_OTHER = 0;
        public static final byte TYPE_STRING = 1;
        public static final byte TYPE_UTF8_SLICE = 2;
        public static final byte TYPE_INT = 3;
        public static final byte TYPE_LONG = 4;
        public static final byte TYPE_FLOAT = 5;
        public static final byte TYPE_DOUBLE = 6;
        public static final byte TYPE_BOOLEAN = 7;
        public static final byte TYPE_RECORD = 8;

        public final byte[] componentTypeCodes;
        public final Object[] defaultArgs;

        RecordMetadata(Class<?> recordClass) {
            this.recordClass = recordClass;
            try {
                if (!Record.class.isAssignableFrom(recordClass)) {
                    throw new IllegalArgumentException("Class must be a Record: " + recordClass.getName());
                }

                java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
                RecordComponent[] components = recordClass.getRecordComponents();
                int n = components.length;
                this.componentNames = new String[n];
                this.componentTypes = new Class<?>[n];
                this.componentTypeCodes = new byte[n];
                this.defaultArgs = new Object[n];
                this.accessorHandles = new java.lang.invoke.MethodHandle[n];
                this.preEncodedKeyBytes = new byte[n][];
                this.fieldWriters = new FieldWriter[n];
                this.componentLengths = new int[n];
                this.componentMasks = new long[n];
                this.componentLongs = new long[n];

                for (int i = 0; i < n; i++) {
                    RecordComponent comp = components[i];
                    String name = comp.getName();
                    Class<?> type = comp.getType();
                    this.componentNames[i] = name;
                    this.componentTypes[i] = type;

                    if (type == String.class) componentTypeCodes[i] = TYPE_STRING;
                    else if (type == dev.cardigan.http.Utf8Slice.class) componentTypeCodes[i] = TYPE_UTF8_SLICE;
                    else if (type == int.class || type == Integer.class) componentTypeCodes[i] = TYPE_INT;
                    else if (type == long.class || type == Long.class) componentTypeCodes[i] = TYPE_LONG;
                    else if (type == float.class || type == Float.class) componentTypeCodes[i] = TYPE_FLOAT;
                    else if (type == double.class || type == Double.class) componentTypeCodes[i] = TYPE_DOUBLE;
                    else if (type == boolean.class || type == Boolean.class) componentTypeCodes[i] = TYPE_BOOLEAN;
                    else if (Record.class.isAssignableFrom(type)) componentTypeCodes[i] = TYPE_RECORD;
                    else componentTypeCodes[i] = TYPE_OTHER;

                    if (type == int.class) defaultArgs[i] = 0;
                    else if (type == long.class) defaultArgs[i] = 0L;
                    else if (type == boolean.class) defaultArgs[i] = Boolean.FALSE;
                    else if (type == double.class) defaultArgs[i] = 0.0;
                    else if (type == float.class) defaultArgs[i] = 0.0f;
                    else if (type == short.class) defaultArgs[i] = (short) 0;
                    else if (type == byte.class) defaultArgs[i] = (byte) 0;
                    else if (type == char.class) defaultArgs[i] = '\0';
                    
                    byte[] b = name.getBytes(StandardCharsets.US_ASCII);
                    this.componentLengths[i] = b.length;
                    long mask = b.length == 8 ? -1L : (1L << (b.length * 8)) - 1;
                    if (b.length > 8) mask = -1L;
                    this.componentMasks[i] = mask;
                    long word = 0;
                    int limit = Math.min(b.length, 8);
                    for (int j = 0; j < limit; j++) {
                        word |= ((long) (b[j] & 0xff)) << (j * 8);
                    }
                    this.componentLongs[i] = word;
                    
                    Method accessor = comp.getAccessor();
                    try {
                        accessor.setAccessible(true);
                    } catch (Exception e) {
                        // unreflect() below determines whether access is sufficient.
                    }
                    this.accessorHandles[i] = lookup.unreflect(accessor);

                    // Pre-encode key segment: {"name": for first, ,"id": for subsequent
                    String keyStr = (i == 0 ? "{\"" : ",\"") + name + "\":";
                    byte[] keyBytes = keyStr.getBytes(StandardCharsets.UTF_8);
                    this.preEncodedKeyBytes[i] = keyBytes;

                    this.fieldWriters[i] = compileFieldWriter(lookup, accessor, comp.getType());
                }

                int tableSize = 8;
                while (tableSize < n * 2) tableSize <<= 1;
                this.hashMask = tableSize - 1;
                this.hashTableLongs = new long[tableSize];
                this.hashLengths = new int[tableSize];
                this.hashIndices = new byte[tableSize];
                java.util.Arrays.fill(this.hashIndices, (byte) -1);

                for (int i = 0; i < n; i++) {
                    if (this.componentLengths[i] <= 8) {
                        long w = this.componentLongs[i];
                        int slot = (int) (w ^ (w >>> 16)) & hashMask;
                        while (this.hashIndices[slot] != -1) {
                            slot = (slot + 1) & hashMask;
                        }
                        this.hashTableLongs[slot] = w;
                        this.hashLengths[slot] = this.componentLengths[i];
                        this.hashIndices[slot] = (byte) i;
                    }
                }

                this.constructor = recordClass.getDeclaredConstructor(componentTypes);
                try {
                    this.constructor.setAccessible(true);
                } catch (Exception e) {
                    // unreflectConstructor() below verifies effective access.
                }
                this.constructorHandle = lookup.unreflectConstructor(this.constructor);
                this.constructorSpreader = this.constructorHandle.asSpreader(Object[].class, n).asType(java.lang.invoke.MethodType.methodType(Record.class, Object[].class));
            } catch (Exception e) {
                throw new RuntimeException("Failed to analyze record class: " + recordClass.getName(), e);
            }
        }

        private static FieldWriter compileFieldWriter(java.lang.invoke.MethodHandles.Lookup lookup, Method accessor, Class<?> type) throws Exception {
            java.lang.invoke.MethodHandle rawMh = lookup.unreflect(accessor);
            if (type == String.class) {
                java.lang.invoke.MethodHandle mh = rawMh.asType(MethodType.methodType(String.class, Record.class));
                return (segment, offset, record) -> {
                    String str = (String) mh.invokeExact(record);
                    return JsonWriter.writeUtf8String(segment, offset, str);
                };
            } else if (type == int.class) {
                java.lang.invoke.MethodHandle mh = rawMh.asType(MethodType.methodType(int.class, Record.class));
                return (segment, offset, record) -> {
                    int val = (int) mh.invokeExact(record);
                    return JsonWriter.writeInt(segment, offset, val);
                };
            } else if (type == Integer.class) {
                java.lang.invoke.MethodHandle mh = rawMh.asType(MethodType.methodType(Integer.class, Record.class));
                return (segment, offset, record) -> {
                    Integer val = (Integer) mh.invokeExact(record);
                    if (val == null) {
                        segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset, 0x6c6c756e);
                        return offset + 4;
                    }
                    return JsonWriter.writeInt(segment, offset, val.intValue());
                };
            } else if (type == long.class) {
                java.lang.invoke.MethodHandle mh = rawMh.asType(MethodType.methodType(long.class, Record.class));
                return (segment, offset, record) -> {
                    long val = (long) mh.invokeExact(record);
                    return JsonWriter.writeLong(segment, offset, val);
                };
            } else if (type == Long.class) {
                java.lang.invoke.MethodHandle mh = rawMh.asType(MethodType.methodType(Long.class, Record.class));
                return (segment, offset, record) -> {
                    Long val = (Long) mh.invokeExact(record);
                    if (val == null) {
                        segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset, 0x6c6c756e);
                        return offset + 4;
                    }
                    return JsonWriter.writeLong(segment, offset, val.longValue());
                };
            } else if (type == boolean.class) {
                java.lang.invoke.MethodHandle mh = rawMh.asType(MethodType.methodType(boolean.class, Record.class));
                return (segment, offset, record) -> {
                    boolean val = (boolean) mh.invokeExact(record);
                    return JsonWriter.writeBoolean(segment, offset, val);
                };
            } else if (type == Boolean.class) {
                java.lang.invoke.MethodHandle mh = rawMh.asType(MethodType.methodType(Boolean.class, Record.class));
                return (segment, offset, record) -> {
                    Boolean val = (Boolean) mh.invokeExact(record);
                    if (val == null) {
                        segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset, 0x6c6c756e);
                        return offset + 4;
                    }
                    return JsonWriter.writeBoolean(segment, offset, val.booleanValue());
                };
            } else if (type == dev.cardigan.http.Utf8Slice.class) {
                java.lang.invoke.MethodHandle mh = rawMh.asType(MethodType.methodType(dev.cardigan.http.Utf8Slice.class, Record.class));
                return (segment, offset, record) -> {
                    dev.cardigan.http.Utf8Slice slice = (dev.cardigan.http.Utf8Slice) mh.invokeExact(record);
                    return JsonWriter.writeSlice(segment, offset, slice);
                };
            } else if (Record.class.isAssignableFrom(type)) {
                java.lang.invoke.MethodHandle mh = rawMh.asType(MethodType.methodType(Record.class, Record.class));
                return (segment, offset, record) -> {
                    Record subRecord = (Record) mh.invokeExact(record);
                    if (subRecord == null) {
                        segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset, 0x6c6c756e);
                        return offset + 4;
                    }
                    return offset + JsonWriter.writeRecord(segment, offset, subRecord);
                };
            } else {
                java.lang.invoke.MethodHandle mh = rawMh.asType(MethodType.methodType(Object.class, Record.class));
                return (segment, offset, record) -> {
                    Object val = mh.invokeExact(record);
                    return JsonWriter.writeValue(segment, offset, val);
                };
            }
        }

        private static final java.lang.invoke.VarHandle BYTE_ARRAY_LONG_UNALIGNED = 
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(long[].class, java.nio.ByteOrder.LITTLE_ENDIAN);
        private static final java.lang.invoke.VarHandle LONG_UNALIGNED = java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED.varHandle();

        public int matchKey(MemorySegment segment, byte[] heapBytes, long keyOffset, long keyLen, int expectedPos) {
            if (heapBytes != null) {
                return matchKeyHeap(heapBytes, (int) keyOffset, (int) keyLen, expectedPos);
            } else {
                return matchKeySegment(segment, keyOffset, (int) keyLen, expectedPos);
            }
        }

        public int matchKeyHeap(byte[] heapBytes, int off, int len, int expectedPos) {
            final int[] lengths = this.componentLengths;
            final long[] longs = this.componentLongs;
            final String[] names = this.componentNames;
            final int n = names.length;

            final int mask = this.hashMask;
            final long[] hashLongs = this.hashTableLongs;
            final int[] hashLens = this.hashLengths;
            final byte[] hashIdxs = this.hashIndices;

            if (len <= 8) {
                long inputWord = getLongUnaligned(heapBytes, off, len);
                long wordMask = len == 8 ? -1L : (1L << (len * 8)) - 1;
                long maskedWord = inputWord & wordMask;

                // Check the expected schema position before probing the table.
                if (expectedPos >= 0 && expectedPos < n) {
                    if (len == lengths[expectedPos] && maskedWord == longs[expectedPos]) {
                        return expectedPos;
                    }
                }

                // Probe the table for out-of-order or unknown keys.
                int slot = (int) (maskedWord ^ (maskedWord >>> 16)) & mask;
                while (true) {
                    int idx = hashIdxs[slot];
                    if (idx == -1) break;
                    if (hashLongs[slot] == maskedWord && hashLens[slot] == len) {
                        return idx;
                    }
                    slot = (slot + 1) & mask;
                }
                return -1;
            } else {
                if (expectedPos >= 0 && expectedPos < n) {
                    if (len == lengths[expectedPos] && equalsString(heapBytes, off, len, names[expectedPos])) {
                        return expectedPos;
                    }
                }
                for (int i = 0; i < n; i++) {
                    if (lengths[i] == len && equalsString(heapBytes, off, len, names[i])) {
                        return i;
                    }
                }
            }
            return -1;
        }

        public int matchKeySegment(MemorySegment segment, long keyOffset, int len, int expectedPos) {
            final int[] lengths = this.componentLengths;
            final long[] longs = this.componentLongs;
            final String[] names = this.componentNames;
            final int n = names.length;

            final int mask = this.hashMask;
            final long[] hashLongs = this.hashTableLongs;
            final int[] hashLens = this.hashLengths;
            final byte[] hashIdxs = this.hashIndices;

            if (len <= 8) {
                long inputWord = getLongUnaligned(segment, keyOffset, len);
                long wordMask = len == 8 ? -1L : (1L << (len * 8)) - 1;
                long maskedWord = inputWord & wordMask;

                if (expectedPos >= 0 && expectedPos < n) {
                    if (len == lengths[expectedPos] && maskedWord == longs[expectedPos]) {
                        return expectedPos;
                    }
                }

                int slot = (int) (maskedWord ^ (maskedWord >>> 16)) & mask;
                while (true) {
                    int idx = hashIdxs[slot];
                    if (idx == -1) break;
                    if (hashLongs[slot] == maskedWord && hashLens[slot] == len) {
                        return idx;
                    }
                    slot = (slot + 1) & mask;
                }
                return -1;
            } else {
                if (expectedPos >= 0 && expectedPos < n) {
                    if (len == lengths[expectedPos] && equalsString(segment, keyOffset, len, names[expectedPos])) {
                        return expectedPos;
                    }
                }
                for (int i = 0; i < n; i++) {
                    if (lengths[i] == len && equalsString(segment, keyOffset, len, names[i])) {
                        return i;
                    }
                }
            }
            return -1;
        }

        public int findComponentIndex(MemorySegment segment, byte[] heapBytes, long keyOffset, long keyLen) {
            return matchKey(segment, heapBytes, keyOffset, keyLen, 0);
        }

        public int findComponentIndex(MemorySegment segment, long keyOffset, long keyLen) {
            return matchKey(segment, null, keyOffset, keyLen, 0);
        }

        private static long getLongUnaligned(byte[] heapBytes, int offset, int len) {
            if (offset + 8 <= heapBytes.length) {
                return (long) BYTE_ARRAY_LONG_UNALIGNED.get(heapBytes, offset);
            }
            long word = 0;
            for (int j = 0; j < len; j++) {
                word |= ((long) (heapBytes[offset + j] & 0xFF)) << (j * 8);
            }
            return word;
        }

        private static boolean equalsString(byte[] heapBytes, int offset, int len, String str) {
            if (str.length() != len) return false;
            for (int i = 0; i < len; i++) {
                if (heapBytes[offset + i] != (byte) str.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        private static long getLongUnaligned(MemorySegment segment, long offset, int len) {
            if (offset + 8 <= segment.byteSize()) {
                return segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
            }
            long word = 0;
            for (int j = 0; j < len; j++) {
                word |= ((long) (segment.get(ValueLayout.JAVA_BYTE, offset + j) & 0xFF)) << (j * 8);
            }
            return word;
        }

        private static boolean equalsString(MemorySegment segment, long offset, int len, String str) {
            if (str.length() != len) return false;
            for (int i = 0; i < len; i++) {
                if (segment.get(ValueLayout.JAVA_BYTE, offset + i) != (byte) str.charAt(i)) {
                    return false;
                }
            }
            return true;
        }
    }
}
