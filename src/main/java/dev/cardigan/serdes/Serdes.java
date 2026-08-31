// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.serdes;

import dev.cardigan.http.Utf8Slice;
import dev.cardigan.json.JsonReader;
import dev.cardigan.json.JsonWriter;
import dev.cardigan.simdjson.Stage1Indexer;
import dev.cardigan.simdjson.Stage2Validator;
import dev.cardigan.simdjson.StructuralIndexes;
import dev.cardigan.simdjson.SimdJsonError;
import dev.cardigan.simdjson.SimdJsonException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main facade for zero-allocation SIMD SerDe operations.
 * Provides high-performance JSON serialization and deserialization using SIMD OnDemand traversal.
 */
public final class Serdes {

    private static final class SerdesState {
        final Stage1Indexer indexer = new Stage1Indexer();
        final Stage2Validator validator = new Stage2Validator();
        final dev.cardigan.simdjson.ondemand.OnDemandParser onDemand = new dev.cardigan.simdjson.ondemand.OnDemandParser();
        final StructuralIndexes rootIndexes =
            StructuralIndexes.operatorsOnly(256);
        final SimdJsonDeserializer rootDeserializer = new SimdJsonDeserializer();
        StructuralIndexes[] idxStack;
        SimdJsonDeserializer[] deStack;
        int depth = 0;

        SerdesState() {
            idxStack = new StructuralIndexes[8];
            deStack = new SimdJsonDeserializer[8];
            for (int i = 0; i < 8; i++) {
                idxStack[i] = StructuralIndexes.operatorsOnly(256);
                deStack[i] = new SimdJsonDeserializer();
            }
        }
    }

    private static final ThreadLocal<SerdesState> STATE = ThreadLocal.withInitial(SerdesState::new);

    private static final ConcurrentHashMap<Class<?>, Deserializer<?>> REGISTRATION_DESERIALIZERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Serialize<?>> REGISTRATION_SERIALIZERS = new ConcurrentHashMap<>();

    private Serdes() {
    }

    /**
     * Registers a custom Deserializer for a given type.
     */
    public static <T> void registerDeserializer(Class<T> type, Deserializer<T> deserializer) {
        REGISTRATION_DESERIALIZERS.put(type, deserializer);
    }

    /**
     * Registers a custom Serializer for a given type.
     */
    public static <T> void registerSerializer(Class<T> type, Serialize<T> serializer) {
        REGISTRATION_SERIALIZERS.put(type, serializer);
    }

    // =========================================================================
    // Deserialization API
    // =========================================================================

    public static dev.cardigan.simdjson.ondemand.Value parseOnDemand(MemorySegment segment) {
        return parseOnDemand(segment, 0, segment.byteSize());
    }

    public static dev.cardigan.simdjson.ondemand.Value parseOnDemand(MemorySegment segment, long offset, long length) {
        MemorySegment slice = (offset == 0 && length == segment.byteSize()) ? segment : segment.asSlice(offset, length);
        return STATE.get().onDemand.parse(slice);
    }

    public static dev.cardigan.simdjson.ondemand.Value parseOnDemand(byte[] bytes) {
        return parseOnDemand(MemorySegment.ofArray(bytes));
    }

    public static dev.cardigan.simdjson.ondemand.Value parseOnDemand(String jsonStr) {
        return parseOnDemand(jsonStr.getBytes(StandardCharsets.UTF_8));
    }

    public static <T> T fromJson(MemorySegment segment, Class<T> clazz) {
        return fromJson(segment, 0, segment.byteSize(), clazz);
    }

    @SuppressWarnings("unchecked")
    public static <T> T fromJson(MemorySegment segment, long offset, long length, Class<T> clazz) {
        MemorySegment slice = (offset == 0 && length == segment.byteSize()) ? segment : segment.asSlice(offset, length);

        if (clazz == dev.cardigan.simdjson.ondemand.Value.class) {
            return (T) parseOnDemand(slice);
        }
        if (clazz == String.class) {
            return (T) parseOnDemand(slice).toString();
        }

        SerdesState state = STATE.get();
        int d = state.depth++;
        try {
            StructuralIndexes indexes;
            SimdJsonDeserializer de;
            if (d == 0) {
                indexes = state.rootIndexes;
                de = state.rootDeserializer;
            } else {
                int sIdx = d - 1;
                if (sIdx >= state.idxStack.length) {
                    int oldLen = state.idxStack.length;
                    state.idxStack = java.util.Arrays.copyOf(state.idxStack, oldLen * 2);
                    state.deStack = java.util.Arrays.copyOf(state.deStack, oldLen * 2);
                    for (int i = oldLen; i < state.idxStack.length; i++) {
                        state.idxStack[i] =
                            StructuralIndexes.operatorsOnly(256);
                        state.deStack[i] = new SimdJsonDeserializer();
                    }
                }
                indexes = state.idxStack[sIdx];
                de = state.deStack[sIdx];
            }

            indexOrThrow(state.indexer, slice, indexes);
            de.reset(
                slice, 0, length, indexes,
                state.indexer.hasBackslash());

            if (Record.class.isAssignableFrom(clazz)) {
                dev.cardigan.json.RecordCache.RecordMetadata metadata =
                    dev.cardigan.json.RecordCache.getMetadata(clazz);
                validateRecordOrThrow(
                    state.validator, slice, indexes, metadata, de);
                return (T) de.readRecord(metadata);
            }

            validateOrThrow(state.validator, slice, indexes);

            Deserializer<T> custom = (Deserializer<T>) REGISTRATION_DESERIALIZERS.get(clazz);
            if (custom != null) {
                return custom.deserialize(de);
            }

            throw new IllegalArgumentException("Unsupported type for auto-deserialization: " + clazz.getName() +
                    ". Implement a Record or register a custom Deserializer via Serdes.registerDeserializer().");
        } finally {
            state.depth--;
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T fromJson(MemorySegment segment, long offset, long length, dev.cardigan.json.RecordCache.RecordMetadata metadata) {
        MemorySegment slice = (offset == 0 && length == segment.byteSize()) ? segment : segment.asSlice(offset, length);
        SerdesState state = STATE.get();
        int d = state.depth++;
        try {
            StructuralIndexes indexes;
            SimdJsonDeserializer de;
            if (d == 0) {
                indexes = state.rootIndexes;
                de = state.rootDeserializer;
            } else {
                int sIdx = d - 1;
                if (sIdx >= state.idxStack.length) {
                    int oldLen = state.idxStack.length;
                    state.idxStack = java.util.Arrays.copyOf(state.idxStack, oldLen * 2);
                    state.deStack = java.util.Arrays.copyOf(state.deStack, oldLen * 2);
                    for (int i = oldLen; i < state.idxStack.length; i++) {
                        state.idxStack[i] =
                            StructuralIndexes.operatorsOnly(256);
                        state.deStack[i] = new SimdJsonDeserializer();
                    }
                }
                indexes = state.idxStack[sIdx];
                de = state.deStack[sIdx];
            }

            indexOrThrow(state.indexer, slice, indexes);
            de.reset(
                slice, 0, length, indexes,
                state.indexer.hasBackslash());
            validateRecordOrThrow(
                state.validator, slice, indexes, metadata, de);
            return (T) de.readRecord(metadata);
        } finally {
            state.depth--;
        }
    }

    @SuppressWarnings("unchecked")
    public static <R extends Record> R readRecordFromValue(dev.cardigan.simdjson.ondemand.Value val, Class<R> recordClass) {
        dev.cardigan.json.RecordCache.RecordMetadata metadata = dev.cardigan.json.RecordCache.getMetadata(recordClass);
        dev.cardigan.simdjson.ondemand.ObjectVal obj = val.getObject();
        int n = metadata.componentNames.length;
        Object[] args = new Object[n];

        for (int i = 0; i < n; i++) {
            String name = metadata.componentNames[i];
            byte typeCode = metadata.componentTypeCodes[i];

            dev.cardigan.simdjson.ondemand.Value fieldVal =
                obj.getOrNull(name);
            if (fieldVal == null) {
                args[i] = metadata.defaultArgs[i];
                continue;
            }
            if (fieldVal.isNull()) {
                args[i] = metadata.defaultArgs[i];
                continue;
            }

            switch (typeCode) {
                case dev.cardigan.json.RecordCache.RecordMetadata.TYPE_STRING ->
                    args[i] = fieldVal.getString();
                case dev.cardigan.json.RecordCache.RecordMetadata.TYPE_UTF8_SLICE -> {
                    int rawOff = fieldVal.getRawOffset();
                    int rawLen = fieldVal.getRawLength();
                    if (rawLen >= 2) {
                        args[i] = new dev.cardigan.http.Utf8Slice(val.segment(), rawOff + 1, rawLen - 2);
                    } else {
                        args[i] = new dev.cardigan.http.Utf8Slice(val.segment(), rawOff, rawLen);
                    }
                }
                case dev.cardigan.json.RecordCache.RecordMetadata.TYPE_INT ->
                    args[i] = checkedInt(fieldVal.getLong());
                case dev.cardigan.json.RecordCache.RecordMetadata.TYPE_LONG ->
                    args[i] = fieldVal.getLong();
                case dev.cardigan.json.RecordCache.RecordMetadata.TYPE_FLOAT ->
                    args[i] = (float) fieldVal.getDouble();
                case dev.cardigan.json.RecordCache.RecordMetadata.TYPE_DOUBLE ->
                    args[i] = fieldVal.getDouble();
                case dev.cardigan.json.RecordCache.RecordMetadata.TYPE_BOOLEAN ->
                    args[i] = fieldVal.getBoolean();
                case dev.cardigan.json.RecordCache.RecordMetadata.TYPE_RECORD ->
                    args[i] = readRecordFromValue(fieldVal, (Class<? extends Record>) metadata.componentTypes[i]);
                default ->
                    args[i] = metadata.defaultArgs[i];
            }
        }

        try {
            return (R) metadata.constructorSpreader.invokeExact(args);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to instantiate record: " + recordClass.getName(), t);
        }
    }

    private static int checkedInt(long value) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new SimdJsonException(SimdJsonError.NUMBER_OUT_OF_RANGE);
        }
        return (int) value;
    }

    public static <T> T fromJson(byte[] bytes, Class<T> clazz) {
        return fromJson(MemorySegment.ofArray(bytes), clazz);
    }

    public static <T> T fromJson(String jsonStr, Class<T> clazz) {
        return fromJson(jsonStr.getBytes(StandardCharsets.UTF_8), clazz);
    }

    public static <T> T fromJson(MemorySegment segment, Deserializer<T> deserializer) {
        return fromJson(segment, 0, segment.byteSize(), deserializer);
    }

    public static <T> T fromJson(MemorySegment segment, long offset, long length, Deserializer<T> deserializer) {
        MemorySegment slice = (offset == 0 && length == segment.byteSize()) ? segment : segment.asSlice(offset, length);
        SerdesState state = STATE.get();
        int d = state.depth++;
        try {
            if (d >= state.idxStack.length) {
                int oldLen = state.idxStack.length;
                state.idxStack = java.util.Arrays.copyOf(state.idxStack, oldLen * 2);
                state.deStack = java.util.Arrays.copyOf(state.deStack, oldLen * 2);
                for (int i = oldLen; i < state.idxStack.length; i++) {
                    state.idxStack[i] =
                        StructuralIndexes.operatorsOnly(256);
                    state.deStack[i] = new SimdJsonDeserializer();
                }
            }
            StructuralIndexes indexes = state.idxStack[d];
            SimdJsonDeserializer de = state.deStack[d];

            indexOrThrow(state.indexer, slice, indexes);
            validateOrThrow(state.validator, slice, indexes);
            de.reset(
                slice, 0, slice.byteSize(), indexes,
                state.indexer.hasBackslash());
            return deserializer.deserialize(de);
        } finally {
            state.depth--;
        }
    }

    public static <T> T fromJson(byte[] bytes, Deserializer<T> deserializer) {
        return fromJson(MemorySegment.ofArray(bytes), deserializer);
    }

    public static <T> T fromJson(String jsonStr, Deserializer<T> deserializer) {
        return fromJson(jsonStr.getBytes(StandardCharsets.UTF_8), deserializer);
    }

    private static void indexOrThrow(
            Stage1Indexer indexer, MemorySegment segment,
            StructuralIndexes indexes) {
        SimdJsonError error = indexer.index(segment, indexes);
        if (error != SimdJsonError.SUCCESS) {
            throw new SimdJsonException(error);
        }
    }

    private static void validateOrThrow(
            Stage2Validator validator, MemorySegment segment,
            StructuralIndexes indexes) {
        SimdJsonError error = validator.validate(segment, indexes);
        if (error != SimdJsonError.SUCCESS) {
            throw new SimdJsonException(error);
        }
    }

    private static void validateRecordOrThrow(
            Stage2Validator validator, MemorySegment segment,
            StructuralIndexes indexes,
            dev.cardigan.json.RecordCache.RecordMetadata metadata,
            SimdJsonDeserializer deserializer) {
        int fieldCount = metadata.componentNames.length;
        if (fieldCount > 0 && fieldCount <= 8
                && indexes.size() == fieldCount * 2 + 1) {
            SimdJsonError error = validator.validatePositionalRecord(
                segment, indexes, fieldCount,
                deserializer.positionalBounds());
            if (error != SimdJsonError.SUCCESS) {
                throw new SimdJsonException(error);
            }
            deserializer.useValidatedPositionalBounds();
            return;
        }
        validateOrThrow(validator, segment, indexes);
    }

    // =========================================================================
    // Serialization API
    // =========================================================================

    public static String toJson(Object value) {
        byte[] bytes = toJsonBytes(value);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static byte[] toJsonBytes(Object value) {
        int capacity = 4096;
        while (true) {
            try {
                byte[] buf = new byte[capacity];
                MemorySegment seg = MemorySegment.ofArray(buf);
                long written = toJson(seg, value);
                byte[] result = new byte[(int) written];
                System.arraycopy(buf, 0, result, 0, (int) written);
                return result;
            } catch (IndexOutOfBoundsException e) {
                byte[] exact = retryWithExactSize(value);
                if (exact != null) {
                    return exact;
                }
                capacity *= 2;
            } catch (RuntimeException e) {
                if (isBufferOverflow(e)) {
                    byte[] exact = retryWithExactSize(value);
                    if (exact != null) {
                        return exact;
                    }
                    capacity *= 2;
                } else {
                    throw e;
                }
            }
        }
    }

    private static byte[] retryWithExactSize(Object value) {
        int exactSize = exactSerializedSize(value);
        if (exactSize < 0) {
            return null;
        }
        byte[] result = new byte[exactSize];
        long written = toJson(MemorySegment.ofArray(result), value);
        if (written != exactSize) {
            throw new IllegalStateException(
                "JSON size mismatch: expected " + exactSize
                    + " bytes, wrote " + written);
        }
        return result;
    }

    private static boolean isBufferOverflow(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof IndexOutOfBoundsException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static int exactSerializedSize(Object value) {
        if (value == null || value instanceof Record
                || value instanceof String || value instanceof Utf8Slice
                || value instanceof Integer || value instanceof Long
                || value instanceof Double || value instanceof Boolean) {
            return JsonWriter.encodedSize(value);
        }
        if (value instanceof dev.cardigan.simdjson.ondemand.Value onDemand) {
            return Math.addExact(onDemand.getRawLength(), 2);
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    public static long toJson(MemorySegment targetSegment, Object value) {
        SimdJsonSerializer ser = new SimdJsonSerializer(targetSegment);
        if (value == null) {
            ser.writeNull();
        } else if (value instanceof Record rec) {
            ser.writeRecord(rec);
        } else if (value instanceof String str) {
            ser.writeString(str);
        } else if (value instanceof Utf8Slice slice) {
            ser.writeSlice(slice);
        } else if (value instanceof dev.cardigan.simdjson.ondemand.Value val) {
            ser.writeSlice(new Utf8Slice(val.segment(), val.getRawOffset(), val.getRawLength()));
        } else if (value instanceof Integer val) {
            ser.writeInt(val);
        } else if (value instanceof Long val) {
            ser.writeLong(val);
        } else if (value instanceof Double val) {
            ser.writeDouble(val);
        } else if (value instanceof Boolean val) {
            ser.writeBoolean(val);
        } else {
            Serialize<Object> custom = (Serialize<Object>) REGISTRATION_SERIALIZERS.get(value.getClass());
            if (custom != null) {
                ser.write(value, custom);
            } else {
                throw new IllegalArgumentException("Unsupported type for serialization: " + value.getClass().getName() +
                        ". Must be a Record or have a registered Serializer via Serdes.registerSerializer().");
            }
        }
        return ser.getBytesWritten();
    }

    public static <T> long toJson(MemorySegment targetSegment, T value, Serialize<T> serializer) {
        SimdJsonSerializer ser = new SimdJsonSerializer(targetSegment);
        serializer.serialize(value, ser);
        return ser.getBytesWritten();
    }
}
