// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.ffi.Libc;
import dev.cardigan.ffi.ThreadAffinity;
import java.lang.foreign.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import dev.cardigan.http.HttpRequest;
import dev.cardigan.pico.PicoHTTPParser;
import dev.cardigan.pico.Header;
import dev.cardigan.pico.Request;
import dev.cardigan.http.Router;
import dev.cardigan.http.Response;
import dev.cardigan.http.ResponseHeaders;
import dev.cardigan.http.EncodedBody;
import dev.cardigan.http.StaticBody;
import dev.cardigan.http.StreamingBody;
import dev.cardigan.http.PreparedInvocation;
import dev.cardigan.http2.Http2Frames;
import dev.cardigan.json.JsonReader;
import dev.cardigan.json.JsonWriter;
import dev.cardigan.ffi.RawSegment;
import dev.cardigan.util.SimpleWaiter;
import dev.cardigan.tls.TlsConfig;
import dev.cardigan.tls.TlsConnection;
import dev.cardigan.tls.TlsContext;
import dev.cardigan.tls.TlsStats;
import dev.cardigan.tls.TlsHandshakeStats;
import dev.cardigan.tls.TlsCapabilities;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

public class CardiganServer implements AutoCloseable, KtlsMultishotReceiver.Observer {

    private final int port;
    private final int cores;
    private final int[] eventLoopCpus;
    private final boolean http2Only;
    private final boolean http1Only;
    private final boolean directKtlsReceiveConfigured;
    private final TlsContext tlsContext;
    private final FixedFilesMode fixedFilesMode;
    private final boolean directAccept;
    private final EgressBufferPool egressBufferPool;
    private final List<UringEventLoop> eventLoops = new ArrayList<>();
    private final List<Integer> serverFds = new ArrayList<>();
    private final List<AcceptHandler> acceptHandlers = new ArrayList<>();
    private final Object listenerLock = new Object();
    private final Router router = new Router();
    private final java.util.Set<ConnectionControl> activeConnections =
        ConcurrentHashMap.newKeySet();
    private final AtomicInteger activeConnectionCount = new AtomicInteger();
    private final long gracefulShutdownMillis;
    private final long forcedShutdownMillis;
    private volatile boolean running = false;
    private volatile int lifecycle;
    private volatile Thread drainWaiter;
    private volatile int forcedConnectionCount;

    private static final int LIFECYCLE_NEW = 0;
    private static final int LIFECYCLE_RUNNING = 1;
    private static final int LIFECYCLE_DRAINING = 2;
    private static final int LIFECYCLE_CLOSED = 3;
    private static final int SHUT_RD = 0;
    private static final int SHUT_RDWR = 2;

    private enum FixedFilesMode {
        AUTO,
        LEGACY,
        ASYNC_EXPLICIT,
        ASYNC_ALLOC,
        DIRECT;

        private static FixedFilesMode configured() {
            String value = System.getProperty(
                "cardigan.fixed.files.mode", "auto");
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "auto" -> AUTO;
                case "legacy" -> LEGACY;
                case "async-explicit" -> ASYNC_EXPLICIT;
                case "async-alloc" -> ASYNC_ALLOC;
                case "direct" -> DIRECT;
                default -> throw new IllegalArgumentException(
                    "cardigan.fixed.files.mode must be one of "
                        + "auto, legacy, async-explicit, async-alloc, direct");
            };
        }
    }

    public static final long MAX_REQUEST_SIZE = Long.getLong("cardigan.max.request.size", 10 * 1024 * 1024L);
    public static final int MAX_HEADER_SIZE = Integer.getInteger("cardigan.max.header.size", 8192);
    public static final int MAX_HTTP1_IN_FLIGHT = Integer.getInteger("cardigan.http1.max.inflight", 128);

    private static final long HTTP1_FRAMING_CHUNKED = 1L << 60;
    private static final long HTTP1_EXPECT_CONTINUE = 1L << 61;
    private static final long HTTP1_EXPECT_UNSUPPORTED = 1L << 62;
    private static final long HTTP1_FRAMING_LENGTH_MASK =
        HTTP1_FRAMING_CHUNKED - 1;
    private static final long HTTP1_FRAMING_INVALID = -2;
    private static final long HTTP1_FRAMING_TOO_LARGE = -3;

    private static MemorySegment createStaticSegment(String asciiStr) {
        byte[] bytes = asciiStr.getBytes(StandardCharsets.US_ASCII);
        MemorySegment seg = Arena.global().allocate(bytes.length);
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, seg, 0, bytes.length);
        return seg;
    }

    private static final MemorySegment SEG_STATUS_200 = createStaticSegment("HTTP/1.1 200 OK\r\n");
    private static final MemorySegment SEG_STATUS_400 = createStaticSegment("HTTP/1.1 400 Bad Request\r\n");
    private static final MemorySegment SEG_STATUS_404 = createStaticSegment("HTTP/1.1 404 Not Found\r\n");
    private static final MemorySegment SEG_STATUS_413 = createStaticSegment("HTTP/1.1 413 Payload Too Large\r\n");
    private static final MemorySegment SEG_STATUS_417 = createStaticSegment("HTTP/1.1 417 Expectation Failed\r\n");
    private static final MemorySegment SEG_STATUS_431 = createStaticSegment("HTTP/1.1 431 Request Header Fields Too Large\r\n");
    private static final MemorySegment SEG_STATUS_500 = createStaticSegment("HTTP/1.1 500 Internal Server Error\r\n");

    private static final MemorySegment SEG_CONTENT_TYPE = createStaticSegment("Content-Type: ");
    private static final MemorySegment SEG_CONTENT_LENGTH = createStaticSegment("Content-Length: ");
    private static final MemorySegment SEG_TRANSFER_CHUNKED =
        createStaticSegment("Transfer-Encoding: chunked\r\n");
    private static final MemorySegment SEG_CRLF = createStaticSegment("\r\n");
    private static final MemorySegment SEG_FINAL_CHUNK =
        createStaticSegment("0\r\n\r\n");
    private static final MemorySegment SEG_100_CONTINUE =
        createStaticSegment("HTTP/1.1 100 Continue\r\n\r\n");
    private static final MemorySegment SEG_CT_JSON = createStaticSegment("application/json\r\n");
    private static final MemorySegment SEG_CT_TEXT = createStaticSegment("text/plain\r\n");

    private static final MemorySegment SEG_CONN_KA = createStaticSegment("Connection: keep-alive\r\n\r\n");
    private static final MemorySegment SEG_CONN_CLOSE = createStaticSegment("Connection: close\r\n\r\n");

    private static final MemorySegment SEG_HDR_200_TEXT = createStaticSegment("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ");
    private static final MemorySegment SEG_HDR_200_JSON = createStaticSegment("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ");
    private static final MemorySegment SEG_POST_LENGTH_DEFAULT = createStaticSegment("\r\n\r\n");
    private static final MemorySegment SEG_POST_LENGTH_KA = createStaticSegment("\r\nConnection: keep-alive\r\n\r\n");

    private static final long ADDR_STATUS_200 = SEG_STATUS_200.address();
    private static final int LEN_STATUS_200 = (int) SEG_STATUS_200.byteSize();
    private static final long ADDR_STATUS_400 = SEG_STATUS_400.address();
    private static final int LEN_STATUS_400 = (int) SEG_STATUS_400.byteSize();
    private static final long ADDR_STATUS_404 = SEG_STATUS_404.address();
    private static final int LEN_STATUS_404 = (int) SEG_STATUS_404.byteSize();
    private static final long ADDR_STATUS_413 = SEG_STATUS_413.address();
    private static final int LEN_STATUS_413 = (int) SEG_STATUS_413.byteSize();
    private static final long ADDR_STATUS_417 = SEG_STATUS_417.address();
    private static final int LEN_STATUS_417 = (int) SEG_STATUS_417.byteSize();
    private static final long ADDR_STATUS_431 = SEG_STATUS_431.address();
    private static final int LEN_STATUS_431 = (int) SEG_STATUS_431.byteSize();
    private static final long ADDR_STATUS_500 = SEG_STATUS_500.address();
    private static final int LEN_STATUS_500 = (int) SEG_STATUS_500.byteSize();

    private static final long ADDR_CONTENT_TYPE = SEG_CONTENT_TYPE.address();
    private static final int LEN_CONTENT_TYPE = (int) SEG_CONTENT_TYPE.byteSize();
    private static final long ADDR_CONTENT_LENGTH = SEG_CONTENT_LENGTH.address();
    private static final int LEN_CONTENT_LENGTH = (int) SEG_CONTENT_LENGTH.byteSize();
    private static final long ADDR_TRANSFER_CHUNKED =
        SEG_TRANSFER_CHUNKED.address();
    private static final int LEN_TRANSFER_CHUNKED =
        (int) SEG_TRANSFER_CHUNKED.byteSize();
    private static final long ADDR_FINAL_CHUNK = SEG_FINAL_CHUNK.address();
    private static final int LEN_FINAL_CHUNK =
        (int) SEG_FINAL_CHUNK.byteSize();
    private static final long ADDR_CRLF = SEG_CRLF.address();
    private static final int LEN_CRLF = (int) SEG_CRLF.byteSize();
    private static final long ADDR_CT_JSON = SEG_CT_JSON.address();
    private static final int LEN_CT_JSON = (int) SEG_CT_JSON.byteSize();
    private static final long ADDR_CT_TEXT = SEG_CT_TEXT.address();
    private static final int LEN_CT_TEXT = (int) SEG_CT_TEXT.byteSize();

    private static final long ADDR_CONN_KA = SEG_CONN_KA.address();
    private static final int LEN_CONN_KA = (int) SEG_CONN_KA.byteSize();
    private static final long ADDR_CONN_CLOSE = SEG_CONN_CLOSE.address();
    private static final int LEN_CONN_CLOSE = (int) SEG_CONN_CLOSE.byteSize();

    private static final long ADDR_HDR_200_TEXT = SEG_HDR_200_TEXT.address();
    private static final int LEN_HDR_200_TEXT = (int) SEG_HDR_200_TEXT.byteSize();
    private static final long ADDR_HDR_200_JSON = SEG_HDR_200_JSON.address();
    private static final int LEN_HDR_200_JSON = (int) SEG_HDR_200_JSON.byteSize();
    private static final long ADDR_POST_LENGTH_DEFAULT =
        SEG_POST_LENGTH_DEFAULT.address();
    private static final int LEN_POST_LENGTH_DEFAULT =
        (int) SEG_POST_LENGTH_DEFAULT.byteSize();
    private static final long ADDR_POST_LENGTH_KA = SEG_POST_LENGTH_KA.address();
    private static final int LEN_POST_LENGTH_KA = (int) SEG_POST_LENGTH_KA.byteSize();

    public CardiganServer(int port) {
        this(port, Runtime.getRuntime().availableProcessors());
    }

    public CardiganServer(int port, int threadCount) {
        this(port, threadCount, TlsConfig.fromSystemProperties());
    }

    public CardiganServer(int port, int threadCount, TlsConfig tlsConfig) {
        this(
            port, threadCount, null, tlsConfig,
            ProtocolMode.HTTP1_AND_HTTP2);
    }

    private CardiganServer(
            int port,
            int threadCount,
            String configuredEventLoopCpus,
            TlsConfig tlsConfig,
            ProtocolMode protocolMode) {
        ThreadAffinity.initialize();
        this.port = port;
        String cpuList = configuredEventLoopCpus;
        if (cpuList == null || cpuList.isBlank()) {
            cpuList = System.getProperty("cardigan.eventloop.cpus", "");
        }
        this.eventLoopCpus = cpuList.isBlank()
            ? ThreadAffinity.processCpus(threadCount)
            : ThreadAffinity.processCpus(cpuList);
        this.cores = eventLoopCpus.length;
        int maxEgressBuffers =
            EgressBufferPool.configuredMaxBuffers(cores);
        Objects.requireNonNull(protocolMode, "protocolMode");
        this.http2Only = protocolMode == ProtocolMode.HTTP2_ONLY;
        this.http1Only = protocolMode == ProtocolMode.HTTP1_ONLY;
        this.directKtlsReceiveConfigured =
            tlsConfig != null && tlsConfig.directKtlsReceive();
        this.fixedFilesMode = FixedFilesMode.configured();
        if (fixedFilesMode == FixedFilesMode.DIRECT && tlsConfig != null) {
            throw new IllegalArgumentException(
                "Direct fixed-file accept cannot be used with OpenSSL TLS; "
                    + "use auto or async-alloc");
        }
        this.directAccept = fixedFilesMode == FixedFilesMode.DIRECT
            || (fixedFilesMode == FixedFilesMode.AUTO && tlsConfig == null);
        this.tlsContext = tlsConfig == null
            ? null
            : new TlsContext(tlsConfig, http2Only, http1Only);
        this.egressBufferPool = new EgressBufferPool(
            maxEgressBuffers, UringEventLoop.EGRESS_FRAME_SIZE);
        this.gracefulShutdownMillis = Math.max(
            0L,
            Long.getLong("cardigan.shutdown.grace.millis", 30_000L));
        this.forcedShutdownMillis = Math.max(
            0L,
            Long.getLong("cardigan.shutdown.force.millis", 2_000L));
    }

    /** Starts explicit, route-neutral server configuration. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int port = 8080;
        private int eventLoops = Runtime.getRuntime().availableProcessors();
        private String eventLoopCpus;
        private TlsConfig tlsConfig;
        private ProtocolMode protocolMode = ProtocolMode.HTTP1_AND_HTTP2;
        private final List<Object> controllers = new ArrayList<>();

        private Builder() {
        }

        public Builder port(int port) {
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException(
                    "port must be between 1 and 65535");
            }
            this.port = port;
            return this;
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

        public Builder protocol(ProtocolMode protocolMode) {
            this.protocolMode = Objects.requireNonNull(
                protocolMode, "protocolMode");
            return this;
        }

        public Builder tls(TlsConfig tlsConfig) {
            this.tlsConfig = Objects.requireNonNull(
                tlsConfig, "tlsConfig");
            return this;
        }

        public Builder tlsFromSystemProperties() {
            this.tlsConfig = TlsConfig.fromSystemProperties();
            return this;
        }

        public Builder plaintext() {
            this.tlsConfig = null;
            return this;
        }

        public Builder routes(Object... controllers) {
            Objects.requireNonNull(controllers, "controllers");
            for (Object controller : controllers) {
                this.controllers.add(
                    Objects.requireNonNull(controller, "controller"));
            }
            return this;
        }

        public CardiganServer build() {
            CardiganServer server = new CardiganServer(
                port, eventLoops, eventLoopCpus, tlsConfig, protocolMode);
            for (Object controller : controllers) {
                server.registerController(controller);
            }
            return server;
        }
    }

    /** Registers application routes before the listener is started. */
    public synchronized CardiganServer registerController(Object controller) {
        if (lifecycle != LIFECYCLE_NEW) {
            throw new IllegalStateException(
                "Controllers must be registered before CardiganServer.start()");
        }
        router.registerController(Objects.requireNonNull(controller));
        return this;
    }

    public synchronized void start() {
        if (lifecycle == LIFECYCLE_RUNNING) return;
        if (lifecycle != LIFECYCLE_NEW) {
            throw new IllegalStateException("CardiganServer cannot be restarted");
        }
        lifecycle = LIFECYCLE_RUNNING;
        running = true;

        System.out.println(
            "Starting Cardigan Server on port " + port + " with " + cores
                + " io_uring event loops on CPUs "
                + Arrays.toString(eventLoopCpus) + ", transport="
                + (tlsContext == null
                    ? "plaintext"
                    : "TLS/OpenSSL")
                + ", fixed-files=" + effectiveFixedFilesMode() + "...");

        try {
            int ingressBuffersPerLoop =
                configuredIngressBuffersPerLoop();
            for (int i = 0; i < cores; i++) {
                int cpuId = eventLoopCpus[i];
                eventLoops.add(new UringEventLoop(
                    cpuId,
                    512,
                    ingressBuffersPerLoop,
                    directKtlsReceiveConfigured,
                    egressBufferPool));
            }

            CountDownLatch listenersReady = new CountDownLatch(cores);
            AtomicReference<Throwable> startupFailure =
                new AtomicReference<>();
            for (int i = 0; i < cores; i++) {
                UringEventLoop loop = eventLoops.get(i);
                int cpuId = eventLoopCpus[i];
                try {
                    loop.execute(() -> {
                        try {
                            initializeListener(loop, cpuId);
                        } catch (Throwable failure) {
                            startupFailure.compareAndSet(null, failure);
                        } finally {
                            listenersReady.countDown();
                        }
                    });
                } catch (Throwable failure) {
                    startupFailure.compareAndSet(null, failure);
                    listenersReady.countDown();
                }
            }

            awaitStartup(listenersReady);
            Throwable failure = startupFailure.get();
            if (failure != null) {
                throw failure;
            }
        } catch (Throwable failure) {
            close();
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                "Cardigan startup failed", failure);
        }
    }

    static int configuredIngressBuffersPerLoop() {
        int configured = Integer.getInteger(
            "cardigan.ingress.buffers.per.loop", 128);
        if (configured <= 0 || configured > 32_768
                || Integer.bitCount(configured) != 1) {
            throw new IllegalArgumentException(
                "cardigan.ingress.buffers.per.loop must be a power of two "
                    + "between 1 and 32768");
        }
        return configured;
    }

    private String effectiveFixedFilesMode() {
        if (directAccept) {
            return "direct";
        }
        return switch (fixedFilesMode) {
            case AUTO, ASYNC_ALLOC -> "async-alloc";
            case ASYNC_EXPLICIT -> "async-explicit";
            case LEGACY -> "legacy";
            case DIRECT -> "direct";
        };
    }

    private static void awaitStartup(CountDownLatch listenersReady) {
        boolean interrupted = false;
        while (listenersReady.getCount() != 0) {
            try {
                listenersReady.await();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Interrupted while waiting for Cardigan listeners");
        }
    }

    private volatile boolean multishotAcceptObserved;
    private volatile boolean multishotReceiveObserved;
    private volatile boolean receiveFallbackUsed;
    private volatile boolean tlsCloseNotifyObserved;
    private volatile boolean tlsDirectReceiveFailed;
    private final AtomicInteger multishotReceiveCompletions = new AtomicInteger();
    private final AtomicInteger receiveBackpressureEvents = new AtomicInteger();
    private final AtomicBoolean tlsCapabilityReported = new AtomicBoolean();
    private final AtomicBoolean requiredReceiveFailureReported =
        new AtomicBoolean();
    private volatile TlsCapabilities tlsCapabilities;

    boolean isMultishotAcceptObserved() {
        return multishotAcceptObserved;
    }

    public TlsHandshakeStats tlsHandshakeStats() {
        return tlsContext == null ? null : tlsContext.handshakeStats();
    }

    public TlsCapabilities tlsCapabilities() {
        return tlsCapabilities;
    }

    boolean isMultishotReceiveObserved() {
        return multishotReceiveObserved;
    }

    boolean isReceiveFallbackUsed() {
        return receiveFallbackUsed;
    }

    boolean isTlsCloseNotifyObserved() {
        return tlsCloseNotifyObserved;
    }

    boolean isTlsDirectReceiveFailed() {
        return tlsDirectReceiveFailed;
    }

    int multishotReceiveCompletionCount() {
        return multishotReceiveCompletions.get();
    }

    int receiveBackpressureEventCount() {
        return receiveBackpressureEvents.get();
    }

    int exchangeWorkerCount() {
        int count = 0;
        for (UringEventLoop loop : eventLoops) {
            count += loop.exchangeWorkerCount();
        }
        return count;
    }

    int http2ParkedSenderCount() {
        int count = 0;
        for (UringEventLoop loop : eventLoops) {
            count += loop.http2ParkedSenderCount();
        }
        return count;
    }

    int activeConnectionCount() {
        return activeConnectionCount.get();
    }

    EgressBufferPool.Stats egressBufferPoolStats() {
        return egressBufferPool.stats();
    }

    boolean isDraining() {
        return lifecycle == LIFECYCLE_DRAINING;
    }

    int forcedConnectionCount() {
        return forcedConnectionCount;
    }

    @Override
    public void multishotCompletionObserved() {
        multishotReceiveObserved = true;
        multishotReceiveCompletions.incrementAndGet();
    }

    @Override
    public void fallbackActivated() {
        receiveFallbackUsed = true;
    }

    @Override
    public void backpressureActivated() {
        receiveBackpressureEvents.incrementAndGet();
    }

    @Override
    public void requiredFeatureRejected(String feature, int result) {
        if (requiredReceiveFailureReported.compareAndSet(false, true)) {
            System.err.println(
                "Required io_uring feature " + feature
                    + " was rejected with error " + result
                    + "; Cardigan requires Linux 6.1 or newer");
        }
    }

    @Override
    public void tlsCloseNotifyObserved() {
        tlsCloseNotifyObserved = true;
    }

    @Override
    public void tlsDirectReceiveFailed() {
        tlsDirectReceiveFailed = true;
    }

    private void initializeListener(UringEventLoop loop, int cpuId) {
        int serverFd = -1;
        boolean listenerOwnedByServer = false;
        AcceptHandler handler = null;
        try {
            serverFd = (int) Libc.socket.invokeExact(2, 1, 0);
            if (serverFd < 0) {
                throw systemCallFailure("socket");
            }

            setSocketOptions(serverFd, cpuId);

            try (Arena localArena = Arena.ofConfined()) {
                MemorySegment addr = createSockAddrIn(localArena, port);
                int ret = (int) Libc.bind.invokeExact(serverFd, addr, 16);
                if (ret < 0) {
                    throw systemCallFailure("bind");
                }
            }

            int ret = (int) Libc.listen.invokeExact(serverFd, 4096);
            if (ret < 0) {
                throw systemCallFailure("listen");
            }

            handler = new AcceptHandler(loop, serverFd, directAccept);
            if (!handler.arm()) {
                throw new IllegalStateException(
                    "Unable to arm required multishot accept on CPU "
                        + cpuId);
            }
            loop.submitPendingOperations();
            synchronized (listenerLock) {
                if (!running) {
                    closeSocket(serverFd);
                    throw new IllegalStateException(
                        "Server stopped during listener initialization");
                }
                serverFds.add(serverFd);
                acceptHandlers.add(handler);
                listenerOwnedByServer = true;
            }

            System.out.println("Loop " + cpuId + " listening on socket FD " + serverFd);
        } catch (Throwable t) {
            if (listenerOwnedByServer) {
                synchronized (listenerLock) {
                    serverFds.remove(Integer.valueOf(serverFd));
                    acceptHandlers.remove(handler);
                }
            }
            closeSocket(serverFd);
            throw new IllegalStateException(
                "Listener initialization failed on CPU " + cpuId
                    + ": " + t.getMessage(),
                t);
        }
    }

    private final class AcceptHandler implements UringEventLoop.CompletionHandler {
        private final UringEventLoop loop;
        private final int serverFd;
        private final boolean direct;
        private final CountDownLatch stopped = new CountDownLatch(1);
        private volatile long acceptToken = -1;

        private AcceptHandler(
                UringEventLoop loop, int serverFd, boolean direct) {
            this.loop = loop;
            this.serverFd = serverFd;
            this.direct = direct;
        }

        @Override
        public void onCompletion(int result, int flags, boolean terminal) {
            if (result >= 0) {
                if (!terminal) {
                    multishotAcceptObserved = true;
                }
                ConnectionSocket socket;
                if (direct) {
                    loop.fixedFileAccepted(result);
                    socket = ConnectionSocket.direct(result);
                } else {
                    socket = ConnectionSocket.raw(result);
                }
                if (running) {
                    startConnection(loop, socket);
                } else {
                    closeAcceptedSocket(socket);
                }
            }

            if (!terminal) {
                return;
            }
            acceptToken = -1;
            if (!running) {
                stopped.countDown();
                return;
            }

            if (direct && result == -23) {
                loop.whenFixedFileAvailable(this::resumeAfterCapacity);
            } else if (result == -22) {
                System.err.println(
                    "Required io_uring multishot accept was rejected; "
                        + "Cardigan requires Linux 6.1 or newer");
                stopped.countDown();
            } else if (!arm()) {
                System.err.println(
                    "Unable to rearm required io_uring multishot accept");
                stopped.countDown();
            }
        }

        private boolean arm() {
            long token = direct
                ? loop.acceptMultishotDirect(serverFd, this)
                : loop.acceptMultishot(serverFd, this);
            acceptToken = token;
            return token >= 0;
        }

        private void resumeAfterCapacity() {
            if (!running) {
                stopped.countDown();
            } else if (!arm()) {
                System.err.println(
                    "Unable to rearm direct multishot accept after "
                        + "fixed-file capacity became available");
                stopped.countDown();
            }
        }

        private void closeAcceptedSocket(ConnectionSocket socket) {
            if (!socket.direct()) {
                closeSocket(socket.rawFd());
                return;
            }
            if (!loop.closeDirectAsync(
                    socket.fixedSlot(), (result, flags, terminal) -> {
                    })) {
                try {
                    loop.startVirtualThread(
                        () -> loop.closeDirect(socket.fixedSlot()));
                } catch (Throwable failure) {
                    System.err.println(
                        "Unable to close direct accepted slot "
                            + socket.fixedSlot());
                }
            }
        }

        private void beginStop() {
            CountDownLatch submitted = new CountDownLatch(1);
            try {
                loop.execute(() -> {
                    try {
                        if (acceptToken < 0) {
                            stopped.countDown();
                        } else if (acceptToken >= 0) {
                            loop.cancelAsync(
                                acceptToken,
                                (result, flags, terminal) -> {
                                });
                        }
                    } finally {
                        submitted.countDown();
                    }
                });
                submitted.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable ignored) {
                stopped.countDown();
            }
        }

        private void awaitStopped() {
            try {
                stopped.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void startConnection(
            UringEventLoop loop, ConnectionSocket socket) {
        ConnectionControl control = new ConnectionControl(loop, socket);
        activeConnectionCount.incrementAndGet();
        activeConnections.add(control);
        try {
            Thread owner = loop.startVirtualThread(
                () -> handleConnection(control));
            control.owner = owner;
        } catch (Throwable failure) {
            connectionClosed(control);
            if (socket.direct()) {
                if (!loop.closeDirectAsync(
                        socket.fixedSlot(), (result, flags, terminal) -> {
                        })) {
                    loop.startVirtualThread(
                        () -> loop.closeDirect(socket.fixedSlot()));
                }
            } else {
                closeSocket(socket.rawFd());
            }
            throw failure;
        }
        if (lifecycle != LIFECYCLE_RUNNING) {
            control.requestDrain();
        }
    }

    private record ConnectionSocket(
        int rawFd,
        int fixedSlot,
        boolean direct
    ) {
        private static ConnectionSocket raw(int fd) {
            return new ConnectionSocket(fd, -1, false);
        }

        private static ConnectionSocket direct(int slot) {
            return new ConnectionSocket(-1, slot, true);
        }
    }

    private static void closeSocket(int fd) {
        if (fd < 0) {
            return;
        }
        try {
            int unused = (int) Libc.close.invokeExact(fd);
        } catch (Throwable ignored) {
        }
    }

    private void handleConnection(ConnectionControl control) {
        UringEventLoop loop = control.loop;
        int clientFd = control.socket.rawFd();
        int fixedSlot = control.socket.fixedSlot();
        TlsConnection tls = null;
        ConnectionWriter writer = null;
        InboundReceiver receiver = null;
        InboundChunkStream inbound = null;
        InboundChunk currentChunk = null;
        MemorySegment currentBuf = null;
        int readOffset = 0;
        try {
            if (!control.socket.direct()) {
                fixedSlot = switch (fixedFilesMode) {
                    case LEGACY -> loop.registerFixedFdLegacy(clientFd);
                    case ASYNC_EXPLICIT ->
                        loop.registerFixedFdAsyncExplicit(clientFd);
                    case AUTO, ASYNC_ALLOC -> loop.registerFixedFd(clientFd);
                    case DIRECT -> throw new IllegalStateException(
                        "Direct accept produced a raw descriptor");
                };
                if (fixedSlot < 0) {
                    // The fixed-file table defines connection admission;
                    // exhaustion rejects the connection and preserves
                    // fixed-descriptor I/O.
                    return;
                }
            }
            control.fixedSlot = fixedSlot;
            if (tlsContext != null) {
                tls = tlsContext.accept(loop, clientFd, fixedSlot);
                if (tls == null) {
                    return;
                }
                control.tls = tls;
                reportTlsCapabilities(tls);
            }
            boolean directKtlsReceive =
                tls != null && tls.directKtlsReceive();
            if (tls == null) {
                receiver = new MultishotReceiver(
                    loop, clientFd, fixedSlot, this);
            } else if (directKtlsReceive) {
                receiver = new KtlsMultishotReceiver(
                    loop, clientFd, fixedSlot, tls, this);
            } else {
                receiver = new TlsReceiver(tls);
            }
            if (directKtlsReceive && TlsStats.ENABLED) {
                TlsStats.directReceiveConnection();
            }
            inbound = new InboundChunkStream(loop, receiver);
            receiver.start();
            currentChunk = inbound.nextChunk();
            if (currentChunk == null) {
                return;
            }
            currentBuf = currentChunk.segment();
            readOffset = currentChunk.length();

            boolean possibleHttp2 = !http1Only
                && (tls == null
                    || tls.selectedProtocol() == TlsConnection.HTTP2);
            int matchedPrefaceBytes = 0;
            while (possibleHttp2) {
                int compared = Math.min(readOffset, Http2Frames.CLIENT_PREFACE.length);
                for (int i = matchedPrefaceBytes; i < compared; i++) {
                    if (currentBuf.get(ValueLayout.JAVA_BYTE, i)
                        != Http2Frames.CLIENT_PREFACE[i]) {
                        possibleHttp2 = false;
                        break;
                    }
                    matchedPrefaceBytes = i + 1;
                }
                if (!possibleHttp2 || readOffset >= Http2Frames.CLIENT_PREFACE.length) {
                    break;
                }
                int appended = inbound.appendOnce(
                    currentChunk, readOffset, Http2Frames.CLIENT_PREFACE.length);
                if (appended <= readOffset) {
                    break;
                }
                readOffset = appended;
            }

            if (possibleHttp2 && readOffset >= Http2Frames.CLIENT_PREFACE.length) {
                writer = ConnectionWriter.forHttp2(
                    loop, clientFd, fixedSlot, tls);
                Http2Connection http2 = new Http2Connection(
                    inbound,
                    writer,
                    currentChunk,
                    Http2Frames.CLIENT_PREFACE.length,
                    router,
                    loop.exchangeExecutor()
                );
                control.http2 = http2;
                control.protocol = ConnectionControl.HTTP2;
                if (control.draining) {
                    control.requestHttp2Drain(http2);
                }
                currentChunk = null;
                currentBuf = null;
                http2.run();
                return;
            }

            writer = new ConnectionWriter(loop, clientFd, fixedSlot, tls);

            // The HTTP/2 prior-knowledge preface begins with a deliberately
            // distinctive HTTP/1-looking request line. Once that line has
            // matched, a later mismatch is an invalid HTTP/2 preface and must
            // receive GOAWAY rather than an HTTP/1 error response.
            if (!possibleHttp2 && (http2Only || matchedPrefaceBytes >= 16)) {
                sendInvalidHttp2Preface(writer);
                return;
            }

            InboundChunk http1Chunk = currentChunk;
            control.protocol = ConnectionControl.HTTP1;
            currentChunk = null;
            currentBuf = null;
            handleHttp1(
                loop, writer, inbound, http1Chunk, readOffset, control);
        } catch (Throwable t) {
            if (!control.draining) {
                t.printStackTrace();
            }
        } finally {
            try {
                if (currentChunk != null) {
                    try {
                        currentChunk.close();
                    } catch (Throwable t) {
                        // Continue teardown so the stream and receiver release
                        // their resources.
                    }
                    currentChunk = null;
                }
                if (inbound != null) {
                    inbound.close();
                }
                if (receiver != null) {
                    receiver.close();
                }
                if (writer != null) {
                    writer.awaitDrained();
                }
                if (tls != null) {
                    tls.close();
                }
                control.closeSocketRegistration(fixedSlot);
            } finally {
                connectionClosed(control);
            }
        }
    }

    private void handleHttp1(UringEventLoop loop, ConnectionWriter writer,
                             InboundChunkStream inbound,
                             InboundChunk initialChunk,
                             int initialReadOffset,
                             ConnectionControl control) {
        Http1Session session = new Http1Session(
            loop,
            writer,
            inbound,
            initialChunk,
            initialReadOffset,
            control
        );
        try {
            while (drainHttp1Session(session)) {
                InboundChunk next = inbound.nextChunk();
                if (next == null) {
                    break;
                }
                session.accept(next);
            }
        } finally {
            session.close();
        }
    }

    /**
     * Drains the input already owned by {@code session}. For an empty-body
     * keep-alive request, this method returns before the receive driver acquires
     * another chunk, so the parser frame is absent when that driver parks.
     * Fragmented headers, coalesced bodies, and streaming bodies may consume
     * multiple chunks through {@link InboundChunkStream}.
     */
    private boolean drainHttp1Session(Http1Session session) {
        UringEventLoop loop = session.loop;
        ConnectionWriter writer = session.writer;
        InboundChunkStream inbound = session.inbound;
        ConnectionControl control = session.control;
        boolean keepAlive = session.keepAlive;
        InboundChunk currentChunk = session.currentChunk;
        MemorySegment currentBuf = session.currentBuf;
        int readOffset = session.readOffset;
        int requestOffset = session.requestOffset;
        HttpRequest request = session.request;
        Http1ExchangeSequencer exchangeSequencer = session.exchangeSequencer;
        try {
            while (keepAlive) {
                if (control.draining) {
                    break;
                }
                if (exchangeSequencer != null
                    && exchangeSequencer.isFailed()) {
                    break;
                }

                if (currentChunk == null) {
                    return true;
                }

                request.init(currentBuf, requestOffset);
                long lastLen = 0;
                long parseRes = PicoHTTPParser.parseRequest(
                    currentBuf, requestOffset,
                    Math.min(readOffset, requestOffset + MAX_HEADER_SIZE),
                    request.picoRequest(), lastLen);

                while (parseRes == PicoHTTPParser.ERROR_PARTIAL
                    && readOffset - requestOffset < MAX_HEADER_SIZE) {
                    if (readOffset == currentBuf.byteSize()
                        && requestOffset != 0) {
                        int partialLength = readOffset - requestOffset;
                        MemorySegment.copy(
                            currentBuf, requestOffset,
                            currentBuf, 0, partialLength);
                        readOffset = partialLength;
                        requestOffset = 0;
                        currentChunk.length(readOffset);
                        request.init(currentBuf);
                    }
                    int previousOffset = readOffset;
                    int appended = inbound.appendOnce(
                        currentChunk, readOffset,
                        Math.min(
                            (int) currentBuf.byteSize(),
                            requestOffset + MAX_HEADER_SIZE));
                    if (appended <= previousOffset) {
                        break;
                    }
                    readOffset = appended;
                    lastLen = previousOffset - requestOffset;
                    parseRes = PicoHTTPParser.parseRequest(
                        currentBuf, requestOffset,
                        Math.min(
                            readOffset, requestOffset + MAX_HEADER_SIZE),
                        request.picoRequest(), lastLen);
                }

                if (parseRes < 0) {
                    if (exchangeSequencer != null) {
                        exchangeSequencer.awaitAll();
                    }
                    if (parseRes == PicoHTTPParser.ERROR_PARSE) {
                        sendResponse(
                            writer,
                            Response.error(
                                "Bad Request (Invalid HTTP structure)"),
                            false);
                    } else {
                        sendResponse(
                            writer, Response.headerFieldsTooLarge(), false);
                    }
                    break;
                }

                request.splitQuery();

                int headerLength = (int) parseRes;
                int headerEndPos = requestOffset + headerLength;
                long framing = parseHttp1Framing(request);
                if (framing == HTTP1_FRAMING_INVALID) {
                    if (exchangeSequencer != null) {
                        exchangeSequencer.awaitAll();
                    }
                    sendResponse(
                        writer,
                        new Response(
                            400, "text/plain", Response.CT_TEXT,
                            "Bad Request (Invalid HTTP framing)"),
                        false
                    );
                    break;
                }
                if (framing == HTTP1_FRAMING_TOO_LARGE) {
                    if (exchangeSequencer != null) {
                        exchangeSequencer.awaitAll();
                    }
                    sendResponse(writer, Response.payloadTooLarge(), false);
                    break;
                }
                boolean chunked =
                    (framing & HTTP1_FRAMING_CHUNKED) != 0;
                boolean expectContinue =
                    (framing & HTTP1_EXPECT_CONTINUE) != 0;
                boolean unsupportedExpectation =
                    (framing & HTTP1_EXPECT_UNSUPPORTED) != 0
                        || (expectContinue && request.version() != 1);
                long contentLength = framing & HTTP1_FRAMING_LENGTH_MASK;
                if (contentLength > MAX_REQUEST_SIZE - headerLength) {
                    if (exchangeSequencer != null) {
                        exchangeSequencer.awaitAll();
                    }
                    sendResponse(writer, Response.payloadTooLarge(), false);
                    break;
                }
                long requestSize = (long) headerLength + contentLength;
                long totalRequestSize = (long) requestOffset + requestSize;

                if (totalRequestSize > readOffset && requestOffset != 0) {
                    int partialLength = readOffset - requestOffset;
                    MemorySegment.copy(
                        currentBuf, requestOffset,
                        currentBuf, 0, partialLength);
                    readOffset = partialLength;
                    requestOffset = 0;
                    currentChunk.length(readOffset);
                    continue;
                }
                int streamingBodyMode = router.streamingBodyMode(request);
                boolean streamingRoute =
                    streamingBodyMode != Router.BODY_BUFFERED;

                if (unsupportedExpectation) {
                    if (exchangeSequencer != null) {
                        exchangeSequencer.awaitAll();
                    }
                    sendResponse(writer, Response.expectationFailed(), false);
                    break;
                }

                if (chunked && !streamingRoute) {
                    if (exchangeSequencer != null) {
                        exchangeSequencer.awaitAll();
                    }
                    sendResponse(
                        writer,
                        new Response(
                            400, "text/plain", Response.CT_TEXT,
                            "Chunked request bodies require a streaming route"),
                        false
                    );
                    break;
                }

                if (expectContinue && (chunked || contentLength != 0)) {
                    boolean ready = exchangeSequencer == null
                        || !exchangeSequencer.hasInFlight()
                        || exchangeSequencer.awaitAll();
                    if (!ready || writer.writeFully(
                            SEG_100_CONTINUE,
                            Math.toIntExact(SEG_100_CONTINUE.byteSize())) <= 0) {
                        break;
                    }
                }

                if (streamingRoute) {
                    boolean requestKeepAlive = request.isKeepAlive();
                    boolean requestKeepAliveHeader =
                        requestKeepAlive && request.version() == 0;
                    keepAlive = requestKeepAlive;
                    boolean isolatedStreamingRoute = streamingBodyMode
                        == Router.BODY_STREAMING_ISOLATED;
                    Http1RequestBody body = chunked
                        ? Http1RequestBody.chunked(
                            inbound,
                            currentChunk,
                            headerEndPos,
                            MAX_REQUEST_SIZE - headerLength,
                            MAX_HEADER_SIZE
                        )
                        : new Http1RequestBody(
                            inbound,
                            currentChunk,
                            headerEndPos,
                            contentLength
                        );
                    currentChunk = null;
                    currentBuf = null;
                    request.setBody(headerEndPos, 0);
                    request.setBodyStream(body);

                    boolean ready = exchangeSequencer == null
                        || !exchangeSequencer.hasInFlight()
                        || exchangeSequencer.awaitAll();
                    Response response = null;
                    if (ready) {
                        response = isolatedStreamingRoute
                            ? dispatchIsolatedHttp1Streaming(
                                loop, request, body, control)
                            : router.dispatch(request);
                    }
                    boolean consumed = body.discardRemaining();
                    request.setBodyStream(null);

                    if (!ready || !consumed) {
                        int failureStatus = body.failureStatus();
                        if (ready && failureStatus != 0) {
                            Response failureResponse = switch (failureStatus) {
                                case 413 -> Response.payloadTooLarge();
                                case 431 -> Response.headerFieldsTooLarge();
                                default -> new Response(
                                    400, "text/plain", Response.CT_TEXT,
                                    "Bad Request (Invalid chunked body)");
                            };
                            sendResponse(writer, failureResponse, false);
                        }
                        body.release();
                        break;
                    }
                    readOffset = body.leftoverLength();
                    currentChunk = body.takeChunk();
                    currentBuf = currentChunk == null
                        ? null
                        : currentChunk.segment();
                    requestOffset = 0;
                    boolean sent = sendResponse(
                        writer,
                        response,
                        requestKeepAlive && !control.draining,
                        requestKeepAliveHeader,
                        readOffset != 0
                    );
                    if (!sent) {
                        break;
                    }
                    continue;
                }

                MemorySegment activeBuf = currentBuf;
                int totalRead = readOffset;
                Arena jumboArena = null;
                MemorySegment jumboBuf = null;

                if (requestSize > UringEventLoop.BUFFER_SIZE) {
                    jumboArena = Arena.ofShared();
                    jumboBuf = jumboArena.allocate(totalRequestSize);
                    MemorySegment.copy(
                        currentBuf, 0, jumboBuf, 0, totalRead);
                    activeBuf = jumboBuf;

                    currentChunk.close();
                    currentChunk = null;
                    currentBuf = null;

                    while (totalRead < totalRequestSize) {
                        int newTotal = inbound.copyOnce(
                            jumboBuf, totalRead, (int) totalRequestSize);
                        if (newTotal <= totalRead) {
                            break;
                        }
                        totalRead = newTotal;
                    }

                    if (totalRead < totalRequestSize) {
                        jumboArena.close();
                        jumboArena = null;
                        break;
                    }
                } else {
                    while (totalRead < totalRequestSize) {
                        int newTotal = inbound.appendOnce(
                            currentChunk, totalRead,
                            (int) totalRequestSize);
                        if (newTotal <= totalRead) {
                            break;
                        }
                        totalRead = newTotal;
                    }

                    if (totalRead < totalRequestSize) {
                        break;
                    }
                    readOffset = totalRead;
                    currentChunk.length(readOffset);
                }

                if (activeBuf != currentBuf) {
                    request.setSegment(activeBuf);
                }
                request.setBody(headerEndPos, contentLength);

                boolean requestKeepAlive = request.isKeepAlive();
                boolean requestKeepAliveHeader =
                    requestKeepAlive && request.version() == 0;
                keepAlive = requestKeepAlive;
                int leftover = jumboArena == null
                    ? (int) (readOffset - totalRequestSize)
                    : 0;

                boolean sentOk;
                if (router.isSafeMethod(request)) {
                    if (exchangeSequencer == null) {
                        exchangeSequencer = new Http1ExchangeSequencer(
                            loop.exchangeExecutor(),
                            MAX_HTTP1_IN_FLIGHT,
                            (completedResponse, responseKeepAlive,
                                    responseKeepAliveHeader) ->
                                sendResponse(
                                    writer, completedResponse,
                                    responseKeepAlive,
                                    responseKeepAliveHeader,
                                    true)
                        );
                        control.http1 = exchangeSequencer;
                        if (control.draining) {
                            exchangeSequencer.beginDrain();
                        }
                    }
                    AutoCloseable requestStorage = null;
                    if (jumboArena != null) {
                        requestStorage = jumboArena;
                        jumboArena = null;
                    } else if (leftover == 0) {
                        requestStorage = currentChunk;
                        currentChunk = null;
                        currentBuf = null;
                    }
                    sentOk = exchangeSequencer.submit(
                        router,
                        request,
                        requestKeepAlive,
                        requestKeepAliveHeader,
                        requestStorage);
                } else if (exchangeSequencer != null
                    && exchangeSequencer.hasInFlight()) {
                    PreparedInvocation invocation = router.prepare(request);
                    sentOk = exchangeSequencer.awaitAll();
                    if (sentOk) {
                        sentOk = sendResponse(
                            writer, invocation.invoke(),
                            requestKeepAlive && !control.draining,
                            requestKeepAliveHeader,
                            leftover > 0);
                    }
                } else {
                    sentOk = sendResponse(
                        writer, router.dispatch(request),
                        requestKeepAlive && !control.draining,
                        requestKeepAliveHeader,
                        leftover > 0);
                }

                if (jumboArena != null) {
                    jumboArena.close();
                    readOffset = 0;
                    requestOffset = 0;
                } else if (leftover == 0) {
                    if (currentChunk != null) {
                        currentChunk.close();
                    }
                    currentChunk = null;
                    currentBuf = null;
                    readOffset = 0;
                    requestOffset = 0;
                } else {
                    requestOffset = (int) totalRequestSize;
                }

                if (!sentOk) {
                    break;
                }
            }
            return false;
        } finally {
            session.keepAlive = keepAlive;
            session.currentChunk = currentChunk;
            session.currentBuf = currentBuf;
            session.readOffset = readOffset;
            session.requestOffset = requestOffset;
            session.exchangeSequencer = exchangeSequencer;
        }
    }

    /** Carries HTTP/1 parser and sequencing state across receive-chunk boundaries. */
    private static final class Http1Session implements AutoCloseable {
        private final UringEventLoop loop;
        private final ConnectionWriter writer;
        private final InboundChunkStream inbound;
        private final ConnectionControl control;
        private final HttpRequest request = new HttpRequest();

        private boolean keepAlive = true;
        private InboundChunk currentChunk;
        private MemorySegment currentBuf;
        private int readOffset;
        private int requestOffset;
        private Http1ExchangeSequencer exchangeSequencer;

        private Http1Session(
            UringEventLoop loop,
            ConnectionWriter writer,
            InboundChunkStream inbound,
            InboundChunk initialChunk,
            int initialReadOffset,
            ConnectionControl control
        ) {
            this.loop = loop;
            this.writer = writer;
            this.inbound = inbound;
            this.control = control;
            this.currentChunk = initialChunk;
            this.currentBuf = initialChunk.segment();
            this.readOffset = initialReadOffset;
        }

        private void accept(InboundChunk chunk) {
            if (currentChunk != null) {
                throw new IllegalStateException(
                    "Cannot replace an unconsumed HTTP/1 input chunk");
            }
            currentChunk = chunk;
            currentBuf = chunk.segment();
            readOffset = chunk.length();
            requestOffset = 0;
        }

        @Override
        public void close() {
            if (currentChunk != null) {
                try {
                    currentChunk.close();
                } catch (Throwable ignored) {
                    // The receiver is closing this connection anyway.
                }
                currentChunk = null;
                currentBuf = null;
            }
            if (exchangeSequencer != null) {
                exchangeSequencer.beginDrain();
                exchangeSequencer.awaitAll();
            }
            control.http1 = null;
        }
    }

    private Response dispatchIsolatedHttp1Streaming(
        UringEventLoop loop,
        HttpRequest request,
        Http1RequestBody source,
        ConnectionControl control
    ) {
        Http1IsolatedRequestBody bridge =
            Http1IsolatedRequestBody.acquire(source.length());
        if (bridge == null) {
            return Response.serviceUnavailable();
        }

        request.setBodyStream(bridge);
        PreparedInvocation invocation = router.prepare(request);
        Http1IsolatedStreamingState state =
            new Http1IsolatedStreamingState(invocation, bridge);
        Object previousHttp1State = control.http1;
        control.http1 = state;
        Thread pump;
        try {
            pump = loop.startVirtualThread(
                () -> pumpHttp1Body(source, bridge));
        } catch (Throwable failure) {
            if (control.http1 == state) {
                control.http1 = previousHttp1State;
            }
            bridge.close();
            bridge.producerComplete();
            invocation.cancel();
            invocation.invokeWithCompletion(bridge::handlerComplete);
            return null;
        }

        try {
            return invocation.invokeWithCompletion(
                bridge::handlerComplete);
        } finally {
            bridge.close();
            awaitThread(pump);
            if (control.http1 == state) {
                control.http1 = previousHttp1State;
            }
            request.setBodyStream(source);
        }
    }

    private static void pumpHttp1Body(
        Http1RequestBody source,
        Http1IsolatedRequestBody destination
    ) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment scratch = arena.allocate(
                UringEventLoop.BUFFER_SIZE);
            while (true) {
                int count = source.read(scratch);
                if (count < 0) {
                    destination.end();
                    return;
                }
                if (!destination.write(scratch, 0, count)) {
                    return;
                }
            }
        } catch (Throwable failure) {
            destination.fail(
                failure.getMessage() != null
                    ? failure.getMessage()
                    : "HTTP/1 request body aborted");
        } finally {
            destination.producerComplete();
        }
    }

    private static void awaitThread(Thread thread) {
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns packed content-length, chunked, and expectation state, or an
     * error sentinel. This is deliberately a single header pass: it also
     * rejects ambiguous framing before a body can desynchronise a keep-alive
     * connection.
     */
    static long parseHttp1Framing(HttpRequest request) {
        long contentLength = 0;
        long flags = 0;
        MemorySegment segment = request.segment();
        Request parsed = request.picoRequest();
        long framingHeaders = parsed.framingHeaders;
        if (framingHeaders == 0) {
            request.cacheConnectionHeader(-1);
            return 0;
        }
        Header[] headers = parsed.headers;
        long contentLengthLane = framingHeaders & 0xffffL;
        long transferEncodingLane =
            (framingHeaders >>> 16) & 0xffffL;
        long expectLane = (framingHeaders >>> 32) & 0xffffL;
        int connectionHeaderIndex = (int) ((framingHeaders >>> 48)
            & Request.FRAMING_INDEX_MASK) - 1;

        if ((contentLengthLane
                & Request.FRAMING_DUPLICATE_MASK) != 0
            || (transferEncodingLane
                & Request.FRAMING_DUPLICATE_MASK) != 0) {
            return HTTP1_FRAMING_INVALID;
        }

        int contentLengthIndex = (int) (contentLengthLane
            & Request.FRAMING_INDEX_MASK) - 1;
        if (contentLengthIndex >= 0) {
            Header header = headers[contentLengthIndex];
            contentLength = parseContentLength(
                segment, header.valueOffset, header.valueLen);
            if (contentLength < 0) {
                return contentLength;
            }
        }

        int transferEncodingIndex = (int) (transferEncodingLane
            & Request.FRAMING_INDEX_MASK) - 1;
        if (transferEncodingIndex >= 0) {
            Header header = headers[transferEncodingIndex];
            if (!isChunkedTransferEncoding(
                    segment, header.valueOffset, header.valueLen)) {
                return HTTP1_FRAMING_INVALID;
            }
        }

        int expectIndex = (int) (expectLane
            & Request.FRAMING_INDEX_MASK) - 1;
        if (expectIndex >= 0) {
            Header header = headers[expectIndex];
            if ((expectLane
                    & Request.FRAMING_DUPLICATE_MASK) != 0
                    || !isContinueExpectation(
                        segment, header.valueOffset, header.valueLen)) {
                flags |= HTTP1_EXPECT_UNSUPPORTED;
            } else {
                flags |= HTTP1_EXPECT_CONTINUE;
            }
        }

        request.cacheConnectionHeader(connectionHeaderIndex);

        if (transferEncodingIndex >= 0) {
            // Cardigan supports only HTTP/1.1's terminal chunked coding;
            // Content-Length plus Transfer-Encoding is ambiguous framing.
            if (contentLengthIndex >= 0 || request.version() != 1) {
                return HTTP1_FRAMING_INVALID;
            }
            return flags | HTTP1_FRAMING_CHUNKED;
        }
        return flags | contentLength;
    }

    private static long parseContentLength(
        MemorySegment segment,
        long offset,
        long length
    ) {
        long end = offset + length;
        while (offset < end && isHttpWhitespace(
            RawSegment.getByte(segment, offset) & 0xff)) {
            offset++;
        }
        while (end > offset && isHttpWhitespace(
            RawSegment.getByte(segment, end - 1) & 0xff)) {
            end--;
        }
        if (offset == end) {
            return HTTP1_FRAMING_INVALID;
        }

        long value = 0;
        while (offset < end) {
            int current = RawSegment.getByte(segment, offset++) & 0xff;
            if (current < '0' || current > '9') {
                return HTTP1_FRAMING_INVALID;
            }
            int digit = current - '0';
            if (value > (Long.MAX_VALUE - digit) / 10) {
                return HTTP1_FRAMING_TOO_LARGE;
            }
            value = value * 10 + digit;
            if (value > MAX_REQUEST_SIZE) {
                return HTTP1_FRAMING_TOO_LARGE;
            }
        }
        return value;
    }

    private static boolean isChunkedTransferEncoding(
        MemorySegment segment,
        long offset,
        long length
    ) {
        long end = offset + length;
        while (offset < end && isHttpWhitespace(
            RawSegment.getByte(segment, offset) & 0xff)) {
            offset++;
        }
        while (end > offset && isHttpWhitespace(
            RawSegment.getByte(segment, end - 1) & 0xff)) {
            end--;
        }
        return end - offset == 7
            && asciiEqualsIgnoreCase(segment, offset, 7, "chunked");
    }

    private static boolean isContinueExpectation(
        MemorySegment segment,
        long offset,
        long length
    ) {
        long end = offset + length;
        while (offset < end && isHttpWhitespace(
            RawSegment.getByte(segment, offset) & 0xff)) {
            offset++;
        }
        while (end > offset && isHttpWhitespace(
            RawSegment.getByte(segment, end - 1) & 0xff)) {
            end--;
        }
        return end - offset == 12
            && asciiEqualsIgnoreCase(
                segment, offset, 12, "100-continue");
    }

    private static boolean asciiEqualsIgnoreCase(
        MemorySegment segment,
        long offset,
        int length,
        String expected
    ) {
        for (int index = 0; index < length; index++) {
            int actual = RawSegment.getByte(segment, offset + index) & 0xff;
            if (actual >= 'A' && actual <= 'Z') {
                actual += 'a' - 'A';
            }
            if (actual != expected.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHttpWhitespace(int value) {
        return value == ' ' || value == '\t';
    }

    private void reportTlsCapabilities(TlsConnection connection) {
        String protocol = connection.selectedProtocol() == TlsConnection.HTTP2
            ? "h2"
            : "http/1.1";
        tlsCapabilities = new TlsCapabilities(
            protocol,
            connection.ktlsSend(),
            connection.ktlsRecv(),
            connection.directKtlsSend(),
            connection.directKtlsReceive()
        );
        if (!tlsCapabilityReported.compareAndSet(false, true)) {
            return;
        }
        System.out.println(
            "TLS/OpenSSL negotiated " + protocol
                + "; kTLS TX="
                + connection.ktlsSend() + ", RX=" + connection.ktlsRecv()
                + ", direct RX=" + connection.directKtlsReceive());
    }

    private static void sendInvalidHttp2Preface(ConnectionWriter writer) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment frame = arena.allocate(Http2Frames.HEADER_SIZE + 8);
            int length = Http2Frames.writeGoAway(
                frame, 0, 0, Http2Frames.PROTOCOL_ERROR);
            writer.writeFully(frame, length);
        }
    }

    private boolean sendResponse(ConnectionWriter writer, Response response, boolean keepAlive) {
        return sendResponse(writer, response, keepAlive, false, false);
    }

    private boolean sendResponse(
            ConnectionWriter writer,
            Response response,
            boolean keepAlive,
            boolean keepAliveHeader,
            boolean hasPipelinedBytes) {
        try {
            if (response.hasMetadata()) {
                return sendMetadataResponse(
                    writer, response, keepAlive, keepAliveHeader);
            }
            UringEventLoop loop = writer.eventLoop();
            int statusCode = response.statusCode();
            int contentTypeCode = response.contentTypeCode();
            String contentType = response.contentType();
            boolean asciiLongBody = response.hasAsciiLongBody();
            Object body = asciiLongBody ? null : response.body();
            byte[] materializedBytes = null;
            if (body instanceof byte[] bytes) {
                materializedBytes = bytes;
            } else if (body instanceof String text) {
                materializedBytes = Http2ResponseWriter.asciiBytes(text);
                if (materializedBytes == null) {
                    materializedBytes = text.getBytes(StandardCharsets.UTF_8);
                }
            }

            int res = 0;
            int egressId = loop.acquireEgressBuffer();
            MemorySegment respSeg = null;
            Arena fallbackArena = null;

            try {
                if (egressId >= 0) {
                    respSeg = loop.getEgressBufferSegment(egressId);
                } else {
                    fallbackArena = Arena.ofConfined();
                    respSeg = fallbackArena.allocate(16 * 1024);
                }

                if (asciiLongBody) {
                    int bodyLen = response.asciiLongBodyLength();
                    int headersLen = writeHeaders(
                        respSeg, statusCode, contentTypeCode,
                        contentType, bodyLen, keepAlive, keepAliveHeader);
                    response.writeAsciiLongBody(
                        respSeg.asSlice(headersLen, bodyLen));
                    int responseLength = headersLen + bodyLen;
                    if (hasPipelinedBytes && keepAlive
                        && egressId >= 0 && fallbackArena == null) {
                        boolean queued = writer.enqueue(
                            egressId, responseLength);
                        egressId = -1;
                        return queued;
                    }
                    res = writer.writeFully(respSeg, responseLength);
                } else if (body == null) {
                    int headersLen = writeHeaders(
                        respSeg, statusCode, contentTypeCode,
                        contentType, 0, keepAlive, keepAliveHeader);
                    if (hasPipelinedBytes && keepAlive && egressId >= 0 && fallbackArena == null) {
                        boolean queued = writer.enqueue(egressId, headersLen);
                        egressId = -1;
                        return queued;
                    }
                    res = writer.writeFully(respSeg, headersLen);
                } else if (body instanceof EncodedBody encodedBody) {
                    int bodyLen = encodedBody.length();
                    int headersLen = writeHeaders(
                        respSeg,
                        statusCode,
                        contentTypeCode,
                        contentType,
                        bodyLen,
                        keepAlive,
                        keepAliveHeader
                    );
                    int totalLength = Math.addExact(headersLen, bodyLen);
                    if (totalLength <= respSeg.byteSize()) {
                        encodedBody.write(
                            respSeg.asSlice(headersLen, bodyLen));
                        if (hasPipelinedBytes && keepAlive
                            && egressId >= 0 && fallbackArena == null) {
                            boolean queued = writer.enqueue(
                                egressId, totalLength);
                            egressId = -1;
                            return queued;
                        }
                        res = writer.writeFully(respSeg, totalLength);
                    } else {
                        if (fallbackArena == null) {
                            fallbackArena = Arena.ofConfined();
                        }
                        MemorySegment encodedResponse =
                            fallbackArena.allocate(totalLength);
                        MemorySegment.copy(
                            respSeg, 0, encodedResponse, 0, headersLen);
                        encodedBody.write(
                            encodedResponse.asSlice(headersLen, bodyLen));
                        res = writer.writeFully(
                            encodedResponse, totalLength);
                    }
                } else if (body instanceof StreamingBody streamingBody) {
                    int bodyLen = streamingBody.length();
                    try {
                        if (bodyLen >= 0) {
                            int headersLen = writeHeaders(
                                respSeg,
                                statusCode,
                                contentTypeCode,
                                contentType,
                                bodyLen,
                                keepAlive,
                                keepAliveHeader
                            );
                            res = writer.writeFully(respSeg, headersLen);
                            int remaining = bodyLen;
                            while (res > 0 && remaining != 0) {
                                int capacity = Math.min(
                                    remaining,
                                    Math.toIntExact(respSeg.byteSize()));
                                int produced = streamingBody.read(
                                    respSeg.asSlice(0, capacity));
                                if (produced <= 0) {
                                    res = -5;
                                    break;
                                }
                                res = writer.writeFully(respSeg, produced);
                                remaining -= produced;
                            }
                        } else {
                            res = sendChunkedResponse(
                                writer,
                                streamingBody,
                                respSeg,
                                statusCode,
                                contentType,
                                keepAlive,
                                keepAliveHeader
                            );
                        }
                    } finally {
                        streamingBody.close();
                    }
                } else if (body instanceof StaticBody staticBody) {
                    int bodyLen = staticBody.length();
                    int headersLen = writeHeaders(
                        respSeg,
                        statusCode,
                        contentTypeCode,
                        contentType,
                        bodyLen,
                        keepAlive,
                        keepAliveHeader
                    );
                    if (egressId >= 0 && fallbackArena == null) {
                        boolean queued = writer.enqueue(egressId, headersLen);
                        egressId = -1;
                        if (!queued) {
                            return false;
                        }
                        if (bodyLen != 0
                            && !writer.enqueueBorrowed(
                                staticBody.segment(), 0, bodyLen)) {
                            return false;
                        }
                        if (hasPipelinedBytes && keepAlive) {
                            return true;
                        }
                        return writer.awaitDrained();
                    }
                    res = writer.writeFully(respSeg, headersLen);
                    if (res > 0 && bodyLen != 0) {
                        res = writer.writeFully(
                            staticBody.segment(), bodyLen);
                    }
                } else if (materializedBytes != null) {
                    int bodyLen = materializedBytes.length;
                    int headersLen = writeHeaders(
                        respSeg, statusCode, contentTypeCode, contentType,
                        bodyLen, keepAlive, keepAliveHeader);
                    int firstBodyLen = Math.min(
                        bodyLen,
                        Math.toIntExact(respSeg.byteSize()) - headersLen);
                    if (firstBodyLen != 0) {
                        RawSegment.copy(
                            materializedBytes, 0,
                            respSeg.address() + headersLen,
                            firstBodyLen);
                    }
                    if (firstBodyLen == bodyLen
                        && hasPipelinedBytes && keepAlive
                        && egressId >= 0 && fallbackArena == null) {
                        boolean queued = writer.enqueue(
                            egressId, headersLen + bodyLen);
                        egressId = -1;
                        return queued;
                    }
                    res = writer.writeFully(
                        respSeg, headersLen + firstBodyLen);
                    int bodyOffset = firstBodyLen;
                    int capacity = Math.toIntExact(respSeg.byteSize());
                    while (res > 0 && bodyOffset < bodyLen) {
                        int length = Math.min(
                            capacity, bodyLen - bodyOffset);
                        RawSegment.copy(
                            materializedBytes, bodyOffset,
                            respSeg.address(), length);
                        res = writer.writeFully(respSeg, length);
                        bodyOffset += length;
                    }
                } else if (body instanceof Record recordBody) {
                    int bodyLen = 0;
                    try {
                        bodyLen = JsonWriter.writeRecord(
                            respSeg, 256, recordBody);
                    } catch (Throwable e) {
                        if (fallbackArena == null) {
                            fallbackArena = Arena.ofConfined();
                        }
                        respSeg = fallbackArena.allocate(2 * 1024 * 1024);
                        bodyLen = JsonWriter.writeRecord(
                            respSeg, 256, recordBody);
                    }
                    int headersLen = writeHeaders(
                        respSeg, statusCode, contentTypeCode, contentType,
                        bodyLen, keepAlive, keepAliveHeader);
                    if (headersLen != 256) {
                        RawSegment.copy(respSeg.address() + 256, respSeg.address() + headersLen, bodyLen);
                    }
                    if (hasPipelinedBytes && keepAlive && egressId >= 0 && fallbackArena == null) {
                        boolean queued = writer.enqueue(egressId, headersLen + bodyLen);
                        egressId = -1;
                        return queued;
                    }
                    res = writer.writeFully(respSeg, headersLen + bodyLen);
                }
            } finally {
                if (egressId >= 0) {
                    loop.releaseEgressBuffer(egressId);
                }
                if (fallbackArena != null) {
                    fallbackArena.close();
                }
            }
            return res > 0;
        } catch (Throwable t) {
            System.err.println("Failed to send response: " + t.getMessage());
            return false;
        }
    }

    private static boolean sendMetadataResponse(
            ConnectionWriter writer,
            Response response,
            boolean keepAlive,
            boolean keepAliveHeader) {
        UringEventLoop loop = writer.eventLoop();
        int egressId = loop.acquireEgressBuffer();
        Arena bufferArena = null;
        Arena bodyArena = null;
        StreamingBody streamingBody = null;
        try {
            MemorySegment buffer;
            if (egressId >= 0) {
                buffer = loop.getEgressBufferSegment(egressId);
            } else {
                bufferArena = Arena.ofConfined();
                buffer = bufferArena.allocate(UringEventLoop.EGRESS_FRAME_SIZE);
            }

            boolean asciiLongBody = response.hasAsciiLongBody();
            Object body = asciiLongBody ? null : response.body();
            byte[] byteBody = null;
            MemorySegment segmentBody = null;
            int bodyLength;
            if (asciiLongBody) {
                bodyLength = response.asciiLongBodyLength();
                bodyArena = Arena.ofConfined();
                segmentBody = bodyArena.allocate(bodyLength);
                response.writeAsciiLongBody(segmentBody);
            } else if (body == null) {
                bodyLength = 0;
            } else if (body instanceof byte[] bytes) {
                byteBody = bytes;
                bodyLength = bytes.length;
            } else if (body instanceof StreamingBody streaming) {
                streamingBody = streaming;
                bodyLength = streaming.length();
            } else if (body instanceof StaticBody staticBody) {
                segmentBody = staticBody.segment();
                bodyLength = staticBody.length();
            } else if (body instanceof String text) {
                byteBody = text.getBytes(StandardCharsets.UTF_8);
                bodyLength = byteBody.length;
            } else if (body instanceof EncodedBody encodedBody) {
                bodyLength = encodedBody.length();
                bodyArena = Arena.ofConfined();
                segmentBody = bodyArena.allocate(
                    Math.max(1, bodyLength)).asSlice(0, bodyLength);
                encodedBody.write(segmentBody);
            } else if (body instanceof Record recordBody) {
                bodyArena = Arena.ofConfined();
                segmentBody = bodyArena.allocate(2 * 1024 * 1024);
                bodyLength = JsonWriter.writeRecord(
                    segmentBody, 0, recordBody);
                segmentBody = segmentBody.asSlice(0, bodyLength);
            } else {
                byteBody = String.valueOf(body)
                    .getBytes(StandardCharsets.UTF_8);
                bodyLength = byteBody.length;
            }

            boolean chunked = !response.trailers().isEmpty()
                || bodyLength == StreamingBody.UNKNOWN_LENGTH;
            int headerLength = writeMetadataHeaders(
                buffer, response, bodyLength,
                keepAlive, keepAliveHeader, chunked);
            int byteBodyOffset = 0;
            int firstLength = headerLength;
            if (!chunked && byteBody != null) {
                int available = Math.toIntExact(buffer.byteSize())
                    - headerLength;
                byteBodyOffset = Math.min(byteBody.length, available);
                if (byteBodyOffset != 0) {
                    RawSegment.copy(
                        byteBody, 0,
                        buffer.address() + headerLength,
                        byteBodyOffset);
                    firstLength += byteBodyOffset;
                }
            }
            if (writer.writeFully(buffer, firstLength) <= 0) {
                return false;
            }

            boolean sent;
            if (streamingBody != null) {
                sent = sendMetadataStreamingBody(
                    writer, buffer, streamingBody, chunked);
            } else if (byteBody != null) {
                sent = sendMetadataByteBody(
                    writer, buffer, byteBody, byteBodyOffset, chunked);
            } else if (segmentBody != null) {
                sent = sendMetadataSegmentBody(
                    writer, buffer, segmentBody, bodyLength, chunked);
            } else {
                sent = true;
            }
            if (!sent) {
                return false;
            }
            if (!chunked) {
                return true;
            }

            int finalLength = response.trailers().isEmpty()
                ? copyFinalChunk(buffer)
                : writeHttp1Trailers(buffer, response.trailers());
            return writer.writeFully(buffer, finalLength) > 0;
        } finally {
            if (streamingBody != null) {
                streamingBody.close();
            }
            if (egressId >= 0) {
                loop.releaseEgressBuffer(egressId);
            }
            if (bodyArena != null) {
                bodyArena.close();
            }
            if (bufferArena != null) {
                bufferArena.close();
            }
        }
    }

    private static boolean sendMetadataStreamingBody(
            ConnectionWriter writer,
            MemorySegment buffer,
            StreamingBody body,
            boolean chunked) {
        int remaining = body.length();
        int prefix = chunked ? 10 : 0;
        int suffix = chunked ? 2 : 0;
        int capacity = Math.toIntExact(buffer.byteSize()) - prefix - suffix;
        while (remaining != 0) {
            int desired = remaining < 0
                ? capacity
                : Math.min(capacity, remaining);
            int produced = body.read(buffer.asSlice(prefix, desired));
            if (produced < 0) {
                return remaining < 0;
            }
            if (chunked) {
                writeChunkPrefix(buffer, produced);
                buffer.set(
                    ValueLayout.JAVA_BYTE, 10L + produced, (byte) '\r');
                buffer.set(
                    ValueLayout.JAVA_BYTE, 11L + produced, (byte) '\n');
            }
            if (writer.writeFully(
                    buffer, prefix + produced + suffix) <= 0) {
                return false;
            }
            if (remaining > 0) {
                remaining -= produced;
            }
        }
        return true;
    }

    private static boolean sendMetadataByteBody(
            ConnectionWriter writer,
            MemorySegment buffer,
            byte[] body,
            int offset,
            boolean chunked) {
        int prefix = chunked ? 10 : 0;
        int suffix = chunked ? 2 : 0;
        int capacity = Math.toIntExact(buffer.byteSize()) - prefix - suffix;
        while (offset < body.length) {
            int length = Math.min(capacity, body.length - offset);
            RawSegment.copy(
                body, offset, buffer.address() + prefix, length);
            if (chunked) {
                writeChunkPrefix(buffer, length);
                buffer.set(
                    ValueLayout.JAVA_BYTE, 10L + length, (byte) '\r');
                buffer.set(
                    ValueLayout.JAVA_BYTE, 11L + length, (byte) '\n');
            }
            if (writer.writeFully(
                    buffer, prefix + length + suffix) <= 0) {
                return false;
            }
            offset += length;
        }
        return true;
    }

    private static boolean sendMetadataSegmentBody(
            ConnectionWriter writer,
            MemorySegment buffer,
            MemorySegment body,
            int bodyLength,
            boolean chunked) {
        if (!chunked) {
            return bodyLength == 0
                || writer.writeFully(body, bodyLength) > 0;
        }

        int offset = 0;
        int capacity = Math.toIntExact(buffer.byteSize()) - 12;
        while (offset < bodyLength) {
            int length = Math.min(capacity, bodyLength - offset);
            MemorySegment.copy(body, offset, buffer, 10, length);
            writeChunkPrefix(buffer, length);
            buffer.set(ValueLayout.JAVA_BYTE, 10L + length, (byte) '\r');
            buffer.set(ValueLayout.JAVA_BYTE, 11L + length, (byte) '\n');
            if (writer.writeFully(buffer, length + 12) <= 0) {
                return false;
            }
            offset += length;
        }
        return true;
    }

    private static int writeMetadataHeaders(
            MemorySegment buffer,
            Response response,
            int bodyLength,
            boolean keepAlive,
            boolean keepAliveHeader,
            boolean chunked) {
        long address = buffer.address();
        int offset = writeStatusLine(
            address, response.statusCode());
        offset = writeHttp1Field(
            address, offset, "content-type", response.contentType());
        offset = writeHttp1Fields(
            address, offset, response.headers());
        if (chunked) {
            RawSegment.copy(
                ADDR_TRANSFER_CHUNKED,
                address + offset,
                LEN_TRANSFER_CHUNKED);
            offset += LEN_TRANSFER_CHUNKED;
            if (!response.trailers().isEmpty()) {
                offset = writeTrailerDeclaration(
                    address, offset, response.trailers());
            }
        } else {
            RawSegment.copy(
                ADDR_CONTENT_LENGTH,
                address + offset,
                LEN_CONTENT_LENGTH);
            offset += LEN_CONTENT_LENGTH;
            offset = (int) JsonWriter.writeInt(
                buffer, offset, bodyLength);
            RawSegment.copy(ADDR_CRLF, address + offset, LEN_CRLF);
            offset += LEN_CRLF;
        }

        return writeConnectionTerminator(
            address, offset, keepAlive, keepAliveHeader);
    }

    private static int writeStatusLine(long address, int statusCode) {
        long source;
        int length;
        switch (statusCode) {
            case 200 -> {
                source = ADDR_STATUS_200;
                length = LEN_STATUS_200;
            }
            case 400 -> {
                source = ADDR_STATUS_400;
                length = LEN_STATUS_400;
            }
            case 404 -> {
                source = ADDR_STATUS_404;
                length = LEN_STATUS_404;
            }
            case 413 -> {
                source = ADDR_STATUS_413;
                length = LEN_STATUS_413;
            }
            case 417 -> {
                source = ADDR_STATUS_417;
                length = LEN_STATUS_417;
            }
            case 431 -> {
                source = ADDR_STATUS_431;
                length = LEN_STATUS_431;
            }
            case 500 -> {
                source = ADDR_STATUS_500;
                length = LEN_STATUS_500;
            }
            default -> {
                byte[] statusLine = (
                    "HTTP/1.1 " + statusCode + " Response\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
                RawSegment.copy(
                    statusLine, 0, address, statusLine.length);
                return statusLine.length;
            }
        }
        RawSegment.copy(source, address, length);
        return length;
    }

    private static int writeHttp1Fields(
            long address,
            int offset,
            ResponseHeaders fields) {
        for (int index = 0; index < fields.size(); index++) {
            offset = writeHttp1Field(
                address, offset, fields.name(index), fields.value(index));
        }
        return offset;
    }

    private static int writeHttp1Field(
            long address,
            int offset,
            String name,
            String value) {
        offset = writeLatin1(address, offset, name);
        RawSegment.BYTE.set(
            RawSegment.raw(address + offset++), 0L, (byte) ':');
        RawSegment.BYTE.set(
            RawSegment.raw(address + offset++), 0L, (byte) ' ');
        offset = writeLatin1(address, offset, value);
        RawSegment.copy(ADDR_CRLF, address + offset, LEN_CRLF);
        return offset + LEN_CRLF;
    }

    private static int writeTrailerDeclaration(
            long address,
            int offset,
            ResponseHeaders trailers) {
        offset = writeLatin1(address, offset, "trailer: ");
        for (int index = 0; index < trailers.size(); index++) {
            if (index != 0) {
                RawSegment.BYTE.set(
                    RawSegment.raw(address + offset++), 0L, (byte) ',');
                RawSegment.BYTE.set(
                    RawSegment.raw(address + offset++), 0L, (byte) ' ');
            }
            offset = writeLatin1(
                address, offset, trailers.name(index));
        }
        RawSegment.copy(ADDR_CRLF, address + offset, LEN_CRLF);
        return offset + LEN_CRLF;
    }

    private static int writeHttp1Trailers(
            MemorySegment buffer,
            ResponseHeaders trailers) {
        long address = buffer.address();
        RawSegment.BYTE.set(
            RawSegment.raw(address), 0L, (byte) '0');
        RawSegment.copy(ADDR_CRLF, address + 1, LEN_CRLF);
        int offset = writeHttp1Fields(address, 3, trailers);
        RawSegment.copy(ADDR_CRLF, address + offset, LEN_CRLF);
        return offset + LEN_CRLF;
    }

    private static int copyFinalChunk(MemorySegment buffer) {
        RawSegment.copy(
            ADDR_FINAL_CHUNK, buffer.address(), LEN_FINAL_CHUNK);
        return LEN_FINAL_CHUNK;
    }

    private static int writeLatin1(
            long address,
            int offset,
            String value) {
        for (int index = 0; index < value.length(); index++) {
            RawSegment.BYTE.set(
                RawSegment.raw(address + offset++),
                0L,
                (byte) value.charAt(index));
        }
        return offset;
    }

    private static int sendChunkedResponse(
        ConnectionWriter writer,
        StreamingBody body,
        MemorySegment buffer,
        int statusCode,
        String contentType,
        boolean keepAlive,
        boolean keepAliveHeader
    ) {
        int result = writer.writeFully(
            buffer,
            writeChunkedHeaders(
                buffer, statusCode, contentType,
                keepAlive, keepAliveHeader));
        int capacity = Math.toIntExact(buffer.byteSize()) - 12;
        while (result > 0) {
            int produced = body.read(buffer.asSlice(10, capacity));
            if (produced < 0) {
                if (body.isClosed()) {
                    return -5;
                }
                RawSegment.copy(
                    ADDR_FINAL_CHUNK,
                    buffer.address(),
                    LEN_FINAL_CHUNK
                );
                return writer.writeFully(buffer, LEN_FINAL_CHUNK);
            }
            writeChunkPrefix(buffer, produced);
            long suffix = 10L + produced;
            buffer.set(ValueLayout.JAVA_BYTE, suffix, (byte) '\r');
            buffer.set(ValueLayout.JAVA_BYTE, suffix + 1, (byte) '\n');
            result = writer.writeFully(buffer, produced + 12);
        }
        return result;
    }

    private static void writeChunkPrefix(
        MemorySegment buffer,
        int length
    ) {
        for (int shift = 28, offset = 0;
             shift >= 0;
             shift -= 4, offset++) {
            int digit = (length >>> shift) & 0x0f;
            buffer.set(
                ValueLayout.JAVA_BYTE,
                offset,
                (byte) (digit < 10
                    ? '0' + digit
                    : 'a' + digit - 10)
            );
        }
        buffer.set(ValueLayout.JAVA_BYTE, 8, (byte) '\r');
        buffer.set(ValueLayout.JAVA_BYTE, 9, (byte) '\n');
    }

    private static int writeChunkedHeaders(
        MemorySegment segment,
        int statusCode,
        String contentType,
        boolean keepAlive,
        boolean keepAliveHeader
    ) {
        long baseAddress = segment.address();
        int offset = 0;
        switch (statusCode) {
            case 200 -> {
                RawSegment.copy(
                    ADDR_STATUS_200,
                    baseAddress,
                    LEN_STATUS_200);
                offset = LEN_STATUS_200;
            }
            case 404 -> {
                RawSegment.copy(
                    ADDR_STATUS_404,
                    baseAddress,
                    LEN_STATUS_404);
                offset = LEN_STATUS_404;
            }
            case 413 -> {
                RawSegment.copy(
                    ADDR_STATUS_413,
                    baseAddress,
                    LEN_STATUS_413);
                offset = LEN_STATUS_413;
            }
            case 431 -> {
                RawSegment.copy(
                    ADDR_STATUS_431,
                    baseAddress,
                    LEN_STATUS_431);
                offset = LEN_STATUS_431;
            }
            case 500 -> {
                RawSegment.copy(
                    ADDR_STATUS_500,
                    baseAddress,
                    LEN_STATUS_500);
                offset = LEN_STATUS_500;
            }
            default -> {
                byte[] statusLine = (
                    "HTTP/1.1 " + statusCode + " Internal Error\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
                RawSegment.copy(
                    statusLine, 0,
                    baseAddress, statusLine.length);
                offset = statusLine.length;
            }
        }

        RawSegment.copy(
            ADDR_CONTENT_TYPE,
            baseAddress + offset,
            LEN_CONTENT_TYPE);
        offset += LEN_CONTENT_TYPE;
        if ("application/json".equals(contentType)) {
            RawSegment.copy(
                ADDR_CT_JSON, baseAddress + offset, LEN_CT_JSON);
            offset += LEN_CT_JSON;
        } else if ("text/plain".equals(contentType)) {
            RawSegment.copy(
                ADDR_CT_TEXT, baseAddress + offset, LEN_CT_TEXT);
            offset += LEN_CT_TEXT;
        } else {
            int contentTypeLength = contentType.length();
            for (int i = 0; i < contentTypeLength; i++) {
                RawSegment.BYTE.set(
                    RawSegment.raw(baseAddress + offset + i),
                    0L,
                    (byte) contentType.charAt(i));
            }
            offset += contentTypeLength;
            RawSegment.copy(
                ADDR_CRLF, baseAddress + offset, LEN_CRLF);
            offset += LEN_CRLF;
        }

        RawSegment.copy(
            ADDR_TRANSFER_CHUNKED,
            baseAddress + offset,
            LEN_TRANSFER_CHUNKED);
        offset += LEN_TRANSFER_CHUNKED;
        return writeConnectionTerminator(
            baseAddress, offset, keepAlive, keepAliveHeader);
    }

    private static int writeHeaders(
            MemorySegment segment,
            int statusCode,
            int contentTypeCode,
            String contentType,
            int bodyLen,
            boolean keepAlive,
            boolean keepAliveHeader) {
        long baseAddr = segment.address();

        if (statusCode == 200 && keepAlive) {
            long hdrAddr = 0;
            int hdrLen = 0;
            if (contentTypeCode == Response.CT_TEXT) {
                hdrAddr = ADDR_HDR_200_TEXT;
                hdrLen = LEN_HDR_200_TEXT;
            } else if (contentTypeCode == Response.CT_JSON) {
                hdrAddr = ADDR_HDR_200_JSON;
                hdrLen = LEN_HDR_200_JSON;
            } else if ("text/plain".equals(contentType)) {
                hdrAddr = ADDR_HDR_200_TEXT;
                hdrLen = LEN_HDR_200_TEXT;
            } else if ("application/json".equals(contentType)) {
                hdrAddr = ADDR_HDR_200_JSON;
                hdrLen = LEN_HDR_200_JSON;
            }

            if (hdrLen > 0) {
                RawSegment.copy(hdrAddr, baseAddr, hdrLen);
                int offset = hdrLen;
                offset = (int) dev.cardigan.json.JsonWriter.writeInt(segment, offset, bodyLen);
                long connAddr = keepAliveHeader
                    ? ADDR_POST_LENGTH_KA
                    : ADDR_POST_LENGTH_DEFAULT;
                int connLen = keepAliveHeader
                    ? LEN_POST_LENGTH_KA
                    : LEN_POST_LENGTH_DEFAULT;
                RawSegment.copy(connAddr, baseAddr + offset, connLen);
                return offset + connLen;
            }
        }

        int offset = 0;
        switch (statusCode) {
            case 200 -> {
                RawSegment.copy(ADDR_STATUS_200, baseAddr + offset, LEN_STATUS_200);
                offset += LEN_STATUS_200;
            }
            case 400 -> {
                RawSegment.copy(ADDR_STATUS_400, baseAddr + offset, LEN_STATUS_400);
                offset += LEN_STATUS_400;
            }
            case 404 -> {
                RawSegment.copy(ADDR_STATUS_404, baseAddr + offset, LEN_STATUS_404);
                offset += LEN_STATUS_404;
            }
            case 413 -> {
                RawSegment.copy(ADDR_STATUS_413, baseAddr + offset, LEN_STATUS_413);
                offset += LEN_STATUS_413;
            }
            case 417 -> {
                RawSegment.copy(ADDR_STATUS_417, baseAddr + offset, LEN_STATUS_417);
                offset += LEN_STATUS_417;
            }
            case 431 -> {
                RawSegment.copy(ADDR_STATUS_431, baseAddr + offset, LEN_STATUS_431);
                offset += LEN_STATUS_431;
            }
            case 500 -> {
                RawSegment.copy(ADDR_STATUS_500, baseAddr + offset, LEN_STATUS_500);
                offset += LEN_STATUS_500;
            }
            default -> {
                byte[] statusLine = ("HTTP/1.1 " + statusCode + " Internal Error\r\n").getBytes(StandardCharsets.US_ASCII);
                RawSegment.copy(statusLine, 0, baseAddr + offset, statusLine.length);
                offset += statusLine.length;
            }
        }

        RawSegment.copy(ADDR_CONTENT_TYPE, baseAddr + offset, LEN_CONTENT_TYPE);
        offset += LEN_CONTENT_TYPE;

        if ("application/json".equals(contentType)) {
            RawSegment.copy(ADDR_CT_JSON, baseAddr + offset, LEN_CT_JSON);
            offset += LEN_CT_JSON;
        } else if ("text/plain".equals(contentType)) {
            RawSegment.copy(ADDR_CT_TEXT, baseAddr + offset, LEN_CT_TEXT);
            offset += LEN_CT_TEXT;
        } else {
            int ctLen = contentType.length();
            for (int i = 0; i < ctLen; i++) {
                RawSegment.BYTE.set(RawSegment.raw(baseAddr + offset + i), 0L, (byte) contentType.charAt(i));
            }
            offset += ctLen;
            RawSegment.copy(ADDR_CRLF, baseAddr + offset, LEN_CRLF);
            offset += LEN_CRLF;
        }

        RawSegment.copy(ADDR_CONTENT_LENGTH, baseAddr + offset, LEN_CONTENT_LENGTH);
        offset += LEN_CONTENT_LENGTH;
        offset = (int) dev.cardigan.json.JsonWriter.writeInt(segment, offset, bodyLen);
        RawSegment.copy(ADDR_CRLF, baseAddr + offset, LEN_CRLF);
        offset += LEN_CRLF;

        return writeConnectionTerminator(
            baseAddr, offset, keepAlive, keepAliveHeader);
    }

    private static int writeConnectionTerminator(
            long address,
            int offset,
            boolean keepAlive,
            boolean keepAliveHeader) {
        long source;
        int length;
        if (!keepAlive) {
            source = ADDR_CONN_CLOSE;
            length = LEN_CONN_CLOSE;
        } else if (keepAliveHeader) {
            source = ADDR_CONN_KA;
            length = LEN_CONN_KA;
        } else {
            source = ADDR_CRLF;
            length = LEN_CRLF;
        }
        RawSegment.copy(source, address + offset, length);
        return offset + length;
    }

    @Override
    public synchronized void close() {
        if (lifecycle == LIFECYCLE_CLOSED) return;
        lifecycle = LIFECYCLE_DRAINING;
        running = false;

        System.out.println("Draining Cardigan Server...");
        for (AcceptHandler handler : acceptHandlers) {
            handler.beginStop();
        }
        for (int fd : serverFds) {
            closeSocket(fd);
        }
        serverFds.clear();
        for (AcceptHandler handler : acceptHandlers) {
            handler.awaitStopped();
        }
        acceptHandlers.clear();

        for (ConnectionControl connection : activeConnections) {
            connection.requestDrain();
        }

        boolean drained = awaitConnections(gracefulShutdownMillis);
        if (!drained) {
            forcedConnectionCount = activeConnectionCount.get();
            System.err.println(
                "Graceful shutdown timed out with "
                    + forcedConnectionCount + " connection(s); forcing close");
            for (ConnectionControl connection : activeConnections) {
                connection.forceClose();
            }
            drained = awaitConnections(forcedShutdownMillis);
        }
        if (!drained) {
            throw new IllegalStateException(
                "Forced shutdown did not quiesce "
                    + activeConnectionCount.get()
                    + " connection(s); retaining their live event loops "
                    + "and native resources");
        }

        if (Http2ResourceStats.ENABLED) {
            System.out.println(
                "HTTP/2 resource stats: "
                    + Http2ResourceStats.snapshot().summary());
        }

        IllegalStateException loopCloseFailure = null;
        for (UringEventLoop loop : eventLoops) {
            try {
                loop.close();
            } catch (Exception e) {
                System.err.println("Error closing event loop: " + e.getMessage());
                if (loopCloseFailure == null) {
                    loopCloseFailure = new IllegalStateException(
                        "One or more event loops could not stop safely", e);
                } else {
                    loopCloseFailure.addSuppressed(e);
                }
            }
        }
        if (loopCloseFailure == null) {
            if (EgressBufferPool.STATS_ENABLED) {
                System.out.println(
                    "Egress buffer pool: "
                        + egressBufferPool.stats().summary());
            }
            try {
                egressBufferPool.close();
            } catch (Exception e) {
                loopCloseFailure = new IllegalStateException(
                    "Egress buffer pool could not be closed safely", e);
            }
        }
        if (loopCloseFailure != null) {
            throw loopCloseFailure;
        }
        if (tlsContext != null && TlsStats.ENABLED) {
            System.out.println(
                "TLS transport stats: " + TlsStats.snapshot().summary());
            System.out.println(
                "TLS handshake admission: "
                    + tlsContext.handshakeStats().summary());
            System.out.println(
                "Linux kTLS activity during server run: "
                    + tlsContext.kernelStatsSummary());
        }
        if (tlsContext != null) {
            try {
                tlsContext.close();
            } catch (Exception e) {
                System.err.println("Error closing TLS context: " + e.getMessage());
            }
        }
        lifecycle = LIFECYCLE_CLOSED;
        System.out.println("Cardigan Server stopped.");
    }

    private boolean awaitConnections(long timeoutMillis) {
        if (activeConnectionCount.get() == 0) {
            return true;
        }
        long timeoutNanos = timeoutMillis >= Long.MAX_VALUE / 1_000_000L
            ? Long.MAX_VALUE
            : timeoutMillis * 1_000_000L;
        long deadline = System.nanoTime() + timeoutNanos;
        Thread current = Thread.currentThread();
        drainWaiter = current;
        try {
            while (activeConnectionCount.get() != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                LockSupport.parkNanos(this, remaining);
            }
            return true;
        } finally {
            drainWaiter = null;
        }
    }

    private void connectionClosed(ConnectionControl control) {
        if (!activeConnections.remove(control)) {
            return;
        }
        activeConnectionCount.decrementAndGet();
        Thread waiter = drainWaiter;
        if (waiter != null) {
            LockSupport.unpark(waiter);
        }
    }

    private static void shutdownSocket(int fd, int how) {
        try {
            int unused = (int) Libc.shutdown.invokeExact(fd, how);
        } catch (Throwable ignored) {
        }
    }

    private static final class Http1IsolatedStreamingState {
        private final PreparedInvocation invocation;
        private final Http1IsolatedRequestBody body;

        private Http1IsolatedStreamingState(
            PreparedInvocation invocation,
            Http1IsolatedRequestBody body
        ) {
            this.invocation = invocation;
            this.body = body;
        }

        private void cancel() {
            body.close();
            invocation.cancel();
        }
    }

    private final class ConnectionControl {
        private static final int HTTP1 = 1;
        private static final int HTTP2 = 2;

        private final UringEventLoop loop;
        private final ConnectionSocket socket;
        private final AtomicBoolean inputShutdown = new AtomicBoolean();
        private final AtomicBoolean forceStarted = new AtomicBoolean();
        private final ReentrantLock socketLifecycleLock =
            new ReentrantLock();
        private volatile Thread owner;
        private volatile int protocol;
        private volatile boolean draining;
        private volatile boolean done;
        private volatile int fixedSlot;
        private volatile TlsConnection tls;
        // Holds either the response sequencer or isolated-streaming state;
        // sharing one slot avoids per-connection storage for both.
        private volatile Object http1;
        private volatile Http2Connection http2;

        private ConnectionControl(
                UringEventLoop loop, ConnectionSocket socket) {
            this.loop = loop;
            this.socket = socket;
            this.fixedSlot = socket.fixedSlot();
        }

        private void requestDrain() {
            if (done) {
                return;
            }
            draining = true;
            Object http1State = http1;
            if (http1State instanceof Http1ExchangeSequencer sequencer) {
                sequencer.beginDrain();
            }
            Http2Connection connection = http2;
            if (protocol == HTTP2 && connection != null) {
                requestHttp2Drain(connection);
            } else {
                requestInputShutdown();
            }
        }

        private void requestInputShutdown() {
            startLoopVirtualThread(this::shutdownInput);
        }

        private void requestHttp2Drain(Http2Connection connection) {
            startLoopVirtualThread(
                () -> connection.beginDrain(this::shutdownInput));
        }

        private void startLoopVirtualThread(Runnable task) {
            try {
                loop.startVirtualThread(task);
            } catch (Throwable ignored) {
                try {
                    loop.execute(() -> {
                        try {
                            loop.startVirtualThread(task);
                        } catch (Throwable retryFailure) {
                            // The owner loop is no longer able to accept work.
                        }
                    });
                } catch (Throwable retryFailure) {
                    // The owner loop is no longer able to accept work.
                }
            }
        }

        private void shutdownInput() {
            if (done) {
                return;
            }
            if (inputShutdown.compareAndSet(false, true)) {
                TlsConnection connection = tls;
                if (connection != null) {
                    // A direct-kTLS receiver can dynamically fall back to
                    // SSL_read, so synchronize OpenSSL before every SHUT_RD.
                    try {
                        connection.prepareInputShutdown();
                    } catch (Throwable failure) {
                        // Wake the parser after aborting TLS shutdown
                        // synchronization; SSL_shutdown requires successfully
                        // marked native receive state.
                        connection.abortTransportShutdown();
                    }
                }
                // SHUT_RD wakes the parser while preserving writes from
                // admitted handlers. The connection owner sends close_notify
                // after output drains.
                socketLifecycleLock.lock();
                try {
                    if (!done) {
                        shutdownSocket(SHUT_RD);
                    }
                } finally {
                    socketLifecycleLock.unlock();
                }
            }
        }

        private void forceClose() {
            if (done) {
                return;
            }
            if (!forceStarted.compareAndSet(false, true)) {
                return;
            }
            TlsConnection tlsConnection = tls;
            if (tlsConnection != null) {
                tlsConnection.abortTransportShutdown();
            }
            Object http1State = http1;
            if (http1State instanceof Http1ExchangeSequencer sequencer) {
                try {
                    loop.startVirtualThread(sequencer::cancelAll);
                } catch (Throwable ignored) {
                }
            } else if (http1State
                    instanceof Http1IsolatedStreamingState streaming) {
                streaming.cancel();
            }
            Http2Connection connection = http2;
            if (connection != null) {
                try {
                    loop.startVirtualThread(connection::forceClose);
                } catch (Throwable ignored) {
                }
            }
            if (socket.direct()) {
                startLoopVirtualThread(this::shutdownDirectAndWakeOwner);
                return;
            }
            socketLifecycleLock.lock();
            try {
                if (!done) {
                    CardiganServer.shutdownSocket(
                        socket.rawFd(), SHUT_RDWR);
                }
            } finally {
                socketLifecycleLock.unlock();
            }
            wakeOwner();
        }

        private void shutdownDirectAndWakeOwner() {
            try {
                socketLifecycleLock.lock();
                try {
                    if (!done) {
                        shutdownSocket(SHUT_RDWR);
                    }
                } finally {
                    socketLifecycleLock.unlock();
                }
            } finally {
                wakeOwner();
            }
        }

        private void wakeOwner() {
            Thread connectionOwner = owner;
            if (connectionOwner != null) {
                LockSupport.unpark(connectionOwner);
            }
        }

        private void closeSocketRegistration(int fixedSlot) {
            socketLifecycleLock.lock();
            try {
                if (done) {
                    return;
                }
                try {
                    if (socket.direct()) {
                        int result = loop.closeDirect(fixedSlot);
                        if (result != 0) {
                            throw new IllegalStateException(
                                "Closing direct socket slot " + fixedSlot
                                    + " failed with error " + result);
                        }
                    } else {
                        switch (fixedFilesMode) {
                            case LEGACY -> loop.unregisterFixedFdLegacy(
                                fixedSlot, socket.rawFd());
                            case ASYNC_EXPLICIT ->
                                loop.unregisterFixedFdAsyncExplicit(
                                    fixedSlot, socket.rawFd());
                            case AUTO, ASYNC_ALLOC -> loop.unregisterFixedFd(
                                fixedSlot, socket.rawFd());
                            case DIRECT -> throw new IllegalStateException(
                                "Direct mode retained a raw socket");
                        }
                    }
                    this.fixedSlot = -1;
                } finally {
                    done = true;
                }
            } finally {
                socketLifecycleLock.unlock();
            }
        }

        private void shutdownSocket(int how) {
            if (socket.direct()) {
                int slot = fixedSlot;
                if (slot >= 0) {
                    loop.shutdownFixed(slot, how);
                }
            } else {
                CardiganServer.shutdownSocket(socket.rawFd(), how);
            }
        }
    }

    private static final int SOL_SOCKET = 1;
    private static final int SO_REUSEADDR = 2;
    private static final int SO_SNDBUF = 7;
    private static final int SO_REUSEPORT = 15;
    private static final int SO_INCOMING_CPU = 49;
    private static final int IPPROTO_TCP = 6;
    private static final int TCP_NODELAY = 1;
    private static final int TCP_DEFER_ACCEPT = 9;

    private static void setSocketOptions(int fd, int cpuId) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment optVal = arena.allocate(ValueLayout.JAVA_INT);
            optVal.set(ValueLayout.JAVA_INT, 0, 1);

            int ret = (int) Libc.setsockopt.invokeExact(fd, SOL_SOCKET, SO_REUSEADDR, optVal, 4);
            if (ret < 0) {
                throw systemCallFailure("setsockopt SO_REUSEADDR");
            }

            ret = (int) Libc.setsockopt.invokeExact(fd, SOL_SOCKET, SO_REUSEPORT, optVal, 4);
            if (ret < 0) {
                throw systemCallFailure("setsockopt SO_REUSEPORT");
            }

            int configuredSendBuffer = Integer.getInteger("cardigan.socket.send.buffer", 0);
            if (configuredSendBuffer > 0) {
                optVal.set(ValueLayout.JAVA_INT, 0, configuredSendBuffer);
                ret = (int) Libc.setsockopt.invokeExact(fd, SOL_SOCKET, SO_SNDBUF, optVal, 4);
                if (ret < 0) {
                    throw systemCallFailure("setsockopt SO_SNDBUF");
                }
            }

            optVal.set(ValueLayout.JAVA_INT, 0, 1);
            ret = (int) Libc.setsockopt.invokeExact(fd, IPPROTO_TCP, TCP_NODELAY, optVal, 4);
            if (ret < 0) {
                throw systemCallFailure("setsockopt TCP_NODELAY");
            }

            optVal.set(ValueLayout.JAVA_INT, 0, 1);
            ret = (int) Libc.setsockopt.invokeExact(fd, IPPROTO_TCP, TCP_DEFER_ACCEPT, optVal, 4);
            if (ret < 0) {
                throw systemCallFailure("setsockopt TCP_DEFER_ACCEPT");
            }

            if (cpuId >= 0) {
                optVal.set(ValueLayout.JAVA_INT, 0, cpuId);
                ret = (int) Libc.setsockopt.invokeExact(fd, SOL_SOCKET, SO_INCOMING_CPU, optVal, 4);
                if (ret < 0) {
                    throw systemCallFailure(
                        "setsockopt SO_INCOMING_CPU for CPU " + cpuId);
                }
            }
        }
    }

    private static IllegalStateException systemCallFailure(
        String operation
    ) {
        return new IllegalStateException(
            operation + " failed with errno " + Libc.errno());
    }

    private static MemorySegment createSockAddrIn(Arena arena, int port) {
        MemorySegment segment = arena.allocate(16);
        segment.fill((byte) 0);
        segment.set(ValueLayout.JAVA_SHORT, 0, (short) 2);
        short sinPort = (short) (((port & 0xFF) << 8) | ((port >> 8) & 0xFF));
        segment.set(ValueLayout.JAVA_SHORT, 2, sinPort);
        segment.set(ValueLayout.JAVA_INT, 4, 0);
        return segment;
    }

}
