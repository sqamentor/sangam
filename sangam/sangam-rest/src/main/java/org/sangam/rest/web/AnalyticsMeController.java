// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.time.Instant;
import java.util.List;

import org.sangam.rest.port.AnalyticsPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Analytics / process mining (R15, R63, R65, R66, UP-07). The caller's identity ({@code /me}) lives in
 * {@link WorkspaceController}.
 */
@RestController
@RequestMapping("/api/v1")
public class AnalyticsMeController {

    private static final int MAX_TRACES = 100_000;

    private final AnalyticsPort analytics;
    private final PermissionPort permissions;

    public AnalyticsMeController(AnalyticsPort analytics, PermissionPort permissions) {
        this.analytics = analytics;
        this.permissions = permissions;
    }

    @GetMapping("/analytics/summary")
    public AnalyticsPort.Summary summary(RequestContext ctx, @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        permissions.require(ctx, Permissions.ANALYTICS_READ);
        return analytics.summary(ctx, new AnalyticsPort.Range(processDefinitionKey, from, to));
    }

    @GetMapping("/analytics/activities")
    public ResponseEntity<List<AnalyticsPort.ActivityStat>> activities(RequestContext ctx,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        permissions.require(ctx, Permissions.ANALYTICS_READ);
        return Lists.ok(analytics.activities(ctx, new AnalyticsPort.Range(processDefinitionKey, from, to)));
    }

    @GetMapping("/analytics/tasks")
    public ResponseEntity<List<AnalyticsPort.TaskStat>> tasks(RequestContext ctx,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        permissions.require(ctx, Permissions.ANALYTICS_READ);
        return Lists.ok(analytics.tasks(ctx, new AnalyticsPort.Range(processDefinitionKey, from, to)));
    }

    @GetMapping("/analytics/variants")
    public ResponseEntity<List<AnalyticsPort.Variant>> variants(RequestContext ctx,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        permissions.require(ctx, Permissions.ANALYTICS_READ);
        if (limit < 1 || limit > 1000) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "limit must be 1..1000");
        }
        return Lists.ok(analytics.variants(ctx, new AnalyticsPort.Range(processDefinitionKey, from, to), limit));
    }

    @GetMapping("/analytics/export/xes")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> xes(RequestContext ctx, @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "10000") int maxTraces) {
        permissions.require(ctx, Permissions.ANALYTICS_READ);
        return DocumentController.download(analytics.exportXes(ctx, new AnalyticsPort.Range(processDefinitionKey, from, to),
                bound(maxTraces)));
    }

    @GetMapping("/analytics/export/ocel")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> ocel(RequestContext ctx, @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "10000") int maxTraces) {
        permissions.require(ctx, Permissions.ANALYTICS_READ);
        return DocumentController.download(analytics.exportOcel(ctx, new AnalyticsPort.Range(processDefinitionKey, from, to),
                bound(maxTraces)));
    }

    private static int bound(int maxTraces) {
        if (maxTraces < 1 || maxTraces > MAX_TRACES) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "maxTraces must be 1.." + MAX_TRACES);
        }
        return maxTraces;
    }
}
