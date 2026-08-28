// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.ffi.ThreadAffinity;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Makes JDK-managed virtual-thread socket I/O ready before an event-loop
 * carrier restricts its CPU affinity.
 */
final class JdkSocketPollerBootstrap {
    private static final int PROBE_ROUNDS = 4;
    private static final int IO_TIMEOUT_MILLIS = 3_000;
    private static final int STARTUP_TIMEOUT_MILLIS = 5_000;
    private static boolean initialized;

    private JdkSocketPollerBootstrap() {
    }

    static synchronized void initialize() {
        if (initialized) {
            return;
        }
        ThreadAffinity.initialize();
        try {
            verifySocketProgress();
            initialized = true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Interrupted while initializing JDK socket polling", error);
        } catch (Exception error) {
            throw new IllegalStateException(
                "JDK virtual-thread socket polling did not become ready "
                    + "before Cardigan event-loop startup",
                error);
        }
    }

    private static void verifySocketProgress() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Thread> clientThread = new AtomicReference<>();

        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress(loopback, 0), 1);
            listener.setSoTimeout(IO_TIMEOUT_MILLIS);

            // Delayed reads exercise readiness delivery and re-arming before
            // handlers can introduce JDK-managed sockets on a pinned carrier.
            Thread coordinator = Thread.ofPlatform()
                .daemon(true)
                .name("cardigan-socket-poller-bootstrap")
                .unstarted(() -> runProbe(
                    listener, loopback, clientThread, failure));
            coordinator.start();
            coordinator.join(STARTUP_TIMEOUT_MILLIS);
            if (coordinator.isAlive()) {
                listener.close();
                Thread client = clientThread.get();
                if (client != null) {
                    client.interrupt();
                }
                coordinator.interrupt();
                coordinator.join(1_000);
                throw new IllegalStateException(
                    "Timed out waiting for JDK socket-poller progress");
            }
        }

        Throwable error = failure.get();
        if (error != null) {
            if (error instanceof Exception exception) {
                throw exception;
            }
            if (error instanceof Error fatal) {
                throw fatal;
            }
            throw new IllegalStateException(error);
        }
    }

    private static void runProbe(
            ServerSocket listener,
            InetAddress loopback,
            AtomicReference<Thread> clientThread,
            AtomicReference<Throwable> failure) {
        try {
            int affinityResult = ThreadAffinity.restoreProcessAffinity();
            if (affinityResult != 0) {
                throw new IllegalStateException(
                    "Failed to restore process CPU affinity for JDK socket "
                        + "poller initialization: error " + affinityResult);
            }

            int port = listener.getLocalPort();
            Thread client = Thread.ofVirtual()
                .name("cardigan-socket-poller-probe")
                .unstarted(() -> runClient(
                    loopback, port, failure));
            clientThread.set(client);
            client.start();

            try (Socket peer = listener.accept()) {
                OutputStream output = peer.getOutputStream();
                for (int round = 0; round < PROBE_ROUNDS; round++) {
                    Thread.sleep(10);
                    output.write(round);
                    output.flush();
                }
            }

            client.join(IO_TIMEOUT_MILLIS);
            if (client.isAlive()) {
                client.interrupt();
                throw new IllegalStateException(
                    "JDK socket-poller probe did not complete");
            }
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
        }
    }

    private static void runClient(
            InetAddress loopback,
            int port,
            AtomicReference<Throwable> failure) {
        try (Socket socket = new Socket()) {
            socket.connect(
                new InetSocketAddress(loopback, port), IO_TIMEOUT_MILLIS);
            socket.setSoTimeout(IO_TIMEOUT_MILLIS);
            InputStream input = socket.getInputStream();
            for (int round = 0; round < PROBE_ROUNDS; round++) {
                int value = input.read();
                if (value != round) {
                    throw new IllegalStateException(
                        "Unexpected socket-poller probe byte: " + value);
                }
            }
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
        }
    }
}
