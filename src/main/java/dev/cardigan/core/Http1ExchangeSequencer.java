// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.Response;
import dev.cardigan.http.Router;
import dev.cardigan.http.StreamingBody;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;

/**
 * Applies HTTP/1 response ordering to otherwise independent exchanges.
 */
final class Http1ExchangeSequencer implements Exchange.Completion {
    private static final VarHandle IN_FLIGHT;
    private static final VarHandle FREE_TASK_COUNT;
    private static final VarHandle TASK_ACTIVE;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            IN_FLIGHT = lookup.findVarHandle(Http1ExchangeSequencer.class, "inFlight", int.class);
            FREE_TASK_COUNT = lookup.findVarHandle(Http1ExchangeSequencer.class, "freeTaskCount", int.class);
            TASK_ACTIVE = lookup.findVarHandle(ExchangeTask.class, "active", boolean.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @FunctionalInterface
    interface ResponseSender {
        boolean send(Response response, boolean keepAlive);
    }

    private final ExchangeExecutor executor;
    private final ResponseSender responseSender;
    private final int maxInFlight;
    private final int mask;
    private final Response[] completedResponses;
    private final boolean[] keepAlive;
    private final ExchangeTask[] freeTasks;
    private final ExchangeTask[] allTasks;

    private long nextSubmission;
    private long nextResponse;
    private int taskCount;
    private int freeTaskCount;
    private int inFlight;
    private boolean sending;
    private volatile boolean failed;
    private volatile boolean draining;
    private volatile Thread waiter;
    private volatile StreamingBody activeResponseBody;

    Http1ExchangeSequencer(ExchangeExecutor executor, int requestedMaxInFlight,
                           ResponseSender responseSender) {
        this.executor = executor;
        this.responseSender = responseSender;

        int capacity = 1;
        while (capacity < Math.max(2, requestedMaxInFlight)) {
            capacity <<= 1;
        }
        this.maxInFlight = capacity;
        this.mask = capacity - 1;
        this.completedResponses = new Response[capacity];
        this.keepAlive = new boolean[capacity];
        this.freeTasks = new ExchangeTask[capacity];
        this.allTasks = new ExchangeTask[capacity];
    }

    boolean submit(Router router, HttpRequest request, boolean requestKeepAlive) {
        return submit(router, request, requestKeepAlive, null);
    }

    boolean submit(
            Router router,
            HttpRequest request,
            boolean requestKeepAlive,
            AutoCloseable requestStorage) {
        if (!awaitCapacity()) {
            closeRequestStorage(requestStorage);
            return false;
        }

        long id = nextSubmission++;
        setInFlight(inFlight() + 1);
        ExchangeTask task = acquireTask();
        try {
            router.prepare(
                request,
                task.exchange.invocation(),
                null,
                requestStorage);
        } finally {
            // Router either retained the storage or closed it before return.
            requestStorage = null;
        }
        task.exchange.prepare(id, requestKeepAlive);
        task.setActive(true);
        if (!executor.submit(task)) {
            task.setActive(false);
            task.exchange.invocation().discard();
            releaseTask(task);
            setInFlight(inFlight() - 1);
            failed = true;
            signalWaiter();
            return false;
        }
        return true;
    }

    private static void closeRequestStorage(AutoCloseable storage) {
        if (storage == null) {
            return;
        }
        try {
            storage.close();
        } catch (Throwable ignored) {
            // A failed submission must still retire the exchange cleanly.
        }
    }

    boolean hasInFlight() {
        return inFlight() != 0;
    }

    boolean isFailed() {
        return failed;
    }

    void beginDrain() {
        draining = true;
    }

    void cancelAll() {
        failed = true;
        StreamingBody activeBody = activeResponseBody;
        if (activeBody != null) {
            try {
                activeBody.close();
            } catch (Throwable ignored) {
                // Continue cancelling the remaining exchanges.
            }
        }
        for (int i = 0; i < taskCount; i++) {
            ExchangeTask task = allTasks[i];
            if (task.isActive()) {
                task.exchange.invocation().cancel();
            }
        }
        discardCompletedResponses();
        signalWaiter();
    }

    boolean awaitAll() {
        if (inFlight() == 0 || failed) {
            return !failed;
        }

        Thread current = Thread.currentThread();
        boolean interrupted = false;
        waiter = current;
        while (inFlight() != 0 && !failed) {
            LockSupport.park(this);
            if (inFlight() != 0 && !failed && Thread.interrupted()) {
                interrupted = true;
            }
        }
        waiter = null;
        if (interrupted) {
            current.interrupt();
        }
        return !failed;
    }

    @Override
    public void complete(Exchange exchange, Response response) {
        if (failed) {
            closeResponseBody(response);
            finishExchange();
            return;
        }

        if (exchange.id() == nextResponse && !sending) {
            sending = true;
            try {
                nextResponse++;
                if (sendCompleted(
                        response,
                        responseKeepAlive(exchange.keepAlive()))) {
                    drainCompletedResponses();
                }
            } finally {
                sending = false;
            }
            return;
        }

        int index = (int) exchange.id() & mask;
        completedResponses[index] = response;
        keepAlive[index] = exchange.keepAlive();
    }

    private boolean awaitCapacity() {
        if (inFlight() < maxInFlight || failed) {
            return !failed;
        }

        Thread current = Thread.currentThread();
        boolean interrupted = false;
        waiter = current;
        while (inFlight() >= maxInFlight && !failed) {
            LockSupport.park(this);
            if (inFlight() >= maxInFlight && !failed && Thread.interrupted()) {
                interrupted = true;
            }
        }
        waiter = null;
        if (interrupted) {
            current.interrupt();
        }
        return !failed;
    }

    private ExchangeTask acquireTask() {
        int available = freeTaskCount();
        if (available != 0) {
            int index = available - 1;
            ExchangeTask task = freeTasks[index];
            freeTasks[index] = null;
            setFreeTaskCount(index);
            return task;
        }
        if (taskCount < freeTasks.length) {
            ExchangeTask task = new ExchangeTask();
            allTasks[taskCount++] = task;
            return task;
        }
        throw new IllegalStateException("No exchange slot available below the in-flight limit");
    }

    private void releaseTask(ExchangeTask task) {
        int available = freeTaskCount();
        freeTasks[available] = task;
        setFreeTaskCount(available + 1);
    }

    private void drainCompletedResponses() {
        while (!failed) {
            int index = (int) nextResponse & mask;
            Response response = completedResponses[index];
            if (response == null) {
                break;
            }

            boolean responseKeepAlive = keepAlive[index];
            completedResponses[index] = null;
            nextResponse++;

            if (!sendCompleted(response, responseKeepAlive(responseKeepAlive))) {
                break;
            }
        }
    }

    private boolean responseKeepAlive(boolean requested) {
        return requested
            && !(draining && nextResponse == nextSubmission);
    }

    private boolean sendCompleted(Response response, boolean responseKeepAlive) {
        StreamingBody streamingBody = response.body()
            instanceof StreamingBody body ? body : null;
        boolean sent;
        if (streamingBody == null) {
            // Only streaming bodies need cancellation publication.
            sent = responseSender.send(response, responseKeepAlive);
        } else {
            activeResponseBody = streamingBody;
            try {
                sent = responseSender.send(response, responseKeepAlive);
            } finally {
                activeResponseBody = null;
            }
        }
        finishExchange();
        if (sent) {
            return true;
        }

        failed = true;
        discardCompletedResponses();
        signalWaiter();
        return false;
    }

    private void failExchange() {
        failed = true;
        finishExchange();
        discardCompletedResponses();
        signalWaiter();
    }

    private void finishExchange() {
        setInFlight(inFlight() - 1);
        signalWaiter();
    }

    private void discardCompletedResponses() {
        for (int i = 0; i < completedResponses.length; i++) {
            Response response = completedResponses[i];
            if (response != null) {
                // Detach ownership before application cleanup. A throwing
                // close action must not retain the slot or strand accounting.
                completedResponses[i] = null;
                try {
                    closeResponseBody(response);
                } finally {
                    setInFlight(inFlight() - 1);
                }
            }
        }
    }

    private static void closeResponseBody(Response response) {
        if (response != null
            && response.body() instanceof StreamingBody streamingBody) {
            try {
                streamingBody.close();
            } catch (Throwable ignored) {
                // Transport retirement cannot depend on application cleanup.
            }
        }
    }

    private void signalWaiter() {
        Thread waitingThread = waiter;
        if (waitingThread != null) {
            LockSupport.unpark(waitingThread);
        }
    }

    private int inFlight() {
        return (int) IN_FLIGHT.getAcquire(this);
    }

    private void setInFlight(int value) {
        IN_FLIGHT.setRelease(this, value);
    }

    private int freeTaskCount() {
        return (int) FREE_TASK_COUNT.getAcquire(this);
    }

    private void setFreeTaskCount(int value) {
        FREE_TASK_COUNT.setRelease(this, value);
    }

    private final class ExchangeTask implements Runnable {
        private final Exchange exchange = new Exchange(Http1ExchangeSequencer.this);
        // Cancellation needs one-way publication, not a full volatile fence.
        private boolean active;

        private boolean isActive() {
            return (boolean) TASK_ACTIVE.getAcquire(this);
        }

        private void setActive(boolean value) {
            TASK_ACTIVE.setRelease(this, value);
        }

        @Override
        public void run() {
            try {
                if (failed) {
                    finishExchange();
                } else {
                    exchange.run();
                }
            } catch (Throwable t) {
                if (failed) {
                    finishExchange();
                } else {
                    failExchange();
                }
            } finally {
                setActive(false);
                if (exchange.invocation().isCancelled()) {
                    exchange.invocation().resetCancellation();
                }
                releaseTask(this);
            }
        }
    }
}
