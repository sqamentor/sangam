// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.casefile.CaseFileException;
import org.sangam.casefile.support.Db;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.io.IOException;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Versioned, hash-pinned store of case types, reference kinds and committee kinds ({@code CF_CONFIG}, R43). A tenant
 * definition wins over the shared ({@code ""}) one with the same key (FLOWABLE-INTO-OPERATON FC-33 fallback). Publishing the same content
 * again is a no-op that returns the existing version.
 */
public class CaseConfigRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record ConfigEntry(String tenantId, String kind, String key, int version, String contentHash, boolean active,
                              String definedBy, Instant definedAt, Map<String, Object> definition) {
    }

    private final Dialect dialect;

    public CaseConfigRegistry(Dialect dialect) {
        this.dialect = dialect;
    }

    /** Validates and stores a new version; returns the version (existing one when the content is unchanged). */
    public int publish(Connection c, String tenant, String kind, Map<String, Object> definition, String actor) {
        String t = Db.tenant(tenant);
        String key = Db.requireText(Db.str(definition, "key", null), "key");
        validate(kind, definition, 1);
        String hash = Db.hash(definition);
        Optional<ConfigEntry> latest = latest(c, t, kind, key, false);
        if (latest.isPresent() && latest.get().tenantId().equals(t) && latest.get().contentHash().equals(hash)) {
            if (!latest.get().active()) {
                Jdbc.update(c, "UPDATE CF_CONFIG SET active=1 WHERE tenant_id=? AND config_kind=? AND config_key=? AND version=?",
                        t, kind, key, latest.get().version());
            }
            return latest.get().version();
        }
        int version = Jdbc.one(c, "SELECT MAX(version) AS v FROM CF_CONFIG WHERE tenant_id=? AND config_kind=? AND config_key=?",
                rs -> rs.getInt("v"), t, kind, key).orElse(0) + 1;
        Jdbc.update(c, "INSERT INTO CF_CONFIG(tenant_id, config_kind, config_key, version, definition_json, content_hash, active,"
                        + " defined_by, defined_at) VALUES (?,?,?,?,?,?,1,?," + dialect.now() + ")",
                t, kind, key, version, Db.json(definition), hash, actor);
        return version;
    }

    /** Parses a JSON document and publishes it. */
    public int publishJson(Connection c, String tenant, String kind, String json, String actor) {
        try {
            Map<String, Object> m = MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return publish(c, tenant, kind, m, actor);
        } catch (IOException e) {
            throw new CaseFileException(CaseFileException.Code.INVALID_CONFIG, "configuration is not a JSON object");
        }
    }

    /** Deactivates every version of a key for this tenant (existing cases keep their pinned version). */
    public int retire(Connection c, String tenant, String kind, String key) {
        return Jdbc.update(c, "UPDATE CF_CONFIG SET active=0 WHERE tenant_id=? AND config_kind=? AND config_key=?",
                Db.tenant(tenant), kind, key);
    }

    public Optional<ConfigEntry> latest(Connection c, String tenant, String kind, String key, boolean activeOnly) {
        String t = Db.tenant(tenant);
        for (String scope : t.isEmpty() ? List.of("") : List.of(t, "")) {
            Optional<ConfigEntry> e = Jdbc.query(c, "SELECT * FROM CF_CONFIG WHERE tenant_id=? AND config_kind=? AND config_key=?"
                    + (activeOnly ? " AND active=1" : "") + " ORDER BY version DESC", CaseConfigRegistry::row, scope, kind, key)
                    .stream().findFirst();
            if (e.isPresent()) {
                return e;
            }
        }
        return Optional.empty();
    }

    public Optional<ConfigEntry> version(Connection c, String tenant, String kind, String key, int version) {
        String t = Db.tenant(tenant);
        for (String scope : t.isEmpty() ? List.of("") : List.of(t, "")) {
            Optional<ConfigEntry> e = Jdbc.one(c, "SELECT * FROM CF_CONFIG WHERE tenant_id=? AND config_kind=? AND config_key=? AND version=?",
                    CaseConfigRegistry::row, scope, kind, key, version);
            if (e.isPresent()) {
                return e;
            }
        }
        return Optional.empty();
    }

    public List<ConfigEntry> list(Connection c, String tenant, String kind) {
        return Jdbc.query(c, "SELECT * FROM CF_CONFIG WHERE tenant_id IN (?, '') AND config_kind=? AND active=1"
                + " ORDER BY config_key, version DESC", CaseConfigRegistry::row, Db.tenant(tenant), kind);
    }

    public CaseConfig.CaseType caseType(Connection c, String tenant, String key) {
        ConfigEntry e = latest(c, tenant, CaseConfig.CASE_TYPE, key, true)
                .orElseThrow(() -> CaseFileException.notFound("case type", key));
        return CaseConfig.caseType(e.definition(), e.version());
    }

    public CaseConfig.CaseType caseType(Connection c, String tenant, String key, int version) {
        ConfigEntry e = version(c, tenant, CaseConfig.CASE_TYPE, key, version)
                .orElseThrow(() -> CaseFileException.notFound("case type", key + "@" + version));
        return CaseConfig.caseType(e.definition(), e.version());
    }

    public CaseConfig.ReferenceKind referenceKind(Connection c, String tenant, String key) {
        ConfigEntry e = latest(c, tenant, CaseConfig.REFERENCE_KIND, key, true)
                .orElseThrow(() -> CaseFileException.notFound("reference kind", key));
        return CaseConfig.referenceKind(e.definition(), e.version());
    }

    public CaseConfig.ReferenceKind referenceKind(Connection c, String tenant, String key, int version) {
        ConfigEntry e = version(c, tenant, CaseConfig.REFERENCE_KIND, key, version)
                .orElseThrow(() -> CaseFileException.notFound("reference kind", key + "@" + version));
        return CaseConfig.referenceKind(e.definition(), e.version());
    }

    public CaseConfig.CommitteeKind committeeKind(Connection c, String tenant, String key) {
        ConfigEntry e = latest(c, tenant, CaseConfig.COMMITTEE_KIND, key, true)
                .orElseThrow(() -> CaseFileException.notFound("committee kind", key));
        return CaseConfig.committeeKind(e.definition(), e.version());
    }

    private static void validate(String kind, Map<String, Object> definition, int version) {
        switch (kind) {
            case CaseConfig.CASE_TYPE -> CaseConfig.caseType(definition, version);
            case CaseConfig.REFERENCE_KIND -> CaseConfig.referenceKind(definition, version);
            case CaseConfig.COMMITTEE_KIND -> CaseConfig.committeeKind(definition, version);
            default -> throw new CaseFileException(CaseFileException.Code.INVALID_CONFIG, "unknown configuration kind " + kind);
        }
    }

    private static ConfigEntry row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ConfigEntry(rs.getString("tenant_id"), rs.getString("config_kind"), rs.getString("config_key"),
                rs.getInt("version"), rs.getString("content_hash"), rs.getInt("active") != 0, rs.getString("defined_by"),
                Jdbc.instant(rs, "defined_at"), Db.map(rs.getString("definition_json")));
    }
}
