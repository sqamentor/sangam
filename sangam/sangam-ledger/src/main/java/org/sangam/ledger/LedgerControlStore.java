// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.config.common.Tree;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable control state of the ledger: requests held for funds (pending queue) and freeze orders. Nothing is lost on
 * restart: the service keeps no in-memory copy and reads the store on every check. The JDBC implementation uses the
 * {@code LDG_HOLD} and {@code LDG_FREEZE} tables of {@link LedgerSchema}.
 */
public interface LedgerControlStore {

    /** A request waiting for funds. */
    record Held(LedgerRequest request, Instant heldAt) {
    }

    /** Parks a request (idempotent by tenant + reference). */
    void hold(LedgerRequest request, Instant at);

    /** Unreleased requests on an account, oldest first. */
    List<Held> holds(AccountKey account);

    /** Marks a held request released (applied after an allocation, or withdrawn). */
    void released(String tenant, String reference, Instant at);

    void freeze(FreezeOrder order);

    boolean releaseFreeze(String tenant, String orderId, Instant at);

    /** Active (unreleased) freeze orders of a tenant. */
    List<FreezeOrder> freezes(String tenant);

    // ------------------------------------------------------------------------------------------------ codec
    ObjectMapper JSON = new ObjectMapper();

    static String write(LedgerRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operation", r.operation());
        m.put("account", key(r.account()));
        m.put("target", r.target() == null ? null : key(r.target()));
        m.put("amount", r.amount() == null ? null : r.amount().toPlainString());
        m.put("currency", r.currency());
        m.put("rateType", r.rateType());
        m.put("reference", r.reference());
        m.put("auditRef", r.auditRef());
        m.put("actor", r.actor());
        m.put("asOf", r.asOf() == null ? null : r.asOf().toString());
        m.put("fromCommitment", r.fromCommitment());
        m.put("fromEncumbrance", r.fromEncumbrance());
        m.put("attrs", stringify(r.attrs()));
        return CanonicalJson.write(m);
    }

    @SuppressWarnings("unchecked")
    static LedgerRequest read(String json) {
        try {
            Map<String, Object> m = JSON.readValue(json, Map.class);
            return new LedgerRequest(Tree.str(m.get("operation"), null), key(Tree.map(m.get("account"))),
                    m.get("target") == null ? null : key(Tree.map(m.get("target"))),
                    m.get("amount") == null ? null : new BigDecimal(m.get("amount").toString()),
                    Tree.str(m.get("currency"), null), Tree.str(m.get("rateType"), null), Tree.str(m.get("reference"), null),
                    Tree.str(m.get("auditRef"), null), Tree.str(m.get("actor"), null),
                    m.get("asOf") == null ? null : LocalDate.parse(m.get("asOf").toString()),
                    Tree.bool(m.get("fromCommitment"), false), Tree.bool(m.get("fromEncumbrance"), false), false,
                    Tree.map(m.get("attrs")));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt held request", e);
        }
    }

    private static Map<String, Object> key(AccountKey k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant", k.tenant());
        m.put("ledger", k.ledger());
        m.put("period", k.period());
        m.put("line", k.line());
        m.put("holder", k.holder());
        m.put("stage", k.stage());
        m.put("sub", k.sub());
        return m;
    }

    private static AccountKey key(Map<String, Object> m) {
        return new AccountKey(Tree.str(m.get("tenant"), null), Tree.str(m.get("ledger"), null), Tree.str(m.get("period"), null),
                Tree.str(m.get("line"), null), Tree.str(m.get("holder"), null), Tree.str(m.get("stage"), null),
                Tree.str(m.get("sub"), null));
    }

    /** Canonical JSON refuses floating point (R33): numbers other than integers are kept as strings. */
    private static Object stringify(Object v) {
        if (v instanceof Map<?, ?> mm) {
            Map<String, Object> out = new LinkedHashMap<>();
            mm.forEach((k, x) -> out.put(String.valueOf(k), stringify(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(LedgerControlStore::stringify).toList();
        }
        if (v instanceof Double || v instanceof Float || v instanceof BigDecimal) {
            return new BigDecimal(v.toString()).toPlainString();
        }
        return v;
    }

    // ------------------------------------------------------------------------------------------------ JDBC
    /** JDBC implementation over {@code LDG_HOLD} / {@code LDG_FREEZE} (four dialects). */
    final class JdbcStore implements LedgerControlStore {
        private final DataSource ds;
        private final Dialect dialect;

        public JdbcStore(DataSource ds, Dialect dialect) {
            this.ds = ds;
            this.dialect = dialect;
        }

        private <T> T tx(java.util.function.Function<Connection, T> f) {
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                try {
                    T r = f.apply(c);
                    c.commit();
                    return r;
                } catch (RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                throw new Jdbc.LayerSqlException("ledger control", e);
            }
        }

        @Override
        public void hold(LedgerRequest r, Instant at) {
            tx(c -> Jdbc.insertIfAbsent(c, dialect, "INSERT INTO LDG_HOLD (tenant_id, reference, account_id, amount, request_json, "
                            + "held_at, released_at) VALUES (?,?,?,?,?,?,?)", r.account().tenant(), r.reference(), r.account().id(),
                    r.amount() == null ? BigDecimal.ZERO : r.amount(), write(r), at, null));
        }

        @Override
        public List<Held> holds(AccountKey account) {
            return tx(c -> Jdbc.query(c, "SELECT request_json, held_at FROM LDG_HOLD WHERE account_id = ? AND released_at IS NULL "
                    + "ORDER BY held_at", rs -> new Held(read(rs.getString(1)), Jdbc.instant(rs, "held_at")), account.id()));
        }

        @Override
        public void released(String tenant, String reference, Instant at) {
            tx(c -> Jdbc.update(c, "UPDATE LDG_HOLD SET released_at = ? WHERE tenant_id = ? AND reference = ? AND released_at IS NULL",
                    at, tenant, reference));
        }

        @Override
        public void freeze(FreezeOrder o) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("ledger", o.ledger());
            p.put("period", o.period());
            p.put("linePrefix", o.linePrefix());
            p.put("holderPrefix", o.holderPrefix());
            p.put("stage", o.stage());
            tx(c -> Jdbc.insertIfAbsent(c, dialect, "INSERT INTO LDG_FREEZE (tenant_id, order_id, predicate_json, reason, ordered_by, "
                            + "ordered_at, released_at) VALUES (?,?,?,?,?,?,?)", o.tenant(), o.id(), CanonicalJson.write(p), o.reason(),
                    o.orderedBy(), o.at() == null ? Instant.now() : o.at(), null));
        }

        @Override
        public boolean releaseFreeze(String tenant, String orderId, Instant at) {
            return tx(c -> Jdbc.update(c, "UPDATE LDG_FREEZE SET released_at = ? WHERE tenant_id = ? AND order_id = ? AND released_at IS NULL",
                    at, tenant, orderId) == 1);
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<FreezeOrder> freezes(String tenant) {
            return tx(c -> Jdbc.query(c, "SELECT order_id, predicate_json, reason, ordered_by, ordered_at FROM LDG_FREEZE "
                    + "WHERE tenant_id = ? AND released_at IS NULL", rs -> {
                Map<String, Object> p;
                try {
                    p = JSON.readValue(rs.getString(2), Map.class);
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("corrupt freeze order", e);
                }
                return new FreezeOrder(tenant, rs.getString(1), Tree.str(p.get("ledger"), null), Tree.str(p.get("period"), null),
                        Tree.str(p.get("linePrefix"), null), Tree.str(p.get("holderPrefix"), null), Tree.str(p.get("stage"), null),
                        rs.getString(3), rs.getString(4), Jdbc.instant(rs, "ordered_at"));
            }, tenant));
        }
    }

    // ------------------------------------------------------------------------------------------------ in memory
    /** Development / test implementation (not for production: state is lost on restart). */
    final class InMemory implements LedgerControlStore {
        private final Map<String, Map<String, Object[]>> holds = new ConcurrentHashMap<>();
        private final Map<String, FreezeOrder> freezes = new ConcurrentHashMap<>();

        @Override
        public void hold(LedgerRequest r, Instant at) {
            holds.computeIfAbsent(r.account().id(), k -> new ConcurrentHashMap<>())
                    .putIfAbsent(r.account().tenant() + "|" + r.reference(), new Object[]{write(r), at, null});
        }

        @Override
        public List<Held> holds(AccountKey account) {
            List<Held> out = new ArrayList<>();
            holds.getOrDefault(account.id(), Map.of()).values().stream().filter(v -> v[2] == null)
                    .sorted(Comparator.comparing(v -> (Instant) v[1]))
                    .forEach(v -> out.add(new Held(read((String) v[0]), (Instant) v[1])));
            return out;
        }

        @Override
        public void released(String tenant, String reference, Instant at) {
            for (Map<String, Object[]> m : holds.values()) {
                Object[] v = m.get(tenant + "|" + reference);
                if (v != null) {
                    v[2] = at;
                }
            }
        }

        @Override
        public void freeze(FreezeOrder o) {
            freezes.putIfAbsent(o.tenant() + "|" + o.id(), o);
        }

        @Override
        public boolean releaseFreeze(String tenant, String orderId, Instant at) {
            return freezes.remove(tenant + "|" + orderId) != null;
        }

        @Override
        public List<FreezeOrder> freezes(String tenant) {
            return freezes.values().stream().filter(f -> f.tenant().equals(tenant)).toList();
        }
    }
}
