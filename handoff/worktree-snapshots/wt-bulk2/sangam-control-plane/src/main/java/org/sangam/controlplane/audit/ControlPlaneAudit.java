// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.audit;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.sangam.core.layer.Hashes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Append-only audit of every control-plane decision: tenant create/update, placement, route change, cell lifecycle,
 * tenant-move step. Each row carries a SHA-256 of its content. The audit of business events stays in the cells (their
 * hash chains); the move process records the source chain head at cut-over so the chain continues across cells.
 */
@Component
public class ControlPlaneAudit {

    private final JdbcTemplate jdbc;

    public ControlPlaneAudit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, String action, String subject, String detail) {
        long now = System.currentTimeMillis();
        String id = Hashes.uuidV7().toString();
        String d = detail == null ? null : (detail.length() > 2000 ? detail.substring(0, 2000) : detail);
        String hash = Hashes.hex(Hashes.sha256((id + "|" + now + "|" + actor + "|" + action + "|" + subject + "|"
                + (d == null ? "" : d)).getBytes(StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO CP_AUDIT(audit_id, at_ms, actor, action, subject, detail, content_hash) VALUES (?,?,?,?,?,?,?)",
                id, now, actor == null ? "system" : actor, action, subject, d, hash);
    }

    public List<Map<String, Object>> forSubject(String subject, int limit) {
        return jdbc.queryForList("SELECT audit_id, at_ms, actor, action, subject, detail, content_hash FROM CP_AUDIT"
                + " WHERE subject = ? ORDER BY at_ms DESC", subject).stream().limit(Math.max(1, Math.min(limit, 1000))).toList();
    }
}
