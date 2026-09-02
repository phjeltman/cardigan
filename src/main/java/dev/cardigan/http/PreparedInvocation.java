// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import dev.cardigan.util.SimpleWaiter;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/**
 * Route matching and request-bound argument extraction separated from the
 * eventual handler execution.
 */
public final class PreparedInvocation {
    private RouteHandler handler;
    private HttpRequest request;
    private long pathParamLong;
    private Object bodyRecord;
    private Router fallbackRouter;
    private Response immediateResponse;
    private HttpRequest reusableStoredRequest;
    private MemorySegment reusableDetachedStorage;
    private Object[] reusableArguments;
    private AutoCloseable requestStorage;
    private boolean safe;
    private volatile boolean cancelled;
    private Thread cancellableThread;
    private SimpleWaiter<?> cancellationWaiter;

    public PreparedInvocation() {
    }

    void beginPreparation() {
        releaseRequestStorage();
        clearReusableArguments();
    }

    Object[] arguments(int count, boolean isolated) {
        if (isolated) {
            return new Object[count];
        }
        if (reusableArguments == null
                || reusableArguments.length != count) {
            reusableArguments = new Object[count];
        }
        return reusableArguments;
    }

    HttpRequest storeRequest(
            HttpRequest source,
            AutoCloseable retainedStorage,
            boolean isolated) {
        if (retainedStorage != null && !isolated) {
            reusableStoredRequest = source.retainedView(
                reusableStoredRequest);
            requestStorage = retainedStorage;
            return reusableStoredRequest;
        }
        if (isolated) {
            return source.detachedCopy();
        }

        long required = source.detachedLength();
        if (reusableDetachedStorage == null
                || reusableDetachedStorage.byteSize() < required) {
            reusableDetachedStorage = Arena.ofAuto().allocate(
                reusableCapacity(required));
        }
        reusableStoredRequest = source.detachedCopyInto(
            reusableStoredRequest, reusableDetachedStorage);
        return reusableStoredRequest;
    }

    boolean ownsRequestStorage(AutoCloseable storage) {
        return storage != null && requestStorage == storage;
    }

    void retainRequestStorage(AutoCloseable storage) {
        requestStorage = storage;
    }

    public void discard() {
        releaseRequestStorage();
        clearReusableArguments();
    }

    PreparedInvocation setHandler(RouteHandler handler, HttpRequest request, long pathParamLong,
                                  Object bodyRecord, boolean safe) {
        this.handler = handler;
        this.request = request;
        this.pathParamLong = pathParamLong;
        this.bodyRecord = bodyRecord;
        this.fallbackRouter = null;
        this.immediateResponse = null;
        this.safe = safe;
        return this;
    }

    PreparedInvocation setFallback(Router router, HttpRequest request, boolean safe) {
        this.handler = null;
        this.request = request;
        this.pathParamLong = 0;
        this.bodyRecord = null;
        this.fallbackRouter = router;
        this.immediateResponse = null;
        this.safe = safe;
        return this;
    }

    PreparedInvocation setImmediate(Response response, boolean safe) {
        releaseRequestStorage();
        this.handler = null;
        this.request = null;
        this.pathParamLong = 0;
        this.bodyRecord = null;
        this.fallbackRouter = null;
        this.immediateResponse = response;
        this.safe = safe;
        return this;
    }

    public boolean isSafe() {
        return safe;
    }

    public Response invoke() {
        AutoCloseable storage = takeRequestStorage();
        if (cancelled) {
            closeStorage(storage);
            clearReusableArguments();
            return null;
        }
        if (immediateResponse != null) {
            closeStorage(storage);
            clearReusableArguments();
            return immediateResponse;
        }
        try {
            if (handler != null) {
                Response response = handler instanceof IsolatedRouteHandler isolated
                    ? isolated.handle(
                        request, pathParamLong, bodyRecord, this)
                    : handler.handle(request, pathParamLong, bodyRecord);
                return response != null || cancelled
                    ? response
                    : Response.error("Route returned no response");
            }
            return fallbackRouter.dispatch(request);
        } catch (Throwable t) {
            return Response.error("Internal Server Error: " + t.getMessage());
        } finally {
            closeStorage(storage);
            clearReusableArguments();
        }
    }

    /**
     * Invokes an isolated handler and releases resources when the handler
     * completes, including after caller cancellation.
     */
    public Response invokeWithCompletion(Runnable completion) {
        if (!(handler instanceof IsolatedRouteHandler isolated)) {
            try {
                return invoke();
            } finally {
                runCompletion(completion);
            }
        }
        if (cancelled) {
            runCompletion(completion);
            return null;
        }
        try {
            Response response = isolated.handleWithCompletion(
                request, pathParamLong, bodyRecord, this, completion);
            return response != null || cancelled
                ? response
                : Response.error("Route returned no response");
        } catch (Throwable t) {
            return Response.error(
                "Internal Server Error: " + t.getMessage());
        }
    }

    public void cancel() {
        Thread thread;
        SimpleWaiter<?> waiter;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            thread = cancellableThread;
            waiter = cancellationWaiter;
        }
        if (waiter != null) {
            IsolatedRouteExecutor.recordCancellation();
            waiter.cancel();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    synchronized boolean startCancellable(
            Thread thread, SimpleWaiter<?> waiter) {
        if (cancelled) {
            waiter.cancel();
            return false;
        }
        cancellableThread = thread;
        cancellationWaiter = waiter;
        try {
            thread.start();
            return true;
        } catch (Throwable error) {
            cancellableThread = null;
            cancellationWaiter = null;
            throw error;
        }
    }

    synchronized void clearCancellable(
            Thread thread, SimpleWaiter<?> waiter) {
        if (cancellableThread == thread) {
            cancellableThread = null;
        }
        if (cancellationWaiter == waiter) {
            cancellationWaiter = null;
        }
    }

    static void runCompletion(Runnable action) {
        if (action != null) {
            action.run();
        }
    }

    public void resetCancellation() {
        cancelled = false;
        cancellableThread = null;
        cancellationWaiter = null;
    }

    private AutoCloseable takeRequestStorage() {
        AutoCloseable storage = requestStorage;
        requestStorage = null;
        return storage;
    }

    private void releaseRequestStorage() {
        closeStorage(takeRequestStorage());
    }

    private void clearReusableArguments() {
        if (reusableArguments != null) {
            Arrays.fill(reusableArguments, null);
        }
    }

    private static void closeStorage(AutoCloseable storage) {
        if (storage == null) {
            return;
        }
        try {
            storage.close();
        } catch (Throwable ignored) {
            // Request-storage retirement must not replace a handler response.
        }
    }

    private static long reusableCapacity(long required) {
        long capacity = 64;
        while (capacity < required) {
            long grown = capacity << 1;
            if (grown <= capacity) {
                return required;
            }
            capacity = grown;
        }
        return capacity;
    }
}
