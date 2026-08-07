// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Opt-in process-wide resource accounting for HTTP/2 flow-control studies.
 * Static-final guards let the JIT eliminate the instrumentation when disabled.
 */
public record Http2ResourceStats(
        boolean enabled,
        int parkedSenderLimitPerLoop,
        int activeStreams,
        int peakActiveStreams,
        int parkedSenders,
        int peakParkedSenders,
        int exchangeWorkers,
        int peakExchangeWorkers,
        int egressBuffersInUse,
        int peakEgressBuffersInUse,
        long sendWindowWaits,
        long rejectedSendWindowWaits,
        long egressBufferMisses) {

    static final boolean ENABLED =
        Boolean.getBoolean("cardigan.http2.resource.stats");

    private static final AtomicInteger ACTIVE_STREAMS = new AtomicInteger();
    private static final AtomicInteger PEAK_ACTIVE_STREAMS = new AtomicInteger();
    private static final AtomicInteger PARKED_SENDERS = new AtomicInteger();
    private static final AtomicInteger PEAK_PARKED_SENDERS = new AtomicInteger();
    private static final AtomicInteger EXCHANGE_WORKERS = new AtomicInteger();
    private static final AtomicInteger PEAK_EXCHANGE_WORKERS = new AtomicInteger();
    private static final AtomicInteger EGRESS_BUFFERS = new AtomicInteger();
    private static final AtomicInteger PEAK_EGRESS_BUFFERS = new AtomicInteger();
    private static final LongAdder SEND_WINDOW_WAITS = new LongAdder();
    private static final LongAdder REJECTED_SEND_WINDOW_WAITS = new LongAdder();
    private static final LongAdder EGRESS_BUFFER_MISSES = new LongAdder();

    public static Http2ResourceStats snapshot() {
        return new Http2ResourceStats(
            ENABLED,
            UringEventLoop.configuredMaxHttp2ParkedSenders(),
            ACTIVE_STREAMS.get(),
            PEAK_ACTIVE_STREAMS.get(),
            PARKED_SENDERS.get(),
            PEAK_PARKED_SENDERS.get(),
            EXCHANGE_WORKERS.get(),
            PEAK_EXCHANGE_WORKERS.get(),
            EGRESS_BUFFERS.get(),
            PEAK_EGRESS_BUFFERS.get(),
            SEND_WINDOW_WAITS.sum(),
            REJECTED_SEND_WINDOW_WAITS.sum(),
            EGRESS_BUFFER_MISSES.sum()
        );
    }

    public String summary() {
        return "parked-sender-limit-per-loop=" + parkedSenderLimitPerLoop
            + ", active-streams=" + activeStreams
            + ", peak-active-streams=" + peakActiveStreams
            + ", parked-senders=" + parkedSenders
            + ", peak-parked-senders=" + peakParkedSenders
            + ", exchange-workers=" + exchangeWorkers
            + ", peak-exchange-workers=" + peakExchangeWorkers
            + ", egress-buffers=" + egressBuffersInUse
            + ", peak-egress-buffers=" + peakEgressBuffersInUse
            + ", send-window-waits=" + sendWindowWaits
            + ", rejected-send-window-waits=" + rejectedSendWindowWaits
            + ", egress-buffer-misses=" + egressBufferMisses;
    }

    static void streamStarted() {
        updatePeak(ACTIVE_STREAMS.incrementAndGet(), PEAK_ACTIVE_STREAMS);
    }

    static void streamCompleted() {
        ACTIVE_STREAMS.decrementAndGet();
    }

    static void senderParked() {
        SEND_WINDOW_WAITS.increment();
        updatePeak(PARKED_SENDERS.incrementAndGet(), PEAK_PARKED_SENDERS);
    }

    static void senderResumed() {
        PARKED_SENDERS.decrementAndGet();
    }

    static void senderRejected() {
        REJECTED_SEND_WINDOW_WAITS.increment();
    }

    static void exchangeWorkerStarted() {
        updatePeak(
            EXCHANGE_WORKERS.incrementAndGet(),
            PEAK_EXCHANGE_WORKERS
        );
    }

    static void exchangeWorkerStopped() {
        EXCHANGE_WORKERS.decrementAndGet();
    }

    static void egressBufferAcquired() {
        updatePeak(
            EGRESS_BUFFERS.incrementAndGet(),
            PEAK_EGRESS_BUFFERS
        );
    }

    static void egressBufferReleased() {
        EGRESS_BUFFERS.decrementAndGet();
    }

    static void egressBufferMissed() {
        EGRESS_BUFFER_MISSES.increment();
    }

    private static void updatePeak(int value, AtomicInteger peak) {
        int current = peak.get();
        while (value > current && !peak.compareAndSet(current, value)) {
            current = peak.get();
        }
    }
}
