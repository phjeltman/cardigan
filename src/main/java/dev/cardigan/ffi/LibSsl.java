// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.ffi;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/** Direct Panama bindings to the small OpenSSL surface used by Cardigan. */
public final class LibSsl {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final java.lang.foreign.Arena OPENSSL_ARENA =
        java.lang.foreign.Arena.global();
    private static final SymbolLookup OPENSSL;
    private static final SymbolLookup LIBC = LINKER.defaultLookup();

    public static final MethodHandle tlsServerMethod;
    public static final MethodHandle contextNew;
    public static final MethodHandle contextFree;
    public static final MethodHandle contextCtrl;
    public static final MethodHandle contextSetOptions;
    public static final MethodHandle contextSetAlpnSelectCallback;
    public static final MethodHandle contextUseCertificateChainFile;
    public static final MethodHandle contextUsePrivateKeyFile;
    public static final MethodHandle contextCheckPrivateKey;

    public static final MethodHandle sslNew;
    public static final MethodHandle sslFree;
    public static final MethodHandle sslSetFd;
    public static final MethodHandle sslSetAcceptState;
    public static final MethodHandle sslSetMessageCallback;
    public static final MethodHandle sslCtrl;
    public static final MethodHandle sslAccept;
    public static final MethodHandle sslRead;
    public static final MethodHandle sslWrite;
    public static final MethodHandle sslGetError;
    public static final MethodHandle sslKeyUpdate;
    public static final MethodHandle sslDoHandshake;
    public static final MethodHandle sslShutdown;
    public static final MethodHandle sslGetSelectedAlpn;
    public static final MethodHandle sslGetWriteBio;
    public static final MethodHandle sslGetReadBio;
    public static final MethodHandle sslGetShutdown;
    public static final MethodHandle sslSetShutdown;

    public static final MethodHandle bioCtrl;
    public static final MethodHandle errorClear;
    public static final MethodHandle errorGet;
    public static final MethodHandle errorPeek;
    public static final MethodHandle errorString;
    public static final MethodHandle versionNumber;

    public static final MethodHandle fcntlGet;
    public static final MethodHandle fcntlSet;
    public static final MethodHandle errnoLocation;
    public static final MethodHandle strerror;

    static {
        OpenSslLibraries libraries = openOpenSsl();
        OPENSSL = name -> libraries.ssl().find(name).or(
            () -> libraries.crypto().find(name));
        try {
            tlsServerMethod = openssl(
                "TLS_server_method", FunctionDescriptor.of(ValueLayout.ADDRESS));
            contextNew = openssl(
                "SSL_CTX_new",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            contextFree = openssl(
                "SSL_CTX_free",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            contextCtrl = openssl(
                "SSL_CTX_ctrl",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS));
            contextSetOptions = openssl(
                "SSL_CTX_set_options",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG));
            contextSetAlpnSelectCallback = openssl(
                "SSL_CTX_set_alpn_select_cb",
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS));
            contextUseCertificateChainFile = openssl(
                "SSL_CTX_use_certificate_chain_file",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS));
            contextUsePrivateKeyFile = openssl(
                "SSL_CTX_use_PrivateKey_file",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT));
            contextCheckPrivateKey = openssl(
                "SSL_CTX_check_private_key",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

            sslNew = openssl(
                "SSL_new",
                FunctionDescriptor.of(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            sslFree = openssl(
                "SSL_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            sslSetFd = openssl(
                "SSL_set_fd",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT));
            sslSetAcceptState = openssl(
                "SSL_set_accept_state",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            sslSetMessageCallback = openssl(
                "SSL_set_msg_callback",
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            sslCtrl = openssl(
                "SSL_ctrl",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS));
            sslAccept = openssl(
                "SSL_accept",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            sslRead = openssl(
                "SSL_read_ex",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS));
            sslWrite = openssl(
                "SSL_write_ex",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS));
            sslGetError = openssl(
                "SSL_get_error",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT));
            sslKeyUpdate = openssl(
                "SSL_key_update",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT));
            sslDoHandshake = openssl(
                "SSL_do_handshake",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            sslShutdown = openssl(
                "SSL_shutdown",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            sslGetSelectedAlpn = openssl(
                "SSL_get0_alpn_selected",
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS));
            sslGetWriteBio = openssl(
                "SSL_get_wbio",
                FunctionDescriptor.of(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            sslGetReadBio = openssl(
                "SSL_get_rbio",
                FunctionDescriptor.of(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            sslGetShutdown = openssl(
                "SSL_get_shutdown",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            sslSetShutdown = openssl(
                "SSL_set_shutdown",
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            bioCtrl = openssl(
                "BIO_ctrl",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS));
            errorClear = opensslCritical(
                "ERR_clear_error", FunctionDescriptor.ofVoid());
            errorGet = openssl(
                "ERR_get_error", FunctionDescriptor.of(ValueLayout.JAVA_LONG));
            errorPeek = openssl(
                "ERR_peek_error", FunctionDescriptor.of(ValueLayout.JAVA_LONG));
            errorString = openssl(
                "ERR_error_string_n",
                FunctionDescriptor.ofVoid(
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG));
            versionNumber = openssl(
                "OpenSSL_version_num",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG));

            fcntlGet = libc(
                "fcntl",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT));
            fcntlSet = libc(
                "fcntl",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT));
            errnoLocation = libc(
                "__errno_location",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
            strerror = libc(
                "strerror",
                FunctionDescriptor.of(
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        } catch (RuntimeException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private LibSsl() {
    }

    public static Linker linker() {
        return LINKER;
    }

    private static OpenSslLibraries openOpenSsl() {
        String configured = System.getProperty(
            "cardigan.openssl.libraryDir", "").trim();
        if (!configured.isEmpty()) {
            Path directory = Path.of(configured);
            Path crypto = directory.resolve("libcrypto.so.3");
            Path ssl = directory.resolve("libssl.so.3");
            if (Files.isRegularFile(crypto) && Files.isRegularFile(ssl)) {
                SymbolLookup cryptoLookup = SymbolLookup.libraryLookup(
                    crypto.toAbsolutePath(), OPENSSL_ARENA);
                SymbolLookup sslLookup = SymbolLookup.libraryLookup(
                    ssl.toAbsolutePath(), OPENSSL_ARENA);
                return new OpenSslLibraries(sslLookup, cryptoLookup);
            }
        }
        SymbolLookup crypto = SymbolLookup.libraryLookup(
            "libcrypto.so.3", OPENSSL_ARENA);
        SymbolLookup ssl = SymbolLookup.libraryLookup(
            "libssl.so.3", OPENSSL_ARENA);
        return new OpenSslLibraries(ssl, crypto);
    }

    private record OpenSslLibraries(
            SymbolLookup ssl, SymbolLookup crypto) {
    }

    private static MethodHandle openssl(
            String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(
            OPENSSL.find(name).orElseThrow(
                () -> new LinkError("OpenSSL", name)),
            descriptor);
    }

    private static MethodHandle opensslCritical(
            String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(
            OPENSSL.find(name).orElseThrow(
                () -> new LinkError("OpenSSL", name)),
            descriptor,
            Linker.Option.critical(false));
    }

    private static MethodHandle libc(
            String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(
            LIBC.find(name).orElseThrow(() -> new LinkError("libc", name)),
            descriptor);
    }

    private static final class LinkError extends RuntimeException {
        private LinkError(String library, String name) {
            super("Failed to resolve " + library + " symbol: " + name);
        }
    }
}
