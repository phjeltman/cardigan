// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.tls;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/** Opt-in counters for separating TLS control and application-data costs. */
public final class TlsStats {
    public static final boolean ENABLED =
        Boolean.getBoolean("cardigan.tls.stats");

    private static final LongAdder HANDSHAKES = new LongAdder();
    private static final LongAdder HANDSHAKE_NANOS = new LongAdder();
    private static final LongAccumulator MAX_HANDSHAKE_NANOS =
        new LongAccumulator(Long::max, 0);
    private static final LongAdder KTLS_TX = new LongAdder();
    private static final LongAdder KTLS_RX = new LongAdder();
    private static final LongAdder READ_CALLS = new LongAdder();
    private static final LongAdder READ_BYTES = new LongAdder();
    private static final LongAdder READ_WANT_READ = new LongAdder();
    private static final LongAdder READ_WANT_WRITE = new LongAdder();
    private static final LongAdder WRITE_CALLS = new LongAdder();
    private static final LongAdder WRITE_BYTES = new LongAdder();
    private static final LongAdder WRITE_WANT_READ = new LongAdder();
    private static final LongAdder WRITE_WANT_WRITE = new LongAdder();
    private static final LongAdder RECEIVE_CHUNKS = new LongAdder();
    private static final LongAdder RECEIVE_ALLOCATED_BYTES = new LongAdder();
    private static final LongAdder RECEIVE_FALLBACKS = new LongAdder();
    private static final LongAdder SEND_TASKS = new LongAdder();
    private static final LongAdder SEND_FRAMES = new LongAdder();
    private static final LongAdder DIRECT_SENDS = new LongAdder();
    private static final LongAdder DIRECT_SEND_FRAMES = new LongAdder();
    private static final LongAdder DIRECT_RECEIVE_CONNECTIONS =
        new LongAdder();
    private static final LongAdder DIRECT_RECEIVE_RECORDS = new LongAdder();
    private static final LongAdder DIRECT_RECEIVE_ALERTS = new LongAdder();
    private static final LongAdder DIRECT_RECEIVE_CLOSE_NOTIFIES =
        new LongAdder();
    private static final LongAdder DIRECT_RECEIVE_FAILURES = new LongAdder();

    private TlsStats() {
    }

    public static void handshakeCompleted(
            long elapsedNanos, boolean ktlsTx, boolean ktlsRx) {
        HANDSHAKES.increment();
        HANDSHAKE_NANOS.add(elapsedNanos);
        MAX_HANDSHAKE_NANOS.accumulate(elapsedNanos);
        if (ktlsTx) {
            KTLS_TX.increment();
        }
        if (ktlsRx) {
            KTLS_RX.increment();
        }
    }

    public static void readResult(int result) {
        READ_CALLS.increment();
        if (result > 0) {
            READ_BYTES.add(result);
        } else if (result == -2) {
            READ_WANT_READ.increment();
        } else if (result == -3) {
            READ_WANT_WRITE.increment();
        }
    }

    public static void writeResult(int result) {
        WRITE_CALLS.increment();
        if (result > 0) {
            WRITE_BYTES.add(result);
        } else if (result == -2) {
            WRITE_WANT_READ.increment();
        } else if (result == -3) {
            WRITE_WANT_WRITE.increment();
        }
    }

    public static void receiveChunk() {
        RECEIVE_CHUNKS.increment();
    }

    public static void receivePoolAllocated(int allocatedBytes) {
        RECEIVE_ALLOCATED_BYTES.add(allocatedBytes);
    }

    public static void receiveFallbackAllocated(int allocatedBytes) {
        RECEIVE_FALLBACKS.increment();
        RECEIVE_ALLOCATED_BYTES.add(allocatedBytes);
    }

    public static void sendTask(int frames) {
        SEND_TASKS.increment();
        SEND_FRAMES.add(frames);
    }

    public static void directSend(int frames) {
        DIRECT_SENDS.increment();
        DIRECT_SEND_FRAMES.add(frames);
    }

    public static void directReceiveConnection() {
        DIRECT_RECEIVE_CONNECTIONS.increment();
    }

    public static void directReceiveRecord() {
        DIRECT_RECEIVE_RECORDS.increment();
    }

    public static void directReceiveAlert() {
        DIRECT_RECEIVE_ALERTS.increment();
    }

    public static void directReceiveCloseNotify() {
        DIRECT_RECEIVE_CLOSE_NOTIFIES.increment();
    }

    public static void directReceiveFailure() {
        DIRECT_RECEIVE_FAILURES.increment();
    }

    public static Snapshot snapshot() {
        return new Snapshot(
            HANDSHAKES.sum(),
            HANDSHAKE_NANOS.sum(),
            MAX_HANDSHAKE_NANOS.get(),
            KTLS_TX.sum(),
            KTLS_RX.sum(),
            READ_CALLS.sum(),
            READ_BYTES.sum(),
            READ_WANT_READ.sum(),
            READ_WANT_WRITE.sum(),
            WRITE_CALLS.sum(),
            WRITE_BYTES.sum(),
            WRITE_WANT_READ.sum(),
            WRITE_WANT_WRITE.sum(),
            RECEIVE_CHUNKS.sum(),
            RECEIVE_ALLOCATED_BYTES.sum(),
            RECEIVE_FALLBACKS.sum(),
            SEND_TASKS.sum(),
            SEND_FRAMES.sum(),
            DIRECT_SENDS.sum(),
            DIRECT_SEND_FRAMES.sum(),
            DIRECT_RECEIVE_CONNECTIONS.sum(),
            DIRECT_RECEIVE_RECORDS.sum(),
            DIRECT_RECEIVE_ALERTS.sum(),
            DIRECT_RECEIVE_CLOSE_NOTIFIES.sum(),
            DIRECT_RECEIVE_FAILURES.sum()
        );
    }

    public record Snapshot(
        long handshakes,
        long handshakeNanos,
        long maxHandshakeNanos,
        long ktlsTx,
        long ktlsRx,
        long readCalls,
        long readBytes,
        long readWantRead,
        long readWantWrite,
        long writeCalls,
        long writeBytes,
        long writeWantRead,
        long writeWantWrite,
        long receiveChunks,
        long receiveAllocatedBytes,
        long receiveFallbacks,
        long sendTasks,
        long sendFrames,
        long directSends,
        long directSendFrames,
        long directReceiveConnections,
        long directReceiveRecords,
        long directReceiveAlerts,
        long directReceiveCloseNotifies,
        long directReceiveFailures
    ) {
        public String summary() {
            double averageHandshakeMicros = handshakes == 0
                ? 0
                : handshakeNanos / (double) handshakes / 1_000.0;
            return "handshakes=" + handshakes
                + ", handshake.avg.us="
                + String.format(java.util.Locale.ROOT, "%.1f", averageHandshakeMicros)
                + ", handshake.max.us="
                + String.format(
                    java.util.Locale.ROOT, "%.1f", maxHandshakeNanos / 1_000.0)
                + ", ktls.tx=" + ktlsTx
                + ", ktls.rx=" + ktlsRx
                + ", read.calls=" + readCalls
                + ", read.bytes=" + readBytes
                + ", read.want_read=" + readWantRead
                + ", read.want_write=" + readWantWrite
                + ", write.calls=" + writeCalls
                + ", write.bytes=" + writeBytes
                + ", write.want_read=" + writeWantRead
                + ", write.want_write=" + writeWantWrite
                + ", receive.chunks=" + receiveChunks
                + ", receive.allocated.bytes=" + receiveAllocatedBytes
                + ", receive.fallbacks=" + receiveFallbacks
                + ", send.tasks=" + sendTasks
                + ", send.frames=" + sendFrames
                + ", direct.sends=" + directSends
                + ", direct.send.frames=" + directSendFrames
                + ", direct.receive.connections="
                + directReceiveConnections
                + ", direct.receive.records=" + directReceiveRecords
                + ", direct.receive.alerts=" + directReceiveAlerts
                + ", direct.receive.close_notifies="
                + directReceiveCloseNotifies
                + ", direct.receive.failures=" + directReceiveFailures;
        }
    }
}
