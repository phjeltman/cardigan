// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.lang.invoke.MethodHandle;

public final class FastRouteHandler implements RouteHandler {
    public static final int TYPE_LONG_PARAM = 1;
    public static final int TYPE_INT_PARAM = 2;
    public static final int TYPE_RECORD_PARAM = 3;
    public static final int TYPE_REQUEST_PARAM = 4;
    public static final int TYPE_NO_ARG = 5;
    public static final int TYPE_VALUE_PARAM = 6;
    public static final int TYPE_STREAMING_BODY_PARAM = 7;
    public static final int TYPE_REQUEST_STREAMING_BODY_PARAM = 8;
    public static final int TYPE_LONG_REQUEST_PARAM = 9;
    public static final int TYPE_INT_REQUEST_PARAM = 10;
    public static final int TYPE_TWO_INT_PARAMS = 11;
    public static final int TYPE_TWO_INT_STREAMING_BODY_PARAMS = 12;
    public static final int TYPE_BOUND_ARGUMENTS = 13;

    private final MethodHandle mhBound;
    private final int handlerType;

    public FastRouteHandler(Object controller, MethodHandle mh, int handlerType) {
        MethodHandle bound = mh.bindTo(controller);
        this.mhBound = handlerType == TYPE_BOUND_ARGUMENTS
            ? bound.asSpreader(Object[].class, bound.type().parameterCount())
            : bound;
        this.handlerType = handlerType;
    }

    @Override
    public boolean usesBoundArguments() {
        return handlerType == TYPE_BOUND_ARGUMENTS;
    }

    @Override
    public Response handle(HttpRequest request, long pathParamLong, Object bodyRecord) throws Throwable {
        return switch (handlerType) {
            case TYPE_LONG_PARAM -> (Response) mhBound.invokeExact(pathParamLong);
            case TYPE_INT_PARAM -> (Response) mhBound.invokeExact((int) pathParamLong);
            case TYPE_RECORD_PARAM -> (Response) mhBound.invoke(bodyRecord);
            case TYPE_REQUEST_PARAM -> (Response) mhBound.invokeExact(request);
            case TYPE_NO_ARG -> (Response) mhBound.invokeExact();
            case TYPE_VALUE_PARAM -> (Response) mhBound.invokeExact(request.bodyJson());
            case TYPE_STREAMING_BODY_PARAM ->
                (Response) mhBound.invokeExact((RequestBody) bodyRecord);
            case TYPE_REQUEST_STREAMING_BODY_PARAM ->
                (Response) mhBound.invokeExact(
                    request, (RequestBody) bodyRecord);
            case TYPE_LONG_REQUEST_PARAM ->
                (Response) mhBound.invokeExact(pathParamLong, request);
            case TYPE_INT_REQUEST_PARAM ->
                (Response) mhBound.invokeExact((int) pathParamLong, request);
            case TYPE_TWO_INT_PARAMS ->
                (Response) mhBound.invokeExact(
                    (int) pathParamLong, (int) (pathParamLong >>> 32));
            case TYPE_TWO_INT_STREAMING_BODY_PARAMS ->
                (Response) mhBound.invokeExact(
                    (int) pathParamLong,
                    (int) (pathParamLong >>> 32),
                    (RequestBody) bodyRecord);
            case TYPE_BOUND_ARGUMENTS ->
                (Response) mhBound.invokeExact((Object[]) bodyRecord);
            default -> Response.error("Unsupported handler type");
        };
    }
}
