// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Http1CqeDriverStatsTest {
    @Test
    void accountsForEachFallbackReasonAndDriverFailure() {
        Http1CqeDriverStats stats = new Http1CqeDriverStats();
        stats.connectionAttempted();
        stats.listenerUnavailable();

        int[] reasons = {
            Http1CqeDriverStats.STOP_COMPLETED,
            Http1CqeDriverStats.STOP_PARTIAL_HEADER,
            Http1CqeDriverStats.STOP_HEADER_REJECTED,
            Http1CqeDriverStats.STOP_REQUEST_FRAMING,
            Http1CqeDriverStats.STOP_UNSAFE_METHOD,
            Http1CqeDriverStats.STOP_SEQUENCER_CAPACITY,
            Http1CqeDriverStats.STOP_SCHEDULE_FAILURE,
            Http1CqeDriverStats.STOP_DRIVER_EXCEPTION
        };
        for (int reason : reasons) {
            stats.connectionAttempted();
            stats.driverStopped(11, 3, 2, reason);
        }

        Http1CqeDriverStats.Snapshot snapshot = stats.snapshot();
        assertEquals(9, snapshot.connectionAttempts());
        assertEquals(8, snapshot.driversStarted());
        assertEquals(1, snapshot.driversCompleted());
        assertEquals(88, snapshot.drivenRequests());
        assertEquals(24, snapshot.protocolRuns());
        assertEquals(16, snapshot.queuedChunksConsumed());
        assertEquals(6, snapshot.fallbacks());
        assertEquals(1, snapshot.partialHeaderFallbacks());
        assertEquals(1, snapshot.rejectedHeaderFallbacks());
        assertEquals(1, snapshot.requestFramingFallbacks());
        assertEquals(1, snapshot.unsafeMethodFallbacks());
        assertEquals(1, snapshot.sequencerCapacityFallbacks());
        assertEquals(1, snapshot.listenerUnavailableFallbacks());
        assertEquals(1, snapshot.scheduleFailures());
        assertEquals(1, snapshot.driverExceptions());
        assertTrue(snapshot.summary().contains("partial-header=1"));
    }
}
