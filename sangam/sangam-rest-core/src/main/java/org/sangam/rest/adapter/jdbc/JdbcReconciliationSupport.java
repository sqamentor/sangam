// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Relay;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.OpsPort.OutboxDelivery;
import org.sangam.rest.port.OpsPort.OutboxStats;
import org.sangam.rest.port.OpsPort.ReconciliationItem;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * Product-layer operator views over the core tables: the reconciliation workbench (RECONCILIATION_ITEM, VI.3 rule 4,
 * VI.5) and the outbox (OUTBOX / OUTBOX_DELIVERY, VI.2). Repairs go through the core {@link Relay} repair API
 * ({@code PARKED → PENDING | SKIPPED}), which keeps the VI.1 lock order and writes the class-B repair event on the
 * root chain. All reads are tenant-scoped (K1).
 */
public class JdbcReconciliationSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TxSupport tx;
    private final RestProperties props;
    private final ReconciliationQueue queue;
    private final Relay relayRepair;
    /**
     * The deployment's repair implementation (the production relay service, VI.2.3 rule 5); it yields null only when
     * the deployment has none (no sangam-integration, hence no dead letters), and then the plain relay repairs.
     */
    private final java.util.function.Supplier<org.sangam.core.layer.OutboxRepair> outboxRepair;

    private org.sangam.core.layer.OutboxRepair repairer() {
        org.sangam.core.layer.OutboxRepair wired = outboxRepair.get();
        return wired != null ? wired : relayRepair;
    }

    public JdbcReconciliationSupport(TxSupport tx, RestProperties props,
                                     java.util.function.Supplier<org.sangam.core.layer.OutboxRepair> outboxRepair) {
        this.tx = tx;
        this.props = props;
        this.outboxRepair = java.util.Objects.requireNonNull(outboxRepair, "outboxRepair supplier");
        AuditChain audit = new AuditChain(tx.dialect());
        this.queue = new ReconciliationQueue(tx.dialect(), audit);
        // Fallback repair only, when no OutboxRepair is wired; publishing is the relay service's job (sangam-integration).
        this.relayRepair = new Relay(tx.dialect(), tx.newTransactions(), (destination, event) -> {
            throw new IllegalStateException("the operator view does not publish");
        }, Relay.Config.defaults(props.getNode()), audit);
    }

    // ------------------------------------------------------------------ reconciliation

    public Page<ReconciliationItem> list(RequestContext ctx, String state, String kind, Page.PageRequest page) {
        StringBuilder where = new StringBuilder(" WHERE tenant_id=?");
        List<Object> params = new ArrayList<>();
        params.add(ctx.tenantId());
        if (state != null) {
            where.append(" AND state=?");
            params.add(state);
        }
        if (kind != null) {
            where.append(" AND kind=?");
            params.add(kind);
        }
        long cap = props.getCountCap();
        return tx.withConnection(c -> {
            List<ReconciliationItem> rows = JdbcAuditAdapter.limited(c,
                    "SELECT * FROM RECONCILIATION_ITEM" + where + " ORDER BY id DESC", page.firstResult() + page.size(),
                    JdbcReconciliationSupport::item, params.toArray());
            List<Long> probe = JdbcAuditAdapter.limited(c, "SELECT id FROM RECONCILIATION_ITEM" + where,
                    (int) Math.min(Integer.MAX_VALUE, cap + 1), rs -> rs.getLong(1), params.toArray());
            boolean capped = probe.size() > cap;
            return Page.of(rows.subList(Math.min(rows.size(), page.firstResult()), rows.size()),
                    capped ? cap : probe.size(), capped);
        });
    }

    public ReconciliationItem get(RequestContext ctx, String id) {
        long itemId = parseId(id, "reconciliation item");
        return tx.withConnection(c -> Jdbc.one(c, "SELECT * FROM RECONCILIATION_ITEM WHERE id=? AND tenant_id=?",
                JdbcReconciliationSupport::item, itemId, ctx.tenantId()))
                .orElseThrow(() -> ApiProblem.notFound("reconciliation item", id));
    }

    /** Resolves in the caller's transaction (ledger update + RECONCILIATION_ACTION audit by the core queue). */
    public ReconciliationItem resolve(RequestContext ctx, String id, ReconciliationQueue.Action action,
                                      String resolutionJson) {
        get(ctx, id);   // tenant check, 404 otherwise
        long itemId = parseId(id, "reconciliation item");
        try {
            tx.withConnection(c -> queue.resolve(c, itemId, action, ctx.principalId(), resolutionJson));
        } catch (ReconciliationQueue.AlreadyResolvedException e) {
            throw ApiProblem.conflict("ALREADY_RESOLVED", "Reconciliation item " + id + " is already resolved");
        } catch (IllegalArgumentException e) {
            throw ApiProblem.unprocessable("RECONCILIATION_ACTION_NOT_ALLOWED", e.getMessage());
        }
        return get(ctx, id);
    }

    // ------------------------------------------------------------------ outbox

    private static final String DELIVERY_COLUMNS = "d.outbox_id, d.destination, d.root_id, d.root_seq, o.event_type,"
            + " d.state, d.attempts, d.next_attempt_at, d.last_error, o.created_at";
    private static final String DELIVERY_FROM = " FROM OUTBOX_DELIVERY d JOIN OUTBOX o ON o.id = d.outbox_id";

    public Page<OutboxDelivery> outbox(RequestContext ctx, String state, String destination, Page.PageRequest page) {
        StringBuilder where = new StringBuilder(" WHERE d.tenant_id=?");
        List<Object> params = new ArrayList<>();
        params.add(ctx.tenantId());
        if (state != null) {
            where.append(" AND d.state=?");
            params.add(state);
        }
        if (destination != null) {
            where.append(" AND d.destination=?");
            params.add(destination);
        }
        long cap = props.getCountCap();
        return tx.withConnection(c -> {
            List<OutboxDelivery> rows = JdbcAuditAdapter.limited(c, "SELECT " + DELIVERY_COLUMNS + DELIVERY_FROM + where
                            + " ORDER BY d.outbox_id DESC", page.firstResult() + page.size(),
                    JdbcReconciliationSupport::delivery, params.toArray());
            List<Long> probe = JdbcAuditAdapter.limited(c, "SELECT d.outbox_id" + DELIVERY_FROM + where,
                    (int) Math.min(Integer.MAX_VALUE, cap + 1), rs -> rs.getLong(1), params.toArray());
            boolean capped = probe.size() > cap;
            return Page.of(rows.subList(Math.min(rows.size(), page.firstResult()), rows.size()),
                    capped ? cap : probe.size(), capped);
        });
    }

    /**
     * Counts for the tenant; the budget and the back-pressure level are global (VI.2.4: the outbox budget is a
     * property of the database): NONE below 60 %, WARN from 60 %, REFUSE_NEW from 80 %.
     */
    public OutboxStats stats(RequestContext ctx) {
        return tx.withConnection(c -> {
            Map<String, Long> byState = new LinkedHashMap<>();
            Jdbc.query(c, "SELECT state, COUNT(*) FROM OUTBOX_DELIVERY WHERE tenant_id=? GROUP BY state",
                    rs -> byState.put(rs.getString(1), rs.getLong(2)), ctx.tenantId());
            long outstanding = Jdbc.one(c,
                    "SELECT COUNT(*) FROM OUTBOX_DELIVERY WHERE state IN ('PENDING','CLAIMED','PARKED')",
                    rs -> rs.getLong(1)).orElse(0L);
            long budget = Math.max(1, props.getOutboxBudget());
            double used = outstanding * 100.0 / budget;
            String level = used >= 80.0 ? "REFUSE_NEW" : used >= 60.0 ? "WARN" : "NONE";
            return new OutboxStats(byState.getOrDefault("PENDING", 0L), byState.getOrDefault("CLAIMED", 0L),
                    byState.getOrDefault("PARKED", 0L), byState.getOrDefault("SKIPPED", 0L), budget,
                    Math.round(used * 100.0) / 100.0, level);
        });
    }

    public OutboxDelivery repair(RequestContext ctx, String outboxId, String destination, boolean requeue,
                                 String reason) {
        long id = parseId(outboxId, "outbox event");
        String dest = destination;
        List<OutboxDelivery> deliveries = tx.withConnection(c -> Jdbc.query(c, "SELECT " + DELIVERY_COLUMNS
                + DELIVERY_FROM + " WHERE d.outbox_id=? AND d.tenant_id=?", JdbcReconciliationSupport::delivery, id,
                ctx.tenantId()));
        if (deliveries.isEmpty()) {
            throw ApiProblem.notFound("outbox event", outboxId);
        }
        if (dest == null) {
            if (deliveries.size() != 1) {
                throw ApiProblem.badRequest("DESTINATION_REQUIRED",
                        "Outbox event " + outboxId + " has several destinations; name one");
            }
            dest = deliveries.get(0).destination();
        }
        String target = dest;
        if (deliveries.stream().noneMatch(d -> d.destination().equals(target))) {
            throw ApiProblem.notFound("outbox delivery", outboxId + "/" + target);
        }
        org.sangam.core.layer.OutboxRepair repair = repairer();
        boolean done = requeue ? repair.requeue(id, target, ctx.principalId(), reason)
                : repair.skip(id, target, ctx.principalId(), reason);
        if (!done) {
            throw ApiProblem.conflict("NOT_PARKED", "Only a PARKED delivery can be " + (requeue ? "requeued" : "skipped"));
        }
        return tx.withConnection(c -> Jdbc.one(c, "SELECT " + DELIVERY_COLUMNS + DELIVERY_FROM
                        + " WHERE d.outbox_id=? AND d.destination=?", JdbcReconciliationSupport::delivery, id, target))
                .orElseThrow(() -> ApiProblem.notFound("outbox delivery", outboxId));
    }

    /** Root id of a reconciliation item, if any (for the root-chain audit event). */
    public Optional<String> rootOf(ReconciliationItem item) {
        return Optional.ofNullable(item.rootId());
    }

    // ------------------------------------------------------------------ mapping

    private static long parseId(String id, String what) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw ApiProblem.notFound(what, id);
        }
    }

    private static Object json(String text) {
        if (text == null) {
            return null;
        }
        try {
            return JSON.readValue(text, Object.class);
        } catch (java.io.IOException e) {
            return text;
        }
    }

    private static ReconciliationItem item(ResultSet rs) throws SQLException {
        return new ReconciliationItem(String.valueOf(rs.getLong("id")), rs.getString("kind"), rs.getString("operation_id"),
                rs.getString("root_id"), rs.getString("task_id"), rs.getString("state"), json(rs.getString("details")),
                rs.getString("action"), rs.getString("resolved_by"), json(rs.getString("resolution")),
                Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "resolved_at"));
    }

    private static OutboxDelivery delivery(ResultSet rs) throws SQLException {
        return new OutboxDelivery(String.valueOf(rs.getLong(1)), rs.getString(2), rs.getString(3), rs.getLong(4),
                rs.getString(5), rs.getString(6), rs.getInt(7), Jdbc.instant(rs, "next_attempt_at"), rs.getString(9),
                Jdbc.instant(rs, "created_at"));
    }
}
