// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sidecar;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import javax.net.ssl.SSLContext;

/**
 * Start-up settings of a sidecar, read from environment variables that share one prefix (for example
 * {@code SANGAM_PDF_VALIDATOR}). Every value is checked; an invalid one stops the start-up with a message instead of
 * falling back. Names after the prefix:
 * <ul>
 *   <li>{@code _BIND} (default {@code 0.0.0.0}; keep the port on the private network only), {@code _PORT};</li>
 *   <li>{@code _THREADS}: jobs at once (default the number of processors), {@code _MAX_BYTES}: largest request,
 *       {@code _PROCESSING_TIMEOUT_MS}: longest job;</li>
 *   <li>{@code _TRANSPORT}: {@code tls} (default) needs {@code _TLS_KEYSTORE} (PKCS#12) and
 *       {@code _TLS_KEYSTORE_PASSWORD_FILE}; {@code mesh} serves plain HTTP behind an authenticated mesh and takes no
 *       key store. TLS without a key store refuses to start (owner decision 153);</li>
 *   <li>{@code _TOKEN_FILE}: the mounted file of accepted caller tokens. Without it the sidecar starts but stays not
 *       ready and refuses every call; there is no unauthenticated mode.</li>
 * </ul>
 */
public record SidecarSettings(String prefix, String bind, int port, int threads, long maxBytes,
                              Duration processingTimeout, SidecarTransport transport, Path tokenFile, Path keyStore,
                              Path keyStorePasswordFile) {

    public static final String BIND = "_BIND";
    public static final String PORT = "_PORT";
    public static final String THREADS = "_THREADS";
    public static final String MAX_BYTES = "_MAX_BYTES";
    public static final String PROCESSING_TIMEOUT_MS = "_PROCESSING_TIMEOUT_MS";
    public static final String TRANSPORT = "_TRANSPORT";
    public static final String TOKEN_FILE = "_TOKEN_FILE";
    public static final String TLS_KEYSTORE = "_TLS_KEYSTORE";
    public static final String TLS_KEYSTORE_PASSWORD_FILE = "_TLS_KEYSTORE_PASSWORD_FILE";

    /**
     * @param prefix the variable prefix, without the trailing underscore
     */
    public static SidecarSettings from(String prefix, Map<String, String> env, int defaultPort, long defaultMaxBytes,
                                       Duration defaultProcessingTimeout) {
        String bind = env.getOrDefault(prefix + BIND, "0.0.0.0").strip();
        if (bind.isEmpty()) {
            throw new IllegalArgumentException(prefix + BIND + " is blank");
        }
        long port = number(env, prefix + PORT, defaultPort);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(prefix + PORT + " must be 1..65535");
        }
        long threads = number(env, prefix + THREADS, Runtime.getRuntime().availableProcessors());
        if (threads < 1 || threads > 1_024) {
            throw new IllegalArgumentException(prefix + THREADS + " must be 1..1024");
        }
        long max = number(env, prefix + MAX_BYTES, defaultMaxBytes);
        if (max < 1 || max > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException(prefix + MAX_BYTES + " must be a positive size below 2 GiB");
        }
        long timeout = number(env, prefix + PROCESSING_TIMEOUT_MS, defaultProcessingTimeout.toMillis());
        if (timeout < 1) {
            throw new IllegalArgumentException(prefix + PROCESSING_TIMEOUT_MS + " must be a positive number");
        }
        SidecarTransport transport;
        try {
            transport = SidecarTransport.parse(env.get(prefix + TRANSPORT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(prefix + TRANSPORT + " must be tls or mesh");
        }
        Path keyStore = path(env, prefix + TLS_KEYSTORE);
        Path password = path(env, prefix + TLS_KEYSTORE_PASSWORD_FILE);
        if (transport == SidecarTransport.TLS && (keyStore == null || password == null)) {
            throw new IllegalArgumentException("transport tls needs " + prefix + TLS_KEYSTORE + " and " + prefix
                    + TLS_KEYSTORE_PASSWORD_FILE + " (owner decision 153); set " + prefix + TRANSPORT
                    + "=mesh only behind an authenticated mesh");
        }
        if (transport == SidecarTransport.MESH && (keyStore != null || password != null)) {
            throw new IllegalArgumentException("transport mesh serves plain HTTP; remove " + prefix + TLS_KEYSTORE
                    + " or use transport tls");
        }
        return new SidecarSettings(prefix, bind, (int) port, (int) threads, max, Duration.ofMillis(timeout), transport,
                path(env, prefix + TOKEN_FILE), keyStore, password);
    }

    /** The caller check: the token file, or none (never ready). */
    public SidecarAuthenticator authenticator() {
        return tokenFile == null ? SidecarAuthenticator.none(prefix + TOKEN_FILE) : SidecarAuthenticator.tokenFile(tokenFile);
    }

    public SidecarServer.Limits limits() {
        return new SidecarServer.Limits(threads, maxBytes, processingTimeout);
    }

    /** Binds the server (TLS from the key store, or plain HTTP for mesh); routes are added before start. */
    public SidecarServer open() throws IOException {
        SSLContext tls = transport == SidecarTransport.TLS ? SidecarServer.tls(keyStore, keyStorePasswordFile) : null;
        return new SidecarServer(new InetSocketAddress(bind, port), transport, tls, authenticator(), limits());
    }

    private static Path path(Map<String, String> env, String key) {
        String v = env.get(key);
        return v == null || v.isBlank() ? null : Path.of(v.strip());
    }

    private static long number(Map<String, String> env, String key, long fallback) {
        String v = env.get(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(v.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " is not a number");
        }
    }
}
