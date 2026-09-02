// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.ffi.ThreadAffinity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Owns the per-CPU transport runtime shared by one or more listeners.
 * Servers attached to the same runtime share one io_uring event loop per CPU.
 */
public final class CardiganRuntime implements AutoCloseable {
    private final int[] eventLoopCpus;
    private final boolean directKtlsReceive;
    private final List<UringEventLoop> eventLoops = new ArrayList<>();
    private int attachedServers;
    private long drivenRequests;
    private boolean closed;

    private CardiganRuntime(
            int eventLoopCount,
            String configuredEventLoopCpus,
            boolean directKtlsReceive) {
        ThreadAffinity.initialize();
        String cpuList = configuredEventLoopCpus;
        if (cpuList == null || cpuList.isBlank()) {
            cpuList = System.getProperty("cardigan.eventloop.cpus", "");
        }
        this.eventLoopCpus = cpuList.isBlank()
            ? ThreadAffinity.processCpus(eventLoopCount)
            : ThreadAffinity.processCpus(cpuList);
        this.directKtlsReceive = directKtlsReceive;

        int ingressBuffersPerLoop =
            CardiganServer.configuredIngressBuffersPerLoop();
        try {
            for (int cpuId : eventLoopCpus) {
                eventLoops.add(new UringEventLoop(
                    cpuId,
                    512,
                    ingressBuffersPerLoop,
                    directKtlsReceive));
            }
        } catch (Throwable failure) {
            closeLoops();
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                "Cardigan runtime initialization failed", failure);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int eventLoops = Runtime.getRuntime().availableProcessors();
        private String eventLoopCpus;
        private boolean directKtlsReceive;

        private Builder() {
        }

        public Builder eventLoops(int eventLoops) {
            if (eventLoops < 1) {
                throw new IllegalArgumentException(
                    "eventLoops must be positive");
            }
            this.eventLoops = eventLoops;
            this.eventLoopCpus = null;
            return this;
        }

        /**
         * Pins event loops to an explicit Linux CPU-list expression. The list
         * must be contained in the process affinity mask.
         */
        public Builder eventLoopCpus(String eventLoopCpus) {
            if (eventLoopCpus == null || eventLoopCpus.isBlank()) {
                throw new IllegalArgumentException(
                    "eventLoopCpus must not be empty");
            }
            this.eventLoopCpus = eventLoopCpus;
            return this;
        }

        /** Allocates the additional buffer ring used by direct kTLS receive. */
        public Builder directKtlsReceive(boolean directKtlsReceive) {
            this.directKtlsReceive = directKtlsReceive;
            return this;
        }

        public CardiganRuntime build() {
            return new CardiganRuntime(
                eventLoops, eventLoopCpus, directKtlsReceive);
        }
    }

    synchronized void attach(boolean requiresDirectKtlsReceive) {
        if (closed) {
            throw new IllegalStateException("CardiganRuntime is closed");
        }
        if (requiresDirectKtlsReceive && !directKtlsReceive) {
            throw new IllegalArgumentException(
                "The shared CardiganRuntime must enable direct kTLS receive "
                    + "before attaching this server");
        }
        attachedServers++;
    }

    synchronized void detach(long serverDrivenRequests) {
        if (attachedServers <= 0) {
            throw new IllegalStateException(
                "CardiganRuntime server ownership is unbalanced");
        }
        attachedServers--;
        drivenRequests += serverDrivenRequests;
    }

    List<UringEventLoop> eventLoops() {
        return eventLoops;
    }

    int[] eventLoopCpus() {
        return Arrays.copyOf(eventLoopCpus, eventLoopCpus.length);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        if (attachedServers != 0) {
            throw new IllegalStateException(
                "Close attached CardiganServer instances before their runtime");
        }
        closed = true;
        printVirtualThreadStats(eventLoops, drivenRequests);
        IllegalStateException failure = closeLoops();
        if (failure != null) {
            throw failure;
        }
    }

    private IllegalStateException closeLoops() {
        IllegalStateException failure = null;
        for (UringEventLoop loop : eventLoops) {
            try {
                loop.close();
            } catch (Exception e) {
                System.err.println(
                    "Error closing shared event loop: " + e.getMessage());
                if (failure == null) {
                    failure = new IllegalStateException(
                        "One or more shared event loops could not stop safely",
                        e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        eventLoops.clear();
        return failure;
    }

    static void printVirtualThreadStats(
            List<UringEventLoop> eventLoops,
            long drivenRequests) {
        if (!LoomRuntime.STATS_ENABLED) return;
        long coreMounts = 0;
        long coreUnmounts = 0;
        long handlerMounts = 0;
        long handlerUnmounts = 0;
        for (UringEventLoop loop : eventLoops) {
            LoomRuntime.VirtualThreadStats stats =
                loop.virtualThreadStats();
            coreMounts += stats.coreMounts();
            coreUnmounts += stats.coreUnmounts();
            handlerMounts += stats.handlerMounts();
            handlerUnmounts += stats.handlerUnmounts();
        }
        long mounts = coreMounts + handlerMounts;
        long unmounts = coreUnmounts + handlerUnmounts;
        String mountsPerRequest = drivenRequests == 0
            ? "n/a"
            : String.format(
                Locale.ROOT,
                "%.6f",
                (double) mounts / drivenRequests);
        System.out.println(
            "Virtual-thread mount stats: core=" + coreMounts
                + ", core-unmounts=" + coreUnmounts
                + ", handlers=" + handlerMounts
                + ", handler-unmounts=" + handlerUnmounts
                + ", total-mounts=" + mounts
                + ", total-unmounts=" + unmounts
                + ", driven-requests=" + drivenRequests
                + ", mounts-per-driven-request="
                + mountsPerRequest);
    }
}
