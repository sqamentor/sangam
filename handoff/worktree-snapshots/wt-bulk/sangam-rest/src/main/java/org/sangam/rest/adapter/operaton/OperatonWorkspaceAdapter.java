// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.operaton.bpm.engine.FormService;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.history.HistoricTaskInstance;
import org.operaton.bpm.engine.repository.CaseDefinition;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.task.IdentityLinkType;
import org.operaton.bpm.engine.task.TaskQuery;
import org.sangam.core.layer.Hashes;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.OpsPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.port.WorkspacePort;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TypedVariables;

/**
 * Default {@link WorkspacePort} on the Operaton task/history API and the {@link AdminPort} seat model.
 * <ul>
 *   <li>Seats are candidate groups (IX.5 rule 1); "my seats" are the caller's assignments effective today.</li>
 *   <li>Counts are capped at {@code sangam.rest.count-cap}: a capped section reports {@code "N+"} (A-16).</li>
 *   <li>Bulk completion (FLOWABLE-INTO-OPERATON D-34) is synchronous, one task per transaction through {@link TaskPort#complete}, and binds
 *       each task to the payload hash the server holds at that moment. Only tasks whose process variable
 *       {@code sangamBulkAllowed} is {@code true} are eligible; the others fail with {@code BULK_NOT_ALLOWED}.</li>
 *   <li>Notifications ({@code REST_DOCUMENT} kind {@value #KIND_NOTIFICATION}, owner = principal, state
 *       UNREAD/READ) and case comments (kind {@value #KIND_CASE_COMMENT}, ref = case instance) are persisted; other
 *       modules add notifications through {@link #addNotification}.</li>
 * </ul>
 */
public class OperatonWorkspaceAdapter implements WorkspacePort {

    static final String BULK_ALLOWED = "sangamBulkAllowed";
    static final String PROPOSED_SEAT = "sangamProposedSeatId";
    static final String FIELD_VISIT = "sangamFieldVisit";
    static final String ADDRESS = "sangamAddress";
    static final String CHECKLIST = "sangamChecklist";
    static final String ESCALATED = "sangamEscalated";
    static final String APPLICANT = "sangamApplicantId";
    private static final int LIST_LIMIT = 500;

    private final TaskPort taskPort;
    private final TaskService tasks;
    private final RuntimeService runtime;
    private final HistoryService history;
    private final RepositoryService repository;
    private final FormService forms;
    private final AdminPort admin;
    private final PermissionPort permissions;
    private final AuditPort audit;
    private final RestProperties props;

    static final String KIND_NOTIFICATION = "notification";
    static final String KIND_CASE_COMMENT = "case-comment";

    private final org.sangam.rest.support.DocumentStore docs;
    private final org.sangam.rest.support.BusinessDates dates;

    public OperatonWorkspaceAdapter(TaskPort taskPort, TaskService tasks, RuntimeService runtime, HistoryService history,
                                    RepositoryService repository, FormService forms, AdminPort admin,
                                    PermissionPort permissions, AuditPort audit, RestProperties props,
                                    org.sangam.rest.support.DocumentStore docs) {
        this(taskPort, tasks, runtime, history, repository, forms, admin, permissions, audit, props, docs,
                org.sangam.rest.support.BusinessDates.utc());
    }

    /** @param dates business dates (assignment validity \"today\") in the tenant's zone */
    public OperatonWorkspaceAdapter(TaskPort taskPort, TaskService tasks, RuntimeService runtime, HistoryService history,
                                    RepositoryService repository, FormService forms, AdminPort admin,
                                    PermissionPort permissions, AuditPort audit, RestProperties props,
                                    org.sangam.rest.support.DocumentStore docs, org.sangam.rest.support.BusinessDates dates) {
        this.dates = java.util.Objects.requireNonNull(dates, "dates");
        this.docs = docs;
        this.taskPort = taskPort;
        this.tasks = tasks;
        this.runtime = runtime;
        this.history = history;
        this.repository = repository;
        this.forms = forms;
        this.admin = admin;
        this.permissions = permissions;
        this.audit = audit;
        this.props = props;
    }

    // ------------------------------------------------------------------ me

    @Override
    public Me me(RequestContext ctx) {
        String tenantName = null;
        try {
            tenantName = admin.tenant(ctx, ctx.tenantId()).name();
        } catch (ApiProblem ignored) {
            // tenant not registered in the admin store (development): name unknown
        }
        Set<String> perms = permissions.permissions(ctx);
        return new Me(ctx.tenantId(), tenantName, ctx.principalId(), ctx.principalId(), ctx.principalId(),
                new ArrayList<>(new TreeSet<>(ctx.groups())), roles(ctx, perms), seats(ctx), ctx.seatId(),
                ctx.actingForSeatId(), ctx.authMethod(), new ArrayList<>(new TreeSet<>(perms)), null);
    }

    private static List<String> roles(RequestContext ctx, Set<String> perms) {
        Set<String> roles = new LinkedHashSet<>();
        Map<String, List<String>> byGroup = Map.ofEntries(
                Map.entry(Permissions.GROUP_USER, List.of("TASK_USER")),
                Map.entry(Permissions.GROUP_OPERATOR, List.of("OPERATOR")),
                Map.entry(Permissions.GROUP_ADMIN, List.of("ADMIN", "PLATFORM_ADMIN")),
                Map.entry(Permissions.GROUP_TENANT_ADMIN, List.of("ADMIN")),
                Map.entry(Permissions.GROUP_MODEL_AUTHOR, List.of("MODELLER")),
                Map.entry(Permissions.GROUP_MODEL_REVIEWER, List.of("REVIEWER")),
                Map.entry(Permissions.GROUP_MODEL_APPROVER, List.of("REVIEWER")),
                Map.entry(Permissions.GROUP_AUDITOR, List.of("AUDITOR")),
                Map.entry(Permissions.GROUP_INTEGRATION, List.of("INTEGRATION")),
                Map.entry(Permissions.GROUP_CITIZEN, List.of("CITIZEN")));
        for (String g : ctx.groups()) {
            roles.addAll(byGroup.getOrDefault(g, List.of()));
        }
        if (perms.contains(Permissions.ANALYTICS_READ) || perms.contains("*")) {
            roles.add("ANALYST");
        }
        return new ArrayList<>(roles);
    }

    private List<SeatHolding> seats(RequestContext ctx) {
        List<SeatHolding> out = new ArrayList<>();
        for (AdminPort.Assignment a : myAssignments(ctx)) {
            String seatName = a.seatId();
            String designation = null;
            String orgUnitName = null;
            try {
                AdminPort.Seat s = admin.seat(ctx, a.seatId());
                seatName = s.name() != null ? s.name() : s.designation();
                designation = s.designation();
                if (s.orgUnitId() != null) {
                    orgUnitName = admin.orgUnit(ctx, s.orgUnitId()).name();
                }
            } catch (ApiProblem ignored) {
                // seat not in the admin store: show the id only
            }
            String actingFor = a.actedForSeatId() == null ? null : holderOf(ctx, a.actedForSeatId());
            out.add(new SeatHolding(a.seatId(), seatName, designation, orgUnitName, a.kind(), actingFor, a.validFrom(),
                    a.validTo(), false));
        }
        return out;
    }

    private List<AdminPort.Assignment> myAssignments(RequestContext ctx) {
        try {
            return admin.assignments(ctx, new AdminPort.AdminFilter(null, null, null, null, ctx.principalId(), null,
                    dates.today(ctx.tenantId()), false), new Page.PageRequest(0, Page.PageRequest.MAX_SIZE)).items();
        } catch (ApiProblem e) {
            return List.of();
        }
    }

    private List<AdminPort.Assignment> seatAssignments(RequestContext ctx, String seatId) {
        try {
            return admin.assignments(ctx, new AdminPort.AdminFilter(null, null, null, seatId, null, null,
                    dates.today(ctx.tenantId()), false), new Page.PageRequest(0, Page.PageRequest.MAX_SIZE)).items();
        } catch (ApiProblem e) {
            return List.of();
        }
    }

    /** The person holding a seat substantively today, or null. */
    private String holderOf(RequestContext ctx, String seatId) {
        return seatAssignments(ctx, seatId).stream().filter(a -> "SUBSTANTIVE".equals(a.kind()))
                .map(AdminPort.Assignment::personId).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ counts and bulk

    @Override
    public Map<String, Object> taskCounts(RequestContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        TaskQuery mySeat = tenant(ctx, tasks.createTaskQuery());
        if (ctx.seatId() != null) {
            mySeat.or().taskAssignee(ctx.principalId()).taskCandidateGroup(ctx.seatId()).endOr();
        } else {
            mySeat.taskAssignee(ctx.principalId());
        }
        out.put("MY_SEAT", capped(mySeat.count()));
        List<String> actingSeats = myAssignments(ctx).stream()
                .filter(a -> "ADDITIONAL_CHARGE".equals(a.kind()) || "LEAVE_SUBSTITUTE".equals(a.kind()))
                .map(AdminPort.Assignment::seatId).filter(Objects::nonNull).distinct().toList();
        out.put("ACTING_FOR", actingSeats.isEmpty() ? 0L
                : capped(tenant(ctx, tasks.createTaskQuery()).taskCandidateGroupIn(actingSeats).count()));
        out.put("POOL", capped(involved(ctx, tenant(ctx, tasks.createTaskQuery())).taskUnassigned().count()));
        out.put("ESCALATED", capped(involved(ctx, tenant(ctx, tasks.createTaskQuery()))
                .taskVariableValueEquals(ESCALATED, true).count()));
        out.put("RETURNED", capped(involved(ctx, tenant(ctx, tasks.createTaskQuery()))
                .processVariableValueEquals(OperatonTaskAdapter.DECISION, "RETURNED").count()));
        out.put("ALL", capped(involved(ctx, tenant(ctx, tasks.createTaskQuery())).count()));
        return out;
    }

    private Object capped(long n) {
        long cap = props.getCountCap();
        return cap > 0 && n > cap ? cap + "+" : (Object) n;
    }

    private static TaskQuery tenant(RequestContext ctx, TaskQuery q) {
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        return q;
    }

    /** The default visibility rule of {@link OperatonTaskAdapter}: assignee, candidate user or candidate group/seat. */
    private static TaskQuery involved(RequestContext ctx, TaskQuery q) {
        if (ctx.inGroup("sangam-admin") || ctx.inGroup("sangam-operator") || ctx.inGroup("sangam-system")) {
            return q;
        }
        List<String> groups = new ArrayList<>(ctx.groups());
        if (ctx.seatId() != null && !groups.contains(ctx.seatId())) {
            groups.add(ctx.seatId());
        }
        q.or().taskAssignee(ctx.principalId()).taskCandidateUser(ctx.principalId());
        if (!groups.isEmpty()) {
            q.taskCandidateGroupIn(groups);
        }
        return q.endOr();
    }

    @Override
    public BulkResult bulkClaim(RequestContext ctx, BulkClaimRequest request) {
        List<String> ok = new ArrayList<>();
        List<BulkFailure> failed = new ArrayList<>();
        for (String id : request.ids()) {
            try {
                taskPort.claim(ctx, id);
                ok.add(id);
            } catch (ApiProblem p) {
                failed.add(new BulkFailure(id, p.code()));
            }
        }
        return new BulkResult(ok, failed);
    }

    @Override
    public OpsPort.Batch bulkComplete(RequestContext ctx, BulkCompleteRequest request) {
        Instant started = Instant.now();
        List<String> ok = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        for (String id : request.ids()) {
            try {
                TaskPort.TaskDetail d = taskPort.get(ctx, id);
                if (!Boolean.TRUE.equals(asBoolean(d.variables().get(BULK_ALLOWED)))) {
                    throw ApiProblem.unprocessable("BULK_NOT_ALLOWED",
                            "This task must be decided individually (sangamBulkAllowed is not set)");
                }
                taskPort.complete(ctx, id, new TaskPort.CompleteTaskRequest(d.payloadHash(), d.caseVersion(),
                        request.action(), request.remarks(), d.formVersion(), Map.of(), null, null, null));
                ok.add(id);
            } catch (ApiProblem p) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", id);
                f.put("reason", p.code());
                failures.add(f);
            }
        }
        String batchId = Hashes.uuidV7().toString();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", request.action());
        params.put("succeeded", ok);
        params.put("failures", failures);
        Map<String, Object> auditPayload = new LinkedHashMap<>(params);
        auditPayload.put("batch_id", batchId);
        audit.record(ctx, "sec:" + ctx.tenantId(), "BULK_COMPLETED", auditPayload);
        String state = failures.isEmpty() ? "COMPLETED" : (ok.isEmpty() ? "FAILED" : "COMPLETED_WITH_FAILURES");
        return new OpsPort.Batch(batchId, "COMPLETE_TASKS", state, request.ids().size(), ok.size(), failures.size(),
                ctx.principalId(), null, request.remarks(), started, Instant.now(), null, params, false);
    }

    private static Boolean asBoolean(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Map<?, ?> m && m.get("value") != null) {
            return Boolean.parseBoolean(String.valueOf(m.get("value")));
        }
        return v == null ? null : Boolean.parseBoolean(String.valueOf(v));
    }

    // ------------------------------------------------------------------ task extras

    @Override
    public List<CandidateSeat> candidates(RequestContext ctx, String taskId, String action) {
        TaskPort.TaskDetail d = taskPort.get(ctx, taskId);
        Object proposed = rawVariable(taskId, PROPOSED_SEAT);
        String proposedSeat = proposed == null ? null : String.valueOf(proposed);
        List<String> currentSeats = d.candidateGroups();
        String orgUnitId = null;
        for (String s : currentSeats) {
            try {
                orgUnitId = admin.seat(ctx, s).orgUnitId();
                break;
            } catch (ApiProblem ignored) {
                // not a registered seat
            }
        }
        List<CandidateSeat> out = new ArrayList<>();
        if (orgUnitId != null) {
            List<AdminPort.Seat> seats = admin.seats(ctx, new AdminPort.AdminFilter(null, null, orgUnitId, null, null,
                    null, null, false), new Page.PageRequest(0, Page.PageRequest.MAX_SIZE)).items();
            boolean anyRoles = seats.stream().anyMatch(s -> s.roles() != null && !s.roles().isEmpty());
            for (AdminPort.Seat s : seats) {
                if (currentSeats.contains(s.id()) || !s.active()) {
                    continue;
                }
                if (action != null && anyRoles && (s.roles() == null
                        || s.roles().stream().noneMatch(r -> r.equalsIgnoreCase(action)))) {
                    continue;
                }
                out.add(candidate(ctx, s.id(), s.name() != null ? s.name() : s.designation(), false,
                        s.id().equals(proposedSeat)));
            }
        }
        if (out.isEmpty()) {
            // No seat registry: offer the task's own candidate groups (outside any known hierarchy).
            for (String g : currentSeats) {
                out.add(candidate(ctx, g, g, orgUnitId == null, g.equals(proposedSeat)));
            }
        }
        if (proposedSeat != null && out.stream().noneMatch(c -> c.seatId().equals(proposedSeat))) {
            out.add(candidate(ctx, proposedSeat, proposedSeat, true, true));
        }
        return out;
    }

    private CandidateSeat candidate(RequestContext ctx, String seatId, String seatName, boolean outOfHierarchy,
                                    boolean proposed) {
        long workload = OperatonSupport.call(() -> tenant(ctx, tasks.createTaskQuery()).taskCandidateGroup(seatId)
                .count());
        List<AdminPort.Assignment> holders = seatAssignments(ctx, seatId);
        String holder = holders.stream().filter(a -> "SUBSTANTIVE".equals(a.kind()))
                .map(AdminPort.Assignment::personId).findFirst().orElse(null);
        boolean onLeave = holders.stream().anyMatch(a -> "LEAVE_SUBSTITUTE".equals(a.kind()));
        return new CandidateSeat(seatId, seatName, holder, (int) Math.min(Integer.MAX_VALUE, workload), onLeave,
                outOfHierarchy, proposed);
    }

    private Object rawVariable(String taskId, String name) {
        try {
            return tasks.getVariable(taskId, name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public List<FieldChange> diff(RequestContext ctx, String taskId, long fromVersion, long toVersion) {
        String pi = processInstanceOf(ctx, taskId);
        Map<String, FieldChange> changes = new LinkedHashMap<>();
        for (AuditPort.AuditEntry e : chainEvents(ctx, pi)) {
            if (!"CASE_RESUBMITTED".equals(e.type())) {
                continue;
            }
            long v = longOf(e.payload() == null ? null : e.payload().get("version"));
            if (v > fromVersion && v <= toVersion) {
                OperatonTaskAdapter.fieldChanges(e, changes);
            }
        }
        return new ArrayList<>(changes.values());
    }

    @Override
    public List<LifecycleEvent> lifecycle(RequestContext ctx, String taskId) {
        HistoricTaskInstance h = OperatonSupport.call(() ->
                history.createHistoricTaskInstanceQuery().taskId(taskId).singleResult());
        if (h == null) {
            throw ApiProblem.notFound("task", taskId);
        }
        OperatonSupport.checkTenant(ctx, h.getTenantId(), "task", taskId);
        List<LifecycleEvent> out = new ArrayList<>();
        out.add(new LifecycleEvent(OperatonSupport.instant(h.getStartTime()), "CREATED", null, null, null));
        for (var l : OperatonSupport.call(() -> history.createHistoricIdentityLinkLogQuery().taskId(taskId)
                .orderByTime().asc().list())) {
            String who = l.getUserId() != null ? l.getUserId() : l.getGroupId();
            String type = l.getType() == null ? "LINK" : l.getType().toUpperCase(java.util.Locale.ROOT);
            String op = l.getOperationType() == null ? "CHANGED" : l.getOperationType().toUpperCase(java.util.Locale.ROOT);
            String event = type + "_" + op;
            out.add(new LifecycleEvent(OperatonSupport.instant(l.getTime()), event,
                    l.getAssignerId() != null ? l.getAssignerId() : who, l.getGroupId(), null));
        }
        String chain = h.getProcessInstanceId() != null ? h.getProcessInstanceId() : h.getCaseInstanceId();
        if (chain != null) {
            for (AuditPort.AuditEntry e : chainEvents(ctx, chain)) {
                Object tid = e.payload() == null ? null : e.payload().get("task_id");
                if (e.type() != null && e.type().startsWith("TASK_") && taskId.equals(tid == null ? null : tid.toString())) {
                    out.add(new LifecycleEvent(e.occurredAt(), e.type(), e.actor(), e.seatId(), e.actedForSeatId()));
                }
            }
        }
        if (h.getEndTime() != null) {
            out.add(new LifecycleEvent(OperatonSupport.instant(h.getEndTime()),
                    h.getDeleteReason() == null || "completed".equals(h.getDeleteReason()) ? "COMPLETED" : "DELETED",
                    h.getAssignee(), null, null));
        }
        out.sort(Comparator.comparing(LifecycleEvent::at, Comparator.nullsFirst(Comparator.naturalOrder())));
        return out;
    }

    private String processInstanceOf(RequestContext ctx, String taskId) {
        HistoricTaskInstance h = OperatonSupport.call(() ->
                history.createHistoricTaskInstanceQuery().taskId(taskId).singleResult());
        if (h == null) {
            throw ApiProblem.notFound("task", taskId);
        }
        OperatonSupport.checkTenant(ctx, h.getTenantId(), "task", taskId);
        if (h.getProcessInstanceId() == null) {
            throw ApiProblem.unprocessable("UNSUPPORTED_SCOPE", "Case versions exist only for process tasks");
        }
        return h.getProcessInstanceId();
    }

    private List<AuditPort.AuditEntry> chainEvents(RequestContext ctx, String chainId) {
        List<AuditPort.AuditEntry> out = new ArrayList<>();
        for (int pageNo = 0; pageNo < 10; pageNo++) {
            Page<AuditPort.AuditEntry> p = audit.search(ctx, new AuditPort.AuditFilter(chainId, null, null, null, null),
                    new Page.PageRequest(pageNo, Page.PageRequest.MAX_SIZE));
            out.addAll(p.items());
            if (p.items().size() < Page.PageRequest.MAX_SIZE) {
                break;
            }
        }
        out.sort(Comparator.comparing(AuditPort.AuditEntry::occurredAt, Comparator.nullsFirst(Comparator.naturalOrder())));
        return out;
    }

    private static long longOf(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? 0 : Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ notifications, delegations, leave

    @Override
    public Page<Notification> notifications(RequestContext ctx, Page.PageRequest page) {
        List<Notification> all = new ArrayList<>(docs.all(ctx.tenantId(), KIND_NOTIFICATION,
                org.sangam.rest.support.DocumentStore.Filter.owner(ctx.principalId())).stream()
                .map(d -> d.as(Notification.class)).toList());
        all.sort(Comparator.comparing(Notification::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return Page.slice(all, page, props.getCountCap());
    }

    @Override
    public void markNotificationRead(RequestContext ctx, String notificationId) {
        var d = docs.get(ctx.tenantId(), KIND_NOTIFICATION, notificationId)
                .filter(x -> ctx.principalId().equals(x.ownerId()))
                .orElseThrow(() -> ApiProblem.notFound("notification", notificationId));
        Notification n = d.as(Notification.class);
        docs.update(ctx.tenantId(), KIND_NOTIFICATION, notificationId, d.revision(), d.key(), "READ", d.ownerId(),
                d.refId(), new Notification(n.id(), n.at(), n.title(), n.body(), true, n.category(), n.link()));
    }

    /** Adds a notification for one principal (used by modules that send in-app notifications, R60). */
    public Notification addNotification(String tenantId, String principalId, Notification n) {
        Notification stored = new Notification(n.id() != null ? n.id() : Hashes.uuidV7().toString(),
                n.at() != null ? n.at() : Instant.now(), n.title(), n.body(), n.read(),
                n.category() != null ? n.category() : "SYSTEM", n.link());
        docs.insert(tenantId, KIND_NOTIFICATION, stored.id(), stored.category(), stored.read() ? "READ" : "UNREAD",
                principalId, null, stored, "system");
        return stored;
    }

    private static String owner(String tenant, String principal) {
        return tenant + "|" + principal;
    }

    @Override
    public List<AdminPort.Assignment> myDelegations(RequestContext ctx) {
        List<AdminPort.Assignment> out = new ArrayList<>();
        try {
            for (AdminPort.Delegation d : admin.delegations(ctx, new AdminPort.AdminFilter(null, null, null, null, null,
                    null, null, false), new Page.PageRequest(0, Page.PageRequest.MAX_SIZE)).items()) {
                boolean mine = ctx.principalId().equals(d.fromPersonId()) || ctx.principalId().equals(d.toPersonId());
                if (!mine || d.revoked()) {
                    continue;
                }
                out.add(new AdminPort.Assignment(d.id(), d.tenantId(), d.toSeatId() != null ? d.toSeatId() : d.seatId(),
                        d.toPersonId(), "DELEGATION_" + d.kind(), d.validFrom(), d.validTo(), d.seatId(), d.orderRef(),
                        d.createdBy(), d.createdAt(), false));
            }
        } catch (ApiProblem ignored) {
            // no delegation store
        }
        for (AdminPort.Assignment a : myAssignments(ctx)) {
            if ("ADDITIONAL_CHARGE".equals(a.kind()) || "LEAVE_SUBSTITUTE".equals(a.kind())) {
                out.add(a);
            }
        }
        return out;
    }

    @Override
    public AdminPort.Assignment requestLeave(RequestContext ctx, LeaveRequest r) {
        if (r.to().isBefore(r.from())) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "to must not be before from");
        }
        boolean holds = myAssignments(ctx).stream().anyMatch(a -> r.seatId().equals(a.seatId()));
        if (!holds && !ctx.inGroup("sangam-admin")) {
            throw ApiProblem.forbidden("SEAT_NOT_HELD", "You do not hold seat " + r.seatId() + " today");
        }
        String substitute = r.substitutePersonId();
        if (substitute == null && r.substituteSeatId() != null) {
            substitute = holderOf(ctx, r.substituteSeatId());
        }
        if (substitute == null) {
            throw ApiProblem.unprocessable("SUBSTITUTE_UNKNOWN", "No person holds the substitute seat today");
        }
        if (substitute.equals(ctx.principalId())) {
            throw ApiProblem.unprocessable("SOD_VIOLATION", "You cannot be your own substitute");
        }
        AdminPort.Assignment created = admin.createAssignment(ctx, new AdminPort.Assignment(null, ctx.tenantId(),
                r.seatId(), substitute, "LEAVE_SUBSTITUTE", r.from(), r.to(), r.seatId(), null, ctx.principalId(),
                null, false));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("assignment_id", created.id());
        p.put("seat_id", r.seatId());
        p.put("substitute_person_id", substitute);
        p.put("substitute_seat_id", r.substituteSeatId());
        p.put("from", r.from().toString());
        p.put("to", r.to().toString());
        p.put("reason", r.reason());
        p.values().removeIf(Objects::isNull);
        audit.record(ctx, "sec:" + ctx.tenantId(), "LEAVE_REQUESTED", p);
        return created;
    }

    // ------------------------------------------------------------------ catalogue and cases

    @Override
    public List<CatalogueEntry> startCatalogue(RequestContext ctx) {
        List<CatalogueEntry> out = new ArrayList<>();
        var pq = repository.createProcessDefinitionQuery().latestVersion().active().startableInTasklist();
        var cq = repository.createCaseDefinitionQuery().latestVersion();
        if (ctx.tenantId() != null) {
            pq.tenantIdIn(ctx.tenantId()).includeProcessDefinitionsWithoutTenantId();
            cq.tenantIdIn(ctx.tenantId()).includeCaseDefinitionsWithoutTenantId();
        }
        for (ProcessDefinition d : OperatonSupport.call(() -> pq.listPage(0, LIST_LIMIT))) {
            String formKey;
            try {
                formKey = forms.getStartFormKey(d.getId());
            } catch (RuntimeException e) {
                formKey = null;
            }
            out.add(new CatalogueEntry(d.getKey(), d.getName() != null ? d.getName() : d.getKey(), "PROCESS", formKey,
                    d.getDescription()));
        }
        for (CaseDefinition d : OperatonSupport.call(() -> cq.listPage(0, LIST_LIMIT))) {
            out.add(new CatalogueEntry(d.getKey(), d.getName() != null ? d.getName() : d.getKey(), "CASE", null, null));
        }
        out.sort(Comparator.comparing(CatalogueEntry::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        // Keys are unique per kind: the latest version of each definition, preferring the tenant's own over shared.
        Map<String, CatalogueEntry> unique = new LinkedHashMap<>();
        out.forEach(e -> unique.putIfAbsent(e.kind() + ":" + e.key(), e));
        return new ArrayList<>(unique.values());
    }

    @Override
    public List<CaseComment> caseComments(RequestContext ctx, String caseInstanceId) {
        scopeTenant(ctx, caseInstanceId);
        return docs.all(ctx.tenantId(), KIND_CASE_COMMENT,
                org.sangam.rest.support.DocumentStore.Filter.ref(caseInstanceId)).stream()
                .map(d -> d.as(CaseComment.class)).toList();
    }

    @Override
    public CaseComment addCaseComment(RequestContext ctx, String caseInstanceId, AddCaseCommentRequest r) {
        scopeTenant(ctx, caseInstanceId);
        return docs.tx().inTx(() -> {
            long n = docs.count(ctx.tenantId(), KIND_CASE_COMMENT,
                    org.sangam.rest.support.DocumentStore.Filter.ref(caseInstanceId), Long.MAX_VALUE - 1);
            CaseComment c = new CaseComment(Hashes.uuidV7().toString(), ctx.principalId(), ctx.seatId(),
                    ctx.actingForSeatId(), Instant.now(), r.text(), (int) n + 1);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("comment_id", c.id());
            p.put("text_digest", Hashes.hex(Hashes.sha256(r.text())));
            audit.record(ctx, caseInstanceId, "CASE_COMMENT_ADDED", p);
            docs.insert(ctx.tenantId(), KIND_CASE_COMMENT, c.id(), null, null, ctx.principalId(), caseInstanceId, c,
                    ctx.principalId());
            return c;
        });
    }

    /** Tenant check for a case (or process) instance id, running or finished. */
    private void scopeTenant(RequestContext ctx, String instanceId) {
        var hc = OperatonSupport.call(() -> history.createHistoricCaseInstanceQuery().caseInstanceId(instanceId)
                .singleResult());
        if (hc != null) {
            OperatonSupport.checkTenant(ctx, hc.getTenantId(), "case instance", instanceId);
            return;
        }
        var hp = OperatonSupport.call(() -> history.createHistoricProcessInstanceQuery().processInstanceId(instanceId)
                .singleResult());
        if (hp == null) {
            throw ApiProblem.notFound("case instance", instanceId);
        }
        OperatonSupport.checkTenant(ctx, hp.getTenantId(), "case instance", instanceId);
    }

    @Override
    public List<Participant> participants(RequestContext ctx, String caseInstanceId) {
        scopeTenant(ctx, caseInstanceId);
        Map<String, Participant> out = new LinkedHashMap<>();
        var hc = OperatonSupport.call(() -> history.createHistoricCaseInstanceQuery().caseInstanceId(caseInstanceId)
                .singleResult());
        boolean isCase = hc != null;
        String applicant = applicantOf(caseInstanceId, isCase);
        String initiator = isCase ? hc.getCreateUserId() : OperatonSupport.call(() -> history
                .createHistoricProcessInstanceQuery().processInstanceId(caseInstanceId).singleResult()).getStartUserId();
        if (initiator != null) {
            out.put(initiator, new Participant(initiator, initiator, "INITIATOR", initiator.equals(applicant)));
        }
        if (applicant != null) {
            out.putIfAbsent(applicant, new Participant(applicant, applicant, "APPLICANT", true));
        }
        var tq = history.createHistoricTaskInstanceQuery();
        if (isCase) {
            tq.caseInstanceId(caseInstanceId);
        } else {
            tq.processInstanceId(caseInstanceId);
        }
        for (HistoricTaskInstance t : OperatonSupport.call(() -> tq.listPage(0, LIST_LIMIT))) {
            if (t.getAssignee() != null) {
                out.putIfAbsent(t.getAssignee(), new Participant(t.getAssignee(), t.getAssignee(), "ASSIGNEE",
                        t.getAssignee().equals(applicant)));
            }
            if (t.getOwner() != null) {
                out.putIfAbsent(t.getOwner(), new Participant(t.getOwner(), t.getOwner(), "OWNER", false));
            }
            for (var l : OperatonSupport.call(() -> history.createHistoricIdentityLinkLogQuery().taskId(t.getId())
                    .type(IdentityLinkType.CANDIDATE).list())) {
                if (l.getGroupId() != null) {
                    out.putIfAbsent("group:" + l.getGroupId(), new Participant(l.getGroupId(), l.getGroupId(),
                            "CANDIDATE_SEAT", false));
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    private String applicantOf(String instanceId, boolean isCase) {
        var q = history.createHistoricVariableInstanceQuery().variableName(APPLICANT);
        if (isCase) {
            q.caseInstanceId(instanceId);
        } else {
            q.processInstanceId(instanceId);
        }
        var list = OperatonSupport.call(() -> q.listPage(0, 1));
        return list.isEmpty() || list.get(0).getValue() == null ? null : String.valueOf(list.get(0).getValue());
    }

    // ------------------------------------------------------------------ field app

    @Override
    public List<FieldVisit> fieldVisits(RequestContext ctx) {
        TaskQuery q = involved(ctx, tenant(ctx, tasks.createTaskQuery())).active().orderByDueDate().asc();
        List<FieldVisit> out = new ArrayList<>();
        Map<String, String> businessKeys = new HashMap<>();
        for (org.operaton.bpm.engine.task.Task t : OperatonSupport.call(() -> q.listPage(0, LIST_LIMIT))) {
            Map<String, Object> vars = OperatonSupport.call(() -> tasks.getVariables(t.getId(),
                    List.of(FIELD_VISIT, ADDRESS, CHECKLIST)));
            if (!Boolean.TRUE.equals(asBoolean(vars.get(FIELD_VISIT)))) {
                continue;
            }
            String bk = t.getProcessInstanceId() == null ? null : businessKeys.computeIfAbsent(t.getProcessInstanceId(),
                    pi -> {
                        var p = runtime.createProcessInstanceQuery().processInstanceId(pi).singleResult();
                        return p == null ? null : p.getBusinessKey();
                    });
            Date when = t.getFollowUpDate() != null ? t.getFollowUpDate() : t.getDueDate();
            out.add(new FieldVisit(t.getId(), t.getId(), bk, vars.get(ADDRESS) == null ? null
                    : String.valueOf(vars.get(ADDRESS)), OperatonSupport.instant(when), checklist(vars.get(CHECKLIST))));
        }
        return out;
    }

    private static List<ChecklistItem> checklist(Object v) {
        List<ChecklistItem> out = new ArrayList<>();
        if (v instanceof Collection<?> c) {
            int i = 1;
            for (Object o : c) {
                if (o instanceof Map<?, ?> m) {
                    out.add(new ChecklistItem(m.get("id") == null ? "item" + i : String.valueOf(m.get("id")),
                            m.get("label") == null ? String.valueOf(m.get("id")) : String.valueOf(m.get("label"))));
                } else if (o != null) {
                    out.add(new ChecklistItem("item" + i, String.valueOf(o)));
                }
                i++;
            }
        }
        return out;
    }

    @Override
    public void recordVisit(RequestContext ctx, String visitId, VisitRecord r) {
        TaskPort.TaskDetail d = taskPort.get(ctx, visitId);
        if (!Boolean.TRUE.equals(asBoolean(rawVariable(visitId, FIELD_VISIT)))) {
            throw ApiProblem.unprocessable("NOT_A_FIELD_VISIT", "Task " + visitId + " is not a field visit");
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        // Stored as canonical JSON text: no object serializer (Spin) is assumed on the engine.
        vars.put("fieldAnswers", PayloadHash.canonical(TypedVariables.fromJson(r.answers())));
        vars.put("fieldNotes", r.notes());
        vars.put("fieldRecordedAt", r.recordedAt() == null ? new Date() : Date.from(r.recordedAt()));
        vars.values().removeIf(Objects::isNull);
        TaskPort.CompleteTaskResult res = taskPort.complete(ctx, visitId, new TaskPort.CompleteTaskRequest(
                d.payloadHash(), d.caseVersion(), "INSPECTED", r.notes(), d.formVersion(), vars, null, null, null));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("task_id", visitId);
        p.put("recorded_at", r.recordedAt() == null ? null : r.recordedAt().toString());
        p.put("answers", r.answers().size());
        p.values().removeIf(Objects::isNull);
        audit.record(ctx, res.processInstanceId() != null ? res.processInstanceId() : "task:" + visitId,
                "FIELD_VISIT_RECORDED", p);
    }
}
