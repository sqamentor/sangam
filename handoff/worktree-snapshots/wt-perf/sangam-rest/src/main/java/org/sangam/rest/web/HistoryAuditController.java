// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.time.Instant;
import java.util.List;

import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.HistoryPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Class-A history queries (R63, PF-01) and class-B audit timeline/verify/export (R42, X.2.3). */
@RestController
@RequestMapping("/api/v1")
public class HistoryAuditController {

    private final HistoryPort history;
    private final AuditPort audit;
    private final PermissionPort permissions;

    public HistoryAuditController(HistoryPort history, AuditPort audit, PermissionPort permissions) {
        this.history = history;
        this.audit = audit;
        this.permissions = permissions;
    }

    /** Common history query parameters (bound by name). */
    public record HistoryQuery(String processDefinitionKey, String processInstanceId, String businessKey,
                               String caseInstanceId, String activityId, String taskDefinitionKey, String assignee,
                               Boolean finished,
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                               Integer page, Integer size) {
        HistoryPort.HistoryFilter filter() {
            return new HistoryPort.HistoryFilter(processDefinitionKey, processInstanceId, businessKey, caseInstanceId,
                    activityId, taskDefinitionKey, assignee, finished, from, to);
        }
    }

    @GetMapping("/history/process-instances")
    public ResponseEntity<List<HistoryPort.HistoricProcessInstance>> processInstances(RequestContext ctx,
                                                                                      @ModelAttribute HistoryQuery q) {
        permissions.require(ctx, Permissions.HISTORY_READ);
        return Lists.ok(history.processInstances(ctx, q.filter(), Lists.page(q.page(), q.size())));
    }

    @GetMapping("/history/activity-instances")
    public ResponseEntity<List<HistoryPort.HistoricActivityInstance>> activityInstances(RequestContext ctx,
                                                                                        @ModelAttribute HistoryQuery q) {
        permissions.require(ctx, Permissions.HISTORY_READ);
        return Lists.ok(history.activityInstances(ctx, q.filter(), Lists.page(q.page(), q.size())));
    }

    @GetMapping("/history/tasks")
    public ResponseEntity<List<HistoryPort.HistoricTask>> tasks(RequestContext ctx, @ModelAttribute HistoryQuery q) {
        permissions.require(ctx, Permissions.HISTORY_READ);
        return Lists.ok(history.tasks(ctx, q.filter(), Lists.page(q.page(), q.size())));
    }

    @GetMapping("/history/variables")
    public ResponseEntity<List<HistoryPort.HistoricVariable>> variables(RequestContext ctx, @ModelAttribute HistoryQuery q) {
        permissions.require(ctx, Permissions.HISTORY_READ);
        return Lists.ok(history.variables(ctx, q.filter(), Lists.page(q.page(), q.size())));
    }

    @GetMapping("/history/decisions")
    public ResponseEntity<List<HistoryPort.HistoricDecision>> decisions(RequestContext ctx, @ModelAttribute HistoryQuery q) {
        permissions.require(ctx, Permissions.HISTORY_READ);
        return Lists.ok(history.decisions(ctx, q.filter(), Lists.page(q.page(), q.size())));
    }

    @GetMapping("/history/incidents")
    public ResponseEntity<List<HistoryPort.HistoricIncident>> incidents(RequestContext ctx, @ModelAttribute HistoryQuery q) {
        permissions.require(ctx, Permissions.HISTORY_READ);
        return Lists.ok(history.incidents(ctx, q.filter(), Lists.page(q.page(), q.size())));
    }

    @GetMapping("/history/job-log")
    public ResponseEntity<List<HistoryPort.HistoricJobLog>> jobLog(RequestContext ctx, @ModelAttribute HistoryQuery q) {
        permissions.require(ctx, Permissions.HISTORY_READ);
        return Lists.ok(history.jobLog(ctx, q.filter(), Lists.page(q.page(), q.size())));
    }

    @GetMapping("/history/operation-log")
    public ResponseEntity<List<HistoryPort.UserOperation>> operationLog(RequestContext ctx, @ModelAttribute HistoryQuery q) {
        permissions.require(ctx, Permissions.HISTORY_READ);
        return Lists.ok(history.operationLog(ctx, q.filter(), Lists.page(q.page(), q.size())));
    }

    @GetMapping("/audit/{businessKey}/timeline")
    public ResponseEntity<List<AuditPort.AuditEntry>> timeline(RequestContext ctx, @PathVariable String businessKey,
            @RequestParam(required = false, defaultValue = "true") boolean includeHistory,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.AUDIT_READ);
        Telemetry.businessKey(businessKey);
        return Lists.ok(audit.timeline(ctx, businessKey, includeHistory, Lists.page(page, size)));
    }

    @GetMapping("/audit/events")
    public ResponseEntity<List<AuditPort.AuditEntry>> events(RequestContext ctx,
            @RequestParam(required = false) String chainId, @RequestParam(required = false) String type,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.AUDIT_READ);
        return Lists.ok(audit.search(ctx, new AuditPort.AuditFilter(chainId, type, actor, from, to), Lists.page(page, size)));
    }

    @GetMapping("/audit/verify")
    public AuditPort.VerifyResult verify(RequestContext ctx, @RequestParam(required = false) String chainId,
                                         @RequestParam(required = false) String businessKey) {
        permissions.require(ctx, Permissions.AUDIT_READ);
        return audit.verify(ctx, chainId, businessKey);
    }

    @GetMapping("/audit/export")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> export(RequestContext ctx, @RequestParam String businessKey,
                                         @RequestParam(required = false, defaultValue = "json") String format) {
        permissions.require(ctx, Permissions.AUDIT_EXPORT);
        if (!format.equals("json") && !format.equals("csv")) {
            throw org.sangam.rest.port.ApiProblem.badRequest("VALIDATION_FAILED", "format must be json or csv");
        }
        return DocumentController.download(audit.export(ctx, businessKey, format));
    }
}
