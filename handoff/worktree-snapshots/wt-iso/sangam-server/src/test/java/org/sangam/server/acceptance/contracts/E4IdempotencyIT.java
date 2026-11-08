// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.sangam.core.layer.IdempotencyStore;
import org.sangam.core.layer.IdempotencyStore.BeginResult;
import org.sangam.core.layer.IdempotencyStore.Claim;
import org.sangam.core.layer.Transactions;

/**
 * E4 (SANGAM.md XI.4, X.2.2, R41) on Sangam: a process start protected by {@code Idempotency-Key}.
 *
 * <ul>
 *   <li>E4-1, E4-2 (payload), E4-3, E4-6 through the real REST command {@code POST /api/v1/process-instances} and its
 *       {@code IdempotencyFilter} (claim in its own transaction, completion as the command's last write).</li>
 *   <li>E4-2 (principal), E4-4 and E4-5 need a second principal, a fault between the engine start and the commit, or a
 *       zombie that commits late; they drive sangam-core's {@link IdempotencyStore} with the start inside one
 *       {@link org.sangam.core.api.EngineAdapter#inEngineTransaction} of the server's engine adapter, on the server's
 *       IDEMPOTENCY_RECORD table (as Stage B did).</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class E4IdempotencyIT extends ContractSupport {

    static final String CMD = "process.start";
    final String processKey = key("e4-file");
    final String tenant = REST_TENANT;

    IdempotencyStore store;
    IdempotencyStore shortClaims;

    @BeforeAll
    void setUp() {
        deploy(tenant, "e4-file.bpmn", Models.singleTask(processKey, "l1Review"));
        Transactions etx = Transactions.of(engine);
        store = new IdempotencyStore(dialect(), etx, "node-e4-1");
        shortClaims = new IdempotencyStore(dialect(), etx, "node-e4-2", Duration.ofMillis(700), Duration.ofHours(24),
                Duration.ofDays(30));
    }

    // ------------------------------------------------------------------ REST command

    record Answer(int status, String instanceId, boolean replayed, String code) {
    }

    private Answer restStart(String idemKey, String businessKey, String l1Group) {
        return restStart(idemKey, businessKey, l1Group, null);
    }

    /** @param person an OIDC person of tenant acc (production-path token), or null for the dev token */
    private Answer restStart(String idemKey, String businessKey, String l1Group, String person) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", processKey);
        body.put("businessKey", businessKey);
        body.put("variables", Map.of("l1Group", l1Group));
        Rest r = person == null
                ? post("/api/v1/process-instances", body, "Idempotency-Key", idemKey)
                : post("/api/v1/process-instances", body, "Idempotency-Key", idemKey,
                        "Authorization", "Bearer " + bearer(tenant, person, ADMIN_GROUPS));
        String id = r.status() == 201 ? r.json().path("id").asText(null) : null;
        return new Answer(r.status(), id, r.header("Idempotent-Replayed") != null, r.status() >= 400 ? r.code() : null);
    }

    @Test
    @Order(1)
    void e4_1_duplicateStartReplays() {
        String bk = key("bk-e4-1");
        Answer a = restStart(key("key-e4-1"), bk, "seat-l1");
        Answer b = restStart(key("key-e4-1"), bk, "seat-l1");
        assertEquals(201, a.status(), String.valueOf(a));
        assertNotNull(a.instanceId());
        assertTrue(b.replayed(), "second answer is a replay: " + b);
        assertEquals(201, b.status());
        assertEquals(a.instanceId(), b.instanceId());
        assertEquals(1, instancesWithBusinessKey(bk));
        assertEquals("COMPLETED", strings("SELECT state FROM IDEMPOTENCY_RECORD WHERE tenant_id=? AND idem_key=?",
                tenant, key("key-e4-1")).get(0));
    }

    @Test
    @Order(2)
    void e4_2_conflictingReuseIs422() {
        String bk = key("bk-e4-2");
        Answer a = restStart(key("key-e4-2"), bk, "seat-l1");
        Answer payload = restStart(key("key-e4-2"), bk, "seat-OTHER");
        assertEquals(201, a.status(), String.valueOf(a));
        assertEquals(422, payload.status(), String.valueOf(payload));
        assertEquals("IDEMPOTENCY_KEY_REUSED", payload.code());
        assertNull(payload.instanceId());
        assertEquals(1, instancesWithBusinessKey(bk));

        // a different principal (two OIDC persons of the same tenant), same key and payload
        Answer first = restStart(key("key-e4-2p"), key("bk-e4-2p"), "seat-l1", key("person-1"));
        Answer principal = restStart(key("key-e4-2p"), key("bk-e4-2p"), "seat-l1", key("person-2"));
        assertEquals(201, first.status(), String.valueOf(first));
        assertEquals(422, principal.status(), String.valueOf(principal));
        assertEquals("IDEMPOTENCY_KEY_PRINCIPAL_MISMATCH", principal.code());
        assertNull(principal.instanceId());
        assertEquals(1, instancesWithBusinessKey(key("bk-e4-2p")));
    }

    @Test
    @Order(3)
    void e4_3_concurrentDuplicatesOneInstance() throws Exception {
        for (int threads : new int[]{2, 4, 8}) {
            String idem = key("key-e4-3-" + threads);
            String bk = key("bk-e4-3-" + threads);
            CyclicBarrier barrier = new CyclicBarrier(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    for (int attempt = 0; attempt < 400; attempt++) {
                        Answer a = restStart(idem, bk, "seat-l1");
                        if (a.status() == 201) {
                            return a.instanceId();
                        }
                        if (a.status() != 409) {
                            throw new IllegalStateException("unexpected " + a);
                        }
                        Thread.sleep(25);   // 409 IDEMPOTENCY_IN_PROGRESS, Retry-After
                    }
                    throw new IllegalStateException("no definite answer");
                }));
            }
            Set<String> ids = new HashSet<>();
            for (Future<String> f : futures) {
                ids.add(f.get(120, TimeUnit.SECONDS));
            }
            pool.shutdown();
            assertEquals(1, instancesWithBusinessKey(bk), threads + " threads");
            assertEquals(1, ids.size(), "all callers see the same instance");
        }
    }

    // ------------------------------------------------------------------ core store with the engine transaction

    interface Fault {
        void afterEngineStart(String pid);
    }

    static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash(String m) {
            super(m);
        }
    }

    private byte[] fingerprint(String businessKey, String l1Group) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("businessKey", businessKey);
        body.put("variables", Map.of("l1Group", l1Group));
        return IdempotencyStore.fingerprint(CMD, "POST", "/process-definitions/{key}/start", Map.of("key", processKey),
                Map.of(), body);
    }

    private Answer start(IdempotencyStore s, String idemKey, String principal, String businessKey, String l1Group, Fault fault) {
        BeginResult r = s.begin(tenant, CMD, idemKey, principal, fingerprint(businessKey, l1Group));
        if (r instanceof IdempotencyStore.New n) {
            return execute(s, n.claim(), businessKey, l1Group, fault);
        }
        return switch (r) {
            case IdempotencyStore.Replay p -> new Answer(p.httpStatus(), p.resourceRef(), true, null);
            case IdempotencyStore.Conflict cf -> new Answer(cf.httpStatus(), null, false, cf.code());
            case IdempotencyStore.InProgress ip -> new Answer(ip.httpStatus(), null, false, ip.code());
            case IdempotencyStore.New n -> throw new IllegalStateException("unexpected claim");
        };
    }

    private Answer execute(IdempotencyStore s, Claim claim, String businessKey, String l1Group, Fault fault) {
        String pid = inEngineTx(c -> {
            String id = engine.startProcess(tenant, processKey, businessKey, Map.of("l1Group", l1Group));
            s.complete(c, claim, 201, ("{\"processInstanceId\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8), id);
            if (fault != null) {
                fault.afterEngineStart(id);
            }
            return id;
        });
        return new Answer(201, pid, false, null);
    }

    private String recordState(String idemKey) {
        List<String> s = strings("SELECT state FROM IDEMPOTENCY_RECORD WHERE tenant_id=? AND command_type=? AND idem_key=?",
                tenant, CMD, idemKey);
        return s.isEmpty() ? "absent" : s.get(0);
    }

    @Test
    @Order(4)
    void e4_4_exceptionAfterStartRollsBackBoth() {
        String bk = key("bk-e4-4");
        String idem = key("key-e4-4");
        BeginResult r = store.begin(tenant, CMD, idem, "user-1", fingerprint(bk, "seat-l1"));
        Claim claim = assertInstanceOf(IdempotencyStore.New.class, r).claim();
        String[] started = new String[1];
        assertThrows(SimulatedCrash.class, () -> execute(store, claim, bk, "seat-l1", pid -> {
            started[0] = pid;
            throw new SimulatedCrash("crash after startProcess and IDEMPOTENCY complete, before commit");
        }));
        assertEquals(0, instancesWithBusinessKey(bk));
        assertEquals(0, runtimeInstancesWithBusinessKey(bk));
        assertEquals("IN_PROGRESS", recordState(idem), "the COMPLETED write rolled back with the engine start");
        assertEquals(0, count("SELECT count(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = ?", started[0]));

        store.abandon(claim);   // X.2.2.4 step 3: transient failure, nothing stored
        assertEquals("absent", recordState(idem));
        Answer retry = start(store, idem, "user-1", bk, "seat-l1", null);
        assertEquals(201, retry.status());
        assertEquals(1, instancesWithBusinessKey(bk));
    }

    @Test
    @Order(5)
    void e4_5_takeoverAndZombieClaimLost() throws Exception {
        String bk = key("bk-e4-5");
        String idem = key("key-e4-5");
        Claim zombie = assertInstanceOf(IdempotencyStore.New.class,
                shortClaims.begin(tenant, CMD, idem, "user-1", fingerprint(bk, "seat-l1"))).claim();
        Answer early = start(shortClaims, idem, "user-1", bk, "seat-l1", null);
        assertEquals(409, early.status(), "live claim: IDEMPOTENCY_IN_PROGRESS");
        Thread.sleep(1_000);    // claim TTL 700 ms (DB clock)
        Answer retry = start(shortClaims, idem, "user-1", bk, "seat-l1", null);
        assertEquals(201, retry.status(), "takeover executed");
        assertThrows(IdempotencyStore.ClaimLostException.class, () -> execute(shortClaims, zombie, bk, "seat-l1", null));
        assertEquals(1, instancesWithBusinessKey(bk), "the zombie's engine start rolled back");
        Answer replay = start(shortClaims, idem, "user-1", bk, "seat-l1", null);
        assertTrue(replay.replayed());
        assertEquals(retry.instanceId(), replay.instanceId());
    }

    @Test
    @Order(6)
    void e4_6_lostResponseReplays() {
        String bk = key("bk-e4-6");
        Answer committed = restStart(key("key-e4-6"), bk, "seat-l1");   // response lost
        Answer retry = restStart(key("key-e4-6"), bk, "seat-l1");
        assertEquals(201, committed.status(), String.valueOf(committed));
        assertTrue(retry.replayed());
        assertEquals(committed.status(), retry.status());
        assertEquals(committed.instanceId(), retry.instanceId());
        assertEquals(1, instancesWithBusinessKey(bk));
    }
}
