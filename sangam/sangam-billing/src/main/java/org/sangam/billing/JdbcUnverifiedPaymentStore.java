// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.PendingAuditDrainer;
import org.sangam.core.layer.Transactions;
import org.sangam.core.money.Money;
import org.sangam.ledger.AccountKey;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * {@link UnverifiedPaymentStore} on {@code BILL_UNVERIFIED_PAYMENT} and {@code BILL_UNVERIFIED_ACCRUAL_PAUSE}
 * (sangam-ledger {@code LedgerSchema}, the billing tables). Portable SQL on the four dialects:
 * <ul>
 *   <li><b>No duplicate acceptance:</b> the unique index on (tenant, channel, payment_ref) decides; a second insert
 *       writes nothing ({@link Jdbc#insertIfAbsent}), so two concurrent acceptances of one payment leave one record.</li>
 *   <li><b>Atomic transitions:</b> the row is locked ({@link Dialect#lockingSelect}), the caller's decision (state and
 *       authorisation recheck) runs on the locked row, and the UPDATE is guarded by the locked version; the audit event
 *       ({@code AUDIT_PENDING}, SL-133) commits with it. Two concurrent resolutions: the second sees the first's state
 *       and is refused.</li>
 *   <li><b>Accrual pauses</b> (decision 195) are written under the same row locks, all of one levy or none, and only
 *       while every payment involved is still pending; a resolution that commits first is seen.</li>
 *   <li><b>Lists</b> filter in SQL before paging.</li>
 *   <li><b>Amounts</b> are integer minor units of the row's currency (SD-398).</li>
 * </ul>
 */
public final class JdbcUnverifiedPaymentStore implements UnverifiedPaymentStore {

    static final String TABLE = "BILL_UNVERIFIED_PAYMENT";
    static final String PAUSES = "BILL_UNVERIFIED_ACCRUAL_PAUSE";
    /** Registry convention (decisions 138, 381(4)): a stored fraction reads as BigDecimal from its text, never Double. */
    static final ObjectMapper JSON = org.sangam.registry.support.Json.MAPPER;
    private static final String COLUMNS = "tenant_id, payment_id, ledger_id, consumer, office_id, channel, payment_ref,"
            + " amount, currency, paid_at, payer_name, instrument, details_json, reason, evidence_ref, policy_version,"
            + " downstream_json, accepted_by, accepted_at, state, version, outcome_reason, verification_ref,"
            + " resolved_by, resolved_at, resolution_evidence_ref, receipt_no";
    private static final String PAUSE_COLUMNS = "payment_id, base_account_id, as_of, paused_from, tenant_id, ledger_id,"
            + " period, line_code, consumer, stage, sub, amount, currency";
    private static final String PENDING = UnverifiedPayment.State.PAYMENT_ACCEPTED_UNVERIFIED.name();

    private final Transactions tx;
    private final Dialect dialect;
    private final PendingAuditDrainer audit;

    public JdbcUnverifiedPaymentStore(Transactions tx, Dialect dialect, PendingAuditDrainer audit) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** The audit chain of one record: its whole history is one chain. */
    static String chainOf(String paymentId) {
        return "bill-unverified:" + paymentId;
    }

    @Override
    public boolean insert(UnverifiedPayment p, String auditType, Map<String, Object> payload) {
        boolean inserted = tx.inTransaction(c -> {
            boolean ok = Jdbc.insertIfAbsent(c, dialect, "INSERT INTO " + TABLE + " (" + COLUMNS + ") VALUES"
                            + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    p.tenant(), p.id(), p.ledgerId(), p.consumer(), p.office(), p.channel(), p.paymentRef(),
                    p.amount().minor(), p.amount().currencyCode(), p.paidAt(), p.payerName(), p.instrument(),
                    CanonicalJson.write(p.details()), p.reason(), p.evidenceRef(), p.policyVersion(),
                    CanonicalJson.write(p.downstreamPermitted()), p.acceptedBy(), p.acceptedAt(), p.state().name(),
                    p.version(), null, null, null, null, null, null);
            if (ok) {
                audit.pending().record(c, p.tenant(), chainOf(p.id()), auditType, payload);
            }
            return ok;
        });
        if (inserted) {
            signal(p.tenant(), p.id());
        }
        return inserted;
    }

    @Override
    public Optional<UnverifiedPayment> find(String tenant, String id) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT " + COLUMNS + " FROM " + TABLE
                + " WHERE tenant_id=? AND payment_id=?", JdbcUnverifiedPaymentStore::row, tenant, id));
    }

    @Override
    public Optional<UnverifiedPayment> findByRef(String tenant, String channel, String paymentRef) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE tenant_id=? AND channel=? AND payment_ref=?", JdbcUnverifiedPaymentStore::row,
                tenant, channel, paymentRef));
    }

    @Override
    public List<UnverifiedPayment> list(String tenant, UnverifiedPayment.State state, int limit) {
        String where = state == null ? " WHERE tenant_id=?" : " WHERE tenant_id=? AND state=?";
        Object[] params = state == null ? new Object[]{tenant} : new Object[]{tenant, state.name()};
        return tx.inTransaction(c -> Jdbc.query(c, page("SELECT " + COLUMNS + " FROM " + TABLE + where
                + " ORDER BY accepted_at, payment_id", 0, limit), JdbcUnverifiedPaymentStore::row, params));
    }

    @Override
    public Slice query(String tenant, Query q) {
        StringBuilder where = new StringBuilder(" WHERE tenant_id=? AND ledger_id=?");
        List<Object> params = new ArrayList<>(List.of(tenant, q.ledgerId()));
        if (q.state() != null) {
            where.append(" AND state=?");
            params.add(q.state().name());
        }
        if (q.channel() != null) {
            where.append(" AND channel=?");
            params.add(q.channel());
        }
        if (q.office() != null) {
            where.append(" AND office_id=?");
            params.add(q.office());
        }
        if (q.acceptedBefore() != null) {
            where.append(" AND accepted_at<=?");
            params.add(q.acceptedBefore());
        }
        Object[] p = params.toArray();
        return tx.inTransaction(c -> {
            long total = Jdbc.one(c, "SELECT COUNT(*) FROM " + TABLE + where, rs -> rs.getLong(1), p).orElse(0L);
            List<UnverifiedPayment> items = total <= q.offset() ? List.of() : Jdbc.query(c, page("SELECT " + COLUMNS
                    + " FROM " + TABLE + where + " ORDER BY accepted_at, payment_id", q.offset(), q.limit()),
                    JdbcUnverifiedPaymentStore::row, p);
            return new Slice(items, total);
        });
    }

    @Override
    public List<String> tenantsWithWork(int limit) {
        return tx.inTransaction(c -> {
            Set<String> out = new TreeSet<>(Jdbc.query(c, "SELECT DISTINCT tenant_id FROM " + TABLE
                            + " WHERE state=? OR (state=? AND receipt_no IS NULL)", rs -> rs.getString(1),
                    PENDING, UnverifiedPayment.State.VERIFIED.name()));
            out.addAll(Jdbc.query(c, "SELECT DISTINCT tenant_id FROM " + PAUSES + " WHERE resumed_at IS NULL",
                    rs -> rs.getString(1)));
            return out.stream().limit(limit).toList();
        });
    }

    @Override
    public List<UnverifiedPayment> verifiedNotAllocated(String tenant, int limit) {
        return tx.inTransaction(c -> Jdbc.query(c, page("SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE tenant_id=? AND state=? AND receipt_no IS NULL ORDER BY accepted_at, payment_id", 0,
                limit), JdbcUnverifiedPaymentStore::row, tenant, UnverifiedPayment.State.VERIFIED.name()));
    }

    @Override
    public UnverifiedPayment transition(String tenant, String id, Function<UnverifiedPayment, Transition> decide) {
        UnverifiedPayment next = tx.inTransaction(c -> {
            UnverifiedPayment locked = lock(c, tenant, id).orElseThrow(() -> new UnverifiedPaymentException(
                    UnverifiedPaymentException.Code.NOT_FOUND, "no unverified payment " + id + " in tenant " + tenant));
            Transition t = decide.apply(locked);
            UnverifiedPayment n = t.next();
            UnverifiedPayment.Resolution r = n.resolution();
            int rows = Jdbc.update(c, "UPDATE " + TABLE + " SET state=?, version=?, outcome_reason=?, verification_ref=?,"
                            + " resolved_by=?, resolved_at=?, resolution_evidence_ref=? WHERE tenant_id=? AND payment_id=?"
                            + " AND version=?",
                    n.state().name(), n.version(), r == null ? null : r.reason(), r == null ? null : r.verificationRef(),
                    r == null ? null : r.resolvedBy(), r == null ? null : r.resolvedAt(),
                    r == null ? null : r.evidenceRef(), tenant, id, locked.version());
            if (rows != 1) {
                throw new UnverifiedPaymentException(UnverifiedPaymentException.Code.INVALID_TRANSITION,
                        "unverified payment " + id + " changed during the transition");
            }
            Map<String, Object> payload = t.audit();
            if (n.state() == UnverifiedPayment.State.VERIFIED) {    // decision 195: VERIFIED forgives what it paused
                payload = new java.util.LinkedHashMap<>(payload);
                payload.put("accrual_forgiven", UnverifiedPayments.pauseView(pauses(c, tenant, id, false)));
            }
            audit.pending().record(c, tenant, chainOf(id), t.auditType(), payload);
            return n;
        });
        signal(tenant, id);
        return next;
    }

    @Override
    public void receipted(String tenant, String id, String receiptNo) {
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE " + TABLE + " SET receipt_no=? WHERE tenant_id=? AND payment_id=?"
                + " AND receipt_no IS NULL", receiptNo, tenant, id));
    }

    // ------------------------------------------------------------------ accrual pauses (decision 195)

    @Override
    public List<Pending> pending(String tenant, String ledgerId, String consumer) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT payment_id, amount, currency, accepted_at FROM " + TABLE
                        + " WHERE tenant_id=? AND ledger_id=? AND consumer=? AND state=? ORDER BY accepted_at, payment_id",
                rs -> new Pending(rs.getString("payment_id"), Money.ofMinor(rs.getLong("amount"),
                        rs.getString("currency")), Jdbc.instant(rs, "accepted_at")),
                tenant, ledgerId, consumer, PENDING));
    }

    @Override
    public Map<String, Money> pausedOn(String tenant, String ledgerId, String consumer, LocalDate asOf) {
        List<Map.Entry<String, Money>> rows = tx.inTransaction(c -> Jdbc.query(c, "SELECT base_account_id, amount,"
                        + " currency FROM " + PAUSES + " WHERE tenant_id=? AND ledger_id=? AND consumer=? AND as_of=?",
                rs -> Map.entry(rs.getString("base_account_id"), Money.ofMinor(rs.getLong("amount"),
                        rs.getString("currency"))), tenant, ledgerId, consumer, asOf.toString()));
        Map<String, Money> out = new java.util.HashMap<>();
        for (Map.Entry<String, Money> r : rows) {
            out.merge(r.getKey(), r.getValue(), Money::plus);
        }
        return out;
    }

    @Override
    public boolean recordPauses(String tenant, String ledgerId, String consumer, LocalDate asOf,
                                List<String> paymentIds, List<Pause> pauses) {
        if (paymentIds.isEmpty()) {
            return pauses.isEmpty();
        }
        try {
            return tx.inTransaction(c -> {
                Instant now = Jdbc.dbNow(c, dialect);
                for (String id : new TreeSet<>(paymentIds)) {    // one lock order: no deadlock between levies
                    Optional<UnverifiedPayment> locked = lock(c, tenant, id);
                    if (locked.isEmpty() || !locked.get().state().pending()
                            || !ledgerId.equals(locked.get().ledgerId()) || !consumer.equals(locked.get().consumer())) {
                        throw new NoLongerPending();
                    }
                }
                for (Pause p : pauses) {
                    if (!paymentIds.contains(p.paymentId()) || !asOf.equals(p.asOf())) {
                        throw new IllegalArgumentException("pause of payment " + p.paymentId() + " on " + p.asOf()
                                + " is not part of this levy");
                    }
                    AccountKey k = p.penaltyAccount();
                    Jdbc.insertIfAbsent(c, dialect, "INSERT INTO " + PAUSES + " (" + PAUSE_COLUMNS + ", recorded_at,"
                                    + " resumed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            p.paymentId(), p.baseAccountId(), p.asOf().toString(), p.pausedFrom().toString(), tenant,
                            k.ledger(), k.period(), k.line(), k.holder(), k.stage(), k.sub(), p.suppressed().minor(),
                            p.suppressed().currencyCode(), now, null);
                }
                return true;
            });
        } catch (NoLongerPending resolved) {
            return false;
        }
    }

    @Override
    public List<Pause> pausesOf(String tenant, String paymentId) {
        return pauses(tenant, paymentId, false);
    }

    @Override
    public List<Pause> pausesToResume(String tenant, String paymentId) {
        return pauses(tenant, paymentId, true);
    }

    @Override
    public List<UnverifiedPayment> resumptionDue(String tenant, int limit) {
        return tx.inTransaction(c -> Jdbc.query(c, page("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE tenant_id=?"
                        + " AND state IN (?, ?) AND payment_id IN (SELECT payment_id FROM " + PAUSES
                        + " WHERE tenant_id=? AND resumed_at IS NULL) ORDER BY accepted_at, payment_id", 0, limit),
                JdbcUnverifiedPaymentStore::row, tenant, UnverifiedPayment.State.REJECTED.name(),
                UnverifiedPayment.State.REVERSED.name(), tenant));
    }

    @Override
    public boolean markResumed(String tenant, String paymentId, Instant at, String auditType,
                               Map<String, Object> payload) {
        boolean marked = tx.inTransaction(c -> {
            int rows = Jdbc.update(c, "UPDATE " + PAUSES + " SET resumed_at=? WHERE tenant_id=? AND payment_id=?"
                    + " AND resumed_at IS NULL", at, tenant, paymentId);
            if (rows > 0) {
                audit.pending().record(c, tenant, chainOf(paymentId), auditType, payload);
            }
            return rows > 0;
        });
        if (marked) {
            signal(tenant, paymentId);
        }
        return marked;
    }

    private List<Pause> pauses(String tenant, String paymentId, boolean unresumedOnly) {
        return tx.inTransaction(c -> pauses(c, tenant, paymentId, unresumedOnly));
    }

    private static List<Pause> pauses(Connection c, String tenant, String paymentId, boolean unresumedOnly) {
        return Jdbc.query(c, "SELECT " + PAUSE_COLUMNS + " FROM " + PAUSES
                        + " WHERE tenant_id=? AND payment_id=?" + (unresumedOnly ? " AND resumed_at IS NULL" : "")
                        + " ORDER BY as_of, base_account_id",
                rs -> new Pause(rs.getString("payment_id"), rs.getString("base_account_id"),
                        LocalDate.parse(rs.getString("as_of")), LocalDate.parse(rs.getString("paused_from")),
                        new AccountKey(rs.getString("tenant_id"), rs.getString("ledger_id"), rs.getString("period"),
                                rs.getString("line_code"), rs.getString("consumer"), rs.getString("stage"),
                                rs.getString("sub")),
                        Money.ofMinor(rs.getLong("amount"), rs.getString("currency"))),
                tenant, paymentId);
    }

    /** Thrown inside {@link #recordPauses} to roll its transaction back. */
    private static final class NoLongerPending extends RuntimeException {
        NoLongerPending() {
            super(null, null, false, false);
        }
    }

    private Optional<UnverifiedPayment> lock(Connection c, String tenant, String id) {
        return Jdbc.one(c, dialect.lockingSelect(COLUMNS, TABLE, "tenant_id=? AND payment_id=?"),
                JdbcUnverifiedPaymentStore::row, tenant, id);
    }

    /** Appends paging to an ordered select (values validated, inlined). */
    private String page(String orderedSelect, int offset, int limit) {
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("offset must be >= 0 and limit >= 1");
        }
        return switch (dialect) {
            case POSTGRESQL, MYSQL -> orderedSelect + " LIMIT " + limit + " OFFSET " + offset;
            case ORACLE, SQLSERVER -> orderedSelect + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
        };
    }

    private void signal(String tenant, String id) {
        try {
            audit.signal(tenant, chainOf(id));
        } catch (RuntimeException lost) {
            // the audit row is committed; the drainer's periodic sweep appends it to the chain
        }
    }

    private static UnverifiedPayment row(ResultSet rs) throws SQLException {
        String currency = rs.getString("currency");
        UnverifiedPayment.State state = UnverifiedPayment.State.valueOf(rs.getString("state"));
        UnverifiedPayment.Resolution resolution = state.pending() ? null : new UnverifiedPayment.Resolution(state,
                rs.getString("outcome_reason"), rs.getString("verification_ref"),
                rs.getString("resolution_evidence_ref"), rs.getString("resolved_by"), Jdbc.instant(rs, "resolved_at"));
        return new UnverifiedPayment(rs.getString("tenant_id"), rs.getString("payment_id"), rs.getString("ledger_id"),
                rs.getString("consumer"), rs.getString("office_id"), rs.getString("channel"),
                rs.getString("payment_ref"), Money.ofMinor(rs.getLong("amount"), currency),
                Jdbc.instant(rs, "paid_at"), rs.getString("payer_name"), rs.getString("instrument"),
                read(rs.getString("details_json"), new TypeReference<Map<String, String>>() { }),
                rs.getString("reason"), rs.getString("evidence_ref"), rs.getString("policy_version"),
                read(rs.getString("downstream_json"), new TypeReference<List<String>>() { }),
                rs.getString("accepted_by"), Jdbc.instant(rs, "accepted_at"), state, rs.getLong("version"),
                resolution, rs.getString("receipt_no"));
    }

    private static <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored unverified payment has malformed JSON: " + e.getOriginalMessage(), e);
        }
    }
}
