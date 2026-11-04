// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC ledger store for the four Tier-1 dialects.
 *
 * <p>Concurrency design (target ≥ 500 ops/s per cell): a posting is one short transaction that (1) inserts the entry
 * row (idempotency: a duplicate reference is detected without touching balances), then (2) applies each leg in
 * account-id order as one guarded {@code UPDATE ... WHERE account_id = ? AND <available after> >= ?}. The UPDATE takes
 * the row lock and evaluates the guard atomically, so there is no read-modify-write window and no oversell; a leg that
 * updates zero rows rolls the whole posting back. Hot accounts can be striped ({@code stripes} in the definition):
 * the service spreads allocations across stripe sub-accounts and tries the reference's preferred stripe first.
 * No remote call happens inside the transaction (R3).
 */
public final class JdbcLedgerStore implements LedgerStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COLS = "account_id, tenant_id, ledger_id, period, line_code, holder, stage, sub, currency, "
            + "total, distributed_amt, committed, encumbered, consumed, state, version, attrs_json";

    private final DataSource ds;
    private final Dialect dialect;

    public JdbcLedgerStore(DataSource ds, Dialect dialect) {
        this.ds = ds;
        this.dialect = dialect;
    }

    @Override
    public PostResult post(Entry e) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                boolean inserted = Jdbc.insertIfAbsent(c, dialect, "INSERT INTO LDG_ENTRY (tenant_id, reference, operation, "
                                + "audit_ref, actor, at_time, legs_json, info_json) VALUES (?,?,?,?,?,?,?,?)", e.tenant(),
                        e.reference(), e.operation(), e.auditRef(), e.actor(), e.at() == null ? Instant.now() : e.at(),
                        legsJson(e.legs()), CanonicalJson.write(e.info() == null ? Map.of() : e.info()));
                if (!inserted) {
                    c.rollback();
                    List<Balance> after = new ArrayList<>();
                    for (Movement m : e.legs()) {
                        get(m.key()).ifPresent(after::add);
                    }
                    return new PostResult(true, true, null, after);
                }
                List<Movement> legs = new ArrayList<>(e.legs());
                legs.sort(Comparator.comparing(m -> m.key().id()));
                List<Balance> after = new ArrayList<>();
                for (Movement m : legs) {
                    ensure(c, m.key(), m.currency());
                    BigDecimal t = d(m, Balance.Bucket.TOTAL);
                    BigDecimal di = d(m, Balance.Bucket.DISTRIBUTED);
                    BigDecimal co = d(m, Balance.Bucket.COMMITTED);
                    BigDecimal en = d(m, Balance.Bucket.ENCUMBERED);
                    BigDecimal cs = d(m, Balance.Bucket.CONSUMED);
                    boolean guard = m.guardAvailable() != null && m.availableDelta().signum() < 0;
                    String sql = "UPDATE LDG_BALANCE SET total = total + ?, distributed_amt = distributed_amt + ?, committed = committed + ?, "
                            + "encumbered = encumbered + ?, consumed = consumed + ?, version = version + 1 WHERE account_id = ?"
                            + (m.requireOpen() ? " AND state = 'OPEN'" : "")
                            + (guard ? " AND (total + ?) - (distributed_amt + ?) - (committed + ?) - (encumbered + ?) - (consumed + ?) >= ?" : "");
                    List<Object> p = new ArrayList<>(List.of(t, di, co, en, cs, m.key().id()));
                    if (guard) {
                        p.addAll(List.of(t, di, co, en, cs, m.guardAvailable()));
                    }
                    int n = Jdbc.update(c, sql, p.toArray());
                    if (n == 0) {
                        Balance b = read(c, m.key()).orElse(Balance.empty(m.key(), m.currency()));
                        c.rollback();
                        String reason = !Balance.OPEN.equals(b.state()) && m.requireOpen() ? "ACCOUNT_" + b.state()
                                : "INSUFFICIENT_AVAILABLE";
                        return new PostResult(false, false, reason, List.of(b));
                    }
                    read(c, m.key()).ifPresent(after::add);
                }
                c.commit();
                return new PostResult(true, false, null, after);
            } catch (RuntimeException ex) {
                c.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new Jdbc.LayerSqlException("ledger post", ex);
        }
    }

    private static BigDecimal d(Movement m, Balance.Bucket b) {
        return m.deltas().getOrDefault(b, BigDecimal.ZERO);
    }

    private void ensure(Connection c, AccountKey k, String currency) {
        Jdbc.insertIfAbsent(c, dialect, "INSERT INTO LDG_BALANCE (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                k.id(), k.tenant(), k.ledger(), k.period(), k.line(), k.holder(), k.stage(), k.sub(), currency,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Balance.OPEN, 0L, "{}");
    }

    private Optional<Balance> read(Connection c, AccountKey k) {
        return Jdbc.one(c, "SELECT " + COLS + " FROM LDG_BALANCE WHERE account_id = ?", JdbcLedgerStore::row, k.id());
    }

    @Override
    public Optional<Balance> get(AccountKey key) {
        try (Connection c = ds.getConnection()) {
            return read(c, key);
        } catch (SQLException e) {
            throw new Jdbc.LayerSqlException("ledger get", e);
        }
    }

    @Override
    public List<Balance> find(String tenant, String ledger, String period, String linePrefix, String holderPrefix, String stage) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM LDG_BALANCE WHERE tenant_id = ? AND ledger_id = ?");
        List<Object> p = new ArrayList<>(List.of(tenant, ledger));
        if (period != null) {
            sql.append(" AND period = ?");
            p.add(period);
        }
        if (stage != null) {
            sql.append(" AND stage = ?");
            p.add(stage);
        }
        if (linePrefix != null) {
            sql.append(" AND line_code LIKE ?");
            p.add(linePrefix.replace("%", "\\%") + "%");
        }
        if (holderPrefix != null) {
            sql.append(" AND holder LIKE ?");
            p.add(holderPrefix.replace("%", "\\%") + "%");
        }
        try (Connection c = ds.getConnection()) {
            return Jdbc.query(c, sql.toString(), JdbcLedgerStore::row, p.toArray());
        } catch (SQLException e) {
            throw new Jdbc.LayerSqlException("ledger find", e);
        }
    }

    @Override
    public Balance setState(AccountKey key, String currency, String state, Map<String, Object> attrs) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                ensure(c, key, currency);
                Balance b = Jdbc.one(c, dialect.lockingSelect(COLS, "LDG_BALANCE", "account_id = ?"), JdbcLedgerStore::row,
                        key.id()).orElseThrow();
                Map<String, Object> a = new HashMap<>(b.attrs());
                if (attrs != null) {
                    a.putAll(attrs);
                }
                Jdbc.update(c, "UPDATE LDG_BALANCE SET state = ?, attrs_json = ?, version = version + 1 WHERE account_id = ?",
                        state == null ? b.state() : state, CanonicalJson.write(a), key.id());
                Balance n = read(c, key).orElseThrow();
                c.commit();
                return n;
            } catch (RuntimeException ex) {
                c.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new Jdbc.LayerSqlException("ledger setState", ex);
        }
    }

    @Override
    public Optional<Entry> entry(String tenant, String reference) {
        try (Connection c = ds.getConnection()) {
            return Jdbc.one(c, "SELECT tenant_id, reference, operation, audit_ref, actor, at_time, info_json FROM LDG_ENTRY "
                    + "WHERE tenant_id = ? AND reference = ?", rs -> new Entry(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), Jdbc.instant(rs, "at_time"), List.of(), map(rs.getString(7))), tenant, reference);
        } catch (SQLException e) {
            throw new Jdbc.LayerSqlException("ledger entry", e);
        }
    }

    private static Balance row(ResultSet rs) throws SQLException {
        AccountKey k = new AccountKey(rs.getString("tenant_id"), rs.getString("ledger_id"), rs.getString("period"),
                rs.getString("line_code"), rs.getString("holder"), rs.getString("stage"), rs.getString("sub"));
        return new Balance(k, rs.getString("currency"), rs.getBigDecimal("total"), rs.getBigDecimal("distributed_amt"),
                rs.getBigDecimal("committed"), rs.getBigDecimal("encumbered"), rs.getBigDecimal("consumed"),
                rs.getString("state"), rs.getLong("version"), map(rs.getString("attrs_json")));
    }

    private static String legsJson(List<Movement> legs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Movement m : legs) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("account", m.key().toString());
            x.put("currency", m.currency());
            Map<String, String> d = new LinkedHashMap<>();
            m.deltas().forEach((b, v) -> d.put(b.name(), v.toPlainString()));
            x.put("deltas", d);
            out.add(x);
        }
        return CanonicalJson.write(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(String s) {
        if (s == null || s.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(s, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt ledger JSON", e);
        }
    }
}
