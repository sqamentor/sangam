// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Transactions;
import org.sangam.core.quota.QuotaLimit;
import org.sangam.core.quota.TenantQuotaExceededException;
import org.sangam.security.quota.JdbcTenantQuotas;

/**
 * SL-43 on the real server and PostgreSQL: per-tenant quotas configured as tenant overrides
 * ({@code SEC_TENANT.quotas}), enforced at the REST filter (API calls) and the engine entry (starts, variable size),
 * answered {@code 429}/{@code 413 TENANT_QUOTA_EXCEEDED} naming the limit, audited, and counted in the shared database
 * so that independent nodes never admit more than the limit together.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantQuotaIT extends ContractSupport {

    final String processKey = key("quota-file");
    String savedQuotas;

    @BeforeAll
    void setUp() {
        deploy(REST_TENANT, "quota-file.bpmn", Models.singleTask(processKey, "l1Review"));
        // the acceptance tenant row (JIT/bootstrap); keep its quotas to restore them
        get("/api/v1/process-definitions");
        savedQuotas = strings("SELECT quotas FROM SEC_TENANT WHERE tenant_id=?", REST_TENANT).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("tenant " + REST_TENANT + " has no SEC_TENANT row"));
    }

    @AfterEach
    void restore() {
        exec("UPDATE SEC_TENANT SET quotas=? WHERE tenant_id=?", savedQuotas, REST_TENANT);
    }

    void setQuotas(String json) {
        exec("UPDATE SEC_TENANT SET quotas=? WHERE tenant_id=?", json, REST_TENANT);
    }

    Rest start(String businessKey, Map<String, Object> variables) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", processKey);
        body.put("businessKey", businessKey);
        body.put("variables", variables);
        return post("/api/v1/process-instances", body, "Idempotency-Key", UUID.randomUUID().toString());
    }

    @Test
    void apiCallsAboveThePerMinuteQuotaGet429WithRetryAfterAndTheLimitName() {
        setQuotas("{\"apiCallsPerMinute\":5}");
        List<Rest> answers = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            answers.add(get("/api/v1/process-definitions"));
        }
        long ok = answers.stream().filter(r -> r.status() == 200).count();
        List<Rest> refused = answers.stream().filter(r -> r.status() == 429).toList();
        assertTrue(ok <= 10, "at most the limit per minute window (a boundary may split the burst): " + ok);
        assertTrue(refused.size() >= 2, "refusals: " + refused.size());
        Rest r = refused.get(0);
        assertEquals("TENANT_QUOTA_EXCEEDED", r.code());
        assertEquals("apiCallsPerMinute", r.json().get("limit").asText());
        assertEquals(5, r.json().get("allowed").asInt());
        int retryAfter = Integer.parseInt(r.header("Retry-After"));
        assertTrue(retryAfter >= 1 && retryAfter <= 60, "Retry-After " + retryAfter);
        assertTrue(r.header("Content-Type").startsWith("application/problem+json"));
        assertTrue(count("SELECT count(*) FROM AUDIT_EVENT WHERE chain_id=? AND event_type='TENANT_QUOTA_EXCEEDED'",
                "quota:" + REST_TENANT) >= 1, "refusal audited");
        // configuration only: lifting the override lifts the limit at once (override TTL 0 in the suite)
        setQuotas(savedQuotas);
        assertEquals(200, get("/api/v1/process-definitions").status());
    }

    @Test
    void startsAboveThePerSecondQuotaAreRefusedAtTheEngineEntryAndCreateNothing() throws Exception {
        setQuotas("{\"startsPerSecond\":2}");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Rest>> calls = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 8; i++) {
            String bk = key("q-start-" + i);
            keys.add(bk);
            calls.add(pool.submit(() -> {
                go.await();
                return start(bk, Map.of("l1Group", "g"));
            }));
        }
        go.countDown();
        List<Rest> answers = new ArrayList<>();
        for (Future<Rest> f : calls) {
            answers.add(f.get(60, TimeUnit.SECONDS));
        }
        long t1 = System.currentTimeMillis();
        pool.shutdown();
        long windows = t1 / 1000 - t0 / 1000 + 1;
        int created = 0;
        int refused = 0;
        for (int i = 0; i < answers.size(); i++) {
            Rest r = answers.get(i);
            long instances = instancesWithBusinessKey(keys.get(i));
            if (r.status() == 429) {
                refused++;
                assertEquals("TENANT_QUOTA_EXCEEDED", r.code());
                assertEquals("startsPerSecond", r.json().get("limit").asText());
                assertEquals("1", r.header("Retry-After"));
                assertEquals(0, instances, "a refused start leaves no instance");
            } else {
                assertTrue(r.status() / 100 == 2, "start answered " + r.status() + " " + r.body());
                assertEquals(1, instances);
                created++;
            }
        }
        assertTrue(created <= 2 * windows, created + " starts in " + windows + " one-second windows");
        assertTrue(refused >= 8 - 2 * windows, "refused " + refused);
    }

    @Test
    void anOversizedVariableRollsTheStartBackWith413() {
        setQuotas("{\"maxVariableBytes\":2000}");
        String bk = key("q-big-var");
        Rest r = start(bk, Map.of("l1Group", "g", "note", "x".repeat(5000)));
        assertEquals(413, r.status(), r.body());
        assertEquals("TENANT_QUOTA_EXCEEDED", r.code());
        assertEquals("maxVariableBytes", r.json().get("limit").asText());
        assertEquals(null, r.header("Retry-After"), "resending the same body cannot succeed");
        assertEquals(0, instancesWithBusinessKey(bk), "the whole command rolled back");
        Rest small = start(key("q-small-var"), Map.of("l1Group", "g", "note", "x".repeat(100)));
        assertTrue(small.status() / 100 == 2, small.body());
    }

    @Test
    void independentNodesSharingPostgresNeverAdmitMoreThanTheLimitTogether() throws Exception {
        String tenant = key("quota-nodes");
        Transactions tx = Transactions.of(dataSource);
        JdbcTenantQuotas.OverrideSource src = t -> Map.of("apiCallsPerMinute", 25);
        List<JdbcTenantQuotas> nodes = List.of(
                new JdbcTenantQuotas(dialect(), tx, src, Map.of(), new AuditChain(dialect()), Clock.systemUTC(), Duration.ZERO),
                new JdbcTenantQuotas(dialect(), tx, src, Map.of(), new AuditChain(dialect()), Clock.systemUTC(), Duration.ZERO),
                new JdbcTenantQuotas(dialect(), tx, src, Map.of(), new AuditChain(dialect()), Clock.systemUTC(), Duration.ZERO));
        // stay inside one minute window
        long msIntoMinute = System.currentTimeMillis() % 60_000;
        if (msIntoMinute > 50_000) {
            Thread.sleep(60_000 - msIntoMinute + 200);
        }
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            JdbcTenantQuotas node = nodes.get(i % 3);
            calls.add(pool.submit(() -> {
                go.await();
                try {
                    node.acquire(tenant, QuotaLimit.API_CALLS_PER_MINUTE, 1);
                    return true;
                } catch (TenantQuotaExceededException e) {
                    return false;
                }
            }));
        }
        go.countDown();
        int admitted = 0;
        for (Future<Boolean> f : calls) {
            admitted += f.get(60, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdown();
        assertEquals(25, admitted);
        assertEquals(1, count("SELECT count(*) FROM AUDIT_EVENT WHERE chain_id=? AND event_type='TENANT_QUOTA_EXCEEDED'",
                "quota:" + tenant), "one audit event for the window, whichever node refused first");
    }
}
