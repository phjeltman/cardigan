// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
final class SchedulerProgressIntegrationTest {
    private static final int SOCKET_READ_ROUNDS = 64;

    @Test
    void jdkSocketPollerResumesAnExchangeWorker() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             UringEventLoop loop = new UringEventLoop(0, 64)) {
            CountDownLatch accepted = new CountDownLatch(1);
            CountDownLatch write = new CountDownLatch(1);
            AtomicReference<Throwable> serverFailure =
                new AtomicReference<>();
            Thread server = Thread.ofPlatform().start(() -> {
                try (Socket socket = listener.accept()) {
                    accepted.countDown();
                    if (!write.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("test write was not released");
                    }
                    for (int round = 0;
                            round < SOCKET_READ_ROUNDS; round++) {
                        Thread.sleep(5);
                        socket.getOutputStream().write(round);
                        socket.getOutputStream().flush();
                    }
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });

            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<Throwable> clientFailure =
                new AtomicReference<>();
            CountDownLatch submitted = new CountDownLatch(1);
            AtomicBoolean submissionAccepted = new AtomicBoolean();
            loop.execute(() -> {
                submissionAccepted.set(loop.exchangeExecutor().submit(() -> {
                    try (Socket socket = new Socket(
                            "127.0.0.1", listener.getLocalPort())) {
                        InputStream input = socket.getInputStream();
                        for (int round = 0;
                                round < SOCKET_READ_ROUNDS; round++) {
                            if (input.read() != round) {
                                throw new AssertionError(
                                    "unexpected response byte at round "
                                        + round);
                            }
                        }
                    } catch (Throwable failure) {
                        clientFailure.set(failure);
                    } finally {
                        completed.countDown();
                    }
                }));
                submitted.countDown();
            });

            assertTrue(submitted.await(2, TimeUnit.SECONDS));
            assertTrue(submissionAccepted.get());
            assertTrue(accepted.await(2, TimeUnit.SECONDS));
            write.countDown();
            assertTrue(completed.await(2, TimeUnit.SECONDS),
                "JDK socket-poller continuation did not resume");
            server.join(2_000);
            assertFalse(server.isAlive());
            assertNull(clientFailure.get());
            assertNull(serverFailure.get());
        }
    }

    @Test
    void jdkSocketPollerResumesAnOwnerScheduledVirtualThread()
            throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             UringEventLoop loop = new UringEventLoop(0, 64)) {
            CountDownLatch accepted = new CountDownLatch(1);
            CountDownLatch write = new CountDownLatch(1);
            AtomicReference<Throwable> serverFailure =
                new AtomicReference<>();
            Thread server = Thread.ofPlatform().start(() -> {
                try (Socket socket = listener.accept()) {
                    accepted.countDown();
                    if (!write.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("test write was not released");
                    }
                    socket.getOutputStream().write(42);
                    socket.getOutputStream().flush();
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });

            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<Throwable> clientFailure =
                new AtomicReference<>();
            Thread client = loop.startVirtualThread(() -> {
                try (Socket socket = new Socket(
                        "127.0.0.1", listener.getLocalPort())) {
                    InputStream input = socket.getInputStream();
                    if (input.read() != 42) {
                        throw new AssertionError("unexpected response byte");
                    }
                } catch (Throwable failure) {
                    clientFailure.set(failure);
                } finally {
                    completed.countDown();
                }
            });

            assertTrue(accepted.await(2, TimeUnit.SECONDS));
            write.countDown();
            assertTrue(completed.await(2, TimeUnit.SECONDS),
                "JDK socket-poller continuation did not resume");
            client.join(2_000);
            server.join(2_000);
            assertFalse(client.isAlive());
            assertFalse(server.isAlive());
            assertNull(clientFailure.get());
            assertNull(serverFailure.get());
        }
    }

    @Test
    void yieldingContinuationCannotKeepPendingIoAwayFromTheRing() throws Exception {
        try (UringEventLoop loop = new UringEventLoop(0, 64)) {
            AtomicBoolean stop = new AtomicBoolean();
            CountDownLatch spinnerStarted = new CountDownLatch(1);
            Thread spinner = loop.startVirtualThread(() -> {
                spinnerStarted.countDown();
                while (!stop.get()) {
                    Thread.yield();
                }
            });

            assertTrue(spinnerStarted.await(2, TimeUnit.SECONDS));

            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread ioWaiter = loop.startVirtualThread(() -> {
                try {
                    int result = loop.nop();
                    if (result != 0) {
                        throw new AssertionError(
                            "io_uring NOP completed with " + result);
                    }
                } catch (Throwable thrown) {
                    failure.set(thrown);
                } finally {
                    completed.countDown();
                }
            });

            try {
                assertTrue(completed.await(2, TimeUnit.SECONDS),
                    "a yielding continuation starved io_uring submission or completion");
                assertNull(failure.get());
            } finally {
                stop.set(true);
            }

            spinner.join(2_000);
            ioWaiter.join(2_000);
            assertFalse(spinner.isAlive());
            assertFalse(ioWaiter.isAlive());
        }
    }

    @Test
    void ownerDomainSchedulingStaysOffTheExternalQueue() throws Exception {
        try (UringEventLoop loop = new UringEventLoop(0, 64)) {
            CountDownLatch scheduled = new CountDownLatch(1);
            CountDownLatch ran = new CountDownLatch(1);

            Thread ownerVirtual = loop.startVirtualThread(() -> {
                loop.execute(ran::countDown);
                scheduled.countDown();
            });

            assertTrue(scheduled.await(2, TimeUnit.SECONDS));
            assertTrue(ran.await(2, TimeUnit.SECONDS));
            ownerVirtual.join(2_000);

            UringEventLoop.SchedulerStats stats = loop.schedulerStats();
            assertTrue(stats.protocolTasks() > 0,
                "mounted virtual-thread work did not use the owner lane");
            // Starting ownerVirtual from the JUnit thread accounts for one
            // external scheduler submission; the nested execute must not add
            // a second one.
            assertTrue(stats.externalTasks() <= 1,
                "owner-domain scheduling bounced through the external queue");
        }
    }
}
