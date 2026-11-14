// SPDX-License-Identifier: Apache-2.0
package org.sangam.operator;

import com.sun.net.httpserver.HttpServer;
import org.sangam.kube.KubeHttp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * The operator process: reconciles its namespace every {@code SANGAM_OPERATOR_RESYNC_SECONDS} (default 15) and serves
 * {@code GET /healthz} on {@code SANGAM_OPERATOR_HEALTH_PORT} (default 8081), which answers 503 when no pass has
 * completed within four resync periods. {@code SANGAM_OPERATOR_NAMESPACE} defaults to the pod's own namespace; the
 * operator needs only a namespaced Role (manifests/operator.yaml).
 *
 * <p>Polling, not watches: every pass reads the desired state again, so a missed event or an operator restart loses
 * nothing (level-triggered).
 */
public final class OperatorMain {

    /** Field manager of the operator's server-side apply (the shared client takes it as an argument, decision 155). */
    static final String FIELD_MANAGER = "sangam-operator";

    private final Reconciler reconciler;
    private final String namespace;
    private final Duration resync;
    private final Clock clock;
    private final AtomicReference<Instant> lastPass = new AtomicReference<>();

    OperatorMain(Reconciler reconciler, String namespace, Duration resync, Clock clock) {
        this.reconciler = reconciler;
        this.namespace = namespace;
        this.resync = resync;
        this.clock = clock;
    }

    public static void main(String[] args) throws IOException {
        Function<String, String> env = System::getenv;
        String ns = env.apply("SANGAM_OPERATOR_NAMESPACE");
        if (ns == null || ns.isBlank()) {
            ns = KubeHttp.podNamespace();
        }
        Duration resync = Duration.ofSeconds(positive(env.apply("SANGAM_OPERATOR_RESYNC_SECONDS"), 15));
        int healthPort = (int) positive(env.apply("SANGAM_OPERATOR_HEALTH_PORT"), 8081);
        OperatorMain op = new OperatorMain(new Reconciler(KubeHttp.inCluster(FIELD_MANAGER), System.err), ns, resync, Clock.systemUTC());
        op.serveHealth(healthPort);
        System.err.println("sangam-operator: watching namespace " + ns + " every " + resync.toSeconds() + "s");
        op.loop();
    }

    static long positive(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        long v = Long.parseLong(value.trim());
        if (v < 1) {
            throw new IllegalArgumentException("must be positive: " + value);
        }
        return v;
    }

    /** Runs passes until interrupted. */
    void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            pass();
            try {
                Thread.sleep(resync.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** One pass; returns whether it completed. A failed pass is logged and retried at the next period. */
    boolean pass() {
        try {
            reconciler.reconcileNamespace(namespace);
            lastPass.set(clock.instant());
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("sangam-operator: pass over " + namespace + " failed: " + e.getMessage());
            return false;
        }
    }

    /** Healthy when a pass completed within four resync periods. */
    boolean healthy() {
        Instant last = lastPass.get();
        return last != null && Duration.between(last, clock.instant()).compareTo(resync.multipliedBy(4)) <= 0;
    }

    private void serveHealth(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/healthz", exchange -> {
            boolean ok = healthy();
            byte[] body = (ok ? "ok" : "no completed reconcile pass").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(ok ? 200 : 503, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }
}
