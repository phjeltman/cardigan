// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.benchmark;

import dev.cardigan.http2.Http2Frames;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deterministic HTTP/2 slow-reader probe. Each disruption connection
 * advertises a zero initial stream window, opens large responses, and leaves
 * them parked. Fast requests must still complete both on one of those
 * connections and on an independent healthy connection. The probe then
 * cancels every parked stream and verifies that all connections recover.
 */
public final class Http2FlowControlProbe {
    private static final String STALLED_PATH = "/some/response/large";
    private static final String FAST_PATH = "/users/423";
    private static final int STREAM_WINDOW = 65_535;

    private Http2FlowControlProbe() {
    }

    public static void main(String[] args) throws Exception {
        Configuration configuration = Configuration.parse(args);
        new Http2FlowControlProbeRunner(configuration).run();
    }

    private record Configuration(
            String host,
            int port,
            int connections,
            int stalledStreams,
            int cycles,
            int timeoutMillis,
            long serverPid) {

        private static Configuration parse(String[] args) {
            String host = "127.0.0.1";
            int port = 8080;
            int connections = 4;
            int stalledStreams = 64;
            int cycles = 5;
            int timeoutMillis = 5_000;
            long serverPid = 0;

            for (int i = 0; i < args.length; i++) {
                String option = args[i];
                String value;
                int separator = option.indexOf('=');
                if (separator >= 0) {
                    value = option.substring(separator + 1);
                    option = option.substring(0, separator);
                } else {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(
                            "Missing value for " + option);
                    }
                    value = args[++i];
                }
                switch (option) {
                    case "--host" -> host = value;
                    case "--port" -> port = positiveInt(option, value);
                    case "--connections" ->
                        connections = positiveInt(option, value);
                    case "--stalled-streams" ->
                        stalledStreams = positiveInt(option, value);
                    case "--cycles" -> cycles = positiveInt(option, value);
                    case "--timeout-millis" ->
                        timeoutMillis = positiveInt(option, value);
                    case "--server-pid" ->
                        serverPid = positiveLong(option, value);
                    default -> throw new IllegalArgumentException(
                        "Unknown option: " + option);
                }
            }

            if (stalledStreams > 127) {
                throw new IllegalArgumentException(
                    "--stalled-streams must be at most 127 so a probe stream "
                        + "fits under Cardigan's default per-connection limit");
            }
            return new Configuration(
                host, port, connections, stalledStreams, cycles,
                timeoutMillis, serverPid);
        }

        private static int positiveInt(String option, String value) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Report one consistent validation error below.
            }
            throw new IllegalArgumentException(
                option + " must be a positive integer: " + value);
        }

        private static long positiveLong(String option, String value) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Report one consistent validation error below.
            }
            throw new IllegalArgumentException(
                option + " must be a positive integer: " + value);
        }
    }

    private static final class Http2FlowControlProbeRunner {
        private final Configuration configuration;
        private final Latencies crossConnection = new Latencies();
        private final Latencies sameConnection = new Latencies();
        private final Latencies cancellationRecovery = new Latencies();

        private Http2FlowControlProbeRunner(Configuration configuration) {
            this.configuration = configuration;
        }

        private void run() throws Exception {
            List<RawConnection> disruptionConnections = new ArrayList<>();
            try (RssSampler rss = new RssSampler(configuration.serverPid());
                 RawConnection healthy = new RawConnection(
                     configuration.host(), configuration.port(),
                     configuration.timeoutMillis(), -1)) {
                for (int i = 0; i < configuration.connections(); i++) {
                    disruptionConnections.add(new RawConnection(
                        configuration.host(), configuration.port(),
                        configuration.timeoutMillis(), 0));
                }

                int totalStalled = configuration.connections()
                    * configuration.stalledStreams();
                System.out.println("HTTP/2 flow-control isolation probe");
                System.out.printf(
                    "Stalling %d streams across %d connections; %d cycles%n",
                    totalStalled, configuration.connections(),
                    configuration.cycles());

                for (int cycle = 1; cycle <= configuration.cycles(); cycle++) {
                    List<Set<Integer>> stalledByConnection = new ArrayList<>();
                    for (RawConnection connection : disruptionConnections) {
                        Set<Integer> stalled = new HashSet<>();
                        for (int i = 0;
                             i < configuration.stalledStreams(); i++) {
                            stalled.add(connection.request(STALLED_PATH));
                        }
                        connection.flush();
                        stalledByConnection.add(stalled);
                    }

                    RawConnection shared = disruptionConnections.getFirst();
                    Set<Integer> sharedStalled = stalledByConnection.getFirst();
                    long sharedStarted = System.nanoTime();
                    int sharedFastStream = shared.request(FAST_PATH);
                    shared.windowUpdate(sharedFastStream, STREAM_WINDOW);
                    shared.flush();

                    long crossStarted = System.nanoTime();
                    int healthyStream = healthy.request(FAST_PATH);
                    healthy.flush();
                    byte[] healthyBody = healthy.readResponse(
                        healthyStream, Set.of());
                    verifyFastResponse(healthyBody);
                    healthy.replenishConnectionWindow(healthyBody.length);
                    long crossNanos = System.nanoTime() - crossStarted;
                    crossConnection.add(crossNanos);

                    byte[] sharedBody = shared.readResponse(
                        sharedFastStream, sharedStalled);
                    verifyFastResponse(sharedBody);
                    shared.replenishConnectionWindow(sharedBody.length);
                    long sharedNanos = System.nanoTime() - sharedStarted;
                    sameConnection.add(sharedNanos);

                    long recoveryStarted = System.nanoTime();
                    for (int i = 0; i < disruptionConnections.size(); i++) {
                        RawConnection connection = disruptionConnections.get(i);
                        for (int streamId : stalledByConnection.get(i)) {
                            connection.cancel(streamId);
                        }
                        connection.ping(cycle);
                        connection.flush();
                    }
                    for (int i = 0; i < disruptionConnections.size(); i++) {
                        disruptionConnections.get(i).awaitPing(
                            cycle, stalledByConnection.get(i));
                    }
                    for (RawConnection connection : disruptionConnections) {
                        int streamId = connection.request(FAST_PATH);
                        connection.windowUpdate(streamId, STREAM_WINDOW);
                        connection.flush();
                        byte[] body = connection.readResponse(
                            streamId, Set.of());
                        verifyFastResponse(body);
                        connection.replenishConnectionWindow(body.length);
                    }
                    long recoveryNanos = System.nanoTime() - recoveryStarted;
                    cancellationRecovery.add(recoveryNanos);

                    System.out.printf(
                        "Cycle %d: other connection %s; shared connection %s; "
                            + "cancel + all-connection recovery %s; RSS %s%n",
                        cycle,
                        duration(crossNanos),
                        duration(sharedNanos),
                        duration(recoveryNanos),
                        rss.currentText());
                }

                System.out.println();
                crossConnection.print("Other-connection fast response");
                sameConnection.print("Shared-connection fast response");
                cancellationRecovery.print("Cancel + recovery sweep");
                int overloadResets = healthy.overloadResets();
                for (RawConnection connection : disruptionConnections) {
                    overloadResets += connection.overloadResets();
                }
                System.out.println(
                    "ENHANCE_YOUR_CALM stream resets: " + overloadResets);
                rss.printSummary();
                System.out.println("Result: PASS (no DATA escaped a zero-window stream)");
                // Let the server reap the final send CQEs before benchmark.sh
                // requests its diagnostic shutdown snapshot.
                Thread.sleep(100);
            } finally {
                for (RawConnection connection : disruptionConnections) {
                    try {
                        connection.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        private static void verifyFastResponse(byte[] body) {
            String text = new String(body, StandardCharsets.UTF_8);
            if (!text.contains("ID: 423")) {
                throw new IllegalStateException(
                    "Fast route returned an unexpected body: " + text);
            }
        }
    }

    private static final class RawConnection implements Closeable {
        private final Socket socket;
        private final DataInputStream input;
        private final OutputStream output;
        private int nextStreamId = 1;
        private int overloadResets;

        private RawConnection(String host, int port, int timeoutMillis,
                              int initialWindow) throws IOException {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(timeoutMillis);
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            input = new DataInputStream(socket.getInputStream());
            output = socket.getOutputStream();

            output.write(Http2Frames.CLIENT_PREFACE);
            if (initialWindow < 0) {
                writeFrame(Http2Frames.SETTINGS, 0, 0, new byte[0]);
            } else {
                byte[] setting = new byte[6];
                putShort(setting, 0,
                    Http2Frames.SETTINGS_INITIAL_WINDOW_SIZE);
                putInt(setting, 2, initialWindow);
                writeFrame(Http2Frames.SETTINGS, 0, 0, setting);
            }
            output.flush();
            negotiateSettings();
        }

        private void negotiateSettings() throws IOException {
            boolean sawSettings = false;
            boolean sawAck = false;
            while (!sawSettings || !sawAck) {
                Frame frame = readFrame();
                if (frame.type() != Http2Frames.SETTINGS) {
                    throw new IOException(
                        "Expected SETTINGS during handshake, received frame type "
                            + frame.type());
                }
                if ((frame.flags() & Http2Frames.FLAG_ACK) != 0) {
                    sawAck = true;
                } else {
                    sawSettings = true;
                    writeFrame(
                        Http2Frames.SETTINGS,
                        Http2Frames.FLAG_ACK,
                        0,
                        new byte[0]
                    );
                    output.flush();
                }
            }
        }

        private int request(String path) throws IOException {
            int streamId = nextStreamId;
            nextStreamId += 2;
            byte[] pathBytes = path.getBytes(StandardCharsets.US_ASCII);
            if (pathBytes.length > 127) {
                throw new IllegalArgumentException(
                    "Probe path is too long for its compact HPACK encoder");
            }
            ByteArrayOutputStream block = new ByteArrayOutputStream(
                pathBytes.length + 4);
            block.write(0x82); // :method: GET
            block.write(0x86); // :scheme: http
            block.write(0x04); // literal without indexing, indexed :path name
            block.write(pathBytes.length);
            block.writeBytes(pathBytes);
            writeFrame(
                Http2Frames.HEADERS,
                Http2Frames.FLAG_END_HEADERS | Http2Frames.FLAG_END_STREAM,
                streamId,
                block.toByteArray()
            );
            return streamId;
        }

        private void cancel(int streamId) throws IOException {
            byte[] payload = new byte[4];
            putInt(payload, 0, Http2Frames.CANCEL);
            writeFrame(Http2Frames.RST_STREAM, 0, streamId, payload);
        }

        private void windowUpdate(int streamId, int increment)
                throws IOException {
            byte[] payload = new byte[4];
            putInt(payload, 0, increment);
            writeFrame(Http2Frames.WINDOW_UPDATE, 0, streamId, payload);
        }

        private void replenishConnectionWindow(int consumed)
                throws IOException {
            if (consumed != 0) {
                windowUpdate(0, consumed);
                flush();
            }
        }

        private void ping(long opaque) throws IOException {
            byte[] payload = new byte[8];
            putLong(payload, 0, opaque);
            writeFrame(Http2Frames.PING, 0, 0, payload);
        }

        private void awaitPing(long opaque, Set<Integer> stalled)
                throws IOException {
            while (true) {
                Frame frame = readFrame();
                observeReset(frame);
                rejectStalledData(frame, stalled);
                if (frame.type() == Http2Frames.GOAWAY) {
                    throw new IOException("Server sent GOAWAY during cancellation");
                }
                if (frame.type() == Http2Frames.PING
                    && (frame.flags() & Http2Frames.FLAG_ACK) != 0
                    && frame.payload().length == Long.BYTES
                    && getLong(frame.payload(), 0) == opaque) {
                    return;
                }
            }
        }

        private byte[] readResponse(int streamId, Set<Integer> stalled)
                throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            boolean sawHeaders = false;
            while (true) {
                Frame frame = readFrame();
                observeReset(frame);
                rejectStalledData(frame, stalled);
                if (frame.type() == Http2Frames.GOAWAY) {
                    throw new IOException("Server sent GOAWAY while awaiting stream "
                        + streamId);
                }
                if (frame.streamId() != streamId) {
                    continue;
                }
                if (frame.type() == Http2Frames.RST_STREAM) {
                    int error = frame.payload().length == Integer.BYTES
                        ? getInt(frame.payload(), 0)
                        : -1;
                    throw new IOException(
                        "Server reset probe stream " + streamId
                            + " with error " + error);
                }
                if (frame.type() == Http2Frames.HEADERS) {
                    sawHeaders = true;
                } else if (frame.type() == Http2Frames.DATA) {
                    body.writeBytes(frame.payload());
                }
                if ((frame.flags() & Http2Frames.FLAG_END_STREAM) != 0) {
                    if (!sawHeaders) {
                        throw new IOException(
                            "Response ended without HEADERS on stream "
                                + streamId);
                    }
                    return body.toByteArray();
                }
            }
        }

        private static void rejectStalledData(
                Frame frame, Set<Integer> stalled) throws IOException {
            if (frame.type() == Http2Frames.DATA
                && stalled.contains(frame.streamId())) {
                throw new IOException(
                    "Server violated flow control on zero-window stream "
                        + frame.streamId());
            }
        }

        private void observeReset(Frame frame) {
            if (frame.type() == Http2Frames.RST_STREAM
                && frame.payload().length == Integer.BYTES
                && getInt(frame.payload(), 0)
                    == Http2Frames.ENHANCE_YOUR_CALM) {
                overloadResets++;
            }
        }

        private int overloadResets() {
            return overloadResets;
        }

        private void writeFrame(int type, int flags, int streamId,
                                byte[] payload) throws IOException {
            byte[] header = new byte[Http2Frames.HEADER_SIZE];
            header[0] = (byte) (payload.length >>> 16);
            header[1] = (byte) (payload.length >>> 8);
            header[2] = (byte) payload.length;
            header[3] = (byte) type;
            header[4] = (byte) flags;
            putInt(header, 5, streamId & Http2Frames.MAX_STREAM_ID);
            output.write(header);
            output.write(payload);
        }

        private Frame readFrame() throws IOException {
            byte[] header = new byte[Http2Frames.HEADER_SIZE];
            input.readFully(header);
            int length = ((header[0] & 0xff) << 16)
                | ((header[1] & 0xff) << 8)
                | (header[2] & 0xff);
            byte[] payload = new byte[length];
            input.readFully(payload);
            return new Frame(
                header[3] & 0xff,
                header[4] & 0xff,
                getInt(header, 5) & Http2Frames.MAX_STREAM_ID,
                payload
            );
        }

        private void flush() throws IOException {
            output.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private record Frame(int type, int flags, int streamId, byte[] payload) {
    }

    private static final class Latencies {
        private final List<Long> samples = new ArrayList<>();

        private void add(long nanos) {
            samples.add(nanos);
        }

        private void print(String label) {
            long[] sorted = samples.stream().mapToLong(Long::longValue).sorted()
                .toArray();
            long total = Arrays.stream(sorted).sum();
            System.out.printf(
                "%s: avg %s; p50 %s; p99 %s; max %s%n",
                label,
                duration(total / sorted.length),
                duration(percentile(sorted, 0.50)),
                duration(percentile(sorted, 0.99)),
                duration(sorted[sorted.length - 1])
            );
        }

        private static long percentile(long[] sorted, double quantile) {
            int index = (int) Math.ceil(quantile * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
        }
    }

    private static final class RssSampler implements AutoCloseable, Runnable {
        private final long pid;
        private final AtomicBoolean running = new AtomicBoolean();
        private final Thread thread;
        private final long baselineKiB;
        private volatile long currentKiB;
        private volatile long peakKiB;

        private RssSampler(long pid) {
            this.pid = pid;
            baselineKiB = readRssKiB(pid);
            currentKiB = baselineKiB;
            peakKiB = baselineKiB;
            if (pid > 0) {
                running.set(true);
                thread = Thread.ofPlatform()
                    .name("cardigan-flow-rss-sampler")
                    .daemon()
                    .start(this);
            } else {
                thread = null;
            }
        }

        @Override
        public void run() {
            while (running.get()) {
                long rss = readRssKiB(pid);
                if (rss > 0) {
                    currentKiB = rss;
                    peakKiB = Math.max(peakKiB, rss);
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }

        private String currentText() {
            return pid > 0 ? memory(currentKiB) : "not sampled";
        }

        private void printSummary() {
            if (pid > 0) {
                System.out.printf(
                    "Server RSS: baseline %s; peak %s; delta %s%n",
                    memory(baselineKiB),
                    memory(peakKiB),
                    memory(Math.max(0, peakKiB - baselineKiB))
                );
            }
        }

        @Override
        public void close() throws InterruptedException {
            if (thread != null) {
                running.set(false);
                thread.interrupt();
                thread.join();
            }
        }

        private static long readRssKiB(long pid) {
            if (pid <= 0) {
                return 0;
            }
            try {
                for (String line : Files.readAllLines(
                        Path.of("/proc", Long.toString(pid), "status"))) {
                    if (line.startsWith("VmRSS:")) {
                        String[] fields = line.trim().split("\\s+");
                        return Long.parseLong(fields[1]);
                    }
                }
            } catch (Exception ignored) {
                // The benchmark process may have exited between samples.
            }
            return 0;
        }
    }

    private static String duration(long nanos) {
        return Duration.ofNanos(nanos).toMillis() + "."
            + String.format("%03d", (nanos / 1_000) % 1_000) + " ms";
    }

    private static String memory(long kibibytes) {
        return String.format("%.1f MiB", kibibytes / 1024.0);
    }

    private static void putShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 8);
        bytes[offset + 1] = (byte) value;
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static int getInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
            | ((bytes[offset + 1] & 0xff) << 16)
            | ((bytes[offset + 2] & 0xff) << 8)
            | (bytes[offset + 3] & 0xff);
    }

    private static void putLong(byte[] bytes, int offset, long value) {
        putInt(bytes, offset, (int) (value >>> 32));
        putInt(bytes, offset + 4, (int) value);
    }

    private static long getLong(byte[] bytes, int offset) {
        return ((long) getInt(bytes, offset) << 32)
            | (getInt(bytes, offset + 4) & 0xffff_ffffL);
    }
}
