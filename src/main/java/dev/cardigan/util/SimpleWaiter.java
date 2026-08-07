// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.util;

import java.util.concurrent.locks.LockSupport;

public final class SimpleWaiter<T> {
    private volatile T result;
    private volatile Throwable error;
    private volatile Thread waiter;
    private volatile boolean cancelled;

    public T await() {
        waiter = Thread.currentThread();
        while (result == null && error == null && !cancelled) {
            LockSupport.park();
        }
        if (cancelled) {
            return null;
        }
        if (error != null) {
            throw new RuntimeException(error);
        }
        return result;
    }

    public void complete(T value) {
        result = value;
        wakeUp();
    }

    public void completeExceptionally(Throwable t) {
        error = t;
        wakeUp();
    }

    public void cancel() {
        cancelled = true;
        wakeUp();
    }

    private void wakeUp() {
        Thread w = waiter;
        if (w != null) LockSupport.unpark(w);
    }
}
