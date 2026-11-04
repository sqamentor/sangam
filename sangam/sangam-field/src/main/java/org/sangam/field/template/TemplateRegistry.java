// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.template;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.field.FieldAudit;
import org.sangam.field.FieldException;
import org.sangam.field.FieldJson;
import org.sangam.field.support.FieldSql;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Versioned template store (FLD_TEMPLATE). Publishing never edits a version: it adds version n+1, so packets captured
 * offline against version n stay verifiable. Templates come from the admin API, a pack's JSON resources or a directory.
 */
public final class TemplateRegistry {

    public record TemplateRow(String key, int version, String status, boolean offlineCapable, String contentHash,
                              String definedBy, Instant definedAt, FieldTemplate template) {
    }

    private final Transactions tx;
    private final Dialect dialect;
    private final FieldAudit audit;
    private final Clock clock;

    public TemplateRegistry(Transactions tx, Dialect dialect, FieldAudit audit, Clock clock) {
        this.tx = tx;
        this.dialect = dialect;
        this.audit = audit;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public TemplateRow publish(String tenantId, FieldTemplate template, String actor) {
        template.validateDefinition();
        return tx.inTransaction(c -> {
            int next = Jdbc.one(c, "SELECT MAX(version) FROM FLD_TEMPLATE WHERE tenant_id = ? AND template_key = ?",
                    rs -> rs.getInt(1), tenantId, template.key()).orElse(0) + 1;
            FieldTemplate t = template.withVersion(next);
            String json = FieldJson.write(t);
            String hash = Hashes.hex(Hashes.sha256(json));
            Instant now = clock.instant();
            Jdbc.update(c, "INSERT INTO FLD_TEMPLATE (tenant_id, template_key, version, status, offline_capable, doc_json, content_hash, "
                            + "defined_by, defined_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)",
                    tenantId, t.key(), next, t.offlineCapable(), json, hash, actor, now);
            FieldSql.recordChange(c, dialect, tenantId, "TEMPLATE", t.key() + ":" + next, null, "UPSERT", now);
            audit.record(c, tenantId, "template:" + t.key(), FieldAudit.TEMPLATE_PUBLISHED, actor,
                    Map.of("key", t.key(), "version", next, "hash", hash));
            return new TemplateRow(t.key(), next, "ACTIVE", t.offlineCapable(), hash, actor, now, t);
        });
    }

    public void retire(String tenantId, String key, int version, String actor) {
        tx.inTransaction(c -> {
            int n = Jdbc.update(c, "UPDATE FLD_TEMPLATE SET status = 'RETIRED' WHERE tenant_id = ? AND template_key = ? AND version = ?",
                    tenantId, key, version);
            if (n == 0) {
                throw FieldException.notFound("template", key + ":" + version);
            }
            FieldSql.recordChange(c, dialect, tenantId, "TEMPLATE", key + ":" + version, null, "RETIRE", clock.instant());
            return null;
        });
    }

    /** {@code version} null = latest ACTIVE version. */
    public Optional<TemplateRow> find(String tenantId, String key, Integer version) {
        return tx.inTransaction(c -> find(c, tenantId, key, version));
    }

    public Optional<TemplateRow> find(java.sql.Connection c, String tenantId, String key, Integer version) {
        String sql = "SELECT * FROM FLD_TEMPLATE WHERE tenant_id = ? AND template_key = ? AND "
                + (version == null ? "status = 'ACTIVE' ORDER BY version DESC" : "version = ?");
        List<TemplateRow> rows = version == null
                ? Jdbc.query(c, sql, TemplateRegistry::map, tenantId, key)
                : Jdbc.query(c, sql, TemplateRegistry::map, tenantId, key, version);
        return rows.stream().findFirst();
    }

    public FieldTemplate require(String tenantId, String key, Integer version) {
        return find(tenantId, key, version).orElseThrow(() -> FieldException.notFound("template", key)).template();
    }

    /** Latest ACTIVE version of every key. */
    public List<TemplateRow> listLatest(String tenantId) {
        return tx.inTransaction(c -> {
            List<TemplateRow> all = Jdbc.query(c, "SELECT * FROM FLD_TEMPLATE WHERE tenant_id = ? AND status = 'ACTIVE' "
                    + "ORDER BY template_key, version DESC", TemplateRegistry::map, tenantId);
            List<TemplateRow> out = new ArrayList<>();
            String last = null;
            for (TemplateRow r : all) {
                if (!r.key().equals(last)) {
                    out.add(r);
                    last = r.key();
                }
            }
            return out;
        });
    }

    private static TemplateRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        FieldTemplate t = FieldJson.read(rs.getString("doc_json"), FieldTemplate.class);
        return new TemplateRow(rs.getString("template_key"), rs.getInt("version"), rs.getString("status"),
                rs.getInt("offline_capable") != 0, rs.getString("content_hash"), rs.getString("defined_by"),
                Jdbc.instant(rs, "defined_at"), t);
    }

    // ------------------------------------------------------------------ loading from configuration

    public static FieldTemplate parse(String json) {
        FieldTemplate t = FieldJson.read(json, FieldTemplate.class);
        t.validateDefinition();
        return t;
    }

    public static FieldTemplate parse(InputStream in) {
        try (in) {
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new FieldException("INVALID_TEMPLATE", "cannot read template: " + e.getMessage(), e);
        }
    }

    /** Publishes every {@code *.json} in a directory whose content differs from the latest stored version. */
    public List<TemplateRow> loadDirectory(String tenantId, Path dir, String actor) {
        List<TemplateRow> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                FieldTemplate t = parse(Files.readString(p));
                Optional<TemplateRow> current = find(tenantId, t.key(), null);
                boolean same = current.isPresent()
                        && FieldJson.write(current.get().template().withVersion(0)).equals(FieldJson.write(t.withVersion(0)));
                if (!same) {
                    out.add(publish(tenantId, t, actor));
                }
            }
        } catch (IOException e) {
            throw new FieldException("INVALID_TEMPLATE", "cannot read template directory: " + e.getMessage(), e);
        }
        return out;
    }

    /** Publishes a classpath template resource (packs). */
    public TemplateRow loadResource(String tenantId, String resource, String actor) {
        InputStream in = TemplateRegistry.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw FieldException.notFound("template resource", resource);
        }
        return publish(tenantId, parse(in), actor);
    }
}
