// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.history.HistoricTaskInstance;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.sangam.core.layer.Hashes;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.Download;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PortalPort;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RegistryPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * Default citizen portal (C01–C06, PER-8) over the process engine.
 * <ul>
 *   <li>Services are the {@code services} registry entries with {@code portal: true}; {@code processKey} defaults to
 *       the service key.</li>
 *   <li>An application is a process instance whose variable {@code sangamApplicantId} is the caller; its reference is
 *       the business key. Applicants see only their own applications (404 otherwise) and officers only as seat
 *       ids/designations, never by name.</li>
 *   <li>Status and stage come from the variables {@code sangamStatus}, {@code sangamStage}, {@code sangamStageLabel},
 *       {@code sangamStageLabelHi}, {@code sangamSlaDate}, {@code sangamPaymentStatus} and
 *       {@code sangamAppealDeadline} that the pack sets. An unknown payment outcome is shown as UNKNOWN_OUTCOME, never
 *       as failed (X.4.2.1).</li>
 *   <li>Clarifications are user tasks whose definition key contains {@code clarification}; a reply completes the task
 *       with {@code clarificationReply}. Withdrawal correlates the message {@code sangam.withdraw}; an appeal starts
 *       {@code <processKey>-appeal}.</li>
 *   <li>Unsubmitted drafts ({@code REST_DOCUMENT} kind {@value #KIND_DRAFT}) and grievances (kind
 *       {@value #KIND_GRIEVANCE}, key = reference) are persisted per applicant (owner = principal).</li>
 * </ul>
 */
public class OperatonPortalAdapter implements PortalPort {

    static final String APPLICANT = "sangamApplicantId";
    static final String STATUS = "sangamStatus";
    static final String SERVICE_KEY = "sangamServiceKey";
    static final String SERVICE_NAME = "sangamServiceName";
    static final String WITHDRAW_MESSAGE = "sangam.withdraw";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int LIMIT = 1000;

    private final ProcessPort processes;
    private final TaskService tasks;
    private final RuntimeService runtime;
    private final HistoryService history;
    private final RepositoryService repository;
    private final DocumentPort documents;
    private final RegistryPort registry;
    private final AuditPort audit;
    private final RestProperties props;
    private final TxSupport tx;

    static final String KIND_DRAFT = "portal:draft";
    static final String KIND_GRIEVANCE = "portal:grievance";

    private final org.sangam.rest.support.DocumentStore docs;
    private final org.sangam.rest.support.BusinessDates dates;

    public OperatonPortalAdapter(ProcessPort processes, TaskService tasks, RuntimeService runtime, HistoryService history,
                                 RepositoryService repository, DocumentPort documents, RegistryPort registry,
                                 AuditPort audit, RestProperties props, TxSupport tx,
                                 org.sangam.rest.support.DocumentStore docs) {
        this(processes, tasks, runtime, history, repository, documents, registry, audit, props, tx, docs,
                org.sangam.rest.support.BusinessDates.utc());
    }

    /** @param dates the year of a reference number is the tenant's business year */
    public OperatonPortalAdapter(ProcessPort processes, TaskService tasks, RuntimeService runtime, HistoryService history,
                                 RepositoryService repository, DocumentPort documents, RegistryPort registry,
                                 AuditPort audit, RestProperties props, TxSupport tx,
                                 org.sangam.rest.support.DocumentStore docs, org.sangam.rest.support.BusinessDates dates) {
        this.dates = java.util.Objects.requireNonNull(dates, "dates");
        this.docs = docs;
        this.processes = processes;
        this.tasks = tasks;
        this.runtime = runtime;
        this.history = history;
        this.repository = repository;
        this.documents = documents;
        this.registry = registry;
        this.audit = audit;
        this.props = props;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ services

    @Override
    public List<PortalService> services(RequestContext ctx) {
        return serviceEntries(ctx).stream().map(OperatonPortalAdapter::service).toList();
    }

    private List<Map<String, Object>> serviceEntries(RequestContext ctx) {
        try {
            // Citizens hold no admin permission: read the registry as the system principal of the same tenant.
            return registry.list(RequestContext.system(ctx.tenantId(), ctx.principalId()), "services", null,
                    new Page.PageRequest(0, Page.PageRequest.MAX_SIZE)).items().stream()
                    .filter(e -> Boolean.TRUE.equals(e.get("portal")) || "true".equals(String.valueOf(e.get("portal"))))
                    .toList();
        } catch (ApiProblem e) {
            return List.of();
        }
    }

    private static PortalService service(Map<String, Object> e) {
        return new PortalService(str(e.get("key")), str(e.get("name")), str(e.get("nameHi")), str(e.get("description")),
                str(e.get("descriptionHi")), intOrNull(e.get("slaDays")), str(e.get("fee")),
                str(e.get("formKey")), strings(e.get("documentsRequired")));
    }

    private Map<String, Object> requireService(RequestContext ctx, String key) {
        return serviceEntries(ctx).stream().filter(e -> key.equals(str(e.get("key")))).findFirst()
                .orElseThrow(() -> ApiProblem.notFound("portal service", key));
    }

    // ------------------------------------------------------------------ applications

    @Override
    public Application apply(RequestContext ctx, ApplyRequest r) {
        Map<String, Object> svc = requireService(ctx, r.serviceKey());
        String processKey = svc.get("processKey") != null ? str(svc.get("processKey")) : r.serviceKey();
        String reference = reference(r.serviceKey(), dates.today(ctx.tenantId()).getYear());
        String serviceName = str(svc.get("name"));
        if (!r.submit()) {
            String id = "draft-" + Hashes.uuidV7();
            Application draft = new Application(id, reference, r.serviceKey(), serviceName, null, "DRAFT", null, null,
                    null, "DRAFT", List.of(), List.of(), null, null, "NOT_REQUIRED");
            docs.insert(ctx.tenantId(), KIND_DRAFT, id, reference, "DRAFT", ctx.principalId(), r.serviceKey(),
                    draft, ctx.principalId());
            return draft;
        }
        Map<String, Object> vars = new LinkedHashMap<>(r.variables() == null ? Map.of() : r.variables());
        vars.keySet().removeIf(k -> k.startsWith("sangam"));
        vars.put(APPLICANT, ctx.principalId());
        vars.put(STATUS, "SUBMITTED");
        vars.put(SERVICE_KEY, r.serviceKey());
        if (serviceName != null) {
            vars.put(SERVICE_NAME, serviceName);
        }
        Integer slaDays = intOrNull(svc.get("slaDays"));
        if (slaDays != null) {
            vars.put("sangamSlaDate", Date.from(Instant.now().plus(slaDays, ChronoUnit.DAYS)));
        }
        ProcessPort.ProcessInstance pi = processes.start(ctx, new ProcessPort.StartProcessRequest(processKey, reference,
                vars, null, null, null, null));
        return application(ctx, pi.id());
    }

    @Override
    public Page<Application> applications(RequestContext ctx, Page.PageRequest page) {
        List<Application> all = new ArrayList<>();
        docs.all(ctx.tenantId(), KIND_DRAFT, org.sangam.rest.support.DocumentStore.Filter.owner(ctx.principalId()))
                .forEach(d -> all.add(d.as(Application.class)));
        var q = history.createHistoricProcessInstanceQuery().variableValueEquals(APPLICANT, ctx.principalId())
                .orderByProcessInstanceStartTime().desc();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        for (HistoricProcessInstance h : OperatonSupport.call(() -> q.listPage(0, LIMIT))) {
            all.add(map(ctx, h));
        }
        return Page.slice(all, page, props.getCountCap());
    }

    @Override
    public Application application(RequestContext ctx, String applicationId) {
        Application draft = ownDraft(ctx, applicationId);
        if (draft != null) {
            return draft;
        }
        return map(ctx, requireOwn(ctx, applicationId));
    }

    /** The application's process instance, if it belongs to the caller; 404 otherwise (no existence leak). */
    private HistoricProcessInstance requireOwn(RequestContext ctx, String id) {
        HistoricProcessInstance h = OperatonSupport.call(() ->
                history.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult());
        if (h == null) {
            throw ApiProblem.notFound("application", id);
        }
        OperatonSupport.checkTenant(ctx, h.getTenantId(), "application", id);
        Object applicant = variables(id).get(APPLICANT);
        if (!ctx.principalId().equals(applicant == null ? null : String.valueOf(applicant))) {
            throw ApiProblem.notFound("application", id);
        }
        return h;
    }

    private Map<String, Object> variables(String processInstanceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (HistoricVariableInstance v : OperatonSupport.call(() -> history.createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId).listPage(0, LIMIT))) {
            if (v.getTaskId() == null) {
                // process-scope (and execution-scope) variables; task-local ones belong to clarifications
                out.putIfAbsent(v.getName(), v.getValue());
            }
        }
        return out;
    }

    private Application map(RequestContext ctx, HistoricProcessInstance h) {
        Map<String, Object> vars = variables(h.getId());
        List<Clarification> clarifications = clarifications(h.getId());
        boolean openClarification = clarifications.stream().anyMatch(c -> c.repliedAt() == null);
        String status = str(vars.get(STATUS));
        String decision = str(vars.get(OperatonTaskAdapter.DECISION));
        if (openClarification) {
            status = "CLARIFICATION_SOUGHT";
        } else if (status == null || "SUBMITTED".equals(status)) {
            if ("APPROVED".equals(decision)) {
                status = "APPROVED";
            } else if ("REJECTED".equals(decision)) {
                status = "REJECTED";
            } else if (h.getEndTime() == null) {
                status = h.getStartTime() == null ? "SUBMITTED" : "IN_PROGRESS";
            } else if (h.getDeleteReason() != null) {
                status = "WITHDRAWN";
            } else {
                status = status == null ? "SUBMITTED" : status;
            }
        }
        String payment = str(vars.get("sangamPaymentStatus"));
        if (payment == null) {
            payment = "NOT_REQUIRED";
        } else if (payment.equals("UNKNOWN") || payment.equals("FAILED_UNKNOWN") || payment.equals("PENDING_CONFIRMATION")) {
            payment = "UNKNOWN_OUTCOME";
        }
        String serviceKey = str(vars.get(SERVICE_KEY));
        String serviceName = str(vars.get(SERVICE_NAME));
        return new Application(h.getId(), h.getBusinessKey(),
                serviceKey != null ? serviceKey : h.getProcessDefinitionKey(),
                serviceName != null ? serviceName : h.getProcessDefinitionName(),
                OperatonSupport.instant(h.getStartTime()), str(vars.get("sangamStage")),
                str(vars.get("sangamStageLabel")), str(vars.get("sangamStageLabelHi")), instant(vars.get("sangamSlaDate")),
                status, clarifications, List.of(), outcomeDocumentId(ctx, h.getId()),
                instant(vars.get("sangamAppealDeadline")), payment);
    }

    private List<Clarification> clarifications(String processInstanceId) {
        List<Clarification> out = new ArrayList<>();
        for (HistoricTaskInstance t : OperatonSupport.call(() -> history.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId).orderByHistoricActivityInstanceStartTime().asc()
                .listPage(0, LIMIT))) {
            if (t.getTaskDefinitionKey() == null
                    || !t.getTaskDefinitionKey().toLowerCase(Locale.ROOT).contains("clarification")) {
                continue;
            }
            Map<String, Object> local = new LinkedHashMap<>();
            for (HistoricVariableInstance v : OperatonSupport.call(() -> history.createHistoricVariableInstanceQuery()
                    .taskIdIn(t.getId()).list())) {
                local.put(v.getName(), v.getValue());
            }
            String question = t.getDescription() != null ? t.getDescription() : str(local.get("sangamQuestion"));
            if (question == null) {
                question = t.getName();
            }
            boolean done = t.getEndTime() != null;
            out.add(new Clarification(t.getId(), question, OperatonSupport.instant(t.getStartTime()),
                    str(local.get(OperatonTaskAdapter.SEAT)), OperatonSupport.instant(t.getDueDate()),
                    done ? replyOf(processInstanceId, t.getId(), local) : null,
                    done ? OperatonSupport.instant(t.getEndTime()) : null));
        }
        return out;
    }

    private String replyOf(String processInstanceId, String taskId, Map<String, Object> local) {
        Object reply = local.get("clarificationReply");
        if (reply != null) {
            return String.valueOf(reply);
        }
        // Completion variables land on the process scope when the task declares no local variable.
        var v = OperatonSupport.call(() -> history.createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId).variableName("clarificationReply").listPage(0, 1));
        return v.isEmpty() || v.get(0).getValue() == null ? null : String.valueOf(v.get(0).getValue());
    }

    private String outcomeDocumentId(RequestContext ctx, String processInstanceId) {
        try {
            return documents.list(ctx, new DocumentPort.DocumentFilter(null, processInstanceId, null, null),
                            new Page.PageRequest(0, 100)).items().stream()
                    .filter(d -> d.name() != null && d.name().toLowerCase(Locale.ROOT).startsWith("outcome"))
                    .max(Comparator.comparing(DocumentPort.Document::createdAt,
                            Comparator.nullsFirst(Comparator.naturalOrder())))
                    .map(DocumentPort.Document::id).orElse(null);
        } catch (ApiProblem e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ actions

    @Override
    public Application reply(RequestContext ctx, String applicationId, String clarificationId, ReplyRequest r) {
        HistoricProcessInstance h = requireOwn(ctx, applicationId);
        org.operaton.bpm.engine.task.Task task = OperatonSupport.call(() -> tasks.createTaskQuery()
                .taskId(clarificationId).processInstanceId(h.getId()).singleResult());
        if (task == null) {
            boolean exists = OperatonSupport.call(() -> history.createHistoricTaskInstanceQuery()
                    .taskId(clarificationId).processInstanceId(h.getId()).count()) > 0;
            if (exists) {
                throw ApiProblem.conflict("CLARIFICATION_CLOSED", "This clarification has already been answered");
            }
            throw ApiProblem.notFound("clarification", clarificationId);
        }
        if (task.getTaskDefinitionKey() == null
                || !task.getTaskDefinitionKey().toLowerCase(Locale.ROOT).contains("clarification")) {
            throw ApiProblem.notFound("clarification", clarificationId);
        }
        tx.runInTx(() -> {
            List<String> docIds = new ArrayList<>();
            if (r.files() != null) {
                for (PortalFile f : r.files()) {
                    DocumentPort.Document d = documents.upload(ctx, new DocumentPort.UploadRequest(f.name(),
                            f.contentType(), f.content(), null, h.getId(), null,
                            Map.of("clarificationId", clarificationId)));
                    docIds.add(d.id());
                }
            }
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("clarificationReply", r.text());
            vars.put("sangamClarificationDocumentIds", String.join(",", docIds));
            OperatonSupport.run(() -> tasks.complete(clarificationId, vars));
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("clarification_id", clarificationId);
            p.put("documents", docIds);
            p.put("reply_digest", Hashes.hex(Hashes.sha256(r.text())));
            audit.record(ctx, h.getId(), "CLARIFICATION_RECEIVED", p);
        });
        return application(ctx, applicationId);
    }

    @Override
    public Application withdraw(RequestContext ctx, String applicationId, WithdrawRequest r) {
        Application draft = ownDraft(ctx, applicationId);
        if (draft != null) {
            docs.delete(ctx.tenantId(), KIND_DRAFT, applicationId);
            return new Application(draft.id(), draft.reference(), draft.serviceKey(), draft.serviceName(), null,
                    draft.stage(), draft.stageLabel(), draft.stageLabelHi(), null, "WITHDRAWN", List.of(), List.of(),
                    null, null, draft.paymentStatus());
        }
        HistoricProcessInstance h = requireOwn(ctx, applicationId);
        if (h.getEndTime() != null) {
            throw ApiProblem.conflict("WITHDRAW_NOT_ALLOWED", "This application is already closed");
        }
        ProcessPort.MessageResult res;
        try {
            res = processes.correlate(ctx, new ProcessPort.MessageRequest(WITHDRAW_MESSAGE, h.getBusinessKey(), h.getId(),
                    null, Map.of("sangamWithdrawReason", r.reason(), STATUS, "WITHDRAWN"), false));
        } catch (ApiProblem p) {
            throw ApiProblem.conflict("WITHDRAW_NOT_ALLOWED",
                    "This application can no longer be withdrawn at its current stage");
        }
        if (res.processInstanceIds() == null || res.processInstanceIds().isEmpty()) {
            throw ApiProblem.conflict("WITHDRAW_NOT_ALLOWED",
                    "This application can no longer be withdrawn at its current stage");
        }
        audit.record(ctx, h.getId(), "CASE_WITHDRAWN", Map.of("reason", r.reason()));
        return application(ctx, applicationId);
    }

    @Override
    public Application appeal(RequestContext ctx, String applicationId, AppealRequest r) {
        HistoricProcessInstance h = requireOwn(ctx, applicationId);
        Application current = map(ctx, h);
        if (current.appealDeadline() != null && Instant.now().isAfter(current.appealDeadline())) {
            throw ApiProblem.unprocessable("APPEAL_DEADLINE_PASSED", "The appeal period ended on "
                    + current.appealDeadline());
        }
        String appealKey = h.getProcessDefinitionKey() + "-appeal";
        var dq = repository.createProcessDefinitionQuery().processDefinitionKey(appealKey).latestVersion();
        if (ctx.tenantId() != null) {
            dq.tenantIdIn(ctx.tenantId()).includeProcessDefinitionsWithoutTenantId();
        }
        if (OperatonSupport.call(dq::count) == 0) {
            throw ApiProblem.unprocessable("APPEAL_NOT_AVAILABLE", "No appeal process is deployed for this service");
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("appealGrounds", r.grounds());
        vars.put(APPLICANT, ctx.principalId());
        vars.put("sangamAppealOf", h.getId());
        vars.put(STATUS, "SUBMITTED");
        vars.put(SERVICE_KEY, current.serviceKey());
        ProcessPort.ProcessInstance appeal = processes.start(ctx, new ProcessPort.StartProcessRequest(appealKey,
                h.getBusinessKey() + "-APPEAL", vars, null, null, null, null));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("appeal_instance_id", appeal.id());
        p.put("grounds_digest", Hashes.hex(Hashes.sha256(r.grounds())));
        audit.record(ctx, h.getId(), "APPEAL_FILED", p);
        return application(ctx, applicationId);
    }

    @Override
    public Download outcome(RequestContext ctx, String applicationId) {
        HistoricProcessInstance h = requireOwn(ctx, applicationId);
        String docId = outcomeDocumentId(ctx, h.getId());
        if (docId == null) {
            throw ApiProblem.notFound("outcome document", applicationId);
        }
        return documents.content(ctx, docId, null);
    }

    // ------------------------------------------------------------------ grievances

    @Override
    public List<Grievance> grievances(RequestContext ctx) {
        List<Grievance> list = new ArrayList<>(docs.all(ctx.tenantId(), KIND_GRIEVANCE,
                org.sangam.rest.support.DocumentStore.Filter.owner(ctx.principalId())).stream()
                .map(d -> d.as(Grievance.class)).toList());
        list.sort(Comparator.comparing(Grievance::filedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return list;
    }

    @Override
    public GrievanceReceipt fileGrievance(RequestContext ctx, GrievanceRequest r) {
        String reference = "GRV-" + dates.today(ctx.tenantId()).getYear() + "-" + digits(6);
        Instant now = Instant.now();
        String kind = r.kind() == null ? "GRIEVANCE" : r.kind();
        var dq = repository.createProcessDefinitionQuery().processDefinitionKey("grievance").latestVersion();
        if (ctx.tenantId() != null) {
            dq.tenantIdIn(ctx.tenantId()).includeProcessDefinitionsWithoutTenantId();
        }
        boolean deployed = OperatonSupport.call(dq::count) > 0;
        return tx.inTx(() -> {
        String status = "FILED";
        if (deployed) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("grievanceSubject", r.subject());
            vars.put("grievanceText", r.text());
            vars.put("grievanceKind", kind);
            vars.put(APPLICANT, ctx.principalId());
            processes.start(ctx, new ProcessPort.StartProcessRequest("grievance", reference, vars, null, null, null,
                    null));
            status = "IN_PROGRESS";
        }
        Grievance g = new Grievance(reference, reference, r.subject(), status, now, now.plus(30, ChronoUnit.DAYS));
        docs.insert(ctx.tenantId(), KIND_GRIEVANCE, Hashes.uuidV7().toString(), reference, status, ctx.principalId(),
                null, g, ctx.principalId());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("reference", reference);
        p.put("kind", kind);
        p.put("subject_digest", Hashes.hex(Hashes.sha256(r.subject())));
        audit.record(ctx, "sec:" + ctx.tenantId(), "GRIEVANCE_FILED", p);
        return new GrievanceReceipt(reference);
        });
    }

    /** The caller's unsubmitted draft, or null (another applicant's draft is invisible). */
    private Application ownDraft(RequestContext ctx, String id) {
        return docs.get(ctx.tenantId(), KIND_DRAFT, id).filter(d -> ctx.principalId().equals(d.ownerId()))
                .map(d -> d.as(Application.class)).orElse(null);
    }

    // ------------------------------------------------------------------ helpers

    private static String owner(RequestContext ctx) {
        return ctx.tenantId() + "|" + ctx.principalId();
    }

    /** {@code <SERVICE>-<yyyy>-<6 digits>}, e.g. {@code TRADE-LICENCE-2026-048213}. */
    static String reference(String serviceKey, int year) {
        String s = serviceKey.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s + "-" + year + "-" + digits(6);
    }

    private static String digits(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer intOrNull(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? null : Integer.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> strings(Object o) {
        if (o instanceof Collection<?> c) {
            return c.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        return o == null ? List.of() : List.of(String.valueOf(o));
    }

    private static Instant instant(Object o) {
        if (o instanceof Date d) {
            return d.toInstant();
        }
        if (o instanceof Instant i) {
            return i;
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Instant.parse(s.trim());
            } catch (RuntimeException e) {
                try {
                    return LocalDate.parse(s.trim()).atStartOfDay(ZoneOffset.UTC).toInstant();
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
