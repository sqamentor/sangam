// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.telemetry;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator side of an alarm episode (N12): acknowledge, close, and the acknowledgement-SLA sweep. Mutations hold the
 * episode row lock and are audited on {@code sec:<tenant>} when an audit chain is configured.
 */
public final class AlarmAcknowledgementService {

    private final Transactions tx;
    private final AlarmEpisodeStore episodes;
    private final DeviceRegistry devices;
    private final RuleSet.Registry rules;
    private final AlarmEventEmitter emitter;
    private final AuditChain audit;

    public AlarmAcknowledgementService(Transactions tx, AlarmEpisodeStore episodes, DeviceRegistry devices, RuleSet.Registry rules,
                                       AlarmEventEmitter emitter, AuditChain auditOrNull) {
        this.tx = tx;
        this.episodes = episodes;
        this.devices = devices;
        this.rules = rules;
        this.emitter = emitter;
        this.audit = auditOrNull;
    }

    /** Idempotent for an episode that is already acknowledged. A cleared episode closes on acknowledgement. */
    public AlarmEpisode acknowledge(String tenant, String episodeId, String actor, String note) {
        return tx.inTransaction(c -> {
            audit(c, tenant, "ALARM_ACKNOWLEDGED", episodeId, actor, note);
            AlarmEpisode e = locked(c, tenant, episodeId);
            if (e.state == AlarmEpisode.State.CLOSED || e.ackedAt != null) {
                return e;
            }
            Instant now = Jdbc.dbNow(c, episodes.dialect());
            e.ackedBy = actor;
            e.ackedAt = now;
            e.ackNote = note;
            if (e.state == AlarmEpisode.State.CLEARED) {
                closeFields(e, "ACKNOWLEDGED_AFTER_CLEAR", now);
            }
            e.updatedAt = now;
            episodes.update(c, e);
            return e;
        });
    }

    /** Operator close with a reason. */
    public AlarmEpisode close(String tenant, String episodeId, String actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a reason is required");
        }
        return tx.inTransaction(c -> {
            audit(c, tenant, "ALARM_CLOSED", episodeId, actor, reason);
            AlarmEpisode e = locked(c, tenant, episodeId);
            if (e.state == AlarmEpisode.State.CLOSED) {
                return e;
            }
            Instant now = Jdbc.dbNow(c, episodes.dialect());
            closeFields(e, reason, now);
            e.updatedAt = now;
            episodes.update(c, e);
            return e;
        });
    }

    public List<AlarmEpisode> overdueAcks(String tenant) {
        return tx.inTransaction(c -> episodes.overdueAcks(c, tenant));
    }

    /** Emits each overdue episode's {@code ackOverdueEventKey} once; a redelivery is deduplicated by the ports id. */
    public int sweepOverdue(String tenant) {
        int n = 0;
        for (AlarmEpisode candidate : overdueAcks(tenant)) {
            boolean emitted = tx.inTransaction(c -> {
                AlarmEpisode e = locked(c, tenant, candidate.episodeId());
                if (e.overdueNotifiedAt != null || e.ackedAt != null || e.state == AlarmEpisode.State.CLOSED) {
                    return false;
                }
                ThresholdRule rule = rules.rule(tenant, e.ruleId()).orElse(null);
                Instant now = Jdbc.dbNow(c, episodes.dialect());
                if (rule != null) {
                    emitter.emit(e, rule, rule.ackOverdueEventKey(), "ACK_OVERDUE", devices.find(c, tenant, e.deviceId()).orElse(null),
                            null, null, now);
                }
                e.overdueNotifiedAt = now;
                e.updatedAt = now;
                episodes.update(c, e);
                return true;
            });
            n += emitted ? 1 : 0;
        }
        return n;
    }

    private AlarmEpisode locked(Connection c, String tenant, String episodeId) {
        return episodes.lock(c, tenant, episodeId)
                .orElseThrow(() -> new IllegalArgumentException("no alarm episode " + episodeId));
    }

    private static void closeFields(AlarmEpisode e, String reason, Instant now) {
        e.state = AlarmEpisode.State.CLOSED;
        e.closedAt = now;
        e.closeReason = reason;
    }

    private void audit(Connection c, String tenant, String type, String episodeId, String actor, String note) {
        if (audit != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("episodeId", episodeId);
            p.put("actor", actor);
            p.put("note", note);
            audit.append(c, tenant, "sec:" + tenant, type, p);
        }
    }
}
