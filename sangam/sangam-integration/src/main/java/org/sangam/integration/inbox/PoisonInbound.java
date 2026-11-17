// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;
import org.sangam.integration.relay.DeadLetterStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.sangam.core.security.LogSafe;

/**
 * Poison inbound messages (R38, VI.2.6): a broker message that can never be stored, because its body is not valid JSON
 * or the mapper cannot read it, must not be redelivered forever. The consumer acks it after this class has
 *
 * <ol>
 *   <li>written it to {@code INTEGRATION_DEAD_LETTER} (kind {@code INBOX}, ref {@code source/messageId}, raw body
 *       truncated to 64 KiB, error), and</li>
 *   <li>opened a reconciliation item of kind {@value #KIND} for that dead letter, exactly once
 *       ({@code task_id = "dead-letter:<id>"}), so the operations console lists it with the other items, and</li>
 *   <li>written {@code POISON_INBOUND_RECEIVED} on the tenant's security audit chain ({@code sec:<tenant>}),</li>
 * </ol>
 * all in one transaction. The operator then {@link #reprocess reprocesses} it with a corrected payload (stored in the
 * inbox under the original message id) or {@link #discard discards} it; both are audited.
 *
 * <p><b>Parked messages</b> (SL-142, owner decision 2026-11-12). A well-formed message of a tenant that has left this
 * cell for good (MOVED or PURGED, {@link org.sangam.integration.relay.TenantAdmission.Action#PARK}) is {@link #park
 * parked} the same way: one transaction writes the dead letter with the <b>full</b> message (not truncated, the broker
 * no longer holds it after the ack), a {@value #KIND} item whose details carry {@code "parked":true}, the tenant
 * state, the target cell and move id, and {@code INBOUND_PARKED} on {@code sec:<tenant>}. Only then does the consumer
 * ack. The operator {@link #replayParked replays} it to the tenant's new cell (the transport is the caller's
 * {@link ParkedReplay}; the target inbox deduplicates by tenant, source and the original message id) or discards it.
 * A parked item is never reprocessed into this cell's inbox.
 *
 * <p>{@code POISON_INBOUND} is a value of the core {@code ReconciliationQueue.Kind} enum, but these items are written
 * and resolved here with SQL, because the core queue's actions do not close the dead letter. The reconciliation
 * engine only claims UNKNOWN_OUTCOME / LATE_RESULT_AFTER_DELETE items.
 */
public final class PoisonInbound {

    private static final Logger LOG = LoggerFactory.getLogger(PoisonInbound.class);

    public static final String KIND = "POISON_INBOUND";
    /** Security audit event of a parked message (SL-142). */
    public static final String PARKED_EVENT = "INBOUND_PARKED";
    /** Dead-letter error prefix of a parked message, followed by the tenant state. */
    public static final String PARKED_PREFIX = "PARKED: tenant ";
    private static final int MAX_BODY = 64 * 1024;

    /** Thrown by {@link InboxService#receive} for a message that can never be stored. Not transient. */
    public static final class PoisonMessageException extends RuntimeException {
        public PoisonMessageException(String message) {
            super(message);
        }
    }

    /** @param parked true for a message parked for a MOVED/PURGED tenant (SL-142), false for a poison message */
    public record Item(long itemId, String tenantId, long deadLetterId, String source, String messageId, String error,
                       String state, Instant createdAt, boolean parked) {
    }

    /** A parked message with everything needed to replay it to the tenant's new cell (SL-142). */
    public record Parked(long itemId, String tenantId, long deadLetterId, String source, String messageId, String eventType,
                         String correlationKey, String claimedTenant, String payloadJson, String tenantState,
                         String targetCell, String moveId) {

        /** The message as the new cell's {@link InboxService#receive} takes it: original id, type, key and tenant. */
        public InboundMessage message() {
            return new InboundMessage(messageId, eventType, correlationKey, tenantId, payloadJson);
        }
    }

    /** The transport that hands a parked message to the tenant's new cell (operator replay, SL-142). */
    @FunctionalInterface
    public interface ParkedReplay {
        /** Delivers the message; an exception leaves the item open. Must be safe to repeat (the target deduplicates). */
        void replay(Parked message) throws Exception;
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final DeadLetterStore deadLetters;
    private final AuditChain audit;

    public PoisonInbound(Dialect dialect, Transactions tx, DeadLetterStore deadLetters, AuditChain auditOrNull) {
        this.dialect = dialect;
        this.tx = tx;
        this.deadLetters = deadLetters;
        this.audit = auditOrNull;
    }

    /**
     * Records a poison message. Never throws for the message itself; returns the reconciliation item id, or -1 when even
     * the dead-letter write failed (the caller then must not ack, so the message is not lost).
     */
    public long record(ChannelBinding binding, String messageId, String eventTypeOrNull, String rawBody, String reason) {
        String tenant = binding.tenantId() != null ? binding.tenantId()
                : binding.allowedTenants().stream().sorted().findFirst().orElse("*");
        String ref = binding.source() + "/" + (messageId == null ? "unknown" : messageId);
        String body = rawBody == null ? null : rawBody.length() > MAX_BODY ? rawBody.substring(0, MAX_BODY) : rawBody;
        try {
            return tx.inTransaction(c -> {
                if (audit != null) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("source", binding.source());
                    p.put("messageId", messageId);
                    p.put("principal", binding.principal());
                    p.put("reason", reason);
                    audit.append(c, tenant, "sec:" + tenant, "POISON_INBOUND_RECEIVED", p);
                }
                long dl = deadLetters.record(c, tenant, DeadLetterStore.Kind.INBOX, ref, binding.source(), null, eventTypeOrNull, body,
                        "POISON: " + reason);
                String taskId = "dead-letter:" + dl;
                Optional<Long> existing = Jdbc.one(c, "SELECT id FROM RECONCILIATION_ITEM WHERE tenant_id=? AND kind=? AND task_id=?",
                        rs -> rs.getLong(1), tenant, KIND, taskId);
                if (existing.isPresent()) {
                    return existing.get();
                }
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("deadLetterId", dl);
                d.put("source", binding.source());
                d.put("messageId", messageId);
                d.put("eventType", eventTypeOrNull);
                d.put("reason", reason);
                return Jdbc.insertReturningId(c, dialect,
                        "INSERT INTO RECONCILIATION_ITEM(tenant_id, kind, operation_id, root_id, task_id, state, details, created_at)"
                                + " VALUES (?,?,NULL,NULL,?,'OPEN',?," + dialect.now() + ")",
                        "id", tenant, KIND, taskId, Json.write(d));
            });
        } catch (RuntimeException e) {
            LOG.error("cannot dead-letter poison message {} from {}; it will be redelivered", messageId, binding.source(), e);
            return -1;
        }
    }

    /**
     * Parks a well-formed message of a tenant that has left this cell for good (SL-142): dead letter with the full
     * message, a {@value #KIND} item marked {@code parked}, and {@code INBOUND_PARKED} on the tenant's security chain,
     * in one transaction, under the resolved {@code tenant}. A redelivery of a message already parked (an ack lost after
     * the park committed) returns the existing item and writes nothing new. Never throws for the message itself;
     * returns the reconciliation item id, or -1 when the write failed (the caller then must not ack).
     *
     * @param tenant   the tenant the binding resolved for the message
     * @param decision the {@link org.sangam.integration.relay.TenantAdmission.Action#PARK PARK} decision
     */
    public long park(ChannelBinding binding, String tenant, InboundMessage m,
                     org.sangam.integration.relay.TenantAdmission.Decision decision) {
        if (decision.action() != org.sangam.integration.relay.TenantAdmission.Action.PARK) {
            throw new IllegalArgumentException("park needs a PARK decision, not " + decision.action());
        }
        String ref = binding.source() + "/" + m.messageId();
        try {
            return tx.inTransaction(c -> {
                long dl = deadLetters.record(c, tenant, DeadLetterStore.Kind.INBOX, ref, binding.source(), null, m.eventType(),
                        m.payloadJson(), PARKED_PREFIX + decision.tenantState());
                String taskId = "dead-letter:" + dl;
                Optional<Long> existing = Jdbc.one(c, "SELECT id FROM RECONCILIATION_ITEM WHERE tenant_id=? AND kind=? AND task_id=?",
                        rs -> rs.getLong(1), tenant, KIND, taskId);
                if (existing.isPresent()) {
                    return existing.get();
                }
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("parked", true);
                d.put("deadLetterId", dl);
                d.put("source", binding.source());
                d.put("messageId", m.messageId());
                d.put("eventType", m.eventType());
                d.put("correlationKey", m.correlationKey());
                d.put("claimedTenant", m.claimedTenant());
                d.put("tenantState", decision.tenantState());
                d.put("targetCell", decision.targetCellOrNull());
                d.put("moveId", decision.moveIdOrNull());
                long item = Jdbc.insertReturningId(c, dialect,
                        "INSERT INTO RECONCILIATION_ITEM(tenant_id, kind, operation_id, root_id, task_id, state, details, created_at)"
                                + " VALUES (?,?,NULL,NULL,?,'OPEN',?," + dialect.now() + ")",
                        "id", tenant, KIND, taskId, Json.write(d));
                if (audit != null) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("itemId", item);
                    p.put("deadLetterId", dl);
                    p.put("source", binding.source());
                    p.put("messageId", m.messageId());
                    p.put("eventType", m.eventType());
                    p.put("principal", binding.principal());
                    p.put("tenantState", decision.tenantState());
                    p.put("targetCell", decision.targetCellOrNull());
                    p.put("moveId", decision.moveIdOrNull());
                    audit.append(c, tenant, "sec:" + tenant, PARKED_EVENT, p);
                }
                return item;
            });
        } catch (RuntimeException e) {
            LOG.error("cannot park message {} from {} for tenant {}; it will be redelivered", LogSafe.text(m.messageId()),
                    LogSafe.text(binding.source()), LogSafe.text(tenant), e);
            return -1;
        }
    }

    /**
     * The parked message behind an open item (SL-142), of ANY tenant: for the cell operator only (owner decision 61,
     * {@code /admin/cell/parked-inbound}, role CELL_ADMIN). A tenant-scoped caller uses {@link #parked(String, long)}.
     * Refused for a poison item or a resolved one.
     */
    public Parked parked(long itemId) {
        return parkedWhere(itemId, null);
    }

    /**
     * The parked message behind an open item of {@code tenant} only; an item of another tenant is not found (the same
     * {@link IllegalArgumentException} as an unknown id, so its existence is not disclosed).
     */
    public Parked parked(String tenant, long itemId) {
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalArgumentException("a tenant is required");
        }
        return parkedWhere(itemId, tenant);
    }

    /** Open parked items of every tenant of this cell (SL-142, decision 61), oldest first: for the cell operator only. */
    public List<Item> listParked() {
        return tx.read(c -> Jdbc.query(c, "SELECT r.id, r.tenant_id, r.task_id, r.state, r.created_at, r.details, d.ref_id, d.error"
                        + " FROM RECONCILIATION_ITEM r JOIN INTEGRATION_DEAD_LETTER d ON r.task_id = " + concat("'dead-letter:'", idText("d.id"))
                        + " WHERE r.kind=? AND r.state='OPEN' ORDER BY r.id",
                PoisonInbound::map, KIND)).stream().filter(Item::parked).toList();
    }

    /** One item (poison or parked, any state) of {@code tenant} only; another tenant's item is not found. */
    public Item item(String tenant, long itemId) {
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalArgumentException("a tenant is required");
        }
        return get(tenant, itemId);
    }

    private Parked parkedWhere(long itemId, String tenantOrNull) {
        String scope = tenantOrNull == null ? "" : " AND r.tenant_id=?";
        Object[] args = tenantOrNull == null ? new Object[]{itemId, KIND} : new Object[]{itemId, KIND, tenantOrNull};
        return tx.read(c -> Jdbc.one(c, "SELECT r.id, r.tenant_id, r.task_id, r.state, r.details, d.ref_id, d.event_type,"
                        + " d.payload FROM RECONCILIATION_ITEM r JOIN INTEGRATION_DEAD_LETTER d ON r.task_id = "
                        + concat("'dead-letter:'", idText("d.id")) + " WHERE r.id=? AND r.kind=?" + scope, rs -> {
                    Map<String, Object> d = Json.readMap(rs.getString("details"));
                    if (!Boolean.TRUE.equals(d.get("parked"))) {
                        throw new IllegalStateException("409 NOT_PARKED: item " + itemId + " is a poison message");
                    }
                    if (!"OPEN".equals(rs.getString("state"))) {
                        throw new IllegalStateException("409 ALREADY_RESOLVED: item " + itemId);
                    }
                    String task = rs.getString("task_id");
                    return new Parked(rs.getLong("id"), rs.getString("tenant_id"), Long.parseLong(task.substring("dead-letter:".length())),
                            text(d.get("source")), text(d.get("messageId")), rs.getString("event_type"), text(d.get("correlationKey")),
                            text(d.get("claimedTenant")), rs.getString("payload"), text(d.get("tenantState")),
                            text(d.get("targetCell")), text(d.get("moveId")));
                }, args))
                .orElseThrow(() -> new IllegalArgumentException("no " + KIND + " item " + itemId));
    }

    /**
     * Operator replay of a parked message to the tenant's new cell (SL-142): hands it to {@code transport}, then closes
     * the item ({@code REPLAYED}, audited) and the dead letter ({@code REQUEUED}). If the transport fails the item stays
     * open; if closing fails after a successful hand-over, a repeated replay is deduplicated by the target inbox
     * (tenant, source, original message id).
     */
    public void replayParked(long itemId, ParkedReplay transport, String actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a reason is required");
        }
        Parked p = parked(itemId);
        try {
            transport.replay(p);
        } catch (Exception e) {
            throw new IllegalStateException("replay of parked item " + itemId + " failed; the item stays open: " + e.getMessage(), e);
        }
        close(get(itemId), "REPLAYED", DeadLetterStore.State.REQUEUED, actor, reason);
    }

    private static String text(Object o) {
        return o == null ? null : o.toString();
    }

    /** True when {@code json} is null or parses as JSON. */
    public static boolean isWellFormedJson(String json) {
        return json == null || Json.tree(json) != null;
    }

    public List<Item> listOpen(String tenantId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT r.id, r.tenant_id, r.task_id, r.state, r.created_at, r.details, d.ref_id, d.error"
                        + " FROM RECONCILIATION_ITEM r JOIN INTEGRATION_DEAD_LETTER d ON r.task_id = " + concat("'dead-letter:'", idText("d.id"))
                        + " WHERE r.tenant_id=? AND r.kind=? AND r.state='OPEN' ORDER BY r.id",
                PoisonInbound::map, tenantId, KIND));
    }

    /**
     * Stores a corrected payload in the inbox under the original message id and closes the item and the dead letter.
     * Throws {@link PoisonMessageException} if the corrected payload is still malformed.
     */
    public void reprocess(InboxService inbox, long itemId, String eventType, String correlationKey, String correctedPayloadJson,
                          String actor, String reason) {
        if (!isWellFormedJson(correctedPayloadJson)) {
            throw new PoisonMessageException("corrected payload is still not valid JSON");
        }
        Item item = get(itemId);
        if (item.parked()) {
            // SL-142: the tenant left this cell; storing it here would bypass the move. Replay it to the new cell.
            throw new IllegalStateException("409 PARKED: item " + itemId + " belongs to a tenant that left this cell; use replayParked");
        }
        String[] ref = splitRef(item);
        inbox.receive(ChannelBinding.of(item.source(), item.tenantId(), "operator:" + actor),
                new InboundMessage(ref[1], eventType, correlationKey, item.tenantId(), correctedPayloadJson));
        close(item, "REPROCESSED", DeadLetterStore.State.REQUEUED, actor, reason);
    }

    /** Discards the message with a reason; closes the item and the dead letter. */
    public void discard(long itemId, String actor, String reason) {
        close(get(itemId), "DISCARDED", DeadLetterStore.State.DISCARDED, actor, reason);
    }

    private Item get(long itemId) {
        return get(null, itemId);
    }

    /** The item; with {@code tenantOrNull} set, only an item of that tenant (another tenant's id is not found). */
    private Item get(String tenantOrNull, long itemId) {
        String scope = tenantOrNull == null ? "" : " AND r.tenant_id=?";
        Object[] args = tenantOrNull == null ? new Object[]{itemId, KIND} : new Object[]{itemId, KIND, tenantOrNull};
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT r.id, r.tenant_id, r.task_id, r.state, r.created_at, r.details, d.ref_id, d.error"
                        + " FROM RECONCILIATION_ITEM r JOIN INTEGRATION_DEAD_LETTER d ON r.task_id = " + concat("'dead-letter:'", idText("d.id"))
                        + " WHERE r.id=? AND r.kind=?" + scope, PoisonInbound::map, args))
                .orElseThrow(() -> new IllegalArgumentException("no " + KIND + " item " + itemId));
    }

    private void close(Item item, String action, DeadLetterStore.State dlState, String actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a reason is required");
        }
        tx.inTransaction(c -> {
            if (audit != null) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("itemId", item.itemId());
                p.put("deadLetterId", item.deadLetterId());
                p.put("action", action);
                p.put("actor", actor);
                p.put("reason", reason);
                audit.append(c, item.tenantId(), "sec:" + item.tenantId(), "POISON_INBOUND_RESOLVED", p);
            }
            int n = Jdbc.update(c, "UPDATE RECONCILIATION_ITEM SET state='RESOLVED', action=?, resolved_by=?, resolution=?, resolved_at="
                    + dialect.now() + " WHERE id=? AND state='OPEN'", action, actor, Json.write(Map.of("reason", reason)), item.itemId());
            if (n == 0) {
                throw new IllegalStateException("409 ALREADY_RESOLVED: item " + item.itemId());
            }
            deadLetters.resolve(c, item.deadLetterId(), dlState, actor, reason);
            return null;
        });
    }

    private static String[] splitRef(Item item) {
        String ref = item.source() + "/" + item.messageId();
        return new String[]{item.source(), item.messageId() == null ? ref : item.messageId()};
    }

    private static Item map(ResultSet rs) throws SQLException {
        String task = rs.getString("task_id");
        String ref = rs.getString("ref_id");
        int slash = ref == null ? -1 : ref.indexOf('/');
        return new Item(rs.getLong("id"), rs.getString("tenant_id"), Long.parseLong(task.substring("dead-letter:".length())),
                slash < 0 ? ref : ref.substring(0, slash), slash < 0 ? null : ref.substring(slash + 1), rs.getString("error"),
                rs.getString("state"), org.sangam.core.layer.Jdbc.instant(rs, "created_at"),
                Boolean.TRUE.equals(Json.readMap(rs.getString("details")).get("parked")));
    }

    private String idText(String col) {
        return switch (dialect) {
            case POSTGRESQL -> "CAST(" + col + " AS VARCHAR)";
            case ORACLE -> "TO_CHAR(" + col + ")";
            case SQLSERVER -> "CAST(" + col + " AS NVARCHAR(30))";
            case MYSQL -> "CAST(" + col + " AS CHAR)";
        };
    }

    private String concat(String a, String b) {
        return switch (dialect) {
            case MYSQL -> "CONCAT(" + a + "," + b + ")";
            case SQLSERVER -> a + " + " + b;
            default -> a + " || " + b;
        };
    }
}
