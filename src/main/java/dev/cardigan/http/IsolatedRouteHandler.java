// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import dev.cardigan.util.SimpleWaiter;
import java.lang.invoke.MethodHandle;

public final class IsolatedRouteHandler implements RouteHandler {
    private final FastRouteHandler handler;

    public IsolatedRouteHandler(Object controller, MethodHandle mh, int handlerType) {
        this.handler = new FastRouteHandler(controller, mh, handlerType);
    }

    @Override
    public boolean usesBoundArguments() {
        return handler.usesBoundArguments();
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
                    handler,
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
        private final FastRouteHandler handler;
        private final HttpRequest request;
        private final long pathParamLong;
        private final Object bodyRecord;
        private final SimpleWaiter<Response> waiter;
        private final Runnable completion;

        IsolatedRouteTask(FastRouteHandler handler, HttpRequest request, long pathParamLong, Object bodyRecord, SimpleWaiter<Response> waiter, Runnable completion) {
            this.handler = handler;
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
                    Response res = handler.handle(
                        request, pathParamLong, bodyRecord);
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
