// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.config.admin.ConfigAdminService;
import org.sangam.config.catalogue.NumberSeries;
import org.sangam.config.common.EffectiveDated;
import org.sangam.config.common.Tree;
import org.sangam.config.master.MasterCode;
import org.sangam.config.master.MasterStore;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileLoader;
import org.sangam.config.profile.ProfileRepository;
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

/**
 * JDBC implementation of the configuration stores on the four Tier-1 dialects (A-15). Every statement binds its
 * scope; writes run in short transactions of their own (or inside the caller's via {@link #inTx}).
 */
public final class JdbcConfigStore implements ProfileRepository, MasterStore, NumberSeries, ConfigAdminService.ChangeLog {

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
    @Override
    public void put(MasterCode m) {
        String from = m.validFrom() == null ? "0000-01-01" : m.validFrom().toString();
        String parent = m.parentCode() == null ? "" : m.parentCode();
        inTx(c -> {
            Jdbc.update(c, "DELETE FROM CFG_MASTER_CODE WHERE scope_id = ? AND scheme_id = ? AND segment_id = ? AND "
                    + "parent_code = ? AND code = ? AND valid_from = ?", m.scope(), m.scheme(), m.segment(), parent, m.code(), from);
            Jdbc.update(c, "INSERT INTO CFG_MASTER_CODE (scope_id, scheme_id, segment_id, parent_code, code, valid_from, "
                            + "valid_to, name_json, flags_json, source_ref) VALUES (?,?,?,?,?,?,?,?,?,?)", m.scope(), m.scheme(),
                    m.segment(), parent, m.code(), from, str(m.validTo()), CanonicalJson.write(m.name()),
                    CanonicalJson.write(m.flags()), m.sourceRef());
            return null;
        });
    }

    @Override
    public List<MasterCode> find(String scope, String scheme, String segment, String parentCode, String code) {
        return inTx(c -> Jdbc.query(c, "SELECT * FROM CFG_MASTER_CODE WHERE scope_id = ? AND scheme_id = ? AND segment_id = ? "
                + "AND parent_code = ? AND code = ?", JdbcConfigStore::code, scope, scheme, segment,
                parentCode == null ? "" : parentCode, code));
    }

    @Override
    public List<MasterCode> list(String scope, String scheme, String segment) {
        return inTx(c -> Jdbc.query(c, "SELECT * FROM CFG_MASTER_CODE WHERE scope_id = ? AND scheme_id = ? AND segment_id = ?",
                JdbcConfigStore::code, scope, scheme, segment));
    }

    private static MasterCode code(java.sql.ResultSet rs) throws SQLException {
        String from = rs.getString("valid_from");
        String parent = rs.getString("parent_code");
        Map<String, String> name = new LinkedHashMap<>();
        Tree.map(json(rs.getString("name_json"))).forEach((k, v) -> name.put(k, String.valueOf(v)));
        return new MasterCode(rs.getString("scope_id"), rs.getString("scheme_id"), rs.getString("segment_id"),
                rs.getString("code"), parent == null || parent.isEmpty() ? null : parent, name,
                Tree.map(json(rs.getString("flags_json"))), "0000-01-01".equals(from) ? null : EffectiveDated.date(from),
                EffectiveDated.date(rs.getString("valid_to")), rs.getString("source_ref"));
    }

    // ------------------------------------------------------------------------------------------- number series
    @Override
    public long next(String scope, String series, String unit, String period) {
        return inTx(c -> {
            Jdbc.insertIfAbsent(c, dialect, "INSERT INTO CFG_SERIES (scope_id, series_id, unit_id, period, last_no) "
                    + "VALUES (?,?,?,?,0)", scope, series, unit, period);
            long cur = Jdbc.one(c, dialect.lockingSelect("last_no", "CFG_SERIES",
                            "scope_id = ? AND series_id = ? AND unit_id = ? AND period = ?"), rs -> rs.getLong(1),
                    scope, series, unit, period).orElseThrow();
            Jdbc.update(c, "UPDATE CFG_SERIES SET last_no = ? WHERE scope_id = ? AND series_id = ? AND unit_id = ? AND period = ?",
                    cur + 1, scope, series, unit, period);
            return cur + 1;
        });
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
