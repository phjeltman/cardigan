// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.util.concurrent.atomic.LongAdder;

/** Opt-in accounting for the HTTP/1 completion-driven request path. */
final class Http1CqeDriverStats {
    static final boolean ENABLED =
        Boolean.getBoolean("cardigan.http1.cqeDriver.stats");

    static final int STOP_COMPLETED = 0;
    static final int STOP_PARTIAL_HEADER = 1;
    static final int STOP_HEADER_REJECTED = 2;
    static final int STOP_REQUEST_FRAMING = 3;
    static final int STOP_UNSAFE_METHOD = 4;
    static final int STOP_SEQUENCER_CAPACITY = 5;
    static final int STOP_SCHEDULE_FAILURE = 6;
    static final int STOP_DRIVER_EXCEPTION = 7;

    private final LongAdder connectionAttempts = new LongAdder();
    private final LongAdder driversStarted = new LongAdder();
    private final LongAdder driversCompleted = new LongAdder();
    private final LongAdder drivenRequests = new LongAdder();
    private final LongAdder protocolRuns = new LongAdder();
    private final LongAdder queuedChunksConsumed = new LongAdder();
    private final LongAdder fallbacks = new LongAdder();
    private final LongAdder partialHeaderFallbacks = new LongAdder();
    private final LongAdder rejectedHeaderFallbacks = new LongAdder();
    private final LongAdder requestFramingFallbacks = new LongAdder();
    private final LongAdder unsafeMethodFallbacks = new LongAdder();
    private final LongAdder sequencerCapacityFallbacks = new LongAdder();
    private final LongAdder listenerUnavailableFallbacks = new LongAdder();
    private final LongAdder scheduleFailures = new LongAdder();
    private final LongAdder driverExceptions = new LongAdder();

    void connectionAttempted() {
        connectionAttempts.increment();
    }

    void listenerUnavailable() {
        fallbacks.increment();
        listenerUnavailableFallbacks.increment();
    }

    void driverStopped(
            long requests,
            long runs,
            long chunks,
            int stopReason) {
        driversStarted.increment();
        drivenRequests.add(requests);
        protocolRuns.add(runs);
        queuedChunksConsumed.add(chunks);
        switch (stopReason) {
            case STOP_COMPLETED -> driversCompleted.increment();
            case STOP_PARTIAL_HEADER -> {
                fallbacks.increment();
                partialHeaderFallbacks.increment();
            }
            case STOP_HEADER_REJECTED -> {
                fallbacks.increment();
                rejectedHeaderFallbacks.increment();
            }
            case STOP_REQUEST_FRAMING -> {
                fallbacks.increment();
                requestFramingFallbacks.increment();
            }
            case STOP_UNSAFE_METHOD -> {
                fallbacks.increment();
                unsafeMethodFallbacks.increment();
            }
            case STOP_SEQUENCER_CAPACITY -> {
                fallbacks.increment();
                sequencerCapacityFallbacks.increment();
            }
            case STOP_SCHEDULE_FAILURE -> scheduleFailures.increment();
            case STOP_DRIVER_EXCEPTION -> driverExceptions.increment();
            default -> throw new IllegalArgumentException(
                "Unknown HTTP/1 CQE driver stop reason: " + stopReason);
        }
    }

    Snapshot snapshot() {
        return new Snapshot(
            connectionAttempts.sum(),
            driversStarted.sum(),
            driversCompleted.sum(),
            drivenRequests.sum(),
            protocolRuns.sum(),
            queuedChunksConsumed.sum(),
            fallbacks.sum(),
            partialHeaderFallbacks.sum(),
            rejectedHeaderFallbacks.sum(),
            requestFramingFallbacks.sum(),
            unsafeMethodFallbacks.sum(),
            sequencerCapacityFallbacks.sum(),
            listenerUnavailableFallbacks.sum(),
            scheduleFailures.sum(),
            driverExceptions.sum()
        );
    }

    record Snapshot(
            long connectionAttempts,
            long driversStarted,
            long driversCompleted,
            long drivenRequests,
            long protocolRuns,
            long queuedChunksConsumed,
            long fallbacks,
            long partialHeaderFallbacks,
            long rejectedHeaderFallbacks,
            long requestFramingFallbacks,
            long unsafeMethodFallbacks,
            long sequencerCapacityFallbacks,
            long listenerUnavailableFallbacks,
            long scheduleFailures,
            long driverExceptions) {

        String summary() {
            return "connection-attempts=" + connectionAttempts
                + ", drivers-started=" + driversStarted
                + ", drivers-completed=" + driversCompleted
                + ", driven-requests=" + drivenRequests
                + ", protocol-runs=" + protocolRuns
                + ", queued-chunks-consumed=" + queuedChunksConsumed
                + ", fallbacks=" + fallbacks
                + ", partial-header=" + partialHeaderFallbacks
                + ", rejected-header=" + rejectedHeaderFallbacks
                + ", request-framing=" + requestFramingFallbacks
                + ", unsafe-method=" + unsafeMethodFallbacks
                + ", sequencer-capacity=" + sequencerCapacityFallbacks
                + ", listener-unavailable="
                + listenerUnavailableFallbacks
                + ", schedule-failures=" + scheduleFailures
                + ", driver-exceptions=" + driverExceptions;
        }
    }
}
