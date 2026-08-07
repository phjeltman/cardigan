// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

public final class Route {
    public final String httpMethod;
    public final int methodCode; // 1 = GET, 2 = POST, 0 = OTHER
    public final String pattern;
    public final String[] segments;
    public final int segmentCount;
    public final boolean[] isParam;
    public final String[] paramNames;
    public final Method method;
    public final MethodHandle methodHandle;
    public final RouteHandler handler;
    public final Object controller;
    public final Class<?>[] parameterTypes;
    public final int[] paramIndexMap;
    public final boolean requiresRequestStorage;
    public final LongBodyDecoder longBodyDecoder;
    public final boolean isBodyRecord;
    public final boolean isStreamingBody;
    public final Class<? extends Record> bodyRecordClass;
    public final dev.cardigan.json.RecordCache.RecordMetadata bodyRecordMetadata;
    public final boolean isIsolated;
    public final String[] queryParameterNames;
    public final int[] queryParameterDefaults;
    public final boolean packsTwoIntArguments;

    public final int[] segmentLengths;
    public final byte[][] segmentBytes;
    public final long[] segmentLongs;
    public final long[] segmentMasks;

    public Route(
        String httpMethod,
        String pattern,
        String[] segments,
        boolean[] isParam,
        String[] paramNames,
        Method method,
        MethodHandle methodHandle,
        RouteHandler handler,
        Object controller,
        Class<?>[] parameterTypes,
        int[] paramIndexMap,
        boolean isBodyRecord,
        Class<? extends Record> bodyRecordClass
    ) {
        this.httpMethod = httpMethod;
        if ("GET".equalsIgnoreCase(httpMethod)) {
            this.methodCode = 1;
        } else if ("POST".equalsIgnoreCase(httpMethod)) {
            this.methodCode = 2;
        } else {
            this.methodCode = 0;
        }
        this.pattern = pattern;
        this.segments = segments;
        this.segmentCount = segments.length;
        this.isParam = isParam;
        this.paramNames = paramNames;
        this.method = method;
        this.methodHandle = methodHandle;
        this.handler = handler;
        this.controller = controller;
        this.parameterTypes = parameterTypes;
        this.paramIndexMap = paramIndexMap;
        java.lang.annotation.Annotation[][] annotations =
            method.getParameterAnnotations();
        this.longBodyDecoder = createLongBodyDecoder(
            method, parameterTypes, annotations);
        boolean storesRequest = false;
        for (Class<?> parameterType : parameterTypes) {
            if (parameterType == HttpRequest.class
                || parameterType == dev.cardigan.simdjson.ondemand.Value.class) {
                storesRequest = true;
                break;
            }
        }
        this.requiresRequestStorage = storesRequest;
        this.isBodyRecord = isBodyRecord;
        boolean streamsBody = false;
        for (Class<?> parameterType : parameterTypes) {
            if (parameterType == RequestBody.class) {
                streamsBody = true;
                break;
            }
        }
        this.isStreamingBody = streamsBody;
        this.bodyRecordClass = bodyRecordClass;
        this.bodyRecordMetadata = isBodyRecord ? dev.cardigan.json.RecordCache.getMetadata(bodyRecordClass) : null;
        this.isIsolated =
            method != null && method.isAnnotationPresent(Isolated.class);
        this.queryParameterNames = new String[parameterTypes.length];
        this.queryParameterDefaults = new int[parameterTypes.length];
        int integerArguments = 0;
        boolean hasQueryParameter = false;
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i] == int.class) {
                integerArguments++;
            }
            for (java.lang.annotation.Annotation annotation : annotations[i]) {
                if (annotation instanceof QueryParam queryParam) {
                    queryParameterNames[i] = queryParam.value();
                    queryParameterDefaults[i] = queryParam.defaultValue();
                    hasQueryParameter = true;
                }
            }
        }
        this.packsTwoIntArguments =
            hasQueryParameter && integerArguments == 2;

        this.segmentLengths = new int[segments.length];
        this.segmentBytes = new byte[segments.length][];
        this.segmentLongs = new long[segments.length];
        this.segmentMasks = new long[segments.length];

        for (int i = 0; i < segments.length; i++) {
            if (!isParam[i]) {
                byte[] b = segments[i].getBytes(StandardCharsets.UTF_8);
                this.segmentBytes[i] = b;
                this.segmentLengths[i] = b.length;
                if (b.length <= 8) {
                    long word = 0;
                    for (int k = 0; k < b.length; k++) {
                        word |= ((long) (b[k] & 0xFF)) << (k * 8);
                    }
                    this.segmentLongs[i] = word;
                    this.segmentMasks[i] = b.length == 8 ? -1L : (1L << (b.length * 8)) - 1L;
                }
            }
        }
    }

    private static LongBodyDecoder createLongBodyDecoder(
        Method method,
        Class<?>[] parameterTypes,
        java.lang.annotation.Annotation[][] annotations
    ) {
        Class<? extends LongBodyDecoder> decoderClass = null;
        int decoderParameter = -1;
        for (int parameter = 0; parameter < annotations.length; parameter++) {
            for (java.lang.annotation.Annotation annotation
                    : annotations[parameter]) {
                if (!(annotation instanceof DecodedBody decodedBody)) {
                    continue;
                }
                if (decoderClass != null) {
                    throw new IllegalArgumentException(
                        "Only one @DecodedBody parameter is supported: "
                            + method);
                }
                decoderClass = decodedBody.value();
                decoderParameter = parameter;
            }
        }
        if (decoderClass == null) {
            return null;
        }
        if (parameterTypes.length != 1 || decoderParameter != 0
            || parameterTypes[0] != long.class) {
            throw new IllegalArgumentException(
                "@DecodedBody requires a sole primitive long parameter: "
                    + method);
        }
        try {
            var constructor = decoderClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException(
                "@DecodedBody decoder requires a no-argument constructor: "
                    + decoderClass.getName(),
                failure
            );
        }
    }
}
