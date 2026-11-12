// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.h2.tools.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * SL-43-DEADLOCK regression (shared body of the two override-ttl variants). An H2 TCP server, a main pool of
 * {@link #POOL} connections and {@code 3 x POOL} concurrent process starts over {@code /engine-rest} with variables,
 * quotas on (rate and size limits checked, nothing refused). Every start runs the REST quota filter, the engine
 * plugin's {@code startsPerSecond} count inside the engine transaction (which holds a pool connection) and the
 * variable size check before the flush. Before the fix the quota read its overrides and counted on a SECOND
 * connection of the same pool while the engine transaction held the first: with more callers than slots every slot is
 * held by a caller waiting for its second, and the starts fail with Hikari's connection timeout (500) after
 * {@link #CONNECTION_TIMEOUT_MS}. Several rounds, so both a cold override cache and the cached path are covered.
 */
abstract class QuotaPoolDeadlockH2Support {

    static final int POOL = 4;
    static final int CALLERS = 3 * POOL;
    static final int ROUNDS = 3;
    static final long CONNECTION_TIMEOUT_MS = 5_000;
    static final String TOKEN = "sangam-dev-token-change-me";
    static final String TENANT = "dev";
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="sl43pool" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                <userTask id="wait"/>
                <sequenceFlow id="f2" sourceRef="wait" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;

    static Server h2;

    static synchronized void database(DynamicPropertyRegistry r, String name, String overrideTtl) {
        try {
            if (h2 == null) {
                int port;
                try (ServerSocket s = new ServerSocket(0)) {
                    port = s.getLocalPort();
                }
                h2 = Server.createTcpServer("-tcpPort", String.valueOf(port), "-ifNotExists").start();
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot start the H2 TCP server", e);
        }
        String url = "jdbc:h2:tcp://localhost:" + h2.getPort() + "/mem:" + name
                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;MODE=PostgreSQL";
        r.add("spring.datasource.url", () -> url);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> String.valueOf(POOL));
        r.add("spring.datasource.hikari.connection-timeout", () -> String.valueOf(CONNECTION_TIMEOUT_MS));
        r.add("sangam.security.dev-token.groups", () -> "sangam-engine-admin");
        // quotas on and checked on every call, high enough that nothing in this test is refused
        r.add("sangam.quotas.enabled", () -> "true");
        r.add("sangam.quotas.defaults.startsPerSecond", () -> "100000");
        r.add("sangam.quotas.defaults.apiCallsPerMinute", () -> "1000000");
        r.add("sangam.quotas.defaults.maxVariableBytes", () -> "1048576");
        r.add("sangam.quotas.override-ttl", () -> overrideTtl);
    }

    @AfterAll
    static synchronized void stopH2() {
        if (h2 != null) {
            h2.stop();
            h2 = null;
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ProcessEngine engine;

    @Autowired
    DataSource dataSource;

    CompletableFuture<HttpResponse<String>> start(int round, int i) {
        String body = "{\"variables\":{\"caller\":{\"value\":\"r" + round + "-c" + i + "\",\"type\":\"String\"},"
                + "\"amount\":{\"value\":" + (1000 + i) + ",\"type\":\"Long\"}}}";
        return HTTP.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/engine-rest/process-definition/key/sl43pool/tenant-id/" + TENANT + "/start"))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void threeTimesThePoolOfConcurrentStartsWithVariablesAndQuotasAllSucceed() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        assertEquals(POOL, pool.getMaximumPoolSize(), "the main pool under test");
        engine.getRepositoryService().createDeployment().tenantId(TENANT).name("sl43pool")
                .addString("sl43pool.bpmn", BPMN).deploy();
        // one sequential start first: provisions the dev person (JIT). Concurrent first use of a new identity races in
        // JIT provisioning (409 "user exists"), a separate finding, not the pool deadlock under test here.
        assertEquals(200, start(-1, 0).get(120, TimeUnit.SECONDS).statusCode(), "warm-up start");
        engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey("sl43pool").list()
                .forEach(pi -> engine.getRuntimeService().deleteProcessInstance(pi.getId(), "warm-up"));
        for (int round = 0; round < ROUNDS; round++) {
            List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
            for (int i = 0; i < CALLERS; i++) {
                calls.add(start(round, i));
            }
            List<String> failures = new ArrayList<>();
            long t0 = System.nanoTime();
            for (CompletableFuture<HttpResponse<String>> f : calls) {
                HttpResponse<String> r = f.get(120, TimeUnit.SECONDS);
                if (r.statusCode() != 200) {
                    failures.add(r.statusCode() + " " + r.body());
                }
            }
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue(failures.isEmpty(), "round " + round + " (" + ms + " ms): " + failures.size() + " of " + CALLERS
                    + " starts failed (pool " + POOL + "): " + failures);
        }
        assertEquals((long) CALLERS * ROUNDS, engine.getRuntimeService().createProcessInstanceQuery()
                .processDefinitionKey("sl43pool").count(), "every start committed");
        // SL-79 diagnostic: the message supplier runs only when the assertion fails, so a pass adds nothing
        assertEquals(0, pool.getHikariPoolMXBean().getThreadsAwaitingConnection(),
                () -> "nobody left waiting" + waitDiagnostic(pool));
    }

    static final int DIAGNOSTIC_FRAMES = 15;

    /**
     * SL-79: who was waiting when "nobody left waiting" failed. The main pool's counts, then every live thread with
     * its state, and the top {@link #DIAGNOSTIC_FRAMES} frames of every thread inside HikariCP. A Hikari frame does
     * not carry its pool instance, so the pool is named from the thread's first frame outside Hikari and the JDK
     * (the caller that asked for the connection); the main pool's name is printed for reference.
     */
    static String waitDiagnostic(HikariDataSource pool) {
        StringBuilder out = new StringBuilder();
        var mx = pool.getHikariPoolMXBean();
        out.append("\n-- SL-79 diagnostic: main pool '").append(pool.getPoolName()).append("' at dump time: active=")
                .append(mx.getActiveConnections()).append(" idle=").append(mx.getIdleConnections())
                .append(" total=").append(mx.getTotalConnections()).append(" awaiting=")
                .append(mx.getThreadsAwaitingConnection()).append('\n');
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        List<Map.Entry<Thread, StackTraceElement[]>> threads = new ArrayList<>(all.entrySet());
        threads.sort((x, y) -> x.getKey().getName().compareTo(y.getKey().getName()));
        out.append("-- ").append(threads.size()).append(" live threads (name, state):\n");
        for (Map.Entry<Thread, StackTraceElement[]> e : threads) {
            out.append("  ").append(e.getKey().getName()).append(" [").append(e.getKey().getState()).append("]\n");
        }
        out.append("-- threads inside HikariCP (top ").append(DIAGNOSTIC_FRAMES).append(" frames):\n");
        for (Map.Entry<Thread, StackTraceElement[]> e : threads) {
            StackTraceElement[] stack = e.getValue();
            boolean inHikari = false;
            for (StackTraceElement f : stack) {
                if (f.getClassName().startsWith("com.zaxxer.hikari")) {
                    inHikari = true;
                    break;
                }
            }
            if (!inHikari) {
                continue;
            }
            String caller = "unknown";
            for (StackTraceElement f : stack) {
                String c = f.getClassName();
                if (!c.startsWith("com.zaxxer.hikari") && !c.startsWith("java.") && !c.startsWith("jdk.")
                        && !c.startsWith("sun.") && !c.startsWith("org.springframework.jdbc.")
                        && !c.startsWith("org.springframework.transaction.")) {
                    caller = f.toString();
                    break;
                }
            }
            boolean waiting = false;
            for (StackTraceElement f : stack) {
                if (f.getClassName().startsWith("com.zaxxer.hikari") && f.getMethodName().equals("getConnection")) {
                    waiting = true;
                    break;
                }
            }
            out.append("  ").append(e.getKey().getName()).append(" [").append(e.getKey().getState()).append("]")
                    .append(waiting ? " in getConnection, pool requested by " + caller : " (Hikari internal)")
                    .append('\n');
            for (int i = 0; i < Math.min(DIAGNOSTIC_FRAMES, stack.length); i++) {
                out.append("      at ").append(stack[i]).append('\n');
            }
        }
        return out.toString();
    }
}
