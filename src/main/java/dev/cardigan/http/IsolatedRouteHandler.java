// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import dev.cardigan.util.SimpleWaiter;
import java.lang.invoke.MethodHandle;

public final class IsolatedRouteHandler implements RouteHandler {
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

    public IsolatedRouteHandler(Object controller, MethodHandle mh, int handlerType) {
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
        return handle(request, pathParamLong, bodyRecord, null);
    }

    Response handle(HttpRequest request, long pathParamLong, Object bodyRecord,
                    PreparedInvocation invocation) throws Throwable {
        return handleWithCompletion(
            request, pathParamLong, bodyRecord, invocation, null);
    }

    Response handleWithCompletion(
            HttpRequest request, long pathParamLong, Object bodyRecord,
            PreparedInvocation invocation, Runnable completion)
            throws Throwable {
        if (!IsolatedRouteExecutor.tryAcquireTask()) {
            PreparedInvocation.runCompletion(completion);
            return Response.serviceUnavailable();
        }
        boolean started = false;
        SimpleWaiter<Response> waiter = new SimpleWaiter<>();
        Thread thread = null;
        try {
            thread = IsolatedRouteExecutor.newThread(
                new IsolatedRouteTask(
                    mhBound,
                    handlerType,
                    request,
                    pathParamLong,
                    bodyRecord,
                    waiter,
                    completion
                )
            );
            if (invocation == null) {
                thread.start();
                started = true;
            } else {
                started = invocation.startCancellable(thread, waiter);
                if (!started) {
                    return null;
                }
            }
            return waiter.await();
        } finally {
            if (invocation != null && started) {
                invocation.clearCancellable(thread, waiter);
            }
            if (!started) {
                IsolatedRouteExecutor.releaseTask();
                PreparedInvocation.runCompletion(completion);
            }
        }
    }

    private static final class IsolatedRouteTask implements Runnable {
        private final MethodHandle mhBound;
        private final int handlerType;
        private final HttpRequest request;
        private final long pathParamLong;
        private final Object bodyRecord;
        private final SimpleWaiter<Response> waiter;
        private final Runnable completion;

        IsolatedRouteTask(MethodHandle mhBound, int handlerType, HttpRequest request, long pathParamLong, Object bodyRecord, SimpleWaiter<Response> waiter, Runnable completion) {
            this.mhBound = mhBound;
            this.handlerType = handlerType;
            this.request = request;
            this.pathParamLong = pathParamLong;
            this.bodyRecord = bodyRecord;
            this.waiter = waiter;
            this.completion = completion;
        }

        @Override
        public void run() {
            try {
                if (Thread.currentThread().isInterrupted()) {
                    waiter.cancel();
                    return;
                }
                try {
                    Response res = switch (handlerType) {
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
                            (Response) mhBound.invokeExact(
                                pathParamLong, request);
                        case TYPE_INT_REQUEST_PARAM ->
                            (Response) mhBound.invokeExact(
                                (int) pathParamLong, request);
                        case TYPE_TWO_INT_PARAMS ->
                            (Response) mhBound.invokeExact(
                                (int) pathParamLong,
                                (int) (pathParamLong >>> 32));
                        case TYPE_TWO_INT_STREAMING_BODY_PARAMS ->
                            (Response) mhBound.invokeExact(
                                (int) pathParamLong,
                                (int) (pathParamLong >>> 32),
                                (RequestBody) bodyRecord);
                        case TYPE_BOUND_ARGUMENTS ->
                            (Response) mhBound.invokeExact(
                                (Object[]) bodyRecord);
                        default -> Response.error("Unsupported handler type");
                    };
                    waiter.complete(res);
                } catch (Throwable t) {
                    waiter.completeExceptionally(t);
                }
            } finally {
                IsolatedRouteExecutor.releaseTask();
                PreparedInvocation.runCompletion(completion);
            }
        }
    }
}
