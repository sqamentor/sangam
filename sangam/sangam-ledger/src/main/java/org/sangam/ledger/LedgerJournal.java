// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import org.sangam.core.layer.AbsentKey;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.PendingAuditDrainer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The ledger's {@link LedgerPorts.PostingJournal} (RM-04): plain Java, written on the posting connection inside the
 * posting transaction of {@link JdbcLedgerStore}.
 *
 * <ul>
 *   <li><b>Audit.</b> {@value #LEDGER_POSTED} goes into {@code AUDIT_PENDING} ({@link org.sangam.core.layer.PendingAudit}
 *       SL-133), never straight onto the chain: the chain is the posting's {@code auditRef} (often a case root whose
 *       {@code ROOT_SEQ} lock the caller's own transaction may hold), and the ledger commits on another connection, so
 *       appending there could wait for the caller forever. After the commit the drain is signalled; it appends the row
 *       to the chain under the root lock (its periodic sweep catches a lost signal). Chain: {@code auditRef}, else
 *       {@code ledger:<tenant>}; payload {@code reference}, {@code operation}, {@code summary}, {@code actor}.</li>
 *   <li><b>General ledger.</b> Every accounting entry is kept in {@code LDG_ACCOUNTING} with its exact GL payload. When
 *       a GL export is configured ({@link GlExport}), one outbox row per entry to the GL destination as well, in the
 *       same transaction, and the entry is marked exported. Without one (an audited production waiver, owner decision
 *       126) the entries stay with {@code exported_at} NULL and are exported later by {@link #exportPending}.</li>
 * </ul>
 * RM-49: a posting or accounting entry without a tenant (null, blank or the reserved shared key) is refused with an
 * {@link IllegalArgumentException} before any row is written, so the posting transaction rolls back; nothing is charged
 * to a default or empty tenant.
 */
public final class LedgerJournal implements LedgerPorts.PostingJournal {

    /** The class-B audit event of an applied posting. */
    public static final String LEDGER_POSTED = "LEDGER_POSTED";

    /** Appends one outbox row on the caller's connection (the integration {@code OutboxWriter.append} shape). */
    @FunctionalInterface
    public interface OutboxAppender {
        void append(Connection c, String tenant, String rootId, String eventType, String payloadJson,
                    Collection<Outbox.Destination> destinations);
    }

    /**
     * Where accounting entries go: the relay destination and event type of the GL publisher, the outbox root id of an
     * entry reference (a VARCHAR(64) key), and the outbox appender.
     */
    public record GlExport(String destination, String eventType, UnaryOperator<String> rootIdOf, OutboxAppender outbox) {
        public GlExport {
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(rootIdOf, "rootIdOf");
            Objects.requireNonNull(outbox, "outbox");
        }
    }

    private final PendingAuditDrainer audit;
    private final GlExport gl;
    private final Clock clock;

    /**
     * @param audit the pending-audit drainer (its {@code pending()} records, its {@code signal} wakes the drain)
     * @param gl    the GL export; null: no GL rows (the host decides whether that is allowed)
     */
    public LedgerJournal(PendingAuditDrainer audit, GlExport gl) {
        this(audit, gl, Clock.systemUTC());
    }

    /** @param clock the time of {@code created_at} and {@code exported_at} */
    public LedgerJournal(PendingAuditDrainer audit, GlExport gl, Clock clock) {
        this.audit = Objects.requireNonNull(audit, "audit");
        this.gl = gl;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The tenant of an audit or GL row, or an {@link IllegalArgumentException} when there is none (RM-49). */
    static String requireTenant(String tenant, String what, String reference) {
        if (tenant == null || tenant.isBlank() || org.sangam.core.layer.TenantKey.SHARED.equals(tenant)) {
            throw new IllegalArgumentException(what + " " + reference + " refused: it names no tenant;"
                    + " ledger audit and GL rows are never charged to a default or empty tenant");
        }
        return tenant;
    }

    /** The audit chain of a posting: its audit reference, else the tenant's ledger chain. */
    public static String chainOf(LedgerStore.Entry e) {
        return e.auditRef() != null && !e.auditRef().isBlank() ? e.auditRef() : "ledger:" + e.tenant();
    }

    /** The stored {@value #LEDGER_POSTED} payload of a posting. */
    public static Map<String, Object> payload(LedgerStore.Entry e) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("reference", e.reference());
        p.put("operation", e.operation());
        p.put("summary", e.info() == null ? Map.of() : e.info());
        p.put("actor", e.actor());
        return p;
    }

    /** The GL row payload of one accounting entry (decimals as strings, R33). */
    public static Map<String, Object> glPayload(LedgerPorts.AccountingEntry en) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reference", en.reference());
        m.put("event", en.event());
        m.put("basis", en.basis());
        m.put("debit", en.debit());
        m.put("credit", en.credit());
        m.put("amount", en.amount() == null ? null : en.amount().toPlainString());
        m.put("currency", en.currency());
        m.put("functionalAmount", en.functionalAmount() == null ? null : en.functionalAmount().toPlainString());
        return m;
    }

    @Override
    public void posted(Connection c, LedgerStore.Entry entry, List<LedgerPorts.AccountingEntry> accounting) {
        audit.pending().record(c, requireTenant(entry.tenant(), "posting", entry.reference()), chainOf(entry),
                LEDGER_POSTED, payload(entry));
        accounting(c, accounting);
    }

    /**
     * Every accounting entry is kept in {@code LDG_ACCOUNTING} with its exact GL payload (RM-05, owner decision 126),
     * in the caller's transaction. With a GL export the outbox row is written in the same transaction and the entry is
     * marked exported ({@code exported_at}); without one (audited waiver) it stays with {@code exported_at} NULL, and
     * no outbox row targets a missing connector, until {@link #exportPending} hands it over.
     */
    @Override
    public void accounting(Connection c, List<LedgerPorts.AccountingEntry> entries) {
        if (entries == null) {
            return;
        }
        for (LedgerPorts.AccountingEntry en : entries) {
            String tenant = requireTenant(en.tenant(), "accounting entry", en.reference());
            String payload = CanonicalJson.write(glPayload(en));
            Instant now = clock.instant();
            Jdbc.update(c, "INSERT INTO LDG_ACCOUNTING (tenant_id, reference, basis, debit, credit, amount, currency,"
                            + " functional_amount, created_at, exported_at, payload_json) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    tenant, en.reference(), orNone(en.basis()), orNone(en.debit()), orNone(en.credit()),
                    units(en.amount(), en.currency()), en.currency(), null, now, gl == null ? null : now, payload);
            if (gl != null) {
                append(c, tenant, en.reference(), payload);
            }
        }
    }

    /** Whether this journal hands accounting entries to a GL export. */
    public boolean exports() {
        return gl != null;
    }

    /** Ids of the entries not handed to the GL export yet (exported_at NULL), oldest first. */
    public static List<Long> pendingExport(Connection c) {
        return Jdbc.query(c, "SELECT entry_id FROM LDG_ACCOUNTING WHERE exported_at IS NULL AND payload_json IS NOT NULL"
                + " ORDER BY entry_id", rs -> rs.getLong(1));
    }

    /**
     * Hands one stored entry to the GL export, in the caller's transaction: marks it exported only if it still is not
     * (so two nodes never export it twice) and writes its outbox row with the stored payload, unchanged.
     *
     * @return true when this call exported it
     * @throws IllegalStateException when this journal has no GL export
     */
    public boolean exportPending(Connection c, long entryId) {
        if (gl == null) {
            throw new IllegalStateException("no GL export is configured: accounting entries stay in LDG_ACCOUNTING");
        }
        record Row(String tenant, String reference, String payload) {
        }
        Optional<Row> row = Jdbc.one(c, "SELECT tenant_id, reference, payload_json FROM LDG_ACCOUNTING"
                        + " WHERE entry_id=? AND exported_at IS NULL",
                rs -> new Row(rs.getString(1), rs.getString(2), rs.getString(3)), entryId);
        if (row.isEmpty() || row.get().payload() == null) {
            return false;
        }
        if (Jdbc.update(c, "UPDATE LDG_ACCOUNTING SET exported_at=? WHERE entry_id=? AND exported_at IS NULL",
                clock.instant(), entryId) != 1) {
            return false;   // another node exported it first
        }
        append(c, row.get().tenant(), row.get().reference(), row.get().payload());
        return true;
    }

    private void append(Connection c, String tenant, String reference, String payload) {
        gl.outbox().append(c, tenant, gl.rootIdOf().apply(reference), gl.eventType(), payload,
                List.of(Outbox.Destination.unordered(gl.destination())));
    }

    /** NOT NULL text columns: an absent value is the absent-key value, never an empty string (Oracle: NULL). */
    private static String orNone(String v) {
        return v == null || v.isEmpty() ? AbsentKey.NONE : v;
    }

    /**
     * The integer units of the amount column, rounded half-even to the account unit; the exact amount is in the
     * stored payload, so this reading column never blocks the posting.
     */
    private static long units(BigDecimal amount, String currency) {
        BigDecimal v = amount == null ? BigDecimal.ZERO : amount;
        return v.setScale(JdbcLedgerStore.exponent(currency), RoundingMode.HALF_EVEN).unscaledValue().longValueExact();
    }

    @Override
    public void committed(LedgerStore.Entry entry) {
        try {
            audit.signal(entry.tenant(), chainOf(entry));
        } catch (RuntimeException lost) {
            // the row is committed; the drainer's periodic sweep appends it
        }
    }
}
