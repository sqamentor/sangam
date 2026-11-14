// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;
import org.sangam.integration.reconciliation.ReconciliationEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Connects reconciliation resolutions to the pack operations (sangam-packs README §4, OJ-02 / O04):
 * <ul>
 *   <li><b>Guard</b>: {@code RETRY_SAME_OPERATION} is refused for class (c) pack operations
 *       ({@code 422 RETRY_NOT_ALLOWED}, VI.3 rule 5); the operator confirms success or failure with evidence.</li>
 *   <li><b>Listener</b>: after a resolution of an operation still waiting (row UNKNOWN or PENDING), the row is settled
 *       under its lock and {@code sangam.reconciliationResolved(resolution, resolutionEvidence, resolvedBy)} is
 *       delivered with resolution {@code CONFIRMED_SUCCESS}, {@code CONFIRMED_FAILURE} or {@code RETRY_SAME_OPERATION}.
 *       A retry of an operation of a process instance marks the row RETRY_REQUESTED, so the pack's next
 *       {@code submit} dispatches again with the same operationId.</li>
 *   <li><b>Decision 290</b>: an operation with no process instance (for example a REST-backed data-object create) has
 *       no pack to re-submit it, so a retry re-dispatches it at once with the same operationId (PENDING, next
 *       generation, a new dispatch event, in the resolving transaction): idempotent, no duplicate. Automatic retries
 *       (actor {@value ReconciliationEngine#SYSTEM_ACTOR}) are bounded by {@code maxRedispatches}: once the operation was
 *       re-dispatched that many times, the row stays UNKNOWN with reason {@value #REDISPATCH_LIMIT_REACHED} and a human
 *       reconciliation item is opened ({@link ReconciliationEngine#openForHuman}). A human's retry re-dispatches.</li>
 * </ul>
 * Final states go through {@link PackOperationStore#settle}, which calls the settlement listener of the operation's
 * kind once (decision 289), and store {@code result_json} in the dispatch shape ({@link #storedResult}).
 */
public final class PackReconciliationHooks implements ReconciliationEngine.ResolutionGuard, ReconciliationEngine.ResolutionListener {

    private static final Logger LOG = LoggerFactory.getLogger(PackReconciliationHooks.class);

    /** The default bound on automatic re-dispatches of an operation with no process instance (decision 290). */
    public static final int DEFAULT_MAX_REDISPATCHES = 3;
    public static final String REDISPATCH_LIMIT_REACHED = "REDISPATCH_LIMIT_REACHED";

    /** Opens a human reconciliation item (decision 290); {@link ReconciliationEngine} in production. */
    public interface HumanReconciliation {
        /** In the caller's transaction on {@code c}; returns the item id. */
        long open(Connection c, UUID ledgerOperationId, String reason);

        /** After the commit: opens the human task of the item. */
        void escalate(long itemId, String reason);
    }

    private final Transactions tx;
    private final PackOperationStore store;
    private final IntegrationConnectorGateway gateway;
    private final PackMessages messages;
    private final int maxRedispatches;
    private volatile HumanReconciliation humans;

    public PackReconciliationHooks(Transactions tx, PackOperationStore store, IntegrationConnectorGateway gateway, PackMessages messages) {
        this(tx, store, gateway, messages, DEFAULT_MAX_REDISPATCHES);
    }

    /** @param maxRedispatches automatic re-dispatches of an operation with no process instance before a human decides (at least 1) */
    public PackReconciliationHooks(Transactions tx, PackOperationStore store, IntegrationConnectorGateway gateway, PackMessages messages,
                                   int maxRedispatches) {
        if (maxRedispatches < 1) {
            throw new IllegalArgumentException("maxRedispatches must be at least 1");
        }
        this.tx = tx;
        this.store = store;
        this.gateway = gateway;
        this.messages = messages;
        this.maxRedispatches = maxRedispatches;
    }

    /** Where a used-up operation goes (set by {@link PacksIntegration#wire}). */
    public PackReconciliationHooks humans(HumanReconciliation humans) {
        this.humans = humans;
        return this;
    }

    public int maxRedispatches() {
        return maxRedispatches;
    }

    private Optional<PackOperationStore.Row> rowOf(Connection c, ReconciliationQueue.Item item) {
        return item.operationId() != null ? store.byLedgerOperation(c, item.operationId())
                : item.taskId() != null ? store.find(c, item.tenantId(), item.taskId()) : Optional.empty();
    }

    @Override
    public void check(ReconciliationQueue.Item item, ReconciliationQueue.Action action, String actor) {
        if (action != ReconciliationQueue.Action.RETRY_SAME_OPERATION) {
            return;
        }
        tx.inTransaction(c -> rowOf(c, item)).ifPresent(r -> {
            if (gateway.capabilityOf(r.connector()) == CapabilityClass.C_NEITHER) {
                throw new IllegalArgumentException("422 RETRY_NOT_ALLOWED: class (c) operations are never repeated;"
                        + " confirm success or failure with evidence (VI.3 rule 5)");
            }
        });
    }

    private enum Step { SETTLED, REDISPATCHED, TO_HUMAN }

    private record Done(PackOperationStore.Row row, Step step, long humanItem) {
    }

    @Override
    public void resolved(ReconciliationQueue.Item item, ReconciliationQueue.Action action, String actor, String resolutionJson) {
        Map<String, Object> res = Json.readMap(resolutionJson);
        String resolution;
        PackOperationStore.Status status;
        String reason = null;
        String ref = res.get("providerRef") == null ? null : String.valueOf(res.get("providerRef"));
        switch (action) {
            case CONFIRM_SUCCESS -> {
                resolution = "CONFIRMED_SUCCESS";
                status = PackOperationStore.Status.COMPLETED;
            }
            case MARK_FAILED -> {
                resolution = "CONFIRMED_FAILURE";
                status = PackOperationStore.Status.FAILED;
                reason = res.get("code") == null ? "CONFIRMED_FAILURE" : String.valueOf(res.get("code"));
            }
            default -> {
                resolution = "RETRY_SAME_OPERATION";
                status = PackOperationStore.Status.RETRY_REQUESTED;
            }
        }
        // the connector output, whichever path settles (decision 289: the listener sees the same shape as on dispatch)
        Map<String, Object> output = asMap(res.get("output"));
        boolean automatic = ReconciliationEngine.SYSTEM_ACTOR.equals(actor);
        HumanReconciliation h = humans;
        String finalReason = reason;
        Optional<Done> done = tx.inTransaction(c -> {
            Optional<PackOperationStore.Row> found = rowOf(c, item);
            if (found.isEmpty()) {
                return Optional.<Done>empty();
            }
            Optional<PackOperationStore.Row> locked = store.lock(c, found.get().tenantId(), found.get().operationId());
            if (locked.isEmpty()) {
                return Optional.<Done>empty();
            }
            PackOperationStore.Row r = locked.get();
            if (r.status() != PackOperationStore.Status.UNKNOWN && r.status() != PackOperationStore.Status.PENDING) {
                return Optional.<Done>empty();   // already settled (e.g. by a lookup the pack made itself)
            }
            if (status == PackOperationStore.Status.RETRY_REQUESTED && r.processInstanceId() == null) {
                // decision 290: generation counts dispatches, so generation - 1 re-dispatches happened already
                if (automatic && r.generation() - 1 >= maxRedispatches) {
                    if (h == null) {
                        throw new IllegalStateException("no human reconciliation is wired for operation " + r.operationId());
                    }
                    String why = "not found downstream after " + (r.generation() - 1)
                            + " re-dispatches with the same operationId (limit " + maxRedispatches + "); decide on the external effect";
                    store.settle(c, r.tenantId(), r.operationId(), PackOperationStore.Status.UNKNOWN, REDISPATCH_LIMIT_REACHED,
                            null, Json.write(Map.of("reason", why)));
                    long itemId = h.open(c, r.ledgerOperationId(), why);
                    return Optional.of(new Done(r, Step.TO_HUMAN, itemId));
                }
                gateway.redispatch(c, r);
                return Optional.of(new Done(r, Step.REDISPATCHED, 0));
            }
            store.settle(c, r.tenantId(), r.operationId(), status, finalReason, ref, storedResult(status, r, output, res),
                    status == PackOperationStore.Status.RETRY_REQUESTED ? null : output, actor);
            return Optional.of(new Done(r, Step.SETTLED, 0));
        });
        if (done.isEmpty()) {
            return;
        }
        PackOperationStore.Row r = done.get().row();
        if (done.get().step() == Step.TO_HUMAN) {
            LOG.info("pack operation {} reached the re-dispatch limit; reconciliation item {} goes to a human",
                    r.operationId(), done.get().humanItem());
            h.escalate(done.get().humanItem(), "pack operation " + r.operationId() + ": " + REDISPATCH_LIMIT_REACHED);
            return;
        }
        if (done.get().step() == Step.REDISPATCHED) {
            LOG.info("pack operation {} re-dispatched with the same operationId (generation {})", r.operationId(), r.generation() + 1);
        }
        messages.reconciliationResolved(r, resolution, resolutionJson, actor, reason);
    }

    /**
     * The {@code result_json} a reconciliation settlement stores: the same shape as a dispatch settlement
     * ({@link PackDispatchPublisher}), never the resolution wrapper {@code {output, providerRef, duplicate}}, so the
     * operation's lookup answers with the record whichever path settled it. COMPLETED stores the connector output,
     * FAILED {@code {message}}, RETRY_REQUESTED keeps what the row had.
     */
    static String storedResult(PackOperationStore.Status status, PackOperationStore.Row r, Map<String, Object> output,
                               Map<String, Object> resolution) {
        return switch (status) {
            case COMPLETED -> Json.write(output);
            case FAILED -> Json.write(Map.of("message", resolution.get("message") == null ? "confirmed failure"
                    : String.valueOf(resolution.get("message"))));
            default -> r.resultJson();
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
