// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

/** Microbenchmark for the scheduler's external MPSC queue snapshot drain. */
public final class MpscArrayQueueBenchmark {
    private static final int WARMUP_SECONDS = Integer.getInteger(
        "cardigan.benchmark.warmup.seconds", 2);
    private static final int RUN_SECONDS = Integer.getInteger(
        "cardigan.benchmark.run.seconds", 3);
    private static final Object TASK = new Object();

    private static volatile long blackhole;

    private MpscArrayQueueBenchmark() {
    }

    public static void run() {
        System.out.println("\n--- Scheduler external-ready snapshot drain ---");
        for (int batchSize : new int[] {1, 8, 64, 1_024}) {
            UringEventLoop.MpscArrayQueue<Object> queue =
                new UringEventLoop.MpscArrayQueue<>(2_048);
            Measurement measurement = measure(queue, batchSize);
            System.out.printf(
                "  %-54s %,14.2f tasks/s  %9.2f ns/task%n",
                "enqueue + snapshot drain, batch " + batchSize + ":",
                measurement.tasks()
                    / (measurement.elapsedNanos() / 1_000_000_000.0),
                (double) measurement.elapsedNanos() / measurement.tasks());
        }
    }

    private static Measurement measure(
            UringEventLoop.MpscArrayQueue<Object> queue, int batchSize) {
        long checksum = 0;
        long warmupDeadline =
            System.nanoTime() + WARMUP_SECONDS * 1_000_000_000L;
        do {
            checksum += cycle(queue, batchSize);
        } while (System.nanoTime() < warmupDeadline);
        blackhole = checksum;

        long tasks = 0;
        checksum = 0;
        long start = System.nanoTime();
        long deadline = start + RUN_SECONDS * 1_000_000_000L;
        do {
            checksum += cycle(queue, batchSize);
            tasks += batchSize;
        } while (System.nanoTime() < deadline);
        long elapsed = System.nanoTime() - start;
        blackhole = checksum;
        return new Measurement(tasks, elapsed);
    }

    private static int cycle(
            UringEventLoop.MpscArrayQueue<Object> queue, int batchSize) {
        for (int index = 0; index < batchSize; index++) {
            if (!queue.offer(TASK)) {
                throw new IllegalStateException("benchmark queue is full");
            }
        }

        long snapshotTail = queue.snapshotTail();
        int drained = 0;
        while (queue.pollSnapshot(snapshotTail) != null) {
            drained++;
        }
        if (drained != batchSize) {
            throw new IllegalStateException(
                "drained " + drained + " of " + batchSize + " tasks");
        }
        return drained;
    }

    private record Measurement(long tasks, long elapsedNanos) {
    }
}
