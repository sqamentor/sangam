// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.reconciliation;

import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reconciliation engine (SANGAM.md VI.3 rule 4, VI.5 rules 3–4, A-2, R36, R58 "ambiguous payment status → no duplicate
 * payment"; OJ-02/OJ-03; T02, T27, T36, T39).
 *
 * <p>For every OPEN {@code UNKNOWN_OUTCOME} item: query the external system by operationId and resolve automatically,
 * or escalate to a human task.
 * <ul>
 *   <li><b>Claim</b> (short transaction, skip-locked): due items get an attempt row with {@code next_attempt_at} pushed
 *       out by the claim TTL, so several nodes never look up the same item at once.</li>
 *   <li><b>Lookup</b> outside any transaction through the item's connector (from {@code CONNECTOR_CALL}).</li>
 *   <li><b>Resolve</b>: found + succeeded → {@code CONFIRM_SUCCESS} with the provider result; found + failed →
 *       {@code MARK_FAILED}; not found → class (a) {@code RETRY_SAME_OPERATION} at once, class (b) only after
 *       {@code notFoundConfirmations} consecutive "not found" answers at least {@code settleDelay} apart (eventual
 *       consistency of the provider), then retry with the same operationId. Each resolution goes through
 *       {@link ReconciliationQueue#resolve} (ledger update + {@code RECONCILIATION_ACTION} audit in one transaction),
 *       then the held work is released ({@link HeldWorkReleaser}).</li>
 *   <li><b>Escalate</b> to a human ({@link ReconciliationTaskPort}): class (c) (no lookup), no connector call on record,
 *       {@code LATE_RESULT_AFTER_DELETE} items (never retried, VI.5 rule 3), lookups unavailable for longer than
 *       {@code maxAutomaticAge} or {@code maxLookups}.</li>
 * </ul>
 * Lookups back off exponentially between {@code minBackoff} and {@code maxBackoff}.
 */
public final class ReconciliationEngine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationEngine.class);
    public static final String SYSTEM_ACTOR = "system:reconciler";

    public record Settings(String node, int batchSize, Duration claimTtl, Duration minBackoff, Duration maxBackoff,
                           int maxLookups, Duration maxAutomaticAge, int notFoundConfirmations, Duration settleDelay,
                           Duration pollInterval) {
        public static Settings defaults(String node) {
            return new Settings(node, 50, Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofHours(1), 20,
                    Duration.ofDays(2), 2, Duration.ofMinutes(5), Duration.ofSeconds(30));
        }
    }

    /** What one pass did. */
    public record PassResult(int examined, int resolved, int escalated, int deferred) {
    }

    /** Called before a manual resolution; throws to refuse it (e.g. RETRY for a class (c) pack operation). */
    @FunctionalInterface
    public interface ResolutionGuard {
        void check(ReconciliationQueue.Item item, ReconciliationQueue.Action action, String actor);
    }

    /**
     * Called after every committed resolution, automatic or manual (outside the resolution transaction). Used to
     * deliver {@code sangam.reconciliationResolved} to the waiting pack instance.
     */
    @FunctionalInterface
    public interface ResolutionListener {
        void resolved(ReconciliationQueue.Item item, ReconciliationQueue.Action action, String actor, String resolutionJson);
    }

    private volatile ResolutionGuard guard = (i, a, u) -> {
    };
    private volatile ResolutionListener listener = (i, a, u, j) -> {
    };

    public ReconciliationEngine hooks(ResolutionGuard guard, ResolutionListener listener) {
        if (guard != null) {
            this.guard = guard;
        }
        if (listener != null) {
            this.listener = listener;
        }
        return this;
    }

    private void notifyResolved(ReconciliationQueue.Item item, ReconciliationQueue.Action action, String actor, String json) {
        try {
            listener.resolved(item, action, actor, json);
        } catch (RuntimeException e) {
            LOG.warn("resolution listener failed for item {}", item.id(), e);
        }
    }

    private record Attempt(int attempts, String lastResult, String escalatedTaskId, int notFoundStreak, long ageMillis) {
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final ReconciliationQueue queue;
    private final OperationLedger ledger;
    private final ConnectorCallJournal journal;
    private final ConnectorRegistry registry;
    private final ReconciliationTaskPort humans;
    private final HeldWorkReleaser releaser;
    private final Settings settings;
    private final ScheduledExecutorService loop;
    private final AtomicBoolean running = new AtomicBoolean();

    public ReconciliationEngine(Dialect dialect, Transactions tx, ReconciliationQueue queue, OperationLedger ledger,
                                ConnectorCallJournal journal, ConnectorRegistry registry, ReconciliationTaskPort humans,
                                HeldWorkReleaser releaser, Settings settings) {
        this.dialect = dialect;
        this.tx = tx;
        this.queue = queue;
        this.ledger = ledger;
        this.journal = journal;
        this.registry = registry;
        this.humans = humans;
        this.releaser = releaser == null ? HeldWorkReleaser.NONE : releaser;
        this.settings = settings;
        this.loop = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sangam-reconciliation-" + settings.node());
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            loop.scheduleWithFixedDelay(() -> {
                try {
                    runOnce();
                } catch (RuntimeException e) {
                    LOG.warn("reconciliation pass failed", e);
                }
            }, 0, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** One pass over due items. */
    public PassResult runOnce() {
        List<ReconciliationQueue.Item> due = claim();
        int resolved = 0, escalated = 0, deferred = 0;
        for (ReconciliationQueue.Item item : due) {
            try {
                switch (process(item)) {
                    case RESOLVED -> resolved++;
                    case ESCALATED -> escalated++;
                    case DEFERRED -> deferred++;
                }
            } catch (RuntimeException e) {
                LOG.warn("reconciliation of item {} failed; retried later", item.id(), e);
                schedule(item.id(), "ERROR: " + e.getMessage(), false);
                deferred++;
            }
        }
        return new PassResult(due.size(), resolved, escalated, deferred);
    }

    private enum Step { RESOLVED, ESCALATED, DEFERRED }

    private List<ReconciliationQueue.Item> claim() {
        return tx.inTransaction(c -> {
            // only the core kinds: POISON_INBOUND items are resolved by PoisonInbound, not by lookups
            String where = "r.state='OPEN' AND r.kind IN ('UNKNOWN_OUTCOME','LATE_RESULT_AFTER_DELETE') AND NOT EXISTS (SELECT 1 FROM RECONCILIATION_ATTEMPT a WHERE a.item_id = r.id"
                    + " AND (a.escalated_task_id IS NOT NULL OR a.next_attempt_at > " + dialect.now() + "))";
            List<Long> ids = Jdbc.query(c, dialect.skipLockedSelect("r.id", "RECONCILIATION_ITEM", "r", where, "r.id", settings.batchSize()),
                    rs -> rs.getLong(1));
            for (Long id : ids) {
                boolean inserted = Jdbc.insertIfAbsent(c, dialect,
                        "INSERT INTO RECONCILIATION_ATTEMPT(item_id, attempts, not_found_streak, next_attempt_at, last_result,"
                                + " escalated_task_id, created_at, updated_at) VALUES (?,0,0," + dialect.nowPlusMillisParam()
                                + ",'CLAIMED',NULL," + dialect.now() + "," + dialect.now() + ")",
                        id, settings.claimTtl().toMillis());
                if (!inserted) {
                    Jdbc.update(c, "UPDATE RECONCILIATION_ATTEMPT SET next_attempt_at=" + dialect.nowPlusMillisParam()
                            + ", updated_at=" + dialect.now() + " WHERE item_id=?", settings.claimTtl().toMillis(), id);
                }
            }
            return ids.stream().map(id -> queue.get(c, id).orElseThrow()).toList();
        });
    }

    private Step process(ReconciliationQueue.Item item) {
        if (item.kind() == ReconciliationQueue.Kind.LATE_RESULT_AFTER_DELETE) {
            return escalate(item, null, "late result after delete: the instance stays deleted; decide on the external effect");
        }
        if (item.operationId() == null) {
            return escalate(item, null, "no operation id on the item");
        }
        Optional<ConnectorCallJournal.Call> call = tx.inTransaction(c -> journal.find(c, item.operationId()));
        Optional<OperationLedger.Entry> entry = tx.inTransaction(c -> ledger.find(c, item.operationId()));
        if (entry.isPresent() && entry.get().state() != OperationLedger.State.UNKNOWN_OUTCOME) {
            // settled by another path (late worker outcome): close the item with the ledger's state
            ReconciliationQueue.Action a = entry.get().state() == OperationLedger.State.FAILED ? ReconciliationQueue.Action.MARK_FAILED
                    : entry.get().state() == OperationLedger.State.SUCCEEDED ? ReconciliationQueue.Action.CONFIRM_SUCCESS
                    : ReconciliationQueue.Action.RETRY_SAME_OPERATION;
            return resolve(item, entry.get(), a, entry.get().outcome() == null ? "{}" : entry.get().outcome());
        }
        if (call.isEmpty()) {
            return escalate(item, null, "no connector call on record (worker-side effect); confirm with the external system");
        }
        ConnectorCallJournal.Call cc = call.get();
        Optional<SangamConnector> connector = registry.find(cc.tenantId(), cc.connectorId());
        if (connector.isEmpty()) {
            return escalate(item, cc.connectorId(), "connector " + cc.connectorId() + " is not registered");
        }
        CapabilityClass cls = connector.get().capabilityClass();
        if (cls == CapabilityClass.C_NEITHER) {
            return escalate(item, cc.connectorId(), "capability class (c): no outcome lookup, human decision required");
        }
        Attempt att = attempt(item.id());
        ConnectorRequest req = new ConnectorRequest(cc.tenantId(), item.operationId(), "reconcile-" + item.id(), att.attempts() + 1,
                cc.rootId(), cc.businessKey(), cc.operation(), cc.requestJson() == null ? Map.of() : Json.readMap(cc.requestJson()),
                java.time.Instant.now().plusSeconds(30));
        LookupResult lr;
        try {
            lr = connector.get().lookup(req);
        } catch (RuntimeException e) {
            lr = new LookupResult.Unavailable(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return switch (lr) {
            case LookupResult.Found f when f.succeeded() -> resolve(item, entry.orElse(null), ReconciliationQueue.Action.CONFIRM_SUCCESS,
                    ConnectorInvoker.successJson(f.output(), f.providerRef(), true));
            case LookupResult.Found f -> resolve(item, entry.orElse(null), ReconciliationQueue.Action.MARK_FAILED,
                    ConnectorInvoker.failureJson(f.failureCode() == null ? "DOWNSTREAM_FAILED" : f.failureCode(),
                            "confirmed by reconciliation lookup", f.output()));
            case LookupResult.NotFound nf -> {
                if (cls == CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP) {
                    yield resolve(item, entry.orElse(null), ReconciliationQueue.Action.RETRY_SAME_OPERATION,
                            "{\"reason\":\"not found downstream; idempotent retry with the same operationId\"}");
                }
                int streak = att.notFoundStreak() + 1;
                if (streak >= settings.notFoundConfirmations()) {
                    yield resolve(item, entry.orElse(null), ReconciliationQueue.Action.RETRY_SAME_OPERATION,
                            "{\"reason\":\"not found downstream after " + streak + " lookups; controlled retry (class b)\"}");
                }
                scheduleAfter(item.id(), "NOT_FOUND", true, settings.settleDelay());
                yield Step.DEFERRED;
            }
            case LookupResult.Unavailable u -> {
                if (att.attempts() + 1 >= settings.maxLookups() || att.ageMillis() > settings.maxAutomaticAge().toMillis()) {
                    yield escalate(item, cc.connectorId(), "lookup unavailable for too long: " + u.reason());
                }
                schedule(item.id(), "UNAVAILABLE: " + u.reason(), false);
                yield Step.DEFERRED;
            }
            case LookupResult.NotSupported ns -> escalate(item, cc.connectorId(), "connector has no outcome lookup");
        };
    }

    private Step resolve(ReconciliationQueue.Item item, OperationLedger.Entry entry, ReconciliationQueue.Action action, String resolutionJson) {
        try {
            tx.inTransaction(c -> {
                queue.resolve(c, item.id(), action, SYSTEM_ACTOR, resolutionJson);
                if (item.operationId() != null) {
                    journal.recordResult(c, item.operationId(), switch (action) {
                        case CONFIRM_SUCCESS -> ConnectorCallJournal.LastResult.SUCCESS;
                        case MARK_FAILED -> ConnectorCallJournal.LastResult.BUSINESS_FAILURE;
                        case RETRY_SAME_OPERATION -> ConnectorCallJournal.LastResult.NO_EFFECT;
                    }, null, "reconciled: " + action);
                }
                Jdbc.update(c, "UPDATE RECONCILIATION_ATTEMPT SET last_result=?, updated_at=" + dialect.now() + " WHERE item_id=?",
                        "RESOLVED " + action, item.id());
                return null;
            });
        } catch (ReconciliationQueue.AlreadyResolvedException e) {
            return Step.RESOLVED;
        }
        release(item, entry);
        notifyResolved(item, action, SYSTEM_ACTOR, resolutionJson);
        return Step.RESOLVED;
    }

    /**
     * Human resolution from the workbench task (confirm success with result / retry with the same operationId / mark
     * failed). The resolution is audited on the root's chain ({@code RECONCILIATION_ACTION}) with the actor.
     */
    public ReconciliationQueue.Item resolveManually(long itemId, ReconciliationQueue.Action action, String actor,
                                                     Map<String, Object> result, String reason) {
        ReconciliationQueue.Item item = tx.inTransaction(c -> queue.get(c, itemId))
                .orElseThrow(() -> new IllegalArgumentException("no reconciliation item " + itemId));
        guard.check(item, action, actor);
        String json = switch (action) {
            case CONFIRM_SUCCESS -> {
                Map<String, Object> out = result == null ? Map.of() : result;
                yield ConnectorInvoker.successJson(out, out.get("providerRef") == null ? null : String.valueOf(out.get("providerRef")), true);
            }
            case MARK_FAILED -> ConnectorInvoker.failureJson(result != null && result.get("code") != null ? String.valueOf(result.get("code"))
                    : "MARKED_FAILED", reason, result);
            case RETRY_SAME_OPERATION -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("reason", reason);
                yield Json.write(m);
            }
        };
        ReconciliationQueue.Item done = tx.inTransaction(c -> {
            ReconciliationQueue.Item r = queue.resolve(c, itemId, action, actor, json);
            if (item.operationId() != null) {
                journal.find(c, item.operationId()).ifPresent(x -> journal.recordResult(c, item.operationId(),
                        action == ReconciliationQueue.Action.RETRY_SAME_OPERATION ? ConnectorCallJournal.LastResult.NO_EFFECT
                                : action == ReconciliationQueue.Action.CONFIRM_SUCCESS ? ConnectorCallJournal.LastResult.SUCCESS
                                : ConnectorCallJournal.LastResult.BUSINESS_FAILURE, null, "resolved by " + actor));
            }
            return r;
        });
        if (item.kind() == ReconciliationQueue.Kind.UNKNOWN_OUTCOME) {
            release(item, item.operationId() == null ? null : tx.inTransaction(c -> ledger.find(c, item.operationId())).orElse(null));
        }
        notifyResolved(item, action, actor, json);
        return done;
    }

    private void release(ReconciliationQueue.Item item, OperationLedger.Entry entry) {
        if (item.kind() != ReconciliationQueue.Kind.UNKNOWN_OUTCOME || item.operationId() == null) {
            return;
        }
        String ai = entry == null ? null : entry.activityInstanceId();
        if (ai != null && ai.contains("#")) {
            ai = ai.substring(0, ai.indexOf('#'));
        }
        try {
            releaser.release(item.tenantId(), item.rootId(), item.operationId(), ai);
        } catch (RuntimeException e) {
            LOG.warn("cannot release held work of operation {}; release it from the operator view", item.operationId(), e);
        }
    }

    private Step escalate(ReconciliationQueue.Item item, String connectorId, String reason) {
        String taskId = humans == null ? null : humans.openHumanTask(item, connectorId, reason);
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE RECONCILIATION_ATTEMPT SET escalated_task_id=?, last_result=?, updated_at="
                + dialect.now() + " WHERE item_id=?", taskId == null ? "unassigned" : taskId, "ESCALATED: " + truncate(reason), item.id()));
        LOG.info("reconciliation item {} escalated to a human: {}", item.id(), reason);
        return Step.ESCALATED;
    }

    private Attempt attempt(long itemId) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT a.attempts, a.last_result, a.escalated_task_id, a.not_found_streak, r.created_at"
                        + " FROM RECONCILIATION_ATTEMPT a JOIN RECONCILIATION_ITEM r ON r.id = a.item_id WHERE a.item_id=?",
                rs -> {
                    java.time.Instant created = Jdbc.instant(rs, "created_at");
                    long age = created == null ? 0 : System.currentTimeMillis() - created.toEpochMilli();
                    return new Attempt(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), age);
                }, itemId).orElse(new Attempt(0, null, null, 0, 0)));
    }

    private void schedule(long itemId, String result, boolean notFound) {
        Attempt a = attempt(itemId);
        long backoff = Math.min(settings.maxBackoff().toMillis(),
                settings.minBackoff().toMillis() * (1L << Math.min(a.attempts(), 16)));
        scheduleAfter(itemId, result, notFound, Duration.ofMillis(backoff));
    }

    private void scheduleAfter(long itemId, String result, boolean notFound, Duration after) {
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE RECONCILIATION_ATTEMPT SET attempts=attempts+1, not_found_streak="
                        + (notFound ? "not_found_streak+1" : "0") + ", last_result=?, next_attempt_at=" + dialect.nowPlusMillisParam()
                        + ", updated_at=" + dialect.now() + " WHERE item_id=?",
                truncate(result), after.toMillis(), itemId));
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 1000);
    }

    @Override
    public void close() {
        running.set(false);
        loop.shutdownNow();
    }
}
