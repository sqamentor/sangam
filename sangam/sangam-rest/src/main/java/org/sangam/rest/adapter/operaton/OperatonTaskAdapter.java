// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.runtime.ActivityInstance;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.IdentityLink;
import org.operaton.bpm.engine.task.IdentityLinkType;
import org.operaton.bpm.engine.task.TaskQuery;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.port.WorkspacePort;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.support.TypedVariables;

/**
 * Human tasks on the Operaton {@link TaskService}. Lists are tenant-scoped and limited to the tasks the caller is
 * involved in (assignee, candidate user, candidate group/seat); admins and operators see the whole tenant. This is the
 * default visibility rule until sangam-security supplies the VI.4 {@code AuthorizedQueryBuilder}.
 * <p>
 * Completion re-reads the task inside the command transaction and checks, in order: assignment/candidacy (K3),
 * segregation of duties (K5), case version (K9) and the material payload hash (K4).
 */
public class OperatonTaskAdapter implements TaskPort {

    static final String SEAT = "sangamSeatId";
    static final String CASE_VERSION = "sangamCaseVersion";
    static final String INITIATOR = "sangamInitiator";
    static final String SOD_EXCLUDE = "sangamSodExclude";
    static final String SOD_MODE = "sangamSod";
    static final String ACTORS = "sangamActors";
    static final String DECISION = "sangamDecision";
    static final String REMARKS = "sangamRemarks";
    static final String RETURN_REASON = "sangamReturnReason";
    static final String FORM_VERSION = "sangamFormVersion";
    static final String RETURN_TARGETS = "sangamReturnTargets";
    static final String REJECT_REASONS = "sangamRejectReasons";
    static final String RETURN_REASONS = "sangamReturnReasons";
    static final String INITIATOR_SEAT = "sangamInitiatorSeatId";
    static final String SOD_EXCEPTION = "sangamSodExceptionId";
    static final String REASON_CODE = "sangamReasonCode";
    static final String NEXT_SEAT = "sangamNextSeatId";
    static final String NEXT_SEAT_REASON = "sangamNextSeatReason";
    static final String RETURN_TARGET_SEAT = "sangamReturnTargetSeatId";
    static final String RETURN_TARGET_LEVEL = "sangamReturnTargetLevel";

    private final TaskService tasks;
    private final RuntimeService runtime;
    private final HistoryService history;
    private final TxSupport tx;
    private final AuditPort audit;
    private final RestProperties props;

    public OperatonTaskAdapter(TaskService tasks, RuntimeService runtime, HistoryService history, TxSupport tx,
                               AuditPort audit, RestProperties props) {
        this.tasks = tasks;
        this.runtime = runtime;
        this.history = history;
        this.tx = tx;
        this.audit = audit;
        this.props = props;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public Page<Task> list(RequestContext ctx, TaskFilter f, Page.PageRequest page) {
        TaskQuery q = tasks.createTaskQuery();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        if (!seesAll(ctx)) {
            // Seats are modelled as candidate groups; Operaton forbids candidateGroup + candidateGroupIn together.
            List<String> candidateGroups = new ArrayList<>(ctx.groups());
            if (ctx.seatId() != null && !candidateGroups.contains(ctx.seatId())) {
                candidateGroups.add(ctx.seatId());
            }
            q.or().taskAssignee(ctx.principalId()).taskCandidateUser(ctx.principalId());
            if (!candidateGroups.isEmpty()) {
                q.taskCandidateGroupIn(candidateGroups);
            }
            q.endOr();
        }
        if (f != null) {
            if (f.assignee() != null) {
                q.taskAssignee(f.assignee());
            }
            if (Boolean.TRUE.equals(f.mine())) {
                q.taskAssignee(ctx.principalId());
            }
            if (Boolean.TRUE.equals(f.unassigned())) {
                q.taskUnassigned();
            }
            if (f.seatId() != null) {
                q.taskCandidateGroup(f.seatId());
            } else if (f.candidateGroup() != null) {
                q.taskCandidateGroup(f.candidateGroup());
            }
            if (f.processInstanceId() != null) {
                q.processInstanceId(f.processInstanceId());
            }
            if (f.caseInstanceId() != null) {
                q.caseInstanceId(f.caseInstanceId());
            }
            if (f.businessKey() != null) {
                q.processInstanceBusinessKey(f.businessKey());
            }
            if (f.processDefinitionKey() != null) {
                q.processDefinitionKey(f.processDefinitionKey());
            }
            if (f.taskDefinitionKey() != null) {
                q.taskDefinitionKey(f.taskDefinitionKey());
            }
            if ("SUSPENDED".equalsIgnoreCase(f.state())) {
                q.suspended();
            } else if ("ACTIVE".equalsIgnoreCase(f.state())) {
                q.active();
            }
            sort(q, f.sortBy(), f.sortOrder());
        } else {
            q.orderByTaskCreateTime().desc();
        }
        q.initializeFormKeys();
        Page<org.operaton.bpm.engine.task.Task> raw = OperatonSupport.page(q, page, props.getCountCap(), t -> t);
        Map<String, String> businessKeys = businessKeys(raw.items());
        return raw.map(t -> map(t, businessKeys.get(t.getProcessInstanceId()), null));
    }

    private static void sort(TaskQuery q, String by, String order) {
        String b = by == null ? "created" : by;
        switch (b) {
            case "due" -> q.orderByDueDate();
            case "priority" -> q.orderByTaskPriority();
            case "name" -> q.orderByTaskName();
            default -> q.orderByTaskCreateTime();
        }
        if ("asc".equalsIgnoreCase(order)) {
            q.asc();
        } else {
            q.desc();
        }
    }

    @Override
    public TaskDetail get(RequestContext ctx, String taskId) {
        org.operaton.bpm.engine.task.Task t = requireVisible(ctx, taskId);
        Map<String, Object> vars = OperatonSupport.call(() -> tasks.getVariables(taskId));
        List<String> users = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        for (IdentityLink l : OperatonSupport.call(() -> tasks.getIdentityLinksForTask(taskId))) {
            if (IdentityLinkType.CANDIDATE.equals(l.getType())) {
                if (l.getUserId() != null) {
                    users.add(l.getUserId());
                }
                if (l.getGroupId() != null) {
                    groups.add(l.getGroupId());
                }
            }
        }
        String bk = businessKeys(List.of(t)).get(t.getProcessInstanceId());
        List<String> actions = new ArrayList<>();
        boolean mine = ctx.principalId().equals(t.getAssignee());
        if (t.getAssignee() == null) {
            actions.add("claim");
        }
        if (mine) {
            actions.addAll(List.of("unclaim", "complete", "return", "delegate"));
        } else if (t.getAssignee() == null) {
            actions.addAll(List.of("complete", "return"));
        }
        actions.add("comment");
        Object formVersion = vars.get(FORM_VERSION);
        List<WorkspacePort.CandidateSeat> candidates = groups.stream()
                .map(g -> new WorkspacePort.CandidateSeat(g, g, null, null, false, false, g.equals(ctx.seatId())))
                .toList();
        String delegatedFrom = t.getDelegationState() == org.operaton.bpm.engine.task.DelegationState.PENDING
                ? t.getOwner() : null;
        return new TaskDetail(map(t, bk, vars), TypedVariables.toJson(vars), PayloadHash.hash(vars), caseVersion(vars),
                formVersion == null ? null : String.valueOf(formVersion), PayloadHash.materialFields(vars), actions,
                users, groups, returnTargets(t, vars), sodCheck(ctx, t, vars), candidates,
                codedReasons(vars.get(REJECT_REASONS)), codedReasons(vars.get(RETURN_REASONS)),
                changedSinceLastSeen(ctx, t), ruleEvidence(ctx, bk), delegatedFrom);
    }

    // ------------------------------------------------------------------ TaskDetail extras (UI S08/S09/S10)

    /**
     * Return targets: the process variable {@code sangamReturnTargets} (list of {level, activityId, seatId, seatName})
     * when the model supplies one; otherwise the earlier user-task steps of this instance, most recent first
     * (level 1 = the step just before this one), with the seat they were assigned to.
     */
    List<ReturnTarget> returnTargets(org.operaton.bpm.engine.task.Task t, Map<String, Object> vars) {
        Object declared = vars.get(RETURN_TARGETS);
        List<ReturnTarget> out = new ArrayList<>();
        if (declared instanceof Collection<?> c) {
            int i = 1;
            for (Object o : c) {
                if (o instanceof Map<?, ?> m) {
                    out.add(new ReturnTarget(intOf(m.get("level"), i), str(m.get("activityId")), str(m.get("seatId")),
                            str(m.get("seatName") != null ? m.get("seatName") : m.get("seatId")),
                            str(m.get("holderName"))));
                    i++;
                }
            }
            return out;
        }
        if (t.getProcessInstanceId() == null) {
            return out;
        }
        List<org.operaton.bpm.engine.history.HistoricTaskInstance> done = OperatonSupport.call(() ->
                history.createHistoricTaskInstanceQuery().processInstanceId(t.getProcessInstanceId()).finished()
                        .orderByHistoricActivityInstanceStartTime().desc().listPage(0, 50));
        Set<String> seen = new LinkedHashSet<>();
        seen.add(t.getTaskDefinitionKey());
        int level = 1;
        for (org.operaton.bpm.engine.history.HistoricTaskInstance h : done) {
            if (h.getTaskDefinitionKey() == null || !seen.add(h.getTaskDefinitionKey())) {
                continue;
            }
            String seat = historicSeat(h.getId());
            out.add(new ReturnTarget(level++, h.getTaskDefinitionKey(), seat, seat != null ? seat : h.getName(),
                    h.getAssignee()));
        }
        return out;
    }

    private String historicSeat(String historicTaskId) {
        var v = OperatonSupport.call(() -> history.createHistoricVariableInstanceQuery().taskIdIn(historicTaskId)
                .variableName(SEAT).listPage(0, 1));
        if (!v.isEmpty() && v.get(0).getValue() != null) {
            return String.valueOf(v.get(0).getValue());
        }
        for (var l : OperatonSupport.call(() -> history.createHistoricIdentityLinkLogQuery().taskId(historicTaskId)
                .type(IdentityLinkType.CANDIDATE).list())) {
            if (l.getGroupId() != null) {
                return l.getGroupId();
            }
        }
        return null;
    }

    /** The same rules as the SoD check on completion, evaluated in advance so the UI can explain them (K5). */
    SodCheck sodCheck(RequestContext ctx, org.operaton.bpm.engine.task.Task t, Map<String, Object> vars) {
        List<String> reasons = new ArrayList<>();
        if (listOf(vars.get(SOD_EXCLUDE)).contains(ctx.principalId())) {
            reasons.add("SAME_PERSON_EARLIER_IN_CHAIN");
        }
        Object mode = OperatonSupport.call(() -> tasks.getVariableLocal(t.getId(), SOD_MODE));
        Object initiator = vars.get(INITIATOR);
        if ("strict".equals(mode) && ctx.principalId().equals(initiator)) {
            reasons.add("SAME_PERSON_AS_INITIATOR");
        }
        Object initiatorSeat = vars.get(INITIATOR_SEAT);
        if ("strict".equals(mode) && ctx.seatId() != null && ctx.seatId().equals(initiatorSeat)) {
            reasons.add("SAME_SEAT_AS_INITIATOR");
        }
        return new SodCheck(reasons.isEmpty(), reasons, initiator == null ? null : String.valueOf(initiator),
                initiatorSeat == null ? null : String.valueOf(initiatorSeat), str(vars.get(SOD_EXCEPTION)));
    }

    private static List<CodedReason> codedReasons(Object v) {
        List<CodedReason> out = new ArrayList<>();
        if (v instanceof Collection<?> c) {
            for (Object o : c) {
                if (o instanceof Map<?, ?> m && m.get("code") != null) {
                    out.add(new CodedReason(str(m.get("code")), str(m.get("label") != null ? m.get("label") : m.get("code")),
                            str(m.get("labelHi"))));
                } else if (o != null) {
                    out.add(new CodedReason(String.valueOf(o), String.valueOf(o), null));
                }
            }
        }
        return out;
    }

    /**
     * Fields changed since the caller last acted on this case: the material changes recorded by every
     * CASE_RESUBMITTED / CASE_RETURNED event after the caller's last TASK_COMPLETED on this chain (or all of them when
     * the caller has not acted yet). From/to values come from the {@code old_values}/{@code new_values} the resubmit
     * records for material fields.
     */
    List<WorkspacePort.FieldChange> changedSinceLastSeen(RequestContext ctx, org.operaton.bpm.engine.task.Task t) {
        if (t.getProcessInstanceId() == null) {
            return List.of();
        }
        List<AuditPort.AuditEntry> events = chainEvents(ctx, t.getProcessInstanceId());
        int start = 0;
        for (int i = 0; i < events.size(); i++) {
            AuditPort.AuditEntry e = events.get(i);
            if ("TASK_COMPLETED".equals(e.type()) && ctx.principalId().equals(e.actor())) {
                start = i + 1;
            }
        }
        Map<String, WorkspacePort.FieldChange> changes = new LinkedHashMap<>();
        for (AuditPort.AuditEntry e : events.subList(start, events.size())) {
            if (!"CASE_RESUBMITTED".equals(e.type()) && !"CASE_RETURNED".equals(e.type())) {
                continue;
            }
            fieldChanges(e, changes);
        }
        return new ArrayList<>(changes.values());
    }

    /** Folds one CASE_RESUBMITTED / CASE_RETURNED event into the field changes (keeps the first "from", last "to"). */
    static void fieldChanges(AuditPort.AuditEntry e, Map<String, WorkspacePort.FieldChange> into) {
        Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
        Map<?, ?> oldValues = p.get("old_values") instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> newValues = p.get("new_values") instanceof Map<?, ?> m ? m : Map.of();
        for (String f : listOf(p.get("changed_fields"))) {
            WorkspacePort.FieldChange prev = into.get(f);
            into.put(f, new WorkspacePort.FieldChange(f, f, prev != null ? prev.from() : oldValues.get(f),
                    newValues.get(f), e.actor(), e.occurredAt()));
        }
    }

    /** Routing / authority decisions recorded on the business-key chain (X.2.3.2 decision_ref). */
    List<RuleEvidence> ruleEvidence(RequestContext ctx, String businessKey) {
        if (businessKey == null) {
            return List.of();
        }
        List<RuleEvidence> out = new ArrayList<>();
        for (AuditPort.AuditEntry e : chainEvents(ctx, businessKey)) {
            if (!"ROUTING_DECISION".equals(e.type()) && !"AUTHORITY_DECISION".equals(e.type())) {
                continue;
            }
            Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
            Object key = p.get("decision_key") != null ? p.get("decision_key") : p.get("decisionKey");
            Object version = p.get("decision_version") != null ? p.get("decision_version") : p.get("version");
            Object result = p.get("output_digest") != null ? p.get("output_digest") : p.get("outputDigest");
            out.add(new RuleEvidence(key == null ? e.type() : String.valueOf(key),
                    version == null ? null : String.valueOf(version), result == null ? null : String.valueOf(result)));
        }
        return out;
    }

    /** All class-B events of one chain, oldest first (bounded at 10 pages). An unreadable audit yields no extras. */
    List<AuditPort.AuditEntry> chainEvents(RequestContext ctx, String chainId) {
        List<AuditPort.AuditEntry> out = new ArrayList<>();
        try {
            for (int pageNo = 0; pageNo < 10; pageNo++) {
                Page<AuditPort.AuditEntry> p = audit.search(ctx, new AuditPort.AuditFilter(chainId, null, null, null,
                        null), new Page.PageRequest(pageNo, Page.PageRequest.MAX_SIZE));
                out.addAll(p.items());
                if (p.items().size() < Page.PageRequest.MAX_SIZE) {
                    break;
                }
            }
        } catch (ApiProblem e) {
            return List.of();
        }
        out.sort(java.util.Comparator.comparing(AuditPort.AuditEntry::occurredAt,
                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
        return out;
    }

    static List<String> listOf(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof Collection<?> c) {
            c.forEach(x -> out.add(String.valueOf(x).trim()));
        } else if (v instanceof String s && !s.isBlank()) {
            for (String x : s.split(",")) {
                if (!x.isBlank()) {
                    out.add(x.trim());
                }
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int intOf(Object o, int dflt) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? dflt : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    @Override
    public Map<String, Object> variables(RequestContext ctx, String taskId) {
        requireVisible(ctx, taskId);
        return TypedVariables.toJson(OperatonSupport.call(() -> tasks.getVariables(taskId)));
    }

    // ------------------------------------------------------------------ commands

    @Override
    public void claim(RequestContext ctx, String taskId) {
        tx.runInTx(() -> {
            org.operaton.bpm.engine.task.Task t = requireVisible(ctx, taskId);
            if (t.getAssignee() != null && !t.getAssignee().equals(ctx.principalId())) {
                throw ApiProblem.conflict("TASK_ALREADY_CLAIMED", "This task was already taken")
                        .with("claimedBy", t.getAssignee());
            }
            if (t.getAssignee() == null) {
                OperatonSupport.run(() -> tasks.claim(taskId, ctx.principalId()));
            }
            audit.record(ctx, root(t), "TASK_CLAIMED", taskPayload(ctx, t, Map.of()));
        });
    }

    @Override
    public void unclaim(RequestContext ctx, String taskId) {
        tx.runInTx(() -> {
            org.operaton.bpm.engine.task.Task t = requireVisible(ctx, taskId);
            if (t.getAssignee() == null) {
                return;
            }
            if (!t.getAssignee().equals(ctx.principalId()) && !seesAll(ctx)) {
                throw ApiProblem.notPermitted("task.unclaim");
            }
            OperatonSupport.run(() -> tasks.setAssignee(taskId, null));
            audit.record(ctx, root(t), "TASK_UNCLAIMED", taskPayload(ctx, t, Map.of("previousAssignee", t.getAssignee())));
        });
    }

    /**
     * Pack contract (sangam-packs README §4, K4/K7): task-local {@code actingPerson} / {@code actingSeat} (the acted-for
     * seat when acting on additional charge, else the selected seat) and {@code presentedPayloadHash} (the hash the
     * officer saw), set in the completing transaction so the packs' SoD, power-limit and approval-binding listeners
     * read them.
     */
    private void setActingLocals(RequestContext ctx, String taskId, String presentedPayloadHash) {
        Map<String, Object> locals = new HashMap<>();
        locals.put("actingPerson", ctx.principalId());
        String seat = ctx.actingForSeatId() != null ? ctx.actingForSeatId() : ctx.seatId();
        if (seat != null) {
            locals.put("actingSeat", seat);
        }
        if (presentedPayloadHash != null) {
            locals.put("presentedPayloadHash", presentedPayloadHash);
        }
        OperatonSupport.run(() -> tasks.setVariablesLocal(taskId, locals));
    }

    @Override
    public CompleteTaskResult complete(RequestContext ctx, String taskId, CompleteTaskRequest r) {
        CompleteTaskRequest req = r == null
                ? new CompleteTaskRequest(null, null, null, null, null, null, null, null, null) : r;
        return tx.inTx(() -> {
            org.operaton.bpm.engine.task.Task t = requireVisible(ctx, taskId);
            Map<String, Object> vars = OperatonSupport.call(() -> tasks.getVariables(taskId));
            ensureMayAct(ctx, t);
            checkSod(ctx, t, vars);
            checkVersions(vars, req.caseVersion(), req.payloadHash(), req.formVersion());
            if (t.getAssignee() == null) {
                OperatonSupport.run(() -> tasks.claim(taskId, ctx.principalId()));
            }
            Map<String, Object> submit = TypedVariables.fromJson(req.variables());
            if (req.decision() != null) {
                submit.put(DECISION, req.decision());
            }
            if (req.remarks() != null) {
                submit.put(REMARKS, req.remarks());
            }
            if (req.reasonCode() != null) {
                submit.put(REASON_CODE, req.reasonCode());
            }
            if (req.nextSeatId() != null) {
                // The model routes the next step to this seat (S07: candidate chosen from the eligible list).
                submit.put(NEXT_SEAT, req.nextSeatId());
                if (req.nextSeatReason() != null) {
                    submit.put(NEXT_SEAT_REASON, req.nextSeatReason());
                }
            }
            submit.put(ACTORS, appendActor(vars.get(ACTORS), ctx.principalId()));
            Map<String, Object> after = new HashMap<>(vars);
            after.putAll(submit);
            String hashAfter = PayloadHash.hash(after);
            String pi = t.getProcessInstanceId();
            setActingLocals(ctx, taskId, req.payloadHash());
            OperatonSupport.run(() -> tasks.complete(taskId, submit));
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("decision", req.decision());
            extra.put("material_payload_hash", PayloadHash.hash(vars));
            extra.put("payload_hash_after", hashAfter);
            extra.put("form_version", req.formVersion());
            extra.put("remarks", req.remarks());
            extra.put("reason_code", req.reasonCode());
            extra.put("next_seat_id", req.nextSeatId());
            String chain = root(t);
            audit.record(ctx, chain, "TASK_COMPLETED", taskPayload(ctx, t, extra));
            if ("APPROVED".equalsIgnoreCase(req.decision())) {
                audit.record(ctx, chain, "APPROVAL_RECORDED", taskPayload(ctx, t, extra));
            }
            if (ctx.actingForSeatId() != null) {
                audit.record(ctx, chain, "ACTED_FOR", taskPayload(ctx, t, Map.of("acted_for_seat_id",
                        ctx.actingForSeatId())));
            }
            return new CompleteTaskResult(taskId, pi, req.decision(), PayloadHash.hash(vars), ended(pi), nextTasks(pi));
        });
    }

    @Override
    public CompleteTaskResult returnTask(RequestContext ctx, String taskId, ReturnTaskRequest r) {
        if (r == null || r.reason() == null || r.reason().isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "A reason is required to return a case");
        }
        return tx.inTx(() -> {
            org.operaton.bpm.engine.task.Task t = requireVisible(ctx, taskId);
            Map<String, Object> vars = OperatonSupport.call(() -> tasks.getVariables(taskId));
            ensureMayAct(ctx, t);
            checkSod(ctx, t, vars);
            checkVersions(vars, r.caseVersion(), r.payloadHash(), null);
            String pi = t.getProcessInstanceId();
            Map<String, Object> submit = TypedVariables.fromJson(r.variables());
            submit.put(DECISION, "RETURNED");
            submit.put(RETURN_REASON, r.reason());
            if (r.reasonCode() != null) {
                submit.put(REASON_CODE, r.reasonCode());
            }
            submit.put(ACTORS, appendActor(vars.get(ACTORS), ctx.principalId()));
            String targetActivity = r.targetActivityId();
            if ((targetActivity == null || targetActivity.isBlank())
                    && (r.targetSeatId() != null || r.targetLevel() != null)) {
                ReturnTarget target = returnTargets(t, vars).stream()
                        .filter(x -> r.targetLevel() == null || x.level() == r.targetLevel())
                        .filter(x -> r.targetSeatId() == null || r.targetSeatId().equals(x.seatId()))
                        .findFirst().orElse(null);
                if (target != null && target.activityId() != null) {
                    targetActivity = target.activityId();
                } else {
                    // No modelled target: let the model route it with the requested level / seat.
                    if (r.targetSeatId() != null) {
                        submit.put(RETURN_TARGET_SEAT, r.targetSeatId());
                    }
                    if (r.targetLevel() != null) {
                        submit.put(RETURN_TARGET_LEVEL, r.targetLevel());
                    }
                }
            }
            final String resolvedTarget = targetActivity;
            if (resolvedTarget != null && !resolvedTarget.isBlank()) {
                if (pi == null) {
                    throw ApiProblem.unprocessable("RETURN_NOT_SUPPORTED", "Only process tasks can be returned to an activity");
                }
                ActivityInstance tree = OperatonSupport.call(() -> runtime.getActivityInstance(pi));
                String activityInstanceId = findActivityInstance(tree, t.getTaskDefinitionKey(), t.getExecutionId());
                if (activityInstanceId == null) {
                    throw ApiProblem.conflict("FORM_VERSION_STALE", "The task is no longer active");
                }
                OperatonSupport.run(() -> runtime.createProcessInstanceModification(pi)
                        .cancelActivityInstance(activityInstanceId)
                        .startBeforeActivity(resolvedTarget)
                        .setVariables(submit)
                        .setAnnotation("RETURN: " + r.reason())
                        .execute());
            } else {
                setActingLocals(ctx, taskId, r.payloadHash());
                OperatonSupport.run(() -> tasks.complete(taskId, submit));
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("reason", r.reason());
            extra.put("reason_code", r.reasonCode());
            extra.put("target_activity_id", resolvedTarget);
            extra.put("target_seat_id", r.targetSeatId());
            extra.put("target_level", r.targetLevel());
            extra.put("version", caseVersion(vars));
            extra.put("payload_hash", PayloadHash.hash(vars));
            audit.record(ctx, root(t), "CASE_RETURNED", taskPayload(ctx, t, extra));
            return new CompleteTaskResult(taskId, pi, "RETURNED", PayloadHash.hash(vars), ended(pi), nextTasks(pi));
        });
    }

    @Override
    public void delegate(RequestContext ctx, String taskId, DelegateTaskRequest r) {
        if (r == null || (r.toUserId() == null && r.toSeatId() == null)) {
            throw ApiProblem.badRequest("DELEGATE_TARGET_REQUIRED", "toUserId or toSeatId is required");
        }
        tx.runInTx(() -> {
            org.operaton.bpm.engine.task.Task t = requireVisible(ctx, taskId);
            ensureMayAct(ctx, t);
            if (r.toUserId() != null) {
                if (t.getAssignee() == null) {
                    OperatonSupport.run(() -> tasks.claim(taskId, ctx.principalId()));
                }
                OperatonSupport.run(() -> tasks.delegateTask(taskId, r.toUserId()));
            } else {
                for (IdentityLink l : OperatonSupport.call(() -> tasks.getIdentityLinksForTask(taskId))) {
                    if (IdentityLinkType.CANDIDATE.equals(l.getType()) && l.getGroupId() != null) {
                        OperatonSupport.run(() -> tasks.deleteCandidateGroup(taskId, l.getGroupId()));
                    }
                }
                OperatonSupport.run(() -> {
                    tasks.addCandidateGroup(taskId, r.toSeatId());
                    tasks.setAssignee(taskId, null);
                    tasks.setVariableLocal(taskId, SEAT, r.toSeatId());
                });
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("to_user_id", r.toUserId());
            extra.put("to_seat_id", r.toSeatId());
            extra.put("reason", r.reason());
            audit.record(ctx, root(t), "TASK_DELEGATED", taskPayload(ctx, t, extra));
        });
    }

    @Override
    public List<Comment> comments(RequestContext ctx, String taskId) {
        requireVisible(ctx, taskId);
        return OperatonSupport.call(() -> tasks.getTaskComments(taskId)).stream()
                .map(c -> new Comment(c.getId(), c.getTaskId(), c.getProcessInstanceId(), c.getUserId(),
                        OperatonSupport.instant(c.getTime()), c.getFullMessage()))
                .toList();
    }

    @Override
    public Comment addComment(RequestContext ctx, String taskId, AddCommentRequest r) {
        if (r == null || r.message() == null || r.message().isBlank()) {
            throw ApiProblem.badRequest("MESSAGE_REQUIRED", "message is required");
        }
        return tx.inTx(() -> {
            org.operaton.bpm.engine.task.Task t = requireVisible(ctx, taskId);
            org.operaton.bpm.engine.task.Comment c = OperatonSupport.call(() ->
                    tasks.createComment(taskId, t.getProcessInstanceId(), r.message()));
            audit.record(ctx, root(t), "TASK_COMMENTED", taskPayload(ctx, t, Map.of("comment_id", c.getId())));
            return new Comment(c.getId(), c.getTaskId(), c.getProcessInstanceId(),
                    c.getUserId() == null ? ctx.principalId() : c.getUserId(), OperatonSupport.instant(c.getTime()),
                    c.getFullMessage());
        });
    }

    // ------------------------------------------------------------------ checks

    private org.operaton.bpm.engine.task.Task requireVisible(RequestContext ctx, String taskId) {
        org.operaton.bpm.engine.task.Task t = OperatonSupport.call(() ->
                tasks.createTaskQuery().taskId(taskId).initializeFormKeys().singleResult());
        if (t == null) {
            throw ApiProblem.notFound("task", taskId);
        }
        OperatonSupport.checkTenant(ctx, t.getTenantId(), "task", taskId);
        if (!seesAll(ctx) && !involved(ctx, t)) {
            throw ApiProblem.notFound("task", taskId);
        }
        return t;
    }

    private boolean involved(RequestContext ctx, org.operaton.bpm.engine.task.Task t) {
        if (ctx.principalId().equals(t.getAssignee()) || ctx.principalId().equals(t.getOwner())) {
            return true;
        }
        return isCandidate(ctx, t.getId());
    }

    private boolean isCandidate(RequestContext ctx, String taskId) {
        Set<String> ids = new LinkedHashSet<>(ctx.groups());
        if (ctx.seatId() != null) {
            ids.add(ctx.seatId());
        }
        for (IdentityLink l : OperatonSupport.call(() -> tasks.getIdentityLinksForTask(taskId))) {
            if (!IdentityLinkType.CANDIDATE.equals(l.getType())) {
                continue;
            }
            if (ctx.principalId().equals(l.getUserId()) || (l.getGroupId() != null && ids.contains(l.getGroupId()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean seesAll(RequestContext ctx) {
        return ctx.inGroup("sangam-admin") || ctx.inGroup("sangam-operator") || ctx.inGroup("sangam-system");
    }

    /** K3: the caller holds the task, or it is unassigned and the caller is a candidate. */
    private void ensureMayAct(RequestContext ctx, org.operaton.bpm.engine.task.Task t) {
        if (ctx.principalId().equals(t.getAssignee())) {
            return;
        }
        if (t.getAssignee() != null) {
            throw ApiProblem.forbidden("ACTION_NOT_PERMITTED", "The task is held by another officer")
                    .with("claimedBy", t.getAssignee());
        }
        if (!isCandidate(ctx, t.getId())) {
            throw ApiProblem.notPermitted("task.complete");
        }
    }

    /** K5: same person may not act twice where the model forbids it (IX.5 rule 5). */
    private void checkSod(RequestContext ctx, org.operaton.bpm.engine.task.Task t, Map<String, Object> vars) {
        boolean violation = false;
        Object exclude = vars.get(SOD_EXCLUDE);
        if (exclude instanceof Collection<?> c && c.stream().map(String::valueOf).anyMatch(ctx.principalId()::equals)) {
            violation = true;
        } else if (exclude instanceof String s && List.of(s.split(",")).stream().map(String::trim)
                .anyMatch(ctx.principalId()::equals)) {
            violation = true;
        }
        Object mode = OperatonSupport.call(() -> tasks.getVariableLocal(t.getId(), SOD_MODE));
        if ("strict".equals(mode) && ctx.principalId().equals(vars.get(INITIATOR))) {
            violation = true;
        }
        if (violation) {
            tx.inNewTx(() -> audit.record(ctx, root(t), "SOD_VIOLATION_BLOCKED", taskPayload(ctx, t, Map.of())));
            throw ApiProblem.unprocessable("SOD_VIOLATION",
                    "You acted earlier on this case and cannot act on this step (segregation of duties)");
        }
    }

    /** K9 + K4: case version, form version and material payload hash must match what the caller saw. */
    private static void checkVersions(Map<String, Object> vars, Long caseVersion, String payloadHash, String formVersion) {
        long current = caseVersion(vars);
        if (caseVersion != null && caseVersion != current) {
            throw ApiProblem.conflict("FORM_VERSION_STALE", "This case changed since you opened it (version "
                    + caseVersion + " to " + current + ")").with("currentVersion", current);
        }
        Object fv = vars.get(FORM_VERSION);
        if (formVersion != null && fv != null && !formVersion.equals(String.valueOf(fv))) {
            throw ApiProblem.conflict("FORM_VERSION_STALE", "The form version of this task is " + fv)
                    .with("currentFormVersion", String.valueOf(fv));
        }
        if (payloadHash != null) {
            String now = PayloadHash.hash(vars);
            if (!now.equalsIgnoreCase(payloadHash)) {
                throw ApiProblem.conflict("APPROVAL_INVALIDATED",
                        "The material payload differs from the one you reviewed; reload before deciding")
                        .with("currentPayloadHash", now);
            }
        }
    }

    // ------------------------------------------------------------------ mapping

    static long caseVersion(Map<String, ?> vars) {
        Object v = vars == null ? null : vars.get(CASE_VERSION);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return 1;
    }

    private static List<String> appendActor(Object existing, String actor) {
        List<String> list = new ArrayList<>();
        if (existing instanceof Collection<?> c) {
            c.forEach(x -> list.add(String.valueOf(x)));
        }
        if (!list.contains(actor)) {
            list.add(actor);
        }
        return list;
    }

    private Map<String, String> businessKeys(List<org.operaton.bpm.engine.task.Task> list) {
        Set<String> ids = list.stream().map(org.operaton.bpm.engine.task.Task::getProcessInstanceId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (ProcessInstance p : OperatonSupport.call(() ->
                runtime.createProcessInstanceQuery().processInstanceIds(ids).list())) {
            if (p.getBusinessKey() != null) {
                out.put(p.getId(), p.getBusinessKey());
            }
        }
        return out;
    }

    private Task map(org.operaton.bpm.engine.task.Task t, String businessKey, Map<String, Object> vars) {
        String seat = null;
        if (vars != null && vars.get(SEAT) != null) {
            seat = String.valueOf(vars.get(SEAT));
        }
        String defKey = t.getProcessDefinitionId() == null ? null : t.getProcessDefinitionId().split(":")[0];
        String state = t.getTaskState() != null ? t.getTaskState()
                : (t.isSuspended() ? "SUSPENDED" : (t.getAssignee() == null ? "CREATED" : "ASSIGNED"));
        return new Task(t.getId(), t.getName(), t.getDescription(), t.getTaskDefinitionKey(), t.getProcessInstanceId(),
                defKey, t.getCaseInstanceId(), businessKey, t.getAssignee(), t.getOwner(), seat, t.getTenantId(),
                OperatonSupport.instant(t.getCreateTime()), OperatonSupport.instant(t.getDueDate()),
                OperatonSupport.instant(t.getFollowUpDate()), OperatonSupport.instant(t.getLastUpdated()), null, state,
                t.getPriority(), safeFormKey(t), t.getDelegationState() == null ? null : t.getDelegationState().name());
    }

    private static String safeFormKey(org.operaton.bpm.engine.task.Task t) {
        try {
            return t.getFormKey();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String root(org.operaton.bpm.engine.task.Task t) {
        if (t.getProcessInstanceId() != null) {
            return t.getProcessInstanceId();
        }
        return t.getCaseInstanceId() != null ? t.getCaseInstanceId() : "task:" + t.getId();
    }

    private static Map<String, Object> taskPayload(RequestContext ctx, org.operaton.bpm.engine.task.Task t,
                                                   Map<String, ?> extra) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("task_id", t.getId());
        p.put("task_definition_key", t.getTaskDefinitionKey());
        p.put("task_name", t.getName());
        p.put("instance_id", t.getProcessInstanceId() != null ? t.getProcessInstanceId() : t.getCaseInstanceId());
        p.put("actor", ctx.actor());
        p.put("seat_id", ctx.seatId());
        p.put("acted_for_seat_id", ctx.actingForSeatId());
        if (extra != null) {
            extra.forEach((k, v) -> {
                if (v != null) {
                    p.put(k, v);
                }
            });
        }
        p.values().removeIf(Objects::isNull);
        return p;
    }

    private static String findActivityInstance(ActivityInstance node, String activityId, String executionId) {
        if (node == null) {
            return null;
        }
        if (activityId.equals(node.getActivityId())
                && (executionId == null || List.of(node.getExecutionIds()).contains(executionId))) {
            return node.getId();
        }
        for (ActivityInstance child : node.getChildActivityInstances()) {
            String found = findActivityInstance(child, activityId, executionId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean ended(String processInstanceId) {
        if (processInstanceId == null) {
            return false;
        }
        return OperatonSupport.call(() ->
                runtime.createProcessInstanceQuery().processInstanceId(processInstanceId).count()) == 0;
    }

    private List<String> nextTasks(String processInstanceId) {
        if (processInstanceId == null) {
            return List.of();
        }
        return OperatonSupport.call(() -> tasks.createTaskQuery().processInstanceId(processInstanceId).list())
                .stream().map(org.operaton.bpm.engine.task.Task::getId).toList();
    }

    /** History is kept for reports that need finished tasks; exposed for subclasses. */
    protected HistoryService history() {
        return history;
    }
}
