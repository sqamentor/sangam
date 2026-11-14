// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sidecar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * The HTTP server shared by the Sangam sidecars (PDF validator, OCR; owner decisions 114, 153, 167), on the JDK server
 * with no framework. It enforces, in this order, for every request except {@code GET /health}: the exact path, the
 * method, then the caller's credentials ({@code 401}, before any body byte is read), then readiness ({@code 503} while
 * the credentials are missing or unusable). Work runs in a bounded pool: at most {@code concurrency} jobs at once
 * ({@code 503} busy beyond that) and at most {@code processingTimeout} each ({@code 503} on timeout). Errors are
 * {@code {"error": ...}} bodies with no internal detail and no secret; findings belong to the success answer only.
 *
 * <p>{@code GET /health} is unauthenticated for container probes and answers only {@code {"status":"UP"}} (200) or
 * {@code {"status":"NOT_READY"}} (503).
 */
public final class SidecarServer implements AutoCloseable {

    public static final String HEALTH_PATH = "/health";

    /** Work bounds of one sidecar. */
    public record Limits(int concurrency, long maxBytes, Duration processingTimeout) {
        public Limits {
            if (concurrency < 1 || maxBytes < 1 || maxBytes > Integer.MAX_VALUE - 8 || processingTimeout == null
                    || processingTimeout.isZero() || processingTimeout.isNegative()) {
                throw new IllegalArgumentException("concurrency, maxBytes (below 2 GiB) and processingTimeout must be"
                        + " positive");
            }
        }
    }

    /** A handler that runs after the path, method and credentials were accepted. */
    @FunctionalInterface
    public interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** All work slots are taken. */
    public static final class Busy extends Exception {
        Busy() {
            super("busy");
        }
    }

    /** The work took longer than the processing timeout; it was cancelled. */
    public static final class TimedOut extends Exception {
        TimedOut() {
            super("timed out");
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final SidecarAuthenticator authenticator;
    private final Limits limits;
    private final ExecutorService handlers;
    private final ThreadPoolExecutor work;

    /**
     * Binds the server; call {@link #start()} after registering the routes.
     *
     * @param tls the server TLS context, or {@code null} only for {@link SidecarTransport#MESH}
     */
    public SidecarServer(InetSocketAddress address, SidecarTransport transport, SSLContext tls,
                         SidecarAuthenticator authenticator, Limits limits) throws IOException {
        if (authenticator == null || limits == null || transport == null) {
            throw new IllegalArgumentException("transport, authenticator and limits are required");
        }
        if (transport == SidecarTransport.TLS && tls == null) {
            throw new IllegalArgumentException("transport tls needs a server TLS context (owner decision 153)");
        }
        this.authenticator = authenticator;
        this.limits = limits;
        if (tls != null) {
            HttpsServer https = HttpsServer.create(address, 0);
            https.setHttpsConfigurator(new HttpsConfigurator(tls));
            this.server = https;
        } else {
            this.server = HttpServer.create(address, 0);
        }
        this.handlers = Executors.newFixedThreadPool(limits.concurrency() + 2);
        this.work = new ThreadPoolExecutor(limits.concurrency(), limits.concurrency(), 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(handlers);
        server.createContext(HEALTH_PATH, this::health);
    }

    public SidecarServer start() {
        server.start();
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public Limits limits() {
        return limits;
    }

    /** Registers {@code method path} (exact path); the handler runs only for an authenticated caller. */
    public void route(String method, String path, Handler handler) {
        server.createContext(path, ex -> {
            try {
                if (!path.equals(ex.getRequestURI().getPath())) {
                    send(ex, 404, Map.of("error", "not found"));
                } else if (!method.equals(ex.getRequestMethod())) {
                    ex.getResponseHeaders().set("Allow", method);
                    send(ex, 405, Map.of("error", "method not allowed"));
                } else if (authorised(ex)) {
                    handler.handle(ex);
                }
            } finally {
                ex.close();
            }
        });
    }

    /**
     * Reads the request body within {@link Limits#maxBytes()}. A larger body (by the declared length, before any byte
     * is read, or by the bytes read) is {@link TooLarge}; the service answers {@code 413}.
     */
    public byte[] body(HttpExchange ex) throws IOException, TooLarge {
        String declared = ex.getRequestHeaders().getFirst("Content-Length");
        if (declared != null) {
            try {
                if (Long.parseLong(declared.strip()) > limits.maxBytes()) {
                    throw new TooLarge(limits.maxBytes());
                }
            } catch (NumberFormatException e) {
                // the bounded read below still enforces the limit
            }
        }
        byte[] in;
        try (InputStream s = ex.getRequestBody()) {
            in = s.readNBytes((int) limits.maxBytes() + 1);
        }
        if (in.length > limits.maxBytes()) {
            throw new TooLarge(limits.maxBytes());
        }
        return in;
    }

    /** The request body is larger than {@link Limits#maxBytes()}. */
    public static final class TooLarge extends Exception {
        TooLarge(long limit) {
            super("the request is larger than " + limit + " bytes");
        }
    }

    /**
     * Runs {@code job} in a work slot within the processing timeout. A timed-out job is cancelled (interrupted); its
     * slot stays taken until the job really ends, so a stuck engine turns later requests into {@link Busy} rather than
     * piling up work.
     */
    public <T> T process(Callable<T> job) throws Busy, TimedOut, ExecutionException, InterruptedException {
        Future<T> f;
        try {
            f = work.submit(job);
        } catch (RejectedExecutionException e) {
            throw new Busy();
        }
        try {
            return f.get(limits.processingTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new TimedOut();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        handlers.shutdownNow();
        work.shutdownNow();
    }

    private boolean authorised(HttpExchange ex) throws IOException {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        boolean ok;
        try {
            ok = authenticator.authenticate(header);
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok) {
            String notReady = authenticator.notReadyReason();
            if (notReady != null) {
                send(ex, 503, Map.of("error", "not ready"));
            } else {
                ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                send(ex, 401, Map.of("error", "unauthorised"));
            }
            return false;
        }
        return true;
    }

    private void health(HttpExchange ex) throws IOException {
        try {
            if (!HEALTH_PATH.equals(ex.getRequestURI().getPath())) {
                send(ex, 404, Map.of("error", "not found"));
            } else if (!"GET".equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Allow", "GET");
                send(ex, 405, Map.of("error", "method not allowed"));
            } else if (authenticator.notReadyReason() != null) {
                send(ex, 503, Map.of("status", "NOT_READY"));
            } else {
                send(ex, 200, Map.of("status", "UP"));
            }
        } finally {
            ex.close();
        }
    }

    /** Writes a JSON answer. */
    public static void send(HttpExchange ex, int status, Map<String, ?> body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * The server TLS context from a PKCS#12 key store and a file holding its password (both mounted secrets). The
     * password is never logged; it is cleared from memory after use.
     */
    public static SSLContext tls(Path keyStore, Path passwordFile) throws IOException {
        char[] password = Files.readString(passwordFile, StandardCharsets.UTF_8).strip().toCharArray();
        try (InputStream in = Files.newInputStream(keyStore)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, password);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (IOException e) {
            throw new IOException("the TLS key store " + keyStore + " cannot be read", e);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("the TLS key store " + keyStore + " is not usable (" + e.getClass().getSimpleName()
                    + ")", e);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
