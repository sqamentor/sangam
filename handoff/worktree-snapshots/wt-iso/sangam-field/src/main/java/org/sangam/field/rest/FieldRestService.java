// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.field.FieldConfig;
import org.sangam.field.FieldException;
import org.sangam.field.FieldJson;
import org.sangam.field.FieldModule;
import org.sangam.field.dispatch.DispatchModel;
import org.sangam.field.dispatch.DispatchService;
import org.sangam.field.evidence.CapturePacket;
import org.sangam.field.evidence.EvidenceService;
import org.sangam.field.schedule.Assignment;
import org.sangam.field.schedule.AssignmentService;
import org.sangam.field.schedule.RandomSelector;
import org.sangam.field.schedule.RouteOrdering;
import org.sangam.field.schedule.ScheduleService;
import org.sangam.field.sync.ConflictResolver;
import org.sangam.field.sync.DeviceRegistry;
import org.sangam.field.sync.QueuePolicy;
import org.sangam.field.template.FieldTemplate;
import org.sangam.field.template.TemplateRegistry;
import org.sangam.field.visit.VisitService;
import org.sangam.geo.model.GeoPoint;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** {@link FieldRestPort} over the module services. Pure mapping: no business rule lives here. */
public final class FieldRestService implements FieldRestPort {

    private static final List<Assignment.Status> OPEN = List.of(Assignment.Status.ASSIGNED, Assignment.Status.IN_PROGRESS,
            Assignment.Status.OVERDUE, Assignment.Status.REJECTED);

    private final FieldModule m;
    private final Transactions tx;
    private final Dialect dialect;
    private final FieldConfig config;
    private final Clock clock;

    public FieldRestService(FieldModule module, Transactions tx, Dialect dialect, FieldConfig config, Clock clock) {
        this.m = module;
        this.tx = tx;
        this.dialect = dialect;
        this.config = config;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ UI contract

    @Override
    public List<Map<String, Object>> visits(FieldCaller caller) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Assignment.Status s : OPEN) {
            for (Assignment a : m.assignments.list(caller.tenantId(), caller.principalId(), s, null, 0, 500)) {
                FieldTemplate t = m.templates.require(caller.tenantId(), a.templateKey(), a.templateVersion());
                List<Map<String, Object>> checklist = new ArrayList<>();
                for (FieldTemplate.Item i : t.items()) {
                    Map<String, Object> it = new LinkedHashMap<>();
                    it.put("id", i.id());
                    it.put("label", Optional.ofNullable(FieldTemplate.label(i.label(), caller.lang())).orElse(i.id()));
                    it.put("type", i.type().name());
                    it.put("required", i.required());
                    checklist.add(it);
                }
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("id", a.id());
                v.put("taskId", a.taskId());
                v.put("businessKey", a.businessKey());
                v.put("address", a.address() != null ? a.address() : a.targetLabel());
                v.put("scheduledAt", a.windowStart() != null ? a.windowStart() : a.dueAt());
                v.put("checklist", checklist);
                v.put("version", a.version());
                out.add(v);
            }
        }
        return out;
    }

    /** Online capture from the web app; idempotent by Idempotency-Key through the same FLD_SYNC_OP store as devices. */
    @Override
    public Map<String, Object> recordVisit(FieldCaller caller, String assignmentId, Map<String, Object> body, String idempotencyKey) {
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : "web:" + idempotencyKey;
        String hash = Hashes.hex(Hashes.sha256(assignmentId + "\n" + CanonicalJson.write(normaliseForHash(body))));
        if (key != null) {
            Optional<Map<String, Object>> prior = claimOrReplay(caller.tenantId(), key, hash);
            if (prior.isPresent()) {
                return prior.get();
            }
        }
        try {
            CapturePacket.GpsFix fix = body.get("lat") == null ? null : new CapturePacket.GpsFix(num(body.get("lat")), num(body.get("lon")),
                    body.get("accuracyM") == null ? null : num(body.get("accuracyM")), null, str(body, "provider"),
                    Boolean.TRUE.equals(body.get("mockLocation")), instant(body, "recordedAt"));
            @SuppressWarnings("unchecked")
            Map<String, Object> answers = body.get("answers") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
            String packetId = key != null ? key : "web:" + org.sangam.field.support.FieldSql.newId();
            VisitService.VisitResult r = m.visits.submit(caller.tenantId(), caller.principalId(), new VisitService.Submission(assignmentId,
                    packetId, key, null, body.get("version") == null ? null : (int) num(body.get("version")), answers, str(body, "notes"),
                    List.of(), fix, instant(body, "recordedAt"), clock.instant(), List.of()));
            Map<String, Object> res = r.toMap();
            if (r.outcome() == VisitService.Outcome.INVALID) {
                if (key != null) {
                    forget(caller.tenantId(), key);
                }
                throw new FieldException("CHECKLIST_INVALID", "checklist validation failed: " + r.violations());
            }
            if (key != null) {
                complete(caller.tenantId(), key, res);
            }
            return res;
        } catch (RuntimeException e) {
            if (key != null && !(e instanceof FieldException fe && "CHECKLIST_INVALID".equals(fe.code()))) {
                forget(caller.tenantId(), key);
            }
            throw e;
        }
    }

    private Optional<Map<String, Object>> claimOrReplay(String tenantId, String key, String hash) {
        return tx.inTransaction(c -> {
            boolean claimed = Jdbc.insertIfAbsent(c, dialect, "INSERT INTO FLD_SYNC_OP (tenant_id, operation_id, device_id, packet_type, "
                    + "request_hash, status, received_at) VALUES (?, ?, 'web', 'VISIT_SUBMIT', ?, 'PROCESSING', ?)", tenantId, key, hash, clock.instant());
            if (claimed) {
                return Optional.<Map<String, Object>>empty();
            }
            record Prior(String hash, String status, String result) {
            }
            Prior p = Jdbc.one(c, "SELECT request_hash, status, result_json FROM FLD_SYNC_OP WHERE tenant_id = ? AND operation_id = ?",
                    rs -> new Prior(rs.getString(1), rs.getString(2), rs.getString(3)), tenantId, key).orElseThrow();
            if (!p.hash().equals(hash)) {
                throw new FieldException("IDEMPOTENCY_CONFLICT", "Idempotency-Key reused with a different request");
            }
            if (!"DONE".equals(p.status())) {
                throw new FieldException("CONFLICT", "a request with this Idempotency-Key is still in progress");
            }
            return Optional.of(FieldJson.map(p.result()));
        });
    }

    private void complete(String tenantId, String key, Map<String, Object> result) {
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE FLD_SYNC_OP SET status = 'DONE', result_json = ? WHERE tenant_id = ? AND operation_id = ?",
                FieldJson.write(result), tenantId, key));
    }

    private void forget(String tenantId, String key) {
        tx.inTransaction(c -> Jdbc.update(c, "DELETE FROM FLD_SYNC_OP WHERE tenant_id = ? AND operation_id = ? AND status = 'PROCESSING'",
                tenantId, key));
    }

    // ------------------------------------------------------------------ templates

    @Override
    public List<Map<String, Object>> listTemplates(FieldCaller caller) {
        return m.templates.listLatest(caller.tenantId()).stream().map(FieldRestService::templateView).toList();
    }

    @Override
    public Map<String, Object> getTemplate(FieldCaller caller, String key, Integer version) {
        return templateView(m.templates.find(caller.tenantId(), key, version).orElseThrow(() -> FieldException.notFound("template", key)));
    }

    @Override
    public Map<String, Object> publishTemplate(FieldCaller caller, Map<String, Object> template) {
        return templateView(m.templates.publish(caller.tenantId(), FieldJson.convert(template, FieldTemplate.class), caller.principalId()));
    }

    @Override
    public void retireTemplate(FieldCaller caller, String key, int version) {
        m.templates.retire(caller.tenantId(), key, version, caller.principalId());
    }

    private static Map<String, Object> templateView(TemplateRegistry.TemplateRow r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("key", r.key());
        v.put("version", r.version());
        v.put("status", r.status());
        v.put("offlineCapable", r.offlineCapable());
        v.put("contentHash", r.contentHash());
        v.put("definedBy", r.definedBy());
        v.put("definedAt", r.definedAt());
        v.put("template", r.template());
        return v;
    }

    // ------------------------------------------------------------------ assignments

    @Override
    public List<Map<String, Object>> listAssignments(FieldCaller caller, String assignee, String status, String targetRef, int offset, int limit) {
        return m.assignments.list(caller.tenantId(), assignee, status == null ? null : Assignment.Status.valueOf(status), targetRef, offset, limit)
                .stream().map(Assignment::view).toList();
    }

    @Override
    public Map<String, Object> getAssignment(FieldCaller caller, String id) {
        return m.assignments.get(caller.tenantId(), id).view();
    }

    @Override
    public Map<String, Object> createAssignment(FieldCaller caller, Map<String, Object> b) {
        return m.assignments.create(caller.tenantId(), FieldJson.convert(b, AssignmentService.NewAssignment.class), caller.principalId()).view();
    }

    @Override
    public Map<String, Object> reassign(FieldCaller caller, String id, Map<String, Object> b) {
        return m.assignments.reassign(caller.tenantId(), id, intOrNull(b.get("version")), str(b, "assignee"), str(b, "seatId"),
                caller.principalId()).view();
    }

    @Override
    public Map<String, Object> transitionAssignment(FieldCaller caller, String id, Map<String, Object> b) {
        return m.assignments.transition(caller.tenantId(), id, intOrNull(b.get("version")), Assignment.Status.valueOf(req(b, "status")),
                caller.principalId(), str(b, "reason")).view();
    }

    @Override
    public Map<String, Object> reviewVisit(FieldCaller caller, String id, Map<String, Object> b) {
        return m.visits.review(caller.tenantId(), id, Boolean.TRUE.equals(b.get("accept")), str(b, "reason"), caller.principalId()).view();
    }

    @Override
    public List<Map<String, Object>> routeList(FieldCaller caller, Map<String, Object> b) {
        String assignee = b.get("assignee") == null ? caller.principalId() : str(b, "assignee");
        List<Assignment> open = new ArrayList<>();
        for (Assignment.Status s : OPEN) {
            open.addAll(m.assignments.list(caller.tenantId(), assignee, s, null, 0, 500));
        }
        GeoPoint start = b.get("lat") == null ? null : new GeoPoint(num(b.get("lat")), num(b.get("lon")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (RouteOrdering.Stop s : RouteOrdering.order(start, open)) {
            Map<String, Object> v = new LinkedHashMap<>(s.assignment().view());
            v.put("legMeters", Math.round(s.legMeters()));
            v.put("cumulativeMeters", Math.round(s.cumulativeMeters()));
            out.add(v);
        }
        return out;
    }

    @Override
    public Map<String, Object> sweepOverdue(FieldCaller caller) {
        return Map.of("overdue", m.assignments.sweepOverdue(caller.tenantId(), config.overdueGraceSeconds()));
    }

    // ------------------------------------------------------------------ schedules and selection

    @Override
    public List<Object> listSchedules(FieldCaller caller) {
        return new ArrayList<>(m.schedules.list(caller.tenantId()));
    }

    @Override
    public Object defineSchedule(FieldCaller caller, Map<String, Object> body) {
        return m.schedules.define(caller.tenantId(), FieldJson.convert(body, ScheduleService.Schedule.class), caller.principalId());
    }

    @Override
    public Object runSchedule(FieldCaller caller, String id) {
        return m.schedules.run(caller.tenantId(), id, caller.principalId());
    }

    @Override
    public List<Object> runDueSchedules(FieldCaller caller) {
        return new ArrayList<>(m.schedules.runDue(caller.tenantId()));
    }

    @Override
    public Object randomSelect(FieldCaller caller, Map<String, Object> b) {
        List<RandomSelector.Candidate> pop = FieldJson.MAPPER.convertValue(b.getOrDefault("population", List.of()),
                new TypeReference<List<RandomSelector.Candidate>>() { });
        return m.selector.select(caller.tenantId(), req(b, "seed"), pop, (int) num(b.get("sampleSize")), str(b, "riskDecisionKey"),
                caller.principalId());
    }

    @Override
    public Map<String, Object> verifySelection(FieldCaller caller, String id) {
        return Map.of("selectionId", id, "reproduced", m.selector.verify(caller.tenantId(), id));
    }

    // ------------------------------------------------------------------ devices and sync

    @Override
    public Map<String, Object> registerDevice(FieldCaller caller, Map<String, Object> b) {
        return m.devices.register(caller.tenantId(), caller.principalId(), FieldJson.convert(b, DeviceRegistry.Registration.class)).view();
    }

    @Override
    public List<Map<String, Object>> listDevices(FieldCaller caller) {
        return m.devices.listForPrincipal(caller.tenantId(), caller.principalId()).stream().map(DeviceRegistry.Device::view).toList();
    }

    @Override
    public Map<String, Object> setDeviceStatus(FieldCaller caller, String deviceId, Map<String, Object> b) {
        return m.devices.setStatus(caller.tenantId(), deviceId, DeviceRegistry.Status.valueOf(req(b, "status")), str(b, "reason"),
                caller.principalId()).view();
    }

    @Override
    public Map<String, Object> setDevicePolicy(FieldCaller caller, String deviceId, Map<String, Object> b) {
        return m.devices.setPolicy(caller.tenantId(), deviceId, FieldJson.convert(b, QueuePolicy.class)).view();
    }

    @Override
    public List<Object> upload(FieldCaller caller, String deviceId, List<Map<String, Object>> packets) {
        List<CapturePacket> ps = packets.stream().map(p -> FieldJson.convert(p, CapturePacket.class)).toList();
        return new ArrayList<>(m.sync.upload(caller.tenantId(), caller.principalId(), deviceId, ps));
    }

    @Override
    public Object pull(FieldCaller caller, String deviceId, long cursor) {
        return m.sync.pull(caller.tenantId(), caller.principalId(), deviceId, cursor);
    }

    // ------------------------------------------------------------------ conflicts

    @Override
    public List<Object> listConflicts(FieldCaller caller, String status) {
        return new ArrayList<>(m.conflicts.list(caller.tenantId(), status, 200));
    }

    @Override
    public Object getConflict(FieldCaller caller, String id) {
        return m.conflicts.get(caller.tenantId(), id);
    }

    @Override
    public Map<String, Object> resolveConflict(FieldCaller caller, String id, Map<String, Object> b) {
        return m.visits.resolveConflict(caller.tenantId(), id, ConflictResolver.Resolution.valueOf(req(b, "resolution")), caller.principalId(),
                str(b, "note")).toMap();
    }

    // ------------------------------------------------------------------ evidence

    @Override
    public Object uploadEvidence(FieldCaller caller, Map<String, Object> meta, byte[] bytes) {
        CapturePacket.GpsFix fix = meta.get("lat") == null ? null : new CapturePacket.GpsFix(num(meta.get("lat")), num(meta.get("lon")),
                meta.get("accuracyM") == null ? null : num(meta.get("accuracyM")), null, str(meta, "provider"),
                Boolean.TRUE.equals(meta.get("mockLocation")) || "true".equals(String.valueOf(meta.get("mockLocation"))), null);
        return m.evidence.upload(caller.tenantId(), caller.principalId(), new EvidenceService.Upload(str(meta, "evidenceId"),
                str(meta, "assignmentId"), str(meta, "itemId"), str(meta, "packetId"), str(meta, "mediaType"), str(meta, "sha256"),
                instant(meta, "capturedAt"), fix), bytes);
    }

    @Override
    public Object getEvidence(FieldCaller caller, String id) {
        return m.evidence.get(caller.tenantId(), id);
    }

    @Override
    public Optional<byte[]> evidenceContent(FieldCaller caller, String id) {
        return m.evidence.content(caller.tenantId(), id);
    }

    @Override
    public List<Object> listEvidence(FieldCaller caller, String assignmentId) {
        return new ArrayList<>(m.evidence.listForAssignment(caller.tenantId(), assignmentId));
    }

    @Override
    public Map<String, Object> reverifyEvidence(FieldCaller caller, String id) {
        return Map.of("evidenceId", id, "intact", m.evidence.reverify(caller.tenantId(), id));
    }

    @Override
    public Map<String, Object> verifyChain(FieldCaller caller, String deviceId) {
        List<EvidenceService.ChainBreak> breaks = m.evidence.verifyChain(caller.tenantId(), deviceId);
        return Map.of("deviceId", deviceId, "intact", breaks.isEmpty(), "breaks", breaks);
    }

    // ------------------------------------------------------------------ dispatch

    @Override
    public Object upsertUnit(FieldCaller caller, Map<String, Object> b) {
        return m.dispatch.upsertUnit(caller.tenantId(), FieldJson.convert(b, DispatchService.UnitInput.class), caller.principalId());
    }

    @Override
    public List<Object> listUnits(FieldCaller caller, String status) {
        return new ArrayList<>(m.dispatch.listUnits(caller.tenantId(), status == null ? null : DispatchModel.UnitStatus.valueOf(status)));
    }

    @Override
    public Object getUnit(FieldCaller caller, String id) {
        return m.dispatch.getUnit(caller.tenantId(), id);
    }

    @Override
    public Object setUnitStatus(FieldCaller caller, String id, Map<String, Object> b) {
        return m.dispatch.setUnitStatus(caller.tenantId(), id, DispatchModel.UnitStatus.valueOf(req(b, "status")), caller.principalId());
    }

    @Override
    public Object ping(FieldCaller caller, String unitId, Map<String, Object> b) {
        Instant at = b.get("at") == null ? clock.instant() : instant(b, "at");
        return m.dispatch.ping(caller.tenantId(), new DispatchModel.PositionFix(unitId, at, num(b.get("lat")), num(b.get("lon")),
                b.get("accuracyM") == null ? null : num(b.get("accuracyM")), b.get("speedMps") == null ? null : num(b.get("speedMps")),
                b.get("headingDeg") == null ? null : (int) num(b.get("headingDeg")), str(b, "source")), caller.principalId());
    }

    @Override
    public List<Object> unitOffers(FieldCaller caller, String unitId) {
        return new ArrayList<>(m.dispatch.offersForUnit(caller.tenantId(), unitId));
    }

    @Override
    public Object eta(FieldCaller caller, String unitId, String jobId) {
        return m.dispatch.eta(caller.tenantId(), unitId, jobId);
    }

    @Override
    public List<Object> listTrips(FieldCaller caller, String unitId) {
        return new ArrayList<>(m.dispatch.listTrips(caller.tenantId(), unitId));
    }

    @Override
    public Object createJob(FieldCaller caller, Map<String, Object> b) {
        Set<String> skills = b.get("requiredSkills") instanceof List<?> l ? new LinkedHashSet<>(l.stream().map(String::valueOf).toList()) : Set.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = b.get("attributes") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
        return m.dispatch.createJob(caller.tenantId(), new DispatchService.JobInput(str(b, "kind"), str(b, "priority"), num(b.get("lat")),
                num(b.get("lon")), str(b, "address"), skills, b.get("slaTargetSeconds") == null ? null : (long) num(b.get("slaTargetSeconds")),
                str(b, "businessKey"), attrs, Boolean.TRUE.equals(b.get("autoDispatch"))), caller.principalId());
    }

    @Override
    public List<Object> listJobs(FieldCaller caller, String status, int limit) {
        return new ArrayList<>(m.dispatch.listJobs(caller.tenantId(), status == null ? null : DispatchModel.JobStatus.valueOf(status), limit));
    }

    @Override
    public Object getJob(FieldCaller caller, String id) {
        return m.dispatch.getJob(caller.tenantId(), id);
    }

    @Override
    public List<Object> candidates(FieldCaller caller, String jobId) {
        return new ArrayList<>(m.dispatch.candidates(caller.tenantId(), jobId));
    }

    @Override
    public Object jobAction(FieldCaller caller, String jobId, String action, Map<String, Object> b) {
        Map<String, Object> body = b == null ? Map.of() : b;
        String t = caller.tenantId(), who = caller.principalId();
        return switch (action) {
            case "dispatch" -> m.dispatch.dispatch(t, jobId, who).map(o -> (Object) o).orElse(Map.of("result", "NO_CANDIDATE"));
            case "assign" -> m.dispatch.assign(t, jobId, req(body, "unitId"), who);
            case "progress" -> m.dispatch.progress(t, jobId, DispatchModel.JobStatus.valueOf(req(body, "status")), who);
            case "close" -> m.dispatch.close(t, jobId, str(body, "outcome"), str(body, "note"), str(body, "proofPacketId"), who);
            case "cancel" -> m.dispatch.cancel(t, jobId, str(body, "reason"), who);
            case "redispatch" -> m.dispatch.redispatch(t, jobId, str(body, "reason"), who).map(o -> (Object) o)
                    .orElse(Map.of("result", "NO_CANDIDATE"));
            default -> throw new FieldException("NOT_FOUND", "unknown job action: " + action);
        };
    }

    @Override
    public Object respondOffer(FieldCaller caller, String offerId, Map<String, Object> b) {
        return m.dispatch.respond(caller.tenantId(), offerId, Boolean.TRUE.equals(b.get("accept")), str(b, "reason"), caller.principalId());
    }

    @Override
    public Map<String, Object> expireOffers(FieldCaller caller) {
        return Map.of("expired", m.dispatch.expireOffers(caller.tenantId()));
    }

    @Override
    public Object startTrip(FieldCaller caller, Map<String, Object> b) {
        return m.dispatch.startTrip(caller.tenantId(), req(b, "unitId"), str(b, "jobId"), str(b, "purpose"),
                b.get("odometer") == null ? null : (long) num(b.get("odometer")), caller.principalId());
    }

    @Override
    public Object tripAction(FieldCaller caller, String tripId, String action, Map<String, Object> b) {
        Map<String, Object> body = b == null ? Map.of() : b;
        Long odo = body.get("odometer") == null ? null : (long) num(body.get("odometer"));
        return switch (action) {
            case "legs" -> m.dispatch.addLeg(caller.tenantId(), tripId, req(body, "label"), odo);
            case "end" -> m.dispatch.endTrip(caller.tenantId(), tripId, odo, caller.principalId());
            case "decision" -> m.dispatch.decideTrip(caller.tenantId(), tripId, Boolean.TRUE.equals(body.get("approve")), str(body, "reason"),
                    caller.principalId());
            default -> throw new FieldException("NOT_FOUND", "unknown trip action: " + action);
        };
    }

    // ------------------------------------------------------------------ helpers

    private static Object normaliseForHash(Object v) {
        if (v instanceof Map<?, ?> mm) {
            Map<String, Object> out = new java.util.TreeMap<>();
            mm.forEach((k, x) -> out.put(String.valueOf(k), normaliseForHash(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(FieldRestService::normaliseForHash).toList();
        }
        if (v instanceof Number n) {
            return new java.math.BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        return v;
    }

    static String str(Map<String, Object> b, String k) {
        Object v = b == null ? null : b.get(k);
        return v == null ? null : v.toString();
    }

    static String req(Map<String, Object> b, String k) {
        String v = str(b, k);
        if (v == null || v.isBlank()) {
            throw new FieldException("INVALID_REQUEST", k + " is required");
        }
        return v;
    }

    static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o == null) {
            throw new FieldException("INVALID_REQUEST", "number expected");
        }
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            throw new FieldException("INVALID_REQUEST", "number expected: " + o);
        }
    }

    static Integer intOrNull(Object o) {
        return o == null ? null : (int) num(o);
    }

    static Instant instant(Map<String, Object> b, String k) {
        String s = str(b, k);
        try {
            return s == null ? null : Instant.parse(s);
        } catch (RuntimeException e) {
            throw new FieldException("INVALID_REQUEST", k + " must be an ISO-8601 instant");
        }
    }
}
