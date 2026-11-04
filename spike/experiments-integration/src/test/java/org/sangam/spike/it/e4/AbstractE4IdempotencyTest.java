package org.sangam.spike.it.e4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.sangam.spike.it.support.Env;
import org.sangam.spike.it.support.ExperimentBase;
import org.sangam.spike.it.support.Scenario;
import org.sangam.spike.it.support.Workflows;
import org.sangam.spike.layer.IdempotencyStore;
import org.sangam.spike.layer.IdempotencyStore.BeginResult;
import org.sangam.spike.layer.IdempotencyStore.Claim;

/**
 * E4 (SANGAM.md XI.4, X.2.2): a process start wrapped in {@link IdempotencyStore}. Step 1 (claim) is its own engine
 * transaction; step 2 runs {@code startProcess} and {@code IdempotencyStore.complete} (last write, L6) in ONE
 * {@code inEngineTransaction}, so property P-1 is tested against real engine state.
 */
public abstract class AbstractE4IdempotencyTest extends ExperimentBase {

    static final String T = "tn-e4";
    static final String CMD = "process.start";

    IdempotencyStore store;
    IdempotencyStore shortClaims;

    @Override
    protected String experiment() {
        return "E4";
    }

    @Override
    protected void setUp() {
        env.deploy(T, Workflows.WF2_FILES);
        store = new IdempotencyStore(env.dialect, env.tx, "node-1");
        shortClaims = new IdempotencyStore(env.dialect, env.tx, "node-2", Duration.ofMillis(700), Duration.ofHours(24), Duration.ofDays(30));
        notes.put("command", "POST /process-definitions/wf2-file/start, Idempotency-Key header, tenant " + T);
        notes.put("claimTtl", "30 s (default store), 700 ms (takeover scenarios); IDEMPOTENCY times use the DB clock");
    }

    /** The REST answer of the start command. */
    record Answer(int status, String instanceId, boolean replayed, String code) {
    }

    @FunctionalInterface
    interface Fault {
        void afterEngineStart(String pid);
    }

    static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash(String m) {
            super(m);
        }
    }

    private static byte[] fingerprint(String businessKey, String l1Group) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("businessKey", businessKey);
        body.put("variables", Map.of("l1Group", l1Group));
        return IdempotencyStore.fingerprint(CMD, "POST", "/process-definitions/{key}/start", Map.of("key", "wf2-file"),
                Map.of(), body);
    }

    private Answer start(IdempotencyStore s, String key, String principal, String businessKey, String l1Group, Fault fault) {
        BeginResult r = s.begin(T, CMD, key, principal, fingerprint(businessKey, l1Group));
        if (r instanceof IdempotencyStore.New n) {
            return execute(s, n.claim(), businessKey, l1Group, fault);
        }
        return answer(r);
    }

    private Answer execute(IdempotencyStore s, Claim claim, String businessKey, String l1Group, Fault fault) {
        String pid = env.inTx(c -> {
            String id = env.engine.startProcess(T, "wf2-file", businessKey, Workflows.wf2Vars(l1Group));
            s.complete(c, claim, 201, ("{\"processInstanceId\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8), id);
            if (fault != null) {
                fault.afterEngineStart(id);
            }
            return id;
        });
        return new Answer(201, pid, false, null);
    }

    private static Answer answer(BeginResult r) {
        return switch (r) {
            case IdempotencyStore.Replay p -> new Answer(p.httpStatus(), p.resourceRef(), true, null);
            case IdempotencyStore.Conflict cf -> new Answer(cf.httpStatus(), null, false, cf.code());
            case IdempotencyStore.InProgress ip -> new Answer(ip.httpStatus(), null, false, ip.code());
            case IdempotencyStore.New n -> throw new IllegalStateException("unexpected claim");
        };
    }

    private String recordState(String key) {
        List<String> s = env.strings("SELECT state FROM IDEMPOTENCY_RECORD WHERE tenant_id=? AND command_type=? AND idem_key=?", T, CMD, key);
        return s.isEmpty() ? "absent" : s.get(0);
    }

    @Test
    @Order(1)
    @Scenario(id = "E4-1", name = "Duplicate start with the same key and payload returns the same instance (replay)",
            tests = {"T01"})
    void duplicateStartReplays() {
        Answer a = start(store, "key-e4-1", "user-1", "bk-e4-1", "seat-l1", null);
        Answer b = start(store, "key-e4-1", "user-1", "bk-e4-1", "seat-l1", null);
        assertEquals(201, a.status());
        assertTrue(b.replayed());
        assertEquals(201, b.status());
        assertEquals(a.instanceId(), b.instanceId());
        assertEquals(1, env.instancesWithBusinessKey("bk-e4-1"));
        assertEquals("COMPLETED", recordState("key-e4-1"));
        m.put("first", a.status() + " " + a.instanceId());
        m.put("second", b.status() + " replayed=" + b.replayed() + " " + b.instanceId());
        m.put("instances", env.instancesWithBusinessKey("bk-e4-1"));
    }

    @Test
    @Order(2)
    @Scenario(id = "E4-2", name = "Conflicting reuse of a key: different payload or different principal gives 422, "
            + "no new instance, no stored body disclosed", tests = {"T56"})
    void conflictingReuseIs422() {
        Answer a = start(store, "key-e4-2", "user-1", "bk-e4-2", "seat-l1", null);
        Answer payload = start(store, "key-e4-2", "user-1", "bk-e4-2", "seat-OTHER", null);
        Answer principal = start(store, "key-e4-2", "user-2", "bk-e4-2", "seat-l1", null);
        assertEquals(201, a.status());
        assertEquals(422, payload.status());
        assertEquals("IDEMPOTENCY_KEY_REUSED", payload.code());
        assertEquals(null, payload.instanceId());
        assertEquals(422, principal.status());
        assertEquals("IDEMPOTENCY_KEY_PRINCIPAL_MISMATCH", principal.code());
        assertEquals(1, env.instancesWithBusinessKey("bk-e4-2"));
        m.put("differentPayload", payload.status() + " " + payload.code());
        m.put("differentPrincipal", principal.status() + " " + principal.code());
        m.put("instances", 1);
    }

    @Test
    @Order(3)
    @Scenario(id = "E4-3", name = "Concurrent duplicates (2, 4, 8 threads, same key and payload): exactly one instance, "
            + "every caller gets that instance", tests = {"T01", "T55"})
    void concurrentDuplicatesOneInstance() throws Exception {
        Map<String, Object> perLevel = new LinkedHashMap<>();
        for (int threads : new int[]{2, 4, 8}) {
            String key = "key-e4-3-" + threads;
            String bk = "bk-e4-3-" + threads;
            CyclicBarrier barrier = new CyclicBarrier(threads);
            Map<String, AtomicInteger> kinds = new ConcurrentHashMap<>();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    for (int attempt = 0; attempt < 200; attempt++) {
                        Answer a = start(store, key, "user-1", bk, "seat-l1", null);
                        kinds.computeIfAbsent(a.status() + (a.replayed() ? "-replay" : a.code() == null ? "" : "-" + a.code()),
                                k -> new AtomicInteger()).incrementAndGet();
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
                ids.add(f.get(60, TimeUnit.SECONDS));
            }
            pool.shutdown();
            long instances = env.instancesWithBusinessKey(bk);
            assertEquals(1, instances, threads + " threads");
            assertEquals(1, ids.size(), "all callers see the same instance");
            Map<String, Integer> k = new LinkedHashMap<>();
            kinds.forEach((x, v) -> k.put(x, v.get()));
            perLevel.put(threads + " threads", Map.of("instances", instances, "distinctInstanceIds", ids.size(), "answers", k));
        }
        m.put("levels", perLevel);
    }

    @Test
    @Order(4)
    @Scenario(id = "E4-4", name = "Exception after the engine start inside the transaction: no instance and no "
            + "COMPLETED record; after abandon no record; the retry executes once (P-1)", tests = {"T57", "T60"})
    void exceptionAfterStartRollsBackBoth() {
        String bk = "bk-e4-4";
        BeginResult r = store.begin(T, CMD, "key-e4-4", "user-1", fingerprint(bk, "seat-l1"));
        Claim claim = assertInstanceOf(IdempotencyStore.New.class, r).claim();
        String[] started = new String[1];
        assertThrows(SimulatedCrash.class, () -> execute(store, claim, bk, "seat-l1", pid -> {
            started[0] = pid;
            throw new SimulatedCrash("crash after startProcess and IDEMPOTENCY complete, before commit");
        }));
        long instances = env.instancesWithBusinessKey(bk);
        long runtime = env.runtimeInstancesWithBusinessKey(bk);
        String stateAfterCrash = recordState("key-e4-4");
        assertEquals(0, instances);
        assertEquals(0, runtime);
        assertEquals("IN_PROGRESS", stateAfterCrash, "the COMPLETED write rolled back with the engine start");
        long tasksOfStarted = env.count("SELECT count(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = ?", started[0]);
        assertEquals(0, tasksOfStarted);

        store.abandon(claim);   // X.2.2.4 step 3: transient failure, nothing stored
        assertEquals("absent", recordState("key-e4-4"));
        Answer retry = start(store, "key-e4-4", "user-1", bk, "seat-l1", null);
        assertEquals(201, retry.status());
        assertEquals(1, env.instancesWithBusinessKey(bk));
        m.put("rolledBackInstanceId", started[0]);
        m.put("instancesAfterCrash", instances);
        m.put("recordAfterCrash", stateAfterCrash);
        m.put("recordAfterAbandon", "absent");
        m.put("instancesAfterRetry", 1);
    }

    @Test
    @Order(5)
    @Scenario(id = "E4-5", name = "Crash before commit with no cleanup: the claim expires, a retry takes over and "
            + "executes once; the zombie's late commit loses its claim and its engine start rolls back", tests = {"T57"})
    void takeoverAndZombieClaimLost() throws Exception {
        String bk = "bk-e4-5";
        String key = "key-e4-5";
        Claim zombie = assertInstanceOf(IdempotencyStore.New.class,
                shortClaims.begin(T, CMD, key, "user-1", fingerprint(bk, "seat-l1"))).claim();
        Answer early = start(shortClaims, key, "user-1", bk, "seat-l1", null);
        assertEquals(409, early.status(), "live claim: IDEMPOTENCY_IN_PROGRESS");
        Thread.sleep(1_000);    // claim TTL 700 ms (DB clock)
        Answer retry = start(shortClaims, key, "user-1", bk, "seat-l1", null);
        assertEquals(201, retry.status(), "takeover executed");
        assertThrows(IdempotencyStore.ClaimLostException.class, () -> execute(shortClaims, zombie, bk, "seat-l1", null));
        assertEquals(1, env.instancesWithBusinessKey(bk), "the zombie's engine start rolled back");
        Answer replay = start(shortClaims, key, "user-1", bk, "seat-l1", null);
        assertTrue(replay.replayed());
        assertEquals(retry.instanceId(), replay.instanceId());
        long epoch = env.count("SELECT claim_epoch FROM IDEMPOTENCY_RECORD WHERE tenant_id=? AND command_type=? AND idem_key=?", T, CMD, key);
        m.put("whileClaimLive", early.status() + " " + early.code());
        m.put("afterExpiry", "201 (takeover, claim_epoch " + epoch + ")");
        m.put("zombieCommit", "ClaimLostException, rolled back");
        m.put("instances", 1);
    }

    @Test
    @Order(6)
    @Scenario(id = "E4-6", name = "Crash after commit, before the response: the retry replays the original response",
            tests = {"T57"})
    void lostResponseReplays() {
        Answer committed = start(store, "key-e4-6", "user-1", "bk-e4-6", "seat-l1", null);   // response lost
        Answer retry = start(store, "key-e4-6", "user-1", "bk-e4-6", "seat-l1", null);
        assertTrue(retry.replayed());
        assertEquals(committed.status(), retry.status());
        assertEquals(committed.instanceId(), retry.instanceId());
        assertEquals(1, env.instancesWithBusinessKey("bk-e4-6"));
        m.put("retry", retry.status() + " replayed, same instance");
    }
}
