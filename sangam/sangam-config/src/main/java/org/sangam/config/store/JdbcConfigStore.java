// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.config.admin.ConfigAdminService;
import org.sangam.config.api.ConfigConnector;
import org.sangam.config.catalogue.NumberSeries;
import org.sangam.config.common.EffectiveDated;
import org.sangam.config.common.Tree;
import org.sangam.config.master.MasterCode;
import org.sangam.config.master.MasterStore;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileLoader;
import org.sangam.config.profile.ProfileRepository;
import org.sangam.core.layer.AbsentKey;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongFunction;

/**
 * JDBC implementation of the configuration stores on the four Tier-1 dialects (A-15). Every statement binds its
 * scope; writes run in short transactions of their own (or inside the caller's via {@link #inTx}).
 */
public final class JdbcConfigStore implements ProfileRepository, MasterStore, NumberSeries, ConfigAdminService.ChangeLog,
        ConfigConnector.SeriesReservations {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource ds;
    private final Dialect dialect;

    public JdbcConfigStore(DataSource ds, Dialect dialect) {
        this.ds = ds;
        this.dialect = dialect;
    }

    public <T> T inTx(Function<Connection, T> work) {
        try (Connection c = ds.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T r = work.apply(c);
                c.commit();
                return r;
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new Jdbc.LayerSqlException("config transaction", e);
        }
    }

    // ------------------------------------------------------------------------------------------- profiles
    @Override
    public void save(ProfileDocument d, Status status) {
        String json = CanonicalJson.write(d.toTree());
        inTx(c -> {
            int n = Jdbc.update(c, "UPDATE CFG_PROFILE SET layer_id = ?, status = ?, effective_from = ?, effective_to = ?, "
                            + "content_hash = ?, document_json = ?, updated_at = ? WHERE profile_id = ? AND version = ?",
                    d.layer(), status.name(), str(d.effectiveFrom()), str(d.effectiveTo()), d.contentHash(), json, Instant.now(),
                    d.id(), d.version());
            if (n == 0) {
                Jdbc.update(c, "INSERT INTO CFG_PROFILE (profile_id, version, layer_id, status, effective_from, effective_to, "
                                + "content_hash, document_json, updated_at) VALUES (?,?,?,?,?,?,?,?,?)", d.id(), d.version(),
                        d.layer(), status.name(), str(d.effectiveFrom()), str(d.effectiveTo()), d.contentHash(), json,
                        Instant.now());
            }
            return null;
        });
    }

    @Override
    public void setStatus(String id, String version, Status status) {
        inTx(c -> Jdbc.update(c, "UPDATE CFG_PROFILE SET status = ?, updated_at = ? WHERE profile_id = ? AND version = ?",
                status.name(), Instant.now(), id, version));
    }

    @Override
    public Optional<Stored> get(String id, String version) {
        return inTx(c -> Jdbc.one(c, "SELECT status, content_hash, document_json FROM CFG_PROFILE WHERE profile_id = ? AND version = ?",
                rs -> new Stored(ProfileLoader.parse(rs.getString(3)), Status.valueOf(rs.getString(1)), rs.getString(2)),
                id, version));
    }

    @Override
    public List<Stored> versions(String id) {
        List<Stored> l = inTx(c -> Jdbc.query(c, "SELECT status, content_hash, document_json FROM CFG_PROFILE WHERE profile_id = ?",
                rs -> new Stored(ProfileLoader.parse(rs.getString(3)), Status.valueOf(rs.getString(1)), rs.getString(2)), id));
        return l.stream().sorted((a, b) -> ProfileRepository.compareVersions(a.document().version(), b.document().version()))
                .toList();
    }

    @Override
    public List<String> ids() {
        return inTx(c -> Jdbc.query(c, "SELECT DISTINCT profile_id FROM CFG_PROFILE ORDER BY profile_id", rs -> rs.getString(1)));
    }

    // ------------------------------------------------------------------------------------------- master codes
    // parent_code is a NOT NULL primary-key column: a root code (no parent) is stored as AbsentKey.NONE, never '',
    // which Oracle stores as NULL (SL-84: ORA-01400 at startup seeding).
    @Override
    public void put(MasterCode m) {
        inTx(c -> {
            put(c, m);
            return null;
        });
    }

    /**
     * Upserts one master code on the caller's connection and transaction (M-130 bulk sink): replaces the row with the
     * same key and {@code valid_from}, so a repeat is idempotent and commits or rolls back with the caller's work.
     */
    public void put(Connection c, MasterCode m) {
        String from = m.validFrom() == null ? "0000-01-01" : m.validFrom().toString();
        String parent = AbsentKey.encode(m.parentCode());
        Jdbc.update(c, "DELETE FROM CFG_MASTER_CODE WHERE scope_id = ? AND scheme_id = ? AND segment_id = ? AND "
                + "parent_code = ? AND code = ? AND valid_from = ?", m.scope(), m.scheme(), m.segment(), parent, m.code(), from);
        Jdbc.update(c, "INSERT INTO CFG_MASTER_CODE (scope_id, scheme_id, segment_id, parent_code, code, valid_from, "
                        + "valid_to, name_json, flags_json, source_ref) VALUES (?,?,?,?,?,?,?,?,?,?)", m.scope(), m.scheme(),
                m.segment(), parent, m.code(), from, str(m.validTo()), CanonicalJson.write(m.name()),
                CanonicalJson.write(m.flags()), m.sourceRef());
    }

    @Override
    public List<MasterCode> find(String scope, String scheme, String segment, String parentCode, String code) {
        return inTx(c -> Jdbc.query(c, "SELECT * FROM CFG_MASTER_CODE WHERE scope_id = ? AND scheme_id = ? AND segment_id = ? "
                + "AND parent_code = ? AND code = ?", JdbcConfigStore::code, scope, scheme, segment,
                AbsentKey.encode(parentCode), code));
    }

    @Override
    public List<MasterCode> list(String scope, String scheme, String segment) {
        return inTx(c -> Jdbc.query(c, "SELECT * FROM CFG_MASTER_CODE WHERE scope_id = ? AND scheme_id = ? AND segment_id = ?",
                JdbcConfigStore::code, scope, scheme, segment));
    }

    @Override
    public List<MasterCode> children(String scope, String scheme, String segment, String parentPrefix) {
        // An empty prefix lists every code of the segment, roots included (as with the former '' root key). A non-empty
        // prefix never selects a root: the stored root key is excluded, so a prefix starting with it cannot match.
        if (parentPrefix.isEmpty()) {
            return list(scope, scheme, segment);
        }
        return inTx(c -> Jdbc.query(c, "SELECT * FROM CFG_MASTER_CODE WHERE scope_id = ? AND scheme_id = ? AND segment_id = ? "
                + "AND parent_code LIKE ? AND parent_code <> ?", JdbcConfigStore::code, scope, scheme, segment,
                parentPrefix + "%", AbsentKey.NONE));
    }

    private static MasterCode code(java.sql.ResultSet rs) throws SQLException {
        String from = rs.getString("valid_from");
        String parent = AbsentKey.decode(rs.getString("parent_code"));
        Map<String, String> name = new LinkedHashMap<>();
        Tree.map(json(rs.getString("name_json"))).forEach((k, v) -> name.put(k, String.valueOf(v)));
        return new MasterCode(rs.getString("scope_id"), rs.getString("scheme_id"), rs.getString("segment_id"),
                rs.getString("code"), parent, name,
                Tree.map(json(rs.getString("flags_json"))), "0000-01-01".equals(from) ? null : EffectiveDated.date(from),
                EffectiveDated.date(rs.getString("valid_to")), rs.getString("source_ref"));
    }

    // ------------------------------------------------------------------------------------------- number series
    @Override
    public long next(String scope, String series, String unit, String period) {
        return inTx(c -> allocate(c, scope, series, unit, period));
    }

    /** The next number of the series, under the counter's row lock, in the caller's transaction. */
    private long allocate(Connection c, String scope, String series, String unit, String period) {
        Jdbc.insertIfAbsent(c, dialect, "INSERT INTO CFG_SERIES (scope_id, series_id, unit_id, period, last_no) "
                + "VALUES (?,?,?,?,0)", scope, series, unit, period);
        long cur = Jdbc.one(c, dialect.lockingSelect("last_no", "CFG_SERIES",
                        "scope_id = ? AND series_id = ? AND unit_id = ? AND period = ?"), rs -> rs.getLong(1),
                scope, series, unit, period).orElseThrow();
        Jdbc.update(c, "UPDATE CFG_SERIES SET last_no = ? WHERE scope_id = ? AND series_id = ? AND unit_id = ? AND period = ?",
                cur + 1, scope, series, unit, period);
        return cur + 1;
    }

    /** A concurrent reservation of the same key committed first: roll back this draw and return the stored one. */
    private static final class LostReservationRace extends RuntimeException {
        LostReservationRace() {
            super(null, null, false, false);
        }
    }

    /**
     * SL-130 follow-up: the durable {@code series.next} reservation. One transaction reads the reservation of
     * {@code key}; when there is none it advances the counter (row lock), formats the number and inserts the
     * reservation, so the counter and the reservation commit together. A retry with the same key (any node, after a
     * restart) returns the stored number and draws none. Two first calls with one key race on the reservation's
     * primary key; the loser's transaction rolls back (its counter step too) and it returns the winner's number.
     */
    @Override
    public ConfigConnector.SeriesReservations.Reserved reserve(String scope, String series, String unit, String period,
                                                               String key, LongFunction<String> display) {
        try {
            return inTx(c -> {
                Optional<ConfigConnector.SeriesReservations.Reserved> prior = findReservation(c, scope, series, key);
                if (prior.isPresent()) {
                    return prior.get();
                }
                long n = allocate(c, scope, series, unit, period);
                String shown = display == null ? null : display.apply(n);
                boolean inserted;
                try {
                    inserted = Jdbc.insertIfAbsent(c, dialect, "INSERT INTO CFG_SERIES_RESERVATION (scope_id, series_id, res_key, "
                                    + "unit_id, period, number_no, display, created_at) VALUES (?,?,?,?,?,?,?," + dialect.now() + ")",
                            scope, series, key, unit, period, n, shown);
                } catch (Jdbc.LayerSqlException e) {
                    if (dialect.isUniqueViolation(e.sql())) {
                        throw new LostReservationRace();
                    }
                    throw e;
                }
                if (!inserted) {
                    throw new LostReservationRace();
                }
                return new ConfigConnector.SeriesReservations.Reserved(n, shown, false);
            });
        } catch (LostReservationRace e) {
            return reserved(scope, series, key).orElseThrow(() -> new IllegalStateException(
                    "series reservation " + key + " lost a race but is not stored"));
        }
    }

    @Override
    public Optional<ConfigConnector.SeriesReservations.Reserved> reserved(String scope, String series, String key) {
        return inTx(c -> findReservation(c, scope, series, key));
    }

    private static Optional<ConfigConnector.SeriesReservations.Reserved> findReservation(Connection c, String scope,
                                                                                          String series, String key) {
        return Jdbc.one(c, "SELECT number_no, display FROM CFG_SERIES_RESERVATION WHERE scope_id = ? AND series_id = ? "
                        + "AND res_key = ?",
                rs -> new ConfigConnector.SeriesReservations.Reserved(rs.getLong(1), rs.getString(2), true), scope, series, key);
    }

    // ------------------------------------------------------------------------------------------- change register
    @Override
    public void append(ConfigAdminService.ChangeRecord r) {
        inTx(c -> Jdbc.update(c, "INSERT INTO CFG_CHANGE (profile_id, version, action, actor, at_time, content_hash, reason) "
                + "VALUES (?,?,?,?,?,?,?)", r.profileId(), r.version(), r.action(), r.actor(), r.at(), r.contentHash(), r.reason()));
    }

    @Override
    public List<ConfigAdminService.ChangeRecord> history(String profileId) {
        return inTx(c -> Jdbc.query(c, "SELECT * FROM CFG_CHANGE WHERE profile_id = ? ORDER BY change_id",
                rs -> new ConfigAdminService.ChangeRecord(rs.getString("profile_id"), rs.getString("version"),
                        rs.getString("action"), rs.getString("actor"), ts(rs.getTimestamp("at_time")),
                        rs.getString("content_hash"), rs.getString("reason")), profileId));
    }

    /**
     * SL-120 change marker: row count and highest id of the append-only change register {@code CFG_CHANGE}. Every
     * lifecycle action of {@link ConfigAdminService} (draft, submit, approve, activate or its refusal, retire, import)
     * appends one row in its own transaction, so the count moves with every committed change on any node; the count
     * (not only the highest id) also catches a lower identity committed after a higher one.
     */
    @Override
    public String changeMarker() {
        return inTx(c -> Jdbc.one(c, "SELECT COUNT(*), MAX(change_id) FROM CFG_CHANGE",
                rs -> rs.getLong(1) + ":" + rs.getLong(2)).orElse("0:0"));
    }

    /** The profile binding stored for a case at its start ({@code CFG_CASE_BINDING}), if any (SL-119). */
    public Optional<String> caseBinding(String tenant, String rootId) {
        if (tenant == null || rootId == null) {
            return Optional.empty();
        }
        return inTx(c -> caseBinding(c, tenant, rootId));
    }

    /**
     * As above, on the caller's connection (SL-124): the engine command that started the case wrote the row on its own
     * connection, so a read in that same transaction sees it before it commits.
     */
    public Optional<String> caseBinding(Connection c, String tenant, String rootId) {
        if (tenant == null || rootId == null) {
            return Optional.empty();
        }
        return Jdbc.one(c, "SELECT binding FROM CFG_CASE_BINDING WHERE tenant_id = ? AND root_id = ?",
                rs -> rs.getString(1), tenant, rootId);
    }

    /** Records the profile binding of a case (audit of "which configuration governed this case"). */
    public void bindCase(String tenant, String rootId, String binding) {
        inTx(c -> Jdbc.insertIfAbsent(c, dialect, "INSERT INTO CFG_CASE_BINDING (tenant_id, root_id, binding, bound_at) "
                + "VALUES (?,?,?,?)", tenant, rootId, binding, Instant.now()));
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Object json(String s) {
        if (s == null || s.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(s, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt JSON in config store", e);
        }
    }
}
