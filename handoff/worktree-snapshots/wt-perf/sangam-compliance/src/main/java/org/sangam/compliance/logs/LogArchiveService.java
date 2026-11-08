// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.logs;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.ports.CompliancePorts.LegalHoldPort;
import org.sangam.compliance.ports.CompliancePorts.LogArchiveStore;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Log retention and archive (IN: CERT-In Directions para (iv); DPDP r.6(1)(e), r.8(3); CMP-091, CMP-092, CMP-106):
 * <ul>
 *   <li><b>Sealed segments.</b> The audit-chain envelopes of a time window (no payloads, so no personal data) are
 *       written as NDJSON to a {@link LogArchiveStore} in the required country, hashed (SHA-256) and linked to the
 *       previous segment's hash. Table {@code CMP_LOG_SEGMENT}. Purged only after the effective retention and never
 *       while a legal hold ({@code logs:<tenant>} or {@code log:<segment>}) is active.</li>
 *   <li><b>Log-source register.</b> Logs of other ICT systems (web servers, databases, IdP, firewalls) live outside
 *       Sangam; each source is registered with retention, location and SIEM forwarder, and non-compliant sources are
 *       reported. Table {@code CMP_LOG_SOURCE}.</li>
 * </ul>
 */
public final class LogArchiveService {

    public record Segment(String segmentId, Instant from, Instant to, long events, String sha256, String prevSha256, URI uri,
                          String country, Instant sealedAt, Instant purgeAfter, Instant purgedAt) {
    }

    public record LogSource(String sourceId, String kind, String description, String country, int retentionDays,
                            String forwarder, boolean compliant) {
    }

    private final Dialect dialect;
    private final ComplianceAudit audit;
    private final ComplianceConfig.Provider config;
    private final LogArchiveStore store;
    private final LegalHoldPort holds;

    public LogArchiveService(Dialect dialect, ComplianceAudit audit, ComplianceConfig.Provider config, LogArchiveStore store,
                             LegalHoldPort holds) {
        this.dialect = dialect;
        this.audit = audit;
        this.config = config;
        this.store = store;
        this.holds = holds;
    }

    /** Seals the audit envelopes of [from, to) into one archive segment. */
    public Segment seal(Connection c, String tenantId, Instant from, Instant to, String actor) {
        if (store == null) {
            throw new IllegalStateException("LogArchiveStore is not configured");
        }
        if (!from.isBefore(to)) {
            throw ComplianceException.invalid("from must be before to");
        }
        ComplianceConfig cfg = config.forTenant(tenantId);
        LogRetentionPolicy.requireResidency(cfg, cfg.logRegion, "the log archive region");
        LogRetentionPolicy.requireResidency(cfg, store.country(), "the log archive store");
        Optional<Segment> prev = latest(c, tenantId);
        if (prev.isPresent() && from.isBefore(prev.get().to())) {
            throw ComplianceException.invalid("segment overlaps the previous one (ends " + prev.get().to() + ")");
        }
        String id = Sql.newId();
        StringBuilder sb = new StringBuilder();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("segment", id);
        header.put("tenant", tenantId);
        header.put("from", from.toString());
        header.put("to", to.toString());
        header.put("prev", prev.map(Segment::sha256).orElse(null));
        header.put("format", "sangam-audit-envelopes-v1");
        sb.append(CanonicalJson.write(header)).append('\n');
        long[] n = {0};
        Jdbc.query(c, "SELECT chain_id, seq, event_type, occurred_at, envelope, hash FROM AUDIT_EVENT WHERE tenant_id=? AND occurred_at>=?"
                + " AND occurred_at<? ORDER BY chain_id, seq", rs -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("chain", rs.getString(1));
            line.put("seq", rs.getLong(2));
            line.put("type", rs.getString(3));
            line.put("at", String.valueOf(Jdbc.instant(rs, "occurred_at")));
            line.put("envelope", rs.getString(5));
            line.put("hash", Hashes.hex(rs.getBytes(6)));
            sb.append(CanonicalJson.write(line)).append('\n');
            n[0]++;
            return null;
        }, tenantId, from, to);
        byte[] content = sb.toString().getBytes(StandardCharsets.UTF_8);
        String sha = Hashes.hex(Hashes.sha256(content));
        URI uri = store.store(tenantId, id, content, cfg.logRegion);
        Instant now = Sql.now(c, dialect);
        Instant purgeAfter = LogRetentionPolicy.purgeAfter(cfg, to);
        Jdbc.update(c, "INSERT INTO CMP_LOG_SEGMENT(tenant_id, segment_id, from_ts, to_ts, event_count, sha256, prev_sha256, storage_uri,"
                        + " country, sealed_at, purge_after, purged_at, sealed_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL,?)",
                tenantId, id, from, to, n[0], sha, prev.map(Segment::sha256).orElse(null), uri.toString(), store.country(), now,
                purgeAfter, actor);
        audit.organisation(c, tenantId, ComplianceAudit.LOG_SEGMENT_SEALED, Sql.kv("segment", id, "from", from.toString(),
                "to", to.toString(), "events", n[0], "sha256", sha, "country", store.country(), "purgeAfter", purgeAfter.toString()));
        return get(c, tenantId, id);
    }

    /** Re-reads a segment and checks its hash and its link to the previous segment. */
    public boolean verify(Connection c, String tenantId, String segmentId) {
        Segment s = get(c, tenantId, segmentId);
        if (s.purgedAt() != null) {
            return true;
        }
        byte[] content = store.read(s.uri());
        if (!Hashes.hex(Hashes.sha256(content)).equals(s.sha256())) {
            return false;
        }
        String first = new String(content, StandardCharsets.UTF_8).lines().findFirst().orElse("{}");
        Object prev = Sql.map(first).get("prev");
        return prev == null ? s.prevSha256() == null : prev.equals(s.prevSha256());
    }

    /** Purges segments past retention that are not under a legal hold. */
    public int purgeExpired(Connection c, String tenantId, String actor) {
        List<Segment> expired = Jdbc.query(c, SELECT + " WHERE tenant_id=? AND purged_at IS NULL AND purge_after<" + dialect.now(),
                LogArchiveService::map, tenantId);
        int n = 0;
        boolean tenantHold = holds != null && holds.isHeld(c, tenantId, "logs:" + tenantId);
        for (Segment s : expired) {
            if (tenantHold || holds != null && holds.isHeld(c, tenantId, "log:" + s.segmentId())) {
                continue;
            }
            store.delete(s.uri());
            Jdbc.update(c, "UPDATE CMP_LOG_SEGMENT SET purged_at=" + dialect.now() + " WHERE tenant_id=? AND segment_id=?", tenantId, s.segmentId());
            audit.organisation(c, tenantId, ComplianceAudit.LOG_SEGMENT_PURGED, Sql.kv("segment", s.segmentId(), "sha256", s.sha256(),
                    "actor", actor));
            n++;
        }
        return n;
    }

    public Optional<Segment> latest(Connection c, String tenantId) {
        return Jdbc.query(c, SELECT + " WHERE tenant_id=? ORDER BY to_ts DESC", LogArchiveService::map, tenantId).stream().findFirst();
    }

    public Segment get(Connection c, String tenantId, String segmentId) {
        return Jdbc.one(c, SELECT + " WHERE tenant_id=? AND segment_id=?", LogArchiveService::map, tenantId, segmentId)
                .orElseThrow(() -> ComplianceException.notFound("log segment", segmentId));
    }

    public List<Segment> segments(Connection c, String tenantId) {
        return Jdbc.query(c, SELECT + " WHERE tenant_id=? ORDER BY from_ts", LogArchiveService::map, tenantId);
    }

    // ---------------------------------------------------------------- external log sources

    public LogSource registerSource(Connection c, String tenantId, String sourceId, String kind, String description, String country,
                                    int retentionDays, String forwarder, String actor) {
        Sql.requireText(sourceId, "sourceId");
        boolean ok = LogRetentionPolicy.compliant(config.forTenant(tenantId), retentionDays, country);
        int n = Jdbc.update(c, "UPDATE CMP_LOG_SOURCE SET kind=?, description=?, country=?, retention_days=?, forwarder=?, updated_by=?,"
                        + " updated_at=" + dialect.now() + " WHERE tenant_id=? AND source_id=?",
                kind, description, country, retentionDays, forwarder, actor, tenantId, sourceId);
        if (n == 0) {
            Jdbc.update(c, "INSERT INTO CMP_LOG_SOURCE(tenant_id, source_id, kind, description, country, retention_days, forwarder,"
                            + " updated_by, updated_at) VALUES (?,?,?,?,?,?,?,?," + dialect.now() + ")",
                    tenantId, sourceId, kind, description, country, retentionDays, forwarder, actor);
        }
        audit.organisation(c, tenantId, ComplianceAudit.LOG_SOURCE_REGISTERED, Sql.kv("source", sourceId, "kind", kind,
                "country", country, "retentionDays", retentionDays, "compliant", ok, "actor", actor));
        return new LogSource(sourceId, kind, description, country, retentionDays, forwarder, ok);
    }

    public List<LogSource> sources(Connection c, String tenantId) {
        ComplianceConfig cfg = config.forTenant(tenantId);
        return Jdbc.query(c, "SELECT source_id, kind, description, country, retention_days, forwarder FROM CMP_LOG_SOURCE WHERE tenant_id=?"
                        + " ORDER BY source_id",
                rs -> new LogSource(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6),
                        LogRetentionPolicy.compliant(cfg, rs.getInt(5), rs.getString(4))), tenantId);
    }

    /** Statement for incident reports and the evidence report. */
    public Map<String, Object> describe(Connection c, String tenantId) {
        ComplianceConfig cfg = config.forTenant(tenantId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("retentionDays", cfg.effectiveLogRetentionDays());
        m.put("region", cfg.logRegion);
        m.put("archiveCountry", store == null ? null : store.country());
        Optional<Segment> last = latest(c, tenantId);
        m.put("lastSealedSegmentEnd", last.map(s -> s.to().toString()).orElse(null));
        List<LogSource> src = sources(c, tenantId);
        m.put("externalSources", src.size());
        List<String> bad = new ArrayList<>();
        src.stream().filter(s -> !s.compliant()).forEach(s -> bad.add(s.sourceId()));
        m.put("nonCompliantSources", bad);
        return m;
    }

    private static final String SELECT = "SELECT segment_id, from_ts, to_ts, event_count, sha256, prev_sha256, storage_uri, country,"
            + " sealed_at, purge_after, purged_at FROM CMP_LOG_SEGMENT";

    private static Segment map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Segment(rs.getString(1), Jdbc.instant(rs, "from_ts"), Jdbc.instant(rs, "to_ts"), rs.getLong(4), rs.getString(5),
                rs.getString(6), URI.create(rs.getString(7)), rs.getString(8), Jdbc.instant(rs, "sealed_at"),
                Jdbc.instant(rs, "purge_after"), Jdbc.instant(rs, "purged_at"));
    }
}
