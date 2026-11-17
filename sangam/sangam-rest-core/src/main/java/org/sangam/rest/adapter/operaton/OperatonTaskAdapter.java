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
    private final org.operaton.bpm.engine.FormService forms;
    private final TxSupport tx;
    private final AuditPort audit;
    private final RestProperties props;
    private final TaskAuditExtras extras;

    public OperatonTaskAdapter(TaskService tasks, RuntimeService runtime, HistoryService history, TxSupport tx,
                               AuditPort audit, RestProperties props) {
        this(tasks, runtime, history, null, tx, audit, props);
    }

    /** @param forms the engine's form service; without it {@link #form} returns the form key only. */
    public OperatonTaskAdapter(TaskService tasks, RuntimeService runtime, HistoryService history,
                               org.operaton.bpm.engine.FormService forms, TxSupport tx, AuditPort audit,
                               RestProperties props) {
        this.forms = forms;
        this.tasks = tasks;
        this.runtime = runtime;
        this.history = history;
        this.tx = tx;
        this.audit = audit;
        this.props = props;
        this.extras = new TaskAuditExtras(audit);
    }

    // ------------------------------------------------------------------ reads

    /**
     * SG-15: the page, its cap probe, the count, the business keys and the card variables are engine reads; they run in
     * one {@linkplain ReadTransactions read transaction} (one commit instead of one per read).
     */
    @Override
    public Page<Task> list(RequestContext ctx, TaskFilter f, Page.PageRequest page) {
        return reads(() -> listPage(ctx, f, page));
    }

    private Page<Task> listPage(RequestContext ctx, TaskFilter f, Page.PageRequest page) {
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
            // Operaton's or() returns the OR branch: every criterion of the branch and endOr() go on that object (on
            // q, the engine refuses "cannot set endOr() before or()" and every non-supervisor list failed).
            TaskQuery any = q.or().taskAssignee(ctx.principalId()).taskCandidateUser(ctx.principalId());
            if (!candidateGroups.isEmpty()) {
                any.taskCandidateGroupIn(candidateGroups);
            }
            any.endOr();
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
            sort(TaskIndex.filter(TaskPriorityDueFilter.apply(q, f), f), f.sortBy(), f.sortOrder());
        } else {
            q.orderByTaskCreateTime().desc();
        }
        q.initializeFormKeys();
        Page<org.operaton.bpm.engine.task.Task> raw = OperatonSupport.page(q, page, props.getCountCap(), t -> t);
        Map<String, String> businessKeys = businessKeys(raw.items());
        List<String> names = f == null ? List.of() : f.variableNames();
        Map<String, TaskIndex.Values> index = TaskIndex.read(runtime, raw.items()); // M-042, one query per page
        // P45 card variables: as GET /tasks/{id}/variables, for rows the caller already sees (nothing unreadable added)
        return raw.map(t -> {
            Task row = TaskIndex.apply(index, map(t, businessKeys.get(t.getProcessInstanceId()), null));
            return names.isEmpty() ? row : row.withVariables(TypedVariables.toJson(
                    OperatonSupport.call(() -> tasks.getVariables(t.getId(), names))));
        });
    }

    /** sortBy and sortOrder may each be a comma-separated list, applied in order; a missing order means desc. */
    private static void sort(TaskQuery q, String by, String order) {
        String[] keys = (by == null ? "created" : by).split(",");
        String[] orders = (order == null ? "" : order).split(",");
        for (int i = 0; i < keys.length; i++) {
            sortOne(q, keys[i].trim(), i < orders.length ? orders[i].trim() : "");
        }
    }

    private static void sortOne(TaskQuery q, String b, String order) {
        switch (b) {
            case "due" -> q.orderByDueDate();
            case "priority" -> q.orderByTaskPriority();
            case "name" -> q.orderByTaskName();
            case "amount", "reference" -> TaskIndex.orderBy(q, b);
            default -> q.orderByTaskCreateTime();
        }
        if ("asc".equalsIgnoreCase(order)) {
            q.asc();
        } else {
            q.desc();
        }
    }

    /** The task detail a user opens: exactly one {@code AUDIT_VIEWED} for its audit extras (SG-15, owner 2026-11-12). */
    @Override
    public TaskDetail get(RequestContext ctx, String taskId) {
        return detail(ctx, taskId, true);
    }

    /**
     * The task detail. SG-15:
     * <ol>
     *   <li>The engine reads (task, variables, identity links, business key, return targets, SoD mode) run in one
     *       {@linkplain ReadTransactions read transaction}, committed before any audit work. The audit read and its
     *       {@code AUDIT_VIEWED} write never run inside it: the write is a new transaction, and a new transaction
     *       while the read transaction holds its connection would be the SL-43 pool-deadlock pattern.</li>
     *   <li>With {@code auditView}, the audit extras ({@code changedSinceLastSeen}, {@code ruleEvidence}) are read from
     *       the instance and business-key chains in one audited read: one {@code AUDIT_VIEWED} for the request, not
     *       one per chain and page. Without it (an internal lookup by another adapter, which shows no audit) no audit
     *       is read and nothing is written; both extras are empty.</li>
     * </ol>
     */
    public TaskDetail detail(RequestContext ctx, String taskId, boolean auditView) {
        TaskDetail engine = reads(() -> engineDetail(ctx, taskId));
        if (!auditView) {
            return engine;
        }
        String pi = engine.task().processInstanceId();
        String bk = engine.task().businessKey();
        Map<String, List<AuditPort.AuditEntry>> events = extras.read(ctx, taskId, pi, bk);
        List<WorkspacePort.FieldChange> changed = TaskAuditExtras.changedSinceLastSeen(ctx, events, pi);
        List<RuleEvidence> evidence = TaskAuditExtras.ruleEvidence(events, bk);
        return new TaskDetail(engine.task(), engine.variables(), engine.payloadHash(), engine.caseVersion(),
                engine.formVersion(), engine.materialFields(), engine.allowedActions(), engine.candidateUsers(),
                engine.candidateGroups(), engine.returnTargets(), engine.sod(), engine.candidates(),
                engine.rejectReasons(), engine.returnReasons(), changed, evidence, engine.delegatedFrom(),
                engine.dscDocument());
    }

    /** Every engine read of {@link #detail}; the audit extras are left empty. */
    private TaskDetail engineDetail(RequestContext ctx, String taskId) {
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
        return new TaskDetail(map(t, bk, vars), TypedVariables.toJson(vars), PayloadHash.hash(vars), TaskVersions.caseVersion(vars),
                formVersion == null ? null : String.valueOf(formVersion), PayloadHash.materialFields(vars), actions,
                users, groups, returnTargets(t, vars), sodCheck(ctx, t, vars), candidates,
                codedReasons(vars.get(REJECT_REASONS)), codedReasons(vars.get(RETURN_REASONS)),
                List.of(), List.of(), delegatedFrom, DscDocument.of(OperatonSupport.call(() ->
                        tasks.getVariablesLocal(taskId, List.of(DscDocument.SLOT_VARIABLE,
                                DscDocument.DOCUMENT_VARIABLE)))));
    }

    private volatile ReadTransactions readTransactions;

    /**
     * SG-15: binds the read transaction of {@link #list} and {@link #detail}. Without it each engine read commits on
     * its own, as before.
     */
    public void setReadTransactions(ReadTransactions readTransactions) {
        this.readTransactions = readTransactions;
    }

    private <T> T reads(java.util.function.Supplier<T> work) {
        ReadTransactions r = readTransactions;
        return r == null ? work.get() : r.inReadTx(work);
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

    /**
     * The task's form for the task UI (SL-37): the deployed embedded HTML or Operaton form (form-js JSON) read from the
     * task's deployment, or just the key for external forms. Same visibility rule as {@link #get}: tenant first, then
     * assignee, owner or candidate (user, group or seat), or a tenant supervisor (admin/operator); otherwise not found.
     */
    @Override
    public TaskForm form(RequestContext ctx, String taskId) {
        org.operaton.bpm.engine.task.Task t = requireVisible(ctx, taskId);
        String key = safeFormKey(t);
        org.operaton.bpm.engine.form.OperatonFormRef ref = safeFormRef(t);
        String kind = formKind(key, ref);
        String content = null;
        String contentType = null;
        boolean deployed = ref != null || (key != null && key.contains("deployment:"));
        if (forms != null && deployed && ("EMBEDDED".equals(kind) || "FORM".equals(kind))) {
            content = OperatonSupport.call(() -> {
                try (java.io.InputStream in = forms.getDeployedTaskForm(taskId)) {
                    return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            contentType = "EMBEDDED".equals(kind) ? "text/html" : "application/json";
        }
        return new TaskForm(taskId, key, kind, contentType, content);
    }

    /** EMBEDDED (HTML), FORM (Operaton form, form-js JSON), EXTERNAL (link), OTHER (unknown key) or NONE. */
    static String formKind(String key, org.operaton.bpm.engine.form.OperatonFormRef ref) {
        if (ref != null || (key != null && key.startsWith("operaton-forms:"))) {
            return "FORM";
        }
        if (key == null || key.isBlank()) {
            return "NONE";
        }
        if (key.startsWith("embedded:")) {
            return "EMBEDDED";
        }
        if (key.startsWith("app:") || key.startsWith("http:") || key.startsWith("https:") || key.startsWith("/")) {
            return "EXTERNAL";
        }
        return "OTHER";
    }

    private static org.operaton.bpm.engine.form.OperatonFormRef safeFormRef(org.operaton.bpm.engine.task.Task t) {
        try {
            return t.getOperatonFormRef();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public Map<String, Object> variables(RequestContext ctx, String taskId) {        requireVisible(ctx, taskId);
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
        setActingLocals(ctx, taskId, presentedPayloadHash, Map.of());
    }

    private void setActingLocals(RequestContext ctx, String taskId, String presentedPayloadHash,
                                 Map<String, Object> submittedLocals) {
        Map<String, Object> locals = new HashMap<>(submittedLocals);
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

    /**
     * Owner decisions 236 and 237: submitted values that belong to the completing task only. {@code dscDocumentId}
     * names the document signed at this step; it is set task-locally (never on the process scope), so the packs' DSC
     * completion gate reads this step's document and a later step never inherits it.
     */
    static final java.util.Set<String> TASK_LOCAL_SUBMIT = java.util.Set.of("dscDocumentId");

    /** Removes the {@link #TASK_LOCAL_SUBMIT} entries from {@code submit} and returns them. */
    static Map<String, Object> takeTaskLocals(Map<String, Object> submit) {
        Map<String, Object> locals = new LinkedHashMap<>();
        for (String k : TASK_LOCAL_SUBMIT) {
            if (submit.containsKey(k)) {
                locals.put(k, submit.remove(k));
            }
        }
        return locals;
    }

    private volatile org.sangam.rest.adapter.jdbc.DenialAudit denialAudit;

    /**
     * SL-132: binds the denial-audit writer on the leaf pool. Without it a denial commits in a new main-pool
     * transaction ({@link org.sangam.rest.adapter.jdbc.DenialAudit#mainPool}), still on the denial chain.
     */
    public void setDenialAudit(org.sangam.rest.adapter.jdbc.DenialAudit denialAudit) {
        this.denialAudit = denialAudit;
    }

    private org.sangam.rest.adapter.jdbc.DenialAudit denials() {
        org.sangam.rest.adapter.jdbc.DenialAudit d = denialAudit;
        return d != null ? d : org.sangam.rest.adapter.jdbc.DenialAudit.mainPool(tx);
    }

    private volatile TaskFormRequirements formRequirements;

    /** SL-77: binds the form-requirement reader; completion then refuses a task whose required form fields are empty. */
    public void setFormRequirements(TaskFormRequirements formRequirements) {
        this.formRequirements = formRequirements;
    }

    /**
     * SL-77: every required field of the task's Sangam form must have a value in the task's variables after the
     * submission ({@code values} = current variables + submitted ones), else 422 {@code REQUIRED_FIELDS_MISSING} naming
     * the fields. Runs inside the completing transaction, before the engine completes the task.
     */
    private void requireFormFields(org.operaton.bpm.engine.task.Task t, Map<String, Object> values) {
        TaskFormRequirements reqs = formRequirements;
        if (reqs == null) {
            return;
        }
        String key = safeFormKey(t);
        List<String> missing = TaskFormRequirements.missing(
                reqs.requiredForTask(t.getProcessDefinitionId(), t.getCaseDefinitionId(), key), values);
        if (!missing.isEmpty()) {
            throw TaskFormRequirements.problem(key, missing);
        }
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
            TaskVersions.check(vars, req.caseVersion(), req.payloadHash(), req.formVersion());
            if (t.getAssignee() == null) {
                OperatonSupport.run(() -> tasks.claim(taskId, ctx.principalId()));
            }
            Map<String, Object> submit = org.sangam.rest.support.SlotVariableGuard.callerVariables(req.variables());
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
            Map<String, Object> taskLocals = takeTaskLocals(submit);
            Map<String, Object> after = new HashMap<>(vars);
            after.putAll(submit);
            after.putAll(taskLocals);
            requireFormFields(t, after);
            String hashAfter = PayloadHash.hash(after);
            String pi = t.getProcessInstanceId();
            setActingLocals(ctx, taskId, req.payloadHash(), taskLocals);
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
            TaskVersions.check(vars, r.caseVersion(), r.payloadHash(), null);
            String pi = t.getProcessInstanceId();
            Map<String, Object> submit = org.sangam.rest.support.SlotVariableGuard.callerVariables(r.variables());
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
            extra.put("version", TaskVersions.caseVersion(vars));
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
            // SL-132: the refusal rolls the completion back, so the denial commits on its own: on the root's denial
            // chain (no wait for the root chain's ROOT_SEQ row this transaction may hold) through the denial-audit
            // leaf pool (no second main-pool connection while this transaction holds one).
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("processInstanceId", t.getProcessInstanceId());
            denials().record(ctx, root(t), "SOD_VIOLATION_BLOCKED", taskPayload(ctx, t, extra));
            throw ApiProblem.unprocessable("SOD_VIOLATION",
                    "You acted earlier on this case and cannot act on this step (segregation of duties)");
        }
    }

    // ------------------------------------------------------------------ mapping

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
        Map<String, String> out = new HashMap<>();   // never Map.of(): a standalone task is looked up by a null id
        if (ids.isEmpty()) {
            return out;
        }
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
