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
 * <p>{@code POISON_INBOUND} is not a value of the core {@code ReconciliationQueue.Kind} enum (core files are owned by
 * the integrator), so these items are written and resolved here with SQL. The reconciliation engine only claims
 * UNKNOWN_OUTCOME / LATE_RESULT_AFTER_DELETE items; core {@code ReconciliationQueue.get/listOpen/resolve} must not be
 * used on them until the enum gains the value (requested in the README).
 */
public final class PoisonInbound {

    private static final Logger LOG = LoggerFactory.getLogger(PoisonInbound.class);

    public static final String KIND = "POISON_INBOUND";
    private static final int MAX_BODY = 64 * 1024;

    /** Thrown by {@link InboxService#receive} for a message that can never be stored. Not transient. */
    public static final class PoisonMessageException extends RuntimeException {
        public PoisonMessageException(String message) {
            super(message);
        }
    }

    public record Item(long itemId, String tenantId, long deadLetterId, String source, String messageId, String error,
                       String state, Instant createdAt) {
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

    /** True when {@code json} is null or parses as JSON. */
    public static boolean isWellFormedJson(String json) {
        return json == null || Json.tree(json) != null;
    }

    public List<Item> listOpen(String tenantId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT r.id, r.tenant_id, r.task_id, r.state, r.created_at, d.ref_id, d.error"
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
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT r.id, r.tenant_id, r.task_id, r.state, r.created_at, d.ref_id, d.error"
                        + " FROM RECONCILIATION_ITEM r JOIN INTEGRATION_DEAD_LETTER d ON r.task_id = " + concat("'dead-letter:'", idText("d.id"))
                        + " WHERE r.id=? AND r.kind=?", PoisonInbound::map, itemId, KIND))
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
                rs.getString("state"), org.sangam.core.layer.Jdbc.instant(rs, "created_at"));
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
