// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.visit;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.field.FieldAudit;
import org.sangam.field.FieldConfig;
import org.sangam.field.FieldException;
import org.sangam.field.FieldJson;
import org.sangam.field.evidence.CapturePacket.GpsFix;
import org.sangam.field.evidence.CapturePacket.MediaRef;
import org.sangam.field.evidence.EvidenceService;
import org.sangam.field.evidence.IntegrityChecks;
import org.sangam.field.evidence.IntegrityReport;
import org.sangam.field.ports.FieldPorts.FieldEvents;
import org.sangam.field.ports.FieldPorts.GeoPort;
import org.sangam.field.schedule.Assignment;
import org.sangam.field.schedule.AssignmentService;
import org.sangam.field.scoring.ScoringService;
import org.sangam.field.support.FieldSql;
import org.sangam.field.sync.ConflictResolver;
import org.sangam.field.template.ChecklistValidator;
import org.sangam.field.template.FieldTemplate;
import org.sangam.field.template.TemplateRegistry;
import org.sangam.geo.geofence.GeofenceResult;
import org.sangam.geo.model.GeoPoint;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Visit submission (N05): conflict check → declared evidence → checklist validation → anti-spoofing and geofence
 * checks → scoring (weighted or DMN) → findings → assignment SUBMITTED. Works for offline packets (through
 * SyncService) and for online capture (the UI's {@code POST /field/visits/{id}/record}). A visit whose integrity is
 * not VERIFIED is still recorded but marked for review: never silently accepted, never silently lost.
 */
public final class VisitService {

    public enum Outcome { ACCEPTED, FLAGGED, CONFLICT, INVALID }

    /** Everything a submission carries; serialised into a conflict row so a reviewer can accept it later. */
    public record Submission(String assignmentId, String packetId, String operationId, String deviceId, Integer baseVersion,
                             Map<String, Object> answers, String notes, List<MediaRef> media, GpsFix fix, Instant deviceTime,
                             Instant receivedAt, List<IntegrityReport.Check> extraChecks) {
        public Submission {
            answers = answers == null ? Map.of() : answers;
            media = media == null ? List.of() : media;
            extraChecks = extraChecks == null ? List.of() : extraChecks;
        }
    }

    public record VisitResult(Outcome outcome, String assignmentId, Map<String, Object> score, IntegrityReport integrity,
                              List<ChecklistValidator.Violation> violations, String conflictId, String reason) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("outcome", outcome.name());
            m.put("assignmentId", assignmentId);
            m.put("score", score);
            m.put("integrity", integrity == null ? null : integrity.toMap());
            m.put("violations", violations);
            m.put("conflictId", conflictId);
            m.put("reason", reason);
            return m;
        }
    }

    private final Transactions tx;
    private final AssignmentService assignments;
    private final TemplateRegistry templates;
    private final EvidenceService evidence;
    private final ScoringService scoring;
    private final ConflictResolver conflicts;
    private final GeoPort geo;
    private final FieldAudit audit;
    private final FieldEvents events;
    private final FieldConfig config;
    private final Clock clock;

    public VisitService(Transactions tx, AssignmentService assignments, TemplateRegistry templates, EvidenceService evidence,
                        ScoringService scoring, ConflictResolver conflicts, GeoPort geo, FieldAudit audit, FieldEvents events,
                        FieldConfig config, Clock clock) {
        this.tx = tx;
        this.assignments = assignments;
        this.templates = templates;
        this.evidence = evidence;
        this.scoring = scoring;
        this.conflicts = conflicts;
        this.geo = geo;
        this.audit = audit;
        this.events = events;
        this.config = config == null ? FieldConfig.defaults() : config;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public VisitResult submit(String tenantId, String principalId, Submission s) {
        return tx.inTransaction(c -> submit(c, tenantId, principalId, s, false));
    }

    public VisitResult submit(Connection c, String tenantId, String principalId, Submission s, boolean conflictOverride) {
        Assignment a = assignments.get(c, tenantId, s.assignmentId());
        FieldTemplate t = templates.find(c, tenantId, a.templateKey(), a.templateVersion())
                .orElseThrow(() -> FieldException.notFound("template", a.templateKey())).template();
        if (!conflictOverride) {
            ConflictResolver.Decision d = ConflictResolver.decide(a, s.baseVersion(), s.deviceId(), principalId);
            if (d.kind() != null) {
                String cid = conflicts.raise(c, tenantId, d, a.id(), s.operationId(), s.deviceId(), FieldJson.toMap(s),
                        a.view(), principalId);
                if (!d.proceed()) {
                    return new VisitResult(Outcome.CONFLICT, a.id(), null, null, List.of(), cid, d.reason());
                }
            }
        }
        if (!s.media().isEmpty()) {
            evidence.registerDeclared(c, tenantId, s.packetId(), a.id(), s.media(), principalId);
        }
        Map<String, Integer> counts = evidence.countsByItem(c, tenantId, a.id());
        List<ChecklistValidator.Violation> violations = ChecklistValidator.validate(t, s.answers(), counts);
        if (!violations.isEmpty()) {
            return new VisitResult(Outcome.INVALID, a.id(), null, null, violations, null, "checklist validation failed");
        }
        Instant received = s.receivedAt() == null ? clock.instant() : s.receivedAt();
        FieldTemplate.EvidenceRules rules = t.evidence();
        long skew = rules.maxClockSkewSeconds() == null ? config.maxClockSkewSeconds() : rules.maxClockSkewSeconds();
        IntegrityReport.Builder b = new IntegrityReport.Builder();
        IntegrityChecks.time(b, s.deviceTime() == null ? received : s.deviceTime(), received, a.windowStart(), a.windowEnd(), skew);
        boolean fenceRequired = a.fence() != null && a.fence().required();
        IntegrityChecks.fix(b, s.fix(), rules, fenceRequired);
        if (a.fence() != null) {
            GeofenceResult gr = s.fix() == null ? null
                    : geo.checkFence(tenantId, a.fence().toGeofence(a.id()), new GeoPoint(s.fix().lat(), s.fix().lon()), s.fix().accuracyM());
            if (gr != null) {
                IntegrityChecks.geofence(b, gr, fenceRequired);
            }
        }
        s.extraChecks().forEach(b::add);
        IntegrityReport integrity = b.build();

        Map<String, Object> ctx = new LinkedHashMap<>(a.attributes());
        ctx.put("targetRef", a.targetRef());
        ctx.put("integrityStatus", integrity.status().name());
        ScoringService.ScoreResult sr = scoring.score(tenantId, t, s.answers(), ctx);
        Instant now = clock.instant();
        for (ScoringService.Finding f : sr.findings()) {
            Jdbc.update(c, "INSERT INTO FLD_FINDING (tenant_id, finding_id, assignment_id, packet_id, item_id, severity, code, description, "
                            + "status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)", tenantId, FieldSql.newId(), a.id(), s.packetId(),
                    f.itemId(), f.severity(), f.code(), f.description(), now);
        }
        Map<String, Object> score = new LinkedHashMap<>();
        score.put("method", sr.method());
        score.put("score", sr.score());
        score.put("grade", sr.grade());
        score.put("passed", sr.passed());
        score.put("criticalFailed", sr.criticalFailed());
        score.put("findings", sr.findings().size());
        score.put("integrityStatus", integrity.status().name());
        score.put("review", integrity.status() != IntegrityReport.Status.VERIFIED);
        score.put("notes", s.notes());
        score.put("answers", s.answers());
        score.put("fix", s.fix());
        score.put("deviceTime", s.deviceTime());
        Assignment fresh = assignments.get(c, tenantId, a.id());
        assignments.markSubmitted(c, fresh, s.packetId(), s.deviceId(), score);
        audit.record(c, tenantId, a.id(), FieldAudit.SCORED, principalId, Map.of("score", String.valueOf(sr.score()),
                "grade", String.valueOf(sr.grade()), "passed", sr.passed(), "integrity", integrity.status().name()));
        if (integrity.status() != IntegrityReport.Status.VERIFIED) {
            audit.record(c, tenantId, a.id(), FieldAudit.INTEGRITY_FLAGGED, principalId,
                    Map.of("problems", integrity.problems().stream().map(IntegrityReport.Check::code).toList()));
        }
        events.publish(c, tenantId, "field.visit.submitted", a.id(), Map.of("taskId", String.valueOf(a.taskId()),
                "businessKey", String.valueOf(a.businessKey()), "passed", sr.passed(), "grade", String.valueOf(sr.grade()),
                "integrity", integrity.status().name()));
        Outcome o = integrity.status() == IntegrityReport.Status.VERIFIED ? Outcome.ACCEPTED : Outcome.FLAGGED;
        return new VisitResult(o, a.id(), score, integrity, List.of(), null, null);
    }

    /** Reviewer decision on a SUBMITTED visit; the reviewer cannot be the inspector (maker-checker). */
    public Assignment review(String tenantId, String assignmentId, boolean accept, String reason, String reviewer) {
        return tx.inTransaction(c -> {
            Assignment a = assignments.get(c, tenantId, assignmentId);
            if (reviewer != null && reviewer.equals(a.assignee())) {
                throw new FieldException("FORBIDDEN", "the inspector cannot review their own visit");
            }
            return assignments.transition(c, tenantId, assignmentId, null, accept ? Assignment.Status.ACCEPTED : Assignment.Status.REJECTED,
                    reviewer, reason);
        });
    }

    /**
     * Resolves an OPEN sync conflict. ACCEPT_CLIENT re-applies the held submission (a duplicate supersedes the earlier
     * submission, which is marked REJECTED first); KEEP_SERVER and DISCARD keep the server state.
     */
    public VisitResult resolveConflict(String tenantId, String conflictId, ConflictResolver.Resolution r, String actor, String note) {
        return tx.inTransaction(c -> {
            ConflictResolver.Conflict cf = conflicts.require(c, tenantId, conflictId);
            conflicts.markResolved(c, tenantId, conflictId, r, actor, note);
            if (r != ConflictResolver.Resolution.ACCEPT_CLIENT) {
                return new VisitResult(Outcome.CONFLICT, cf.assignmentId(), null, null, List.of(), conflictId, "server state kept");
            }
            Assignment a = assignments.get(c, tenantId, cf.assignmentId());
            if (a.status() == Assignment.Status.CANCELLED) {
                throw new FieldException("INVALID_TRANSITION", "a cancelled assignment cannot take a submission; reopen it as a new assignment");
            }
            if (a.status() == Assignment.Status.SUBMITTED) {
                assignments.transition(c, tenantId, a.id(), null, Assignment.Status.REJECTED, actor, "superseded by conflict " + conflictId);
            } else if (a.status() == Assignment.Status.ACCEPTED) {
                throw new FieldException("INVALID_TRANSITION", "the visit was already accepted; raise a new assignment instead");
            }
            Submission s = FieldJson.convert(cf.client(), Submission.class);
            return submit(c, tenantId, actor, s, true);
        });
    }
}
