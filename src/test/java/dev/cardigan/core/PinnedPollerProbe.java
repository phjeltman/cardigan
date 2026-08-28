// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Fresh-JVM probe for socket continuation progress on a pinned carrier. */
final class PinnedPollerProbe {
    private static final int READ_ROUNDS = 64;

    private PinnedPollerProbe() {
    }

    public static void main(String[] args) throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             UringEventLoop loop = new UringEventLoop(0, 64)) {
            CountDownLatch accepted = new CountDownLatch(1);
            CountDownLatch completed = new CountDownLatch(1);
            CountDownLatch submitted = new CountDownLatch(1);
            AtomicBoolean submissionAccepted = new AtomicBoolean();
            AtomicInteger handlerProcessors = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread server = Thread.ofPlatform().start(() -> {
                try (Socket socket = listener.accept()) {
                    accepted.countDown();
                    for (int round = 0; round < READ_ROUNDS; round++) {
                        Thread.sleep(5);
                        socket.getOutputStream().write(round);
                        socket.getOutputStream().flush();
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });

            loop.execute(() -> {
                submissionAccepted.set(loop.exchangeExecutor().submit(() -> {
                    try {
                        handlerProcessors.set(
                            Runtime.getRuntime().availableProcessors());
                        try (Socket socket = new Socket(
                                "127.0.0.1", listener.getLocalPort())) {
                            InputStream input = socket.getInputStream();
                            for (int round = 0;
                                    round < READ_ROUNDS; round++) {
                                if (input.read() != round) {
                                    throw new AssertionError(
                                        "unexpected byte at round " + round);
                                }
                            }
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    } finally {
                        completed.countDown();
                    }
                }));
                submitted.countDown();
            });
            if (!submitted.await(2, TimeUnit.SECONDS)
                    || !submissionAccepted.get()) {
                throw new AssertionError("exchange task was rejected");
            }

            if (!accepted.await(2, TimeUnit.SECONDS)) {
                if (failure.get() != null) {
                    throw new AssertionError(
                        "client failed before accept", failure.get());
                }
                throw new AssertionError("server did not accept the socket");
            }
            if (!completed.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError(
                    "socket continuation stalled; handler processors="
                        + handlerProcessors.get());
            }
            server.join(2_000);
            if (server.isAlive()) {
                throw new AssertionError("server thread did not terminate");
            }
            if (failure.get() != null) {
                throw new AssertionError("socket probe failed", failure.get());
            }
            System.out.println(
                "socket probe completed; handler processors="
                    + handlerProcessors.get());
        }
    }
}
