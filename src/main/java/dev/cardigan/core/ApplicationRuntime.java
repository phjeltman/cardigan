// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

/** Runtime services layered over a core-local reactor. */
interface ApplicationRuntime extends BlockingSupport, AutoCloseable {
    ApplicationLane applicationLane();

    RuntimeTask startTask(Runnable task);

    void executeTask(Runnable task);

    CompletionWait beginCompletionWait();

    boolean inCarrierDomain();

    int workerCount();

    VirtualThreadStats stats();

    boolean awaitTermination(long timeoutMillis);

    @Override
    void close();

    interface RuntimeTask {
        void join(long timeoutMillis);

        boolean isAlive();

        void wake();

        StackTraceElement[] stackTrace();
    }

    interface CompletionWait extends UringEventLoop.CompletionHandler {
        int awaitResult();

        int flags();

        void abandon();
    }

    record VirtualThreadStats(
        long coreMounts,
        long coreUnmounts,
        long handlerMounts,
        long handlerUnmounts
    ) {
        long mounts() {
            return coreMounts + handlerMounts;
        }

        long unmounts() {
            return coreUnmounts + handlerUnmounts;
        }
    }
}
