// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.indicator;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.programme.ProgrammeException;
import org.sangam.programme.config.IndicatorDefinition;
import org.sangam.programme.config.ProgrammeConfig;
import org.sangam.programme.config.ReturnDefinition;
import org.sangam.programme.support.Db;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Versioned, hash-pinned programme configuration per tenant (R43 style): the whole configuration is stored in
 * {@code PG_CONFIG}; indicator and return definitions are materialised in {@code PG_INDICATOR} / {@code PG_RETURN_DEF}
 * so reports can join them. A definition key+version is immutable: re-publishing it with other content is refused,
 * a change needs a new version. Values already reported keep their meaning because returns pin the definition version.
 */
public final class IndicatorRegistry {

    public record Published(int version, String contentHash, boolean changed) {
    }

    private final Dialect dialect;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(int version, ProgrammeConfig config) {
    }

    public IndicatorRegistry(Dialect dialect) {
        this.dialect = dialect;
    }

    /** Validates and publishes a configuration; returns the new (or unchanged) version. */
    public Published publish(Connection c, String tenant, Map<String, Object> configMap, String actor) {
        String t = Db.tenant(tenant);
        ProgrammeConfig cfg = ProgrammeConfig.parse(configMap);
        String json = Db.json(configMap);
        String hash = Db.hashOf(configMap);
        Optional<Object[]> cur = Jdbc.one(c, "SELECT version, content_hash FROM PG_CONFIG WHERE tenant_id=? AND current_flag=1",
                rs -> new Object[]{rs.getInt("version"), rs.getString("content_hash")}, t);
        if (cur.isPresent() && hash.equals(cur.get()[1])) {
            return new Published((Integer) cur.get()[0], hash, false);
        }
        for (IndicatorDefinition d : cfg.indicators().values()) {
            pinIndicator(c, t, d, actor);
        }
        for (ReturnDefinition r : cfg.returns().values()) {
            pinReturn(c, t, r, actor);
        }
        int next = Jdbc.one(c, "SELECT COALESCE(MAX(version),0) AS v FROM PG_CONFIG WHERE tenant_id=?", rs -> rs.getInt("v"), t).orElse(0) + 1;
        Jdbc.update(c, "UPDATE PG_CONFIG SET current_flag=0 WHERE tenant_id=? AND current_flag=1", t);
        Jdbc.update(c, "INSERT INTO PG_CONFIG(tenant_id, version, config_json, content_hash, current_flag, defined_by, defined_at)"
                + " VALUES (?,?,?,?,1,?," + dialect.now() + ")", t, next, json, hash, actor);
        cache.put(t, new Cached(next, cfg));
        return new Published(next, hash, true);
    }

    private void pinIndicator(Connection c, String t, IndicatorDefinition d, String actor) {
        String hash = Db.hashOf(d.toMap());
        Optional<String> existing = Jdbc.one(c, "SELECT content_hash FROM PG_INDICATOR WHERE tenant_id=? AND indicator_key=? AND version=?",
                rs -> rs.getString(1), t, d.key(), d.version());
        if (existing.isPresent()) {
            if (!existing.get().equals(hash)) {
                throw new ProgrammeException(ProgrammeException.Code.INVALID_CONFIG,
                        "indicator " + d.key() + " version " + d.version() + " already published with other content: raise the version");
            }
            Jdbc.update(c, "UPDATE PG_INDICATOR SET active=? WHERE tenant_id=? AND indicator_key=? AND version=?", Db.small(d.active()), t, d.key(), d.version());
            return;
        }
        Jdbc.update(c, "INSERT INTO PG_INDICATOR(tenant_id, indicator_key, version, name, unit, frequency, kind, aggregation, formula,"
                        + " weight_indicator, direction, cumulative, decimals, validation_json, parent_indicator_key, weight, active, content_hash,"
                        + " defined_by, defined_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?," + dialect.now() + ")",
                t, d.key(), d.version(), d.name(), d.unit(), d.frequency().name(), d.kind(), d.aggregation().name(), d.formula(),
                d.weightIndicator(), d.direction(), Db.small(d.cumulative()), d.decimals(), Db.json(d.validation()), d.parentIndicatorKey(),
                d.weight(), Db.small(d.active()), hash, actor);
    }

    private void pinReturn(Connection c, String t, ReturnDefinition r, String actor) {
        String hash = Db.hashOf(r.toMap());
        Optional<String> existing = Jdbc.one(c, "SELECT content_hash FROM PG_RETURN_DEF WHERE tenant_id=? AND return_key=? AND version=?",
                rs -> rs.getString(1), t, r.key(), r.version());
        if (existing.isPresent()) {
            if (!existing.get().equals(hash)) {
                throw new ProgrammeException(ProgrammeException.Code.INVALID_CONFIG,
                        "return " + r.key() + " version " + r.version() + " already published with other content: raise the version");
            }
            Jdbc.update(c, "UPDATE PG_RETURN_DEF SET active=? WHERE tenant_id=? AND return_key=? AND version=?", Db.small(r.active()), t, r.key(), r.version());
            return;
        }
        Jdbc.update(c, "INSERT INTO PG_RETURN_DEF(tenant_id, return_key, version, name, frequency, programme_id, indicators_json, rules_json,"
                        + " form_key, form_version, validation_dmn_key, due_offset_days, reminders_json, approval_levels, reporting_level, process_key,"
                        + " active, content_hash, defined_by, defined_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?," + dialect.now() + ")",
                t, r.key(), r.version(), r.name(), r.frequency().name(), r.programmeId(), Db.json(r.toMap().get("indicators")),
                Db.json(r.rules()), r.formKey(), r.formVersion(), r.validationDmnKey(), r.dueOffsetDays(), Db.json(r.reminderDays()),
                r.approvalLevels(), r.reportingLevel(), r.processKey(), Db.small(r.active()), hash, actor);
    }

    /** The current configuration of the tenant. */
    public ProgrammeConfig config(Connection c, String tenant) {
        String t = Db.tenant(tenant);
        Optional<Object[]> cur = Jdbc.one(c, "SELECT version, config_json FROM PG_CONFIG WHERE tenant_id=? AND current_flag=1",
                rs -> new Object[]{rs.getInt("version"), rs.getString("config_json")}, t);
        if (cur.isEmpty()) {
            throw new ProgrammeException(ProgrammeException.Code.NOT_FOUND, "no programme configuration published for the tenant");
        }
        int v = (Integer) cur.get()[0];
        Cached hit = cache.get(t);
        if (hit != null && hit.version() == v) {
            return hit.config();
        }
        ProgrammeConfig cfg = ProgrammeConfig.parse((String) cur.get()[1]);
        cache.put(t, new Cached(v, cfg));
        return cfg;
    }

    public IndicatorDefinition indicator(Connection c, String tenant, String key) {
        IndicatorDefinition d = config(c, tenant).indicators().get(key);
        if (d == null) {
            throw ProgrammeException.notFound("indicator", key);
        }
        return d;
    }

    /** A pinned indicator version (for a return that was opened under an older configuration). */
    public Optional<IndicatorDefinition> indicatorVersion(Connection c, String tenant, String key, int version) {
        return Jdbc.one(c, "SELECT * FROM PG_INDICATOR WHERE tenant_id=? AND indicator_key=? AND version=?", rs -> IndicatorDefinition.fromMap(Db.kv(
                "key", rs.getString("indicator_key"), "version", rs.getInt("version"), "name", rs.getString("name"), "unit", rs.getString("unit"),
                "frequency", rs.getString("frequency"), "kind", rs.getString("kind"), "aggregation", rs.getString("aggregation"),
                "formula", rs.getString("formula"), "weightIndicator", rs.getString("weight_indicator"), "direction", rs.getString("direction"),
                "cumulative", rs.getInt("cumulative") != 0, "decimals", rs.getInt("decimals"), "validation", Db.map(rs.getString("validation_json")),
                "parent", rs.getString("parent_indicator_key"), "weight", rs.getBigDecimal("weight"), "active", rs.getInt("active") != 0)),
                Db.tenant(tenant), key, version);
    }

    public ReturnDefinition returnDefinition(Connection c, String tenant, String key) {
        ReturnDefinition r = config(c, tenant).returns().get(key);
        if (r == null) {
            throw ProgrammeException.notFound("return definition", key);
        }
        return r;
    }

    /** A pinned return-definition version. */
    public ReturnDefinition returnDefinition(Connection c, String tenant, String key, int version) {
        ReturnDefinition cur = config(c, tenant).returns().get(key);
        if (cur != null && cur.version() == version) {
            return cur;
        }
        return Jdbc.one(c, "SELECT * FROM PG_RETURN_DEF WHERE tenant_id=? AND return_key=? AND version=?", rs -> ReturnDefinition.fromMap(Db.kv(
                        "key", rs.getString("return_key"), "version", rs.getInt("version"), "name", rs.getString("name"),
                        "frequency", rs.getString("frequency"), "programmeId", rs.getString("programme_id"),
                        "indicators", Db.list(rs.getString("indicators_json")), "rules", Db.list(rs.getString("rules_json")),
                        "formKey", rs.getString("form_key"), "formVersion", rs.getObject("form_version"),
                        "validationDmnKey", rs.getString("validation_dmn_key"), "dueOffsetDays", rs.getInt("due_offset_days"),
                        "reminderDays", Db.list(rs.getString("reminders_json")), "approvalLevels", rs.getInt("approval_levels"),
                        "reportingLevel", rs.getString("reporting_level"), "processKey", rs.getString("process_key"),
                        "active", rs.getInt("active") != 0)), Db.tenant(tenant), key, version)
                .orElseThrow(() -> ProgrammeException.notFound("return definition", key + "@" + version));
    }

    public List<Map<String, Object>> history(Connection c, String tenant) {
        return Jdbc.query(c, "SELECT version, content_hash, current_flag, defined_by, defined_at FROM PG_CONFIG WHERE tenant_id=? ORDER BY version DESC",
                rs -> Db.kv("version", rs.getInt("version"), "contentHash", rs.getString("content_hash"),
                        "current", rs.getInt("current_flag") != 0, "definedBy", rs.getString("defined_by"),
                        "definedAt", Jdbc.instant(rs, "defined_at")), Db.tenant(tenant));
    }
}
