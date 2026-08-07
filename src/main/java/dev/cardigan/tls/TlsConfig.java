// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.tls;

import java.nio.file.Path;
import java.util.Objects;

/** Certificate material and transport options for an OpenSSL TLS context. */
public record TlsConfig(
        Path certificateChain,
        Path privateKey,
        boolean tls12Only,
        boolean directKtlsReceive) {

    public static final String CERTIFICATE_PROPERTY =
        "cardigan.tls.certificate";
    public static final String PRIVATE_KEY_PROPERTY =
        "cardigan.tls.privateKey";
    public static final String VERSION_PROPERTY =
        "cardigan.tls.version";
    public static final String DIRECT_KTLS_RECEIVE_PROPERTY =
        "cardigan.tls.directKtlsReceive";
    public static final String DIRECT_KTLS_SEND_PROPERTY =
        "cardigan.tls.directKtlsSend";
    public static final String KTLS_PROPERTY = "cardigan.tls.ktls";
    public TlsConfig(Path certificateChain, Path privateKey) {
        this(certificateChain, privateKey, false, false);
    }

    public TlsConfig {
        certificateChain = Objects.requireNonNull(
            certificateChain, "certificateChain").toAbsolutePath();
        privateKey = Objects.requireNonNull(
            privateKey, "privateKey").toAbsolutePath();
        if (directKtlsReceive && !tls12Only) {
            throw new IllegalArgumentException(
                "Direct kTLS receive currently requires TLS 1.2");
        }
    }

    /**
     * Returns the system-property configuration, or {@code null} when TLS is
     * not configured. Supplying only one path is treated as a configuration
     * error rather than silently starting a plaintext listener.
     */
    public static TlsConfig fromSystemProperties() {
        String certificate = System.getProperty(CERTIFICATE_PROPERTY, "").trim();
        String key = System.getProperty(PRIVATE_KEY_PROPERTY, "").trim();
        if (certificate.isEmpty() && key.isEmpty()) {
            return null;
        }
        if (certificate.isEmpty() || key.isEmpty()) {
            throw new IllegalArgumentException(
                "Both " + CERTIFICATE_PROPERTY + " and "
                    + PRIVATE_KEY_PROPERTY + " must be configured");
        }
        String version = System.getProperty(VERSION_PROPERTY, "").trim();
        boolean tls12Only;
        if (version.isEmpty()
                || version.equalsIgnoreCase("default")
                || version.equals("1.3")
                || version.equalsIgnoreCase("TLSv1.3")) {
            tls12Only = false;
        } else if (version.equals("1.2")
                || version.equalsIgnoreCase("TLSv1.2")) {
            tls12Only = true;
        } else {
            throw new IllegalArgumentException(
                "Unsupported " + VERSION_PROPERTY + ": " + version);
        }
        boolean directReceive = Boolean.getBoolean(
            DIRECT_KTLS_RECEIVE_PROPERTY);
        return new TlsConfig(
            Path.of(certificate),
            Path.of(key),
            tls12Only,
            directReceive
        );
    }
}
