// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.tls;

import dev.cardigan.ffi.LibSsl;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** OpenSSL transport glue implemented directly with Panama downcalls. */
final class PanamaTls {
    static final int ERROR = -1;
    static final int WANT_READ = -2;
    static final int WANT_WRITE = -3;
    static final int CONTROL_PENDING = -4;
    static final int READ_CONTROL_FLAG = 1 << 30;

    private static final int COMPLETE = 1;
    private static final int CLOSED = 0;

    private static final int SSL_ERROR_SSL = 1;
    private static final int SSL_ERROR_WANT_READ = 2;
    private static final int SSL_ERROR_WANT_WRITE = 3;
    private static final int SSL_ERROR_SYSCALL = 5;
    private static final int SSL_ERROR_ZERO_RETURN = 6;
    private static final int SSL_CTRL_MODE = 33;
    private static final int SSL_CTRL_SET_MSG_CALLBACK_ARG = 16;
    private static final int SSL_CTRL_SET_MIN_PROTO_VERSION = 123;
    private static final int SSL_CTRL_SET_MAX_PROTO_VERSION = 124;
    private static final int BIO_CTRL_GET_KTLS_SEND = 73;
    private static final int BIO_CTRL_GET_KTLS_RECV = 76;
    private static final int SSL_FILETYPE_PEM = 1;
    private static final int SSL_RECEIVED_SHUTDOWN = 2;
    private static final int SSL3_RT_HANDSHAKE = 22;
    private static final int SSL3_MT_KEY_UPDATE = 24;
    private static final int SSL_KEY_UPDATE_NOT_REQUESTED = 0;
    private static final int SSL_KEY_UPDATE_REQUESTED = 1;
    private static final int TLS1_2_VERSION = 0x0303;
    private static final int TLS1_3_VERSION = 0x0304;

    private static final int F_GETFL = 3;
    private static final int F_SETFL = 4;
    private static final int O_NONBLOCK = 0x800;
    private static final int ECONNRESET = 104;
    private static final int EPIPE = 32;

    private static final int ERR_LIB_SSL = 20;
    private static final int SSL_R_UNEXPECTED_EOF_WHILE_READING = 294;
    private static final long ERR_LIB_MASK = 0xffL;
    private static final long ERR_REASON_MASK = 0x7fffffL;
    private static final int ERR_LIB_OFFSET = 23;

    private static final long SSL_OP_ENABLE_KTLS = 1L << 3;
    private static final long SSL_OP_NO_RENEGOTIATION = 1L << 30;
    private static final long SSL_MODE_ENABLE_PARTIAL_WRITE = 1L;
    private static final long SSL_MODE_ACCEPT_MOVING_WRITE_BUFFER = 2L;
    private static final long OPENSSL_TLS13_KTLS_REKEY_MIN = 0x30500000L;

    private static final int SSL_TLSEXT_ERR_OK = 0;
    private static final int SSL_TLSEXT_ERR_ALERT_FATAL = 2;
    private static final int SSL_TLSEXT_ERR_NOACK = 3;

    private static final int SCRATCH_SIZE = 536;
    private static final int ERROR_OFFSET = 16;
    private static final int ERROR_SIZE = 512;
    private static final int KEY_UPDATE_OFFSET = ERROR_OFFSET + ERROR_SIZE;
    private static final Pattern KERNEL_VERSION =
        Pattern.compile("^(\\d+)\\.(\\d+)");
    private static final Arena CALLBACK_ARENA = Arena.global();
    private static final MemorySegment HTTP_ALPN_CALLBACK;
    private static final MemorySegment HTTP1_ALPN_CALLBACK;
    private static final MemorySegment HTTP2_ALPN_CALLBACK;
    private static final MemorySegment KEY_UPDATE_CALLBACK;
    private static final long OPENSSL_VERSION;

    static {
        try {
            long version = (long) LibSsl.versionNumber.invokeExact();
            if ((version >>> 28) < 3) {
                throw new LinkageError(
                    "Cardigan requires OpenSSL 3.x, found version 0x"
                        + Long.toHexString(version));
            }
            OPENSSL_VERSION = version;
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            FunctionDescriptor descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS
            );
            MethodType type = MethodType.methodType(
                int.class,
                MemorySegment.class,
                MemorySegment.class,
                MemorySegment.class,
                MemorySegment.class,
                int.class,
                MemorySegment.class
            );
            MethodHandle http = lookup.findStatic(
                PanamaTls.class, "selectHttpAlpn", type);
            MethodHandle http2 = lookup.findStatic(
                PanamaTls.class, "selectHttp2Alpn", type);
            MethodHandle http1 = lookup.findStatic(
                PanamaTls.class, "selectHttp1Alpn", type);
            HTTP_ALPN_CALLBACK = LibSsl.linker().upcallStub(
                http, descriptor, CALLBACK_ARENA);
            HTTP1_ALPN_CALLBACK = LibSsl.linker().upcallStub(
                http1, descriptor, CALLBACK_ARENA);
            HTTP2_ALPN_CALLBACK = LibSsl.linker().upcallStub(
                http2, descriptor, CALLBACK_ARENA);

            FunctionDescriptor messageDescriptor = FunctionDescriptor.ofVoid(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS
            );
            MethodType messageType = MethodType.methodType(
                void.class,
                int.class,
                int.class,
                int.class,
                MemorySegment.class,
                long.class,
                MemorySegment.class,
                MemorySegment.class
            );
            MethodHandle message = lookup.findStatic(
                PanamaTls.class, "observeTlsMessage", messageType);
            KEY_UPDATE_CALLBACK = LibSsl.linker().upcallStub(
                message, messageDescriptor, CALLBACK_ARENA);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private PanamaTls() {
    }

    static final class Context implements AutoCloseable {
        private final boolean tls13;
        private MemorySegment sslContext;

        private Context(MemorySegment sslContext, boolean tls13) {
            this.sslContext = sslContext;
            this.tls13 = tls13;
        }

        static Context create(
                TlsConfig config, boolean http2Only, boolean http1Only) {
            clearErrors();
            MemorySegment context = MemorySegment.NULL;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment method = (MemorySegment)
                    LibSsl.tlsServerMethod.invokeExact();
                context = (MemorySegment) LibSsl.contextNew.invokeExact(method);
                if (isNull(context)) {
                    throw failure("SSL_CTX_new", null);
                }

                int version = config.tls12Only()
                    ? TLS1_2_VERSION : TLS1_3_VERSION;
                if ((long) LibSsl.contextCtrl.invokeExact(
                        context,
                        SSL_CTRL_SET_MIN_PROTO_VERSION,
                        (long) version,
                        MemorySegment.NULL) != 1L) {
                    throw failure("setting minimum TLS version", null);
                }
                if ((long) LibSsl.contextCtrl.invokeExact(
                        context,
                        SSL_CTRL_SET_MAX_PROTO_VERSION,
                        (long) version,
                        MemorySegment.NULL) != 1L) {
                    throw failure("setting maximum TLS version", null);
                }

                long options = SSL_OP_NO_RENEGOTIATION;
                boolean ktls = Boolean.parseBoolean(System.getProperty(
                    TlsConfig.KTLS_PROPERTY, "true"));
                if (ktls && (config.tls12Only()
                        || (supportsTls13KtlsRekey(OPENSSL_VERSION)
                            && kernelSupportsRekey()))) {
                    options |= SSL_OP_ENABLE_KTLS;
                }
                long ignoredOptions = (long)
                    LibSsl.contextSetOptions.invokeExact(context, options);
                long ignoredMode = (long) LibSsl.contextCtrl.invokeExact(
                    context,
                    SSL_CTRL_MODE,
                    SSL_MODE_ENABLE_PARTIAL_WRITE
                        | SSL_MODE_ACCEPT_MOVING_WRITE_BUFFER,
                    MemorySegment.NULL);

                MemorySegment alpnCallback = http2Only
                    ? HTTP2_ALPN_CALLBACK
                    : (http1Only
                        ? HTTP1_ALPN_CALLBACK : HTTP_ALPN_CALLBACK);
                LibSsl.contextSetAlpnSelectCallback.invokeExact(
                    context, alpnCallback, MemorySegment.NULL);

                MemorySegment certificate = arena.allocateFrom(
                    config.certificateChain().toString());
                if ((int) LibSsl.contextUseCertificateChainFile.invokeExact(
                        context, certificate) != 1) {
                    throw failure("loading certificate chain", null);
                }
                MemorySegment privateKey = arena.allocateFrom(
                    config.privateKey().toString());
                if ((int) LibSsl.contextUsePrivateKeyFile.invokeExact(
                        context, privateKey, SSL_FILETYPE_PEM) != 1) {
                    throw failure("loading private key", null);
                }
                if ((int) LibSsl.contextCheckPrivateKey.invokeExact(context)
                        != 1) {
                    throw failure("checking private key", null);
                }
                Context result = new Context(context, !config.tls12Only());
                context = MemorySegment.NULL;
                return result;
            } catch (TlsException e) {
                throw e;
            } catch (Throwable t) {
                throw failure("initializing OpenSSL", t);
            } finally {
                if (!isNull(context)) {
                    freeContext(context);
                }
            }
        }

        Connection createConnection(int fd) {
            MemorySegment current = sslContext;
            if (isNull(current)) {
                throw new IllegalStateException("TLS context is closed");
            }
            return Connection.create(current, tls13, fd);
        }

        @Override
        public void close() {
            MemorySegment current = sslContext;
            sslContext = MemorySegment.NULL;
            if (!isNull(current)) {
                freeContext(current);
            }
        }
    }

    static final class Connection implements AutoCloseable {
        private final int fd;
        private final int originalFlags;
        private final Arena arena;
        private final MemorySegment scratch;
        private final boolean tls13;
        private MemorySegment ssl;
        private String lastError = "OpenSSL operation failed";
        private boolean trackKeyUpdates;
        private boolean reciprocalKeyUpdatePending;
        private boolean reciprocalKeyUpdateStarted;

        private Connection(int fd, int originalFlags, Arena arena,
                           MemorySegment scratch, MemorySegment ssl,
                           boolean tls13) {
            this.fd = fd;
            this.originalFlags = originalFlags;
            this.arena = arena;
            this.scratch = scratch;
            this.ssl = ssl;
            this.tls13 = tls13;
        }

        static Connection create(
                MemorySegment context, boolean tls13, int fd) {
            if (fd < 0) {
                throw new TlsException("Invalid TLS connection descriptor");
            }
            clearErrors();
            int originalFlags = -1;
            MemorySegment ssl = MemorySegment.NULL;
            Arena arena = Arena.ofShared();
            boolean transferred = false;
            try {
                originalFlags = (int) LibSsl.fcntlGet.invokeExact(fd, F_GETFL);
                if (originalFlags < 0
                        || (int) LibSsl.fcntlSet.invokeExact(
                            fd, F_SETFL, originalFlags | O_NONBLOCK) < 0) {
                    throw failure("making TLS socket nonblocking", null);
                }
                ssl = (MemorySegment) LibSsl.sslNew.invokeExact(context);
                if (isNull(ssl)) {
                    throw failure("SSL_new", null);
                }
                if ((int) LibSsl.sslSetFd.invokeExact(ssl, fd) != 1) {
                    throw failure("SSL_set_fd", null);
                }
                LibSsl.sslSetAcceptState.invokeExact(ssl);
                Connection connection = new Connection(
                    fd,
                    originalFlags,
                    arena,
                    arena.allocate(SCRATCH_SIZE, 8),
                    ssl,
                    tls13
                );
                transferred = true;
                return connection;
            } catch (TlsException e) {
                throw e;
            } catch (Throwable t) {
                throw failure("creating TLS connection", t);
            } finally {
                if (!transferred) {
                    if (!isNull(ssl)) {
                        freeSsl(ssl);
                    }
                    restoreFlags(fd, originalFlags);
                    arena.close();
                }
            }
        }

        int handshake() {
            try {
                clearErrors();
                int result = (int) LibSsl.sslAccept.invokeExact(requireSsl());
                return result == 1
                    ? COMPLETE : status(result, "TLS handshake");
            } catch (TlsException e) {
                throw e;
            } catch (Throwable t) {
                throw new TlsException("TLS handshake failed", t);
            }
        }

        int read(MemorySegment destination, long length) {
            try {
                MemorySegment current = requireSsl();
                clearErrors();
                int result = (int) LibSsl.sslRead.invokeExact(
                    current, destination, length, scratch);
                if (result == 1) {
                    int count = boundedCount(
                        scratch.get(ValueLayout.JAVA_LONG, 0));
                    if (keyUpdatePending()) {
                        return count | READ_CONTROL_FLAG;
                    }
                    return count;
                }
                int status = status(result, "TLS read");
                if ((status == WANT_READ || status == WANT_WRITE)
                        && keyUpdatePending()) {
                    return CONTROL_PENDING;
                }
                return status;
            } catch (TlsException e) {
                throw e;
            } catch (Throwable t) {
                throw new TlsException("TLS read failed", t);
            }
        }

        int write(MemorySegment source, long length) {
            try {
                clearErrors();
                int result = (int) LibSsl.sslWrite.invokeExact(
                    requireSsl(), source, length, scratch);
                if (result == 1) {
                    return boundedCount(scratch.get(ValueLayout.JAVA_LONG, 0));
                }
                return status(result, "TLS write");
            } catch (TlsException e) {
                throw e;
            } catch (Throwable t) {
                throw new TlsException("TLS write failed", t);
            }
        }

        int flushControl() {
            if (!reciprocalKeyUpdatePending) {
                return COMPLETE;
            }
            try {
                MemorySegment current = requireSsl();
                if (!reciprocalKeyUpdateStarted) {
                    clearErrors();
                    if ((int) LibSsl.sslKeyUpdate.invokeExact(
                            current, SSL_KEY_UPDATE_NOT_REQUESTED) != 1) {
                        lastError = PanamaTls.errorMessage(
                            "scheduling reciprocal TLS KeyUpdate",
                            scratch.asSlice(ERROR_OFFSET, ERROR_SIZE));
                        return ERROR;
                    }
                    reciprocalKeyUpdateStarted = true;
                }
                clearErrors();
                int result = (int)
                    LibSsl.sslDoHandshake.invokeExact(current);
                if (result == 1) {
                    reciprocalKeyUpdatePending = false;
                    reciprocalKeyUpdateStarted = false;
                    scratch.set(
                        ValueLayout.JAVA_INT, KEY_UPDATE_OFFSET, 0);
                    return COMPLETE;
                }
                return status(result, "flushing TLS KeyUpdate");
            } catch (TlsException e) {
                throw e;
            } catch (Throwable t) {
                throw new TlsException("TLS KeyUpdate failed", t);
            }
        }

        int shutdown() {
            try {
                clearErrors();
                int result = (int)
                    LibSsl.sslShutdown.invokeExact(requireSsl());
                if (result >= 0) {
                    return result == 1 ? COMPLETE : CLOSED;
                }
                return status(result, "TLS shutdown");
            } catch (TlsException e) {
                throw e;
            } catch (Throwable t) {
                throw new TlsException("TLS shutdown failed", t);
            }
        }

        int selectedProtocol() {
            try {
                LibSsl.sslGetSelectedAlpn.invokeExact(
                    requireSsl(), scratch, scratch.asSlice(8));
                int length = scratch.get(ValueLayout.JAVA_INT, 8);
                MemorySegment protocol = scratch.get(ValueLayout.ADDRESS, 0);
                if (length == 2 && !isNull(protocol)) {
                    MemorySegment bytes = protocol.reinterpret(2);
                    if (bytes.get(ValueLayout.JAVA_BYTE, 0) == 'h'
                            && bytes.get(ValueLayout.JAVA_BYTE, 1) == '2') {
                        return TlsConnection.HTTP2;
                    }
                }
                return TlsConnection.HTTP1;
            } catch (Throwable t) {
                throw new TlsException("Reading negotiated ALPN failed", t);
            }
        }

        boolean ktlsSend() {
            try {
                MemorySegment bio = (MemorySegment)
                    LibSsl.sslGetWriteBio.invokeExact(requireSsl());
                return !isNull(bio)
                    && (long) LibSsl.bioCtrl.invokeExact(
                        bio,
                        BIO_CTRL_GET_KTLS_SEND,
                        0L,
                        MemorySegment.NULL) > 0;
            } catch (Throwable t) {
                throw new TlsException("Reading kTLS TX state failed", t);
            }
        }

        boolean ktlsRecv() {
            try {
                MemorySegment bio = (MemorySegment)
                    LibSsl.sslGetReadBio.invokeExact(requireSsl());
                return !isNull(bio)
                    && (long) LibSsl.bioCtrl.invokeExact(
                        bio,
                        BIO_CTRL_GET_KTLS_RECV,
                        0L,
                        MemorySegment.NULL) > 0;
            } catch (Throwable t) {
                throw new TlsException("Reading kTLS RX state failed", t);
            }
        }

        void trackKeyUpdates(boolean enabled) {
            boolean track = enabled && tls13;
            trackKeyUpdates = track;
            if (!track) {
                return;
            }
            try {
                MemorySegment current = requireSsl();
                MemorySegment flag = scratch.asSlice(
                    KEY_UPDATE_OFFSET, ValueLayout.JAVA_INT.byteSize());
                flag.set(ValueLayout.JAVA_INT, 0, 0);
                LibSsl.sslSetMessageCallback.invokeExact(
                    current, KEY_UPDATE_CALLBACK);
                long ignored = (long) LibSsl.sslCtrl.invokeExact(
                    current,
                    SSL_CTRL_SET_MSG_CALLBACK_ARG,
                    0L,
                    flag);
            } catch (Throwable t) {
                throw new TlsException(
                    "Installing TLS KeyUpdate callback failed", t);
            }
        }

        int markReceivedShutdown() {
            try {
                MemorySegment current = requireSsl();
                int shutdown = (int)
                    LibSsl.sslGetShutdown.invokeExact(current);
                LibSsl.sslSetShutdown.invokeExact(
                    current, shutdown | SSL_RECEIVED_SHUTDOWN);
                return COMPLETE;
            } catch (Throwable t) {
                lastError = "Synchronizing peer TLS shutdown failed: "
                    + t.getMessage();
                return ERROR;
            }
        }

        String errorMessage() {
            return lastError;
        }

        private boolean keyUpdatePending() {
            if (!trackKeyUpdates) {
                return false;
            }
            int requested = scratch.get(
                ValueLayout.JAVA_INT, KEY_UPDATE_OFFSET);
            if (requested == 0) {
                return false;
            }
            reciprocalKeyUpdatePending = true;
            return true;
        }

        private int status(int result, String operation) throws Throwable {
            MemorySegment current = requireSsl();
            int error = (int)
                LibSsl.sslGetError.invokeExact(current, result);
            if (error == SSL_ERROR_WANT_READ) {
                return WANT_READ;
            }
            if (error == SSL_ERROR_WANT_WRITE) {
                return WANT_WRITE;
            }
            if (error == SSL_ERROR_ZERO_RETURN) {
                return CLOSED;
            }
            if (error == SSL_ERROR_SYSCALL) {
                int errno = errno();
                if (result == 0 || errno == ECONNRESET || errno == EPIPE) {
                    return CLOSED;
                }
            }
            if (error == SSL_ERROR_SSL) {
                long opensslError = (long)
                    LibSsl.errorPeek.invokeExact();
                int library = (int)
                    ((opensslError >>> ERR_LIB_OFFSET) & ERR_LIB_MASK);
                int reason = (int) (opensslError & ERR_REASON_MASK);
                if (library == ERR_LIB_SSL
                        && reason == SSL_R_UNEXPECTED_EOF_WHILE_READING) {
                    LibSsl.errorClear.invokeExact();
                    return CLOSED;
                }
            }
            lastError = PanamaTls.errorMessage(
                operation, scratch.asSlice(ERROR_OFFSET, ERROR_SIZE));
            return ERROR;
        }

        private MemorySegment requireSsl() {
            MemorySegment current = ssl;
            if (isNull(current)) {
                throw new IllegalStateException("TLS connection is closed");
            }
            return current;
        }

        @Override
        public void close() {
            MemorySegment current = ssl;
            ssl = MemorySegment.NULL;
            if (!isNull(current)) {
                freeSsl(current);
            }
            restoreFlags(fd, originalFlags);
            arena.close();
        }
    }

    private static int selectHttpAlpn(
            MemorySegment ssl, MemorySegment output,
            MemorySegment outputLength, MemorySegment input,
            int inputLength, MemorySegment argument) {
        return selectAlpn(
            output, outputLength, input, inputLength, false, false);
    }

    private static int selectHttp2Alpn(
            MemorySegment ssl, MemorySegment output,
            MemorySegment outputLength, MemorySegment input,
            int inputLength, MemorySegment argument) {
        return selectAlpn(
            output, outputLength, input, inputLength, true, false);
    }

    private static int selectHttp1Alpn(
            MemorySegment ssl, MemorySegment output,
            MemorySegment outputLength, MemorySegment input,
            int inputLength, MemorySegment argument) {
        return selectAlpn(
            output, outputLength, input, inputLength, false, true);
    }

    private static void observeTlsMessage(
            int write, int version, int contentType,
            MemorySegment buffer, long length, MemorySegment ssl,
            MemorySegment argument) {
        if (write != 0 || contentType != SSL3_RT_HANDSHAKE || length < 5
                || isNull(argument) || isNull(buffer)) {
            return;
        }
        try {
            MemorySegment message = buffer.reinterpret(5);
            if ((message.get(ValueLayout.JAVA_BYTE, 0) & 0xff)
                    == SSL3_MT_KEY_UPDATE
                    && (message.get(ValueLayout.JAVA_BYTE, 4) & 0xff)
                        == SSL_KEY_UPDATE_REQUESTED) {
                argument.reinterpret(ValueLayout.JAVA_INT.byteSize()).set(
                    ValueLayout.JAVA_INT, 0, 1);
            }
        } catch (Throwable ignored) {
            // OpenSSL independently validates malformed protocol messages.
        }
    }

    private static int selectAlpn(
            MemorySegment output, MemorySegment outputLength,
            MemorySegment input, int inputLength,
            boolean http2Only, boolean http1Only) {
        try {
            MemorySegment protocols = input.reinterpret(inputLength);
            if (!http1Only) {
                int h2 = findProtocol(protocols, inputLength, "h2");
                if (h2 >= 0) {
                    selectProtocol(output, outputLength, protocols, h2, 2);
                    return SSL_TLSEXT_ERR_OK;
                }
            }
            if (!http2Only) {
                int http11 = findProtocol(
                    protocols, inputLength, "http/1.1");
                if (http11 >= 0) {
                    selectProtocol(
                        output, outputLength, protocols, http11, 8);
                    return SSL_TLSEXT_ERR_OK;
                }
            }
            return http2Only || http1Only
                ? SSL_TLSEXT_ERR_ALERT_FATAL : SSL_TLSEXT_ERR_NOACK;
        } catch (Throwable ignored) {
            return SSL_TLSEXT_ERR_ALERT_FATAL;
        }
    }

    private static int findProtocol(
            MemorySegment input, int inputLength, String expected) {
        int offset = 0;
        while (offset < inputLength) {
            int length = input.get(ValueLayout.JAVA_BYTE, offset++) & 0xff;
            if (length == 0 || length > inputLength - offset) {
                return -1;
            }
            if (length == expected.length()) {
                boolean matches = true;
                for (int i = 0; i < length; i++) {
                    if ((input.get(ValueLayout.JAVA_BYTE, offset + i) & 0xff)
                            != expected.charAt(i)) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    return offset;
                }
            }
            offset += length;
        }
        return -1;
    }

    private static void selectProtocol(
            MemorySegment output, MemorySegment outputLength,
            MemorySegment input, int offset, int length) {
        output.reinterpret(ValueLayout.ADDRESS.byteSize()).set(
            ValueLayout.ADDRESS, 0, input.asSlice(offset, length));
        outputLength.reinterpret(1).set(
            ValueLayout.JAVA_BYTE, 0, (byte) length);
    }

    private static boolean kernelSupportsRekey() {
        try {
            String statistics = Files.readString(Path.of("/proc/net/tls_stat"));
            if (statistics.contains("TlsRxRekeyOk")
                    && statistics.contains("TlsTxRekeyOk")) {
                return true;
            }
        } catch (IOException ignored) {
            // The module may not have been loaded yet.
        }
        Matcher version = KERNEL_VERSION.matcher(
            System.getProperty("os.version", ""));
        if (!version.find()) {
            return false;
        }
        int major = Integer.parseInt(version.group(1));
        int minor = Integer.parseInt(version.group(2));
        return major > 6 || (major == 6 && minor >= 14);
    }

    static boolean supportsTls13KtlsRekey(long opensslVersion) {
        return opensslVersion >= OPENSSL_TLS13_KTLS_REKEY_MIN;
    }

    private static int boundedCount(long count) {
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    /**
     * OpenSSL's error queue belongs to the current native thread, not the
     * virtual thread or SSL object. SSL_get_error requires it to be empty
     * before the corresponding SSL operation, so every retry must clear the
     * carrier's queue after a virtual thread may have migrated.
     */
    private static void clearErrors() {
        try {
            LibSsl.errorClear.invokeExact();
        } catch (Throwable t) {
            throw new TlsException("Failed to clear OpenSSL error state", t);
        }
    }

    private static TlsException failure(String operation, Throwable cause) {
        String message;
        try (Arena arena = Arena.ofConfined()) {
            message = errorMessage(
                operation, arena.allocate(ERROR_SIZE, 8));
        }
        return cause == null
            ? new TlsException(message)
            : new TlsException(message, cause);
    }

    private static String errorMessage(
            String operation, MemorySegment buffer) {
        try {
            long error = (long) LibSsl.errorGet.invokeExact();
            if (error != 0) {
                LibSsl.errorString.invokeExact(
                    error, buffer, (long) buffer.byteSize());
                String detail = buffer.getString(0);
                while ((long) LibSsl.errorGet.invokeExact() != 0) {
                    // Preserve the first and most relevant OpenSSL error.
                }
                return operation + ": " + detail;
            }
            int errno = errno();
            if (errno != 0) {
                MemorySegment detail = (MemorySegment)
                    LibSsl.strerror.invokeExact(errno);
                if (!isNull(detail)) {
                    return operation + ": "
                        + detail.reinterpret(256).getString(0);
                }
                return operation + ": errno " + errno;
            }
            return operation + " failed";
        } catch (Throwable t) {
            return operation + " failed: " + t.getMessage();
        }
    }

    private static int errno() {
        try {
            MemorySegment location = (MemorySegment)
                LibSsl.errnoLocation.invokeExact();
            return location.reinterpret(ValueLayout.JAVA_INT.byteSize()).get(
                ValueLayout.JAVA_INT, 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void restoreFlags(int fd, int originalFlags) {
        if (fd < 0 || originalFlags < 0) {
            return;
        }
        try {
            int ignored = (int)
                LibSsl.fcntlSet.invokeExact(fd, F_SETFL, originalFlags);
        } catch (Throwable ignored) {
        }
    }

    private static void freeContext(MemorySegment context) {
        try {
            LibSsl.contextFree.invokeExact(context);
        } catch (Throwable ignored) {
        }
    }

    private static void freeSsl(MemorySegment ssl) {
        try {
            LibSsl.sslFree.invokeExact(ssl);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == null || segment.address() == 0;
    }
}
