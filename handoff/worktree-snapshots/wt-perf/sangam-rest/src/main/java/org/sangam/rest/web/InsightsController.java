// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.time.Instant;
import java.util.List;

import org.sangam.rest.port.AnalyticsPort;
import org.sangam.rest.port.InsightsPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Analytics extras: throughput, SLA (R79), conformance (R66), duration reports (PF-07), alerts (R65), agents (R72). */
@RestController
@RequestMapping("/api/v1/analytics")
public class InsightsController {

    private final InsightsPort insights;
    private final PermissionPort permissions;

    public InsightsController(InsightsPort insights, PermissionPort permissions) {
        this.insights = insights;
        this.permissions = permissions;
    }

    @GetMapping("/throughput")
    public ResponseEntity<List<InsightsPort.TimePoint>> throughput(RequestContext ctx,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "day") String interval) {
        permissions.require(ctx, Permissions.ANALYTICS_READ);
        return Lists.ok(insights.throughput(ctx, new AnalyticsPort.Range(processDefinitionKey, from, to), interval));
    }

    @GetMapping("/sla")
    public ResponseEntity<List<InsightsPort.SlaRow>> sla(RequestContext ctx,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        permissions.require(ctx, Permissions.ANALYTICS_READ);
        return Lists.ok(insights.sla(ctx, new AnalyticsPort.Range(processDefinitionKey, from, to)));
    }

    @GetMapping("/conformance")
    public InsightsPort.ConformanceResult conformance(RequestContext ctx, @RequestParam String processDefinitionKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        permissions.require(ctx, Permissions.ANALYTICS_READ);
        return insights.conformance(ctx, new AnalyticsPort.Range(processDefinitionKey, from, to));
    }

    @GetMapping("/reports/duration")
    public ResponseEntity<List<InsightsPort.DurationReportRow>> durationReport(RequestContext ctx,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "day") String period) {
        permissions.require(ctx, Permissions.ANALYTICS_READ);
        return Lists.ok(insights.durationReport(ctx, new AnalyticsPort.Range(processDefinitionKey, from, to), period));
    }

    @GetMapping("/alerts")
    public ResponseEntity<List<InsightsPort.AlertRule>> alerts(RequestContext ctx) {
        permissions.require(ctx, Permissions.ANALYTICS_READ);
        return Lists.ok(insights.alerts(ctx));
    }

    @PostMapping("/alerts")
    public ResponseEntity<InsightsPort.AlertRule> createAlert(RequestContext ctx, @RequestBody InsightsPort.AlertRule body) {
        permissions.require(ctx, Permissions.ANALYTICS_ADMIN);
        InsightsPort.AlertRule r = insights.createAlert(ctx, body);
        return Lists.created("/api/v1/analytics/alerts/" + r.id(), r.id(), r);
    }

    @PutMapping("/alerts/{id}")
    public ResponseEntity<InsightsPort.AlertRule> updateAlert(RequestContext ctx, @PathVariable String id,
                                                              @RequestBody InsightsPort.AlertRule body) {
        permissions.require(ctx, Permissions.ANALYTICS_ADMIN);
        return Lists.okRef(id, insights.updateAlert(ctx, id, body));
    }

    @DeleteMapping("/alerts/{id}")
    public ResponseEntity<Void> deleteAlert(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.ANALYTICS_ADMIN);
        insights.deleteAlert(ctx, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/agents")
    public ResponseEntity<List<InsightsPort.AgentUsage>> agents(RequestContext ctx,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        permissions.require(ctx, Permissions.ANALYTICS_READ);
        return Lists.ok(insights.agentUsage(ctx, new AnalyticsPort.Range(null, from, to)));
    }
}
