// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.PendingAuditDrainer;

import java.sql.Connection;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 *   <li><b>General ledger.</b> When a GL export is configured ({@link GlExport}), one outbox row per accounting entry,
 *       to the GL destination, in the same transaction. Without one the {@code LDG_ACCOUNTING} reading stays the only
 *       accounting record; a host that requires the export refuses to start without it (owner decision 126).</li>
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

    /**
     * @param audit the pending-audit drainer (its {@code pending()} records, its {@code signal} wakes the drain)
     * @param gl    the GL export; null: no GL rows (the host decides whether that is allowed)
     */
    public LedgerJournal(PendingAuditDrainer audit, GlExport gl) {
        this.audit = Objects.requireNonNull(audit, "audit");
        this.gl = gl;
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

    @Override
    public void accounting(Connection c, List<LedgerPorts.AccountingEntry> entries) {
        if (gl == null || entries == null) {
            return;
        }
        for (LedgerPorts.AccountingEntry en : entries) {
            String tenant = requireTenant(en.tenant(), "accounting entry", en.reference());
            gl.outbox().append(c, tenant, gl.rootIdOf().apply(en.reference()), gl.eventType(),
                    CanonicalJson.write(glPayload(en)), List.of(Outbox.Destination.unordered(gl.destination())));
        }
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
