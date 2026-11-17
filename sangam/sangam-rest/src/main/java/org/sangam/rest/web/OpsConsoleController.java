// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.io.IOException;
import java.util.List;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.InstanceControlPort;
import org.sangam.rest.port.OpsConsolePort;
import org.sangam.rest.port.OpsPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Operations console extras (O01–O09, R63): dashboard, job summary and stack traces, inbox (R38), workers and topics,
 * incident and reconciliation work handling (OJ-01, OJ-02), history TTL (R29) and notification failures (R60).
 */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsConsoleController {

    private final OpsConsolePort console;
    private final PermissionPort permissions;

    public OpsConsoleController(OpsConsolePort console, PermissionPort permissions) {
        this.console = console;
        this.permissions = permissions;
    }

    @GetMapping("/dashboard")
    public OpsConsolePort.Dashboard dashboard(RequestContext ctx) {
        permissions.require(ctx, Permissions.OPS_READ);
        return console.dashboard(ctx);
    }

    @GetMapping("/jobs/summary")
    public OpsConsolePort.JobSummary jobSummary(RequestContext ctx) {
        permissions.require(ctx, Permissions.OPS_READ);
        return console.jobSummary(ctx);
    }

    @GetMapping(value = "/jobs/{id}/stacktrace", produces = MediaType.TEXT_PLAIN_VALUE)
    public String jobStacktrace(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.OPS_READ);
        return console.jobStacktrace(ctx, id);
    }

    @GetMapping("/inbox")
    public ResponseEntity<List<OpsConsolePort.InboxEntry>> inbox(RequestContext ctx,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.OPS_READ);
        return Lists.ok(console.inbox(ctx, status, Lists.page(page, size)));
    }

    @GetMapping("/workers")
    public ResponseEntity<List<OpsConsolePort.WorkerInfo>> workers(RequestContext ctx) {
        permissions.require(ctx, Permissions.OPS_READ);
        return Lists.ok(console.workers(ctx));
    }

    @GetMapping("/topics")
    public ResponseEntity<List<OpsConsolePort.TopicInfo>> topics(RequestContext ctx) {
        permissions.require(ctx, Permissions.OPS_READ);
        return Lists.ok(console.topics(ctx));
    }

    /** Decision 393: the value can carry '/' or ':', refused encoded in a path segment, so it is a required query parameter. */
    @PostMapping("/topics/by-name/pause")
    public ResponseEntity<OpsConsolePort.TopicInfo> pauseTopic(RequestContext ctx, @RequestParam("name") String name,
                                                               @RequestBody OpsConsolePort.ReasonRequest body) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        ProcessController.requireReason(body == null ? null : body.reason());
        return Lists.okRef(name, console.setTopicPaused(ctx, name, true, body.reason()));
    }

    /** Decision 393: the value can carry '/' or ':', refused encoded in a path segment, so it is a required query parameter. */
    @PostMapping("/topics/by-name/resume")
    public ResponseEntity<OpsConsolePort.TopicInfo> resumeTopic(RequestContext ctx, @RequestParam("name") String name,
                                                                @RequestBody OpsConsolePort.ReasonRequest body) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        ProcessController.requireReason(body == null ? null : body.reason());
        return Lists.okRef(name, console.setTopicPaused(ctx, name, false, body.reason()));
    }

    @GetMapping("/incidents/{id}/preview")
    public InstanceControlPort.ConsequencePreview incidentPreview(RequestContext ctx, @PathVariable String id,
                                                                  @RequestParam String action) {
        permissions.require(ctx, Permissions.OPS_READ);
        return console.incidentPreview(ctx, id, action);
    }

    @PostMapping("/incidents/{id}/claim")
    public ResponseEntity<OpsPort.Incident> claimIncident(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        return Lists.okRef(id, console.claimIncident(ctx, id));
    }

    @PostMapping("/incidents/{id}/note")
    public ResponseEntity<OpsPort.Incident> noteIncident(RequestContext ctx, @PathVariable String id,
                                                         @RequestBody OpsConsolePort.NoteRequest body) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        if (body == null || body.note() == null || body.note().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "note is required");
        }
        return Lists.okRef(id, console.noteIncident(ctx, id, body));
    }

    @PostMapping("/reconciliation/{id}/claim")
    public ResponseEntity<OpsPort.ReconciliationItem> claimReconciliation(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.RECON_RESOLVE);
        return Lists.okRef(id, console.claimReconciliation(ctx, id));
    }

    @PostMapping("/reconciliation/{id}/lookup")
    public ResponseEntity<OpsConsolePort.LookupResult> lookupReconciliation(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.RECON_RESOLVE);
        return Lists.okRef(id, console.lookupReconciliation(ctx, id));
    }

    @PostMapping(value = "/reconciliation/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<OpsConsolePort.Evidence> addEvidence(RequestContext ctx, @PathVariable String id,
            @RequestParam String kind, @RequestPart("file") MultipartFile file) throws IOException {
        permissions.require(ctx, Permissions.RECON_RESOLVE);
        OpsConsolePort.Evidence e = console.addReconciliationEvidence(ctx, id, kind, file.getOriginalFilename(),
                file.getContentType(), file.getBytes());
        return Lists.created("/api/v1/ops/reconciliation/" + id, e.id(), e);
    }

    /** Decision 393: the value can carry '/' or ':', refused encoded in a path segment, so it is a required query parameter. */
    @PutMapping("/cleanup/ttl/by-key")
    public ResponseEntity<OpsPort.CleanupStatus> setTtl(RequestContext ctx, @RequestParam("key") String key,
                                                        @RequestBody OpsConsolePort.TtlRequest body) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        ProcessController.requireReason(body == null ? null : body.reason());
        return Lists.okRef(key, console.setHistoryTtl(ctx, key, body));
    }

    @GetMapping("/notification-failures")
    public ResponseEntity<List<OpsConsolePort.NotificationFailure>> notificationFailures(RequestContext ctx,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.OPS_READ);
        return Lists.ok(console.notificationFailures(ctx, Lists.page(page, size)));
    }

    /** Decision 393: the value can carry '/' or ':', refused encoded in a path segment, so it is a required query parameter. */
    @PostMapping("/notification-failures/by-id/resend")
    public ResponseEntity<Void> resend(RequestContext ctx, @RequestParam("id") String id,
                                       @RequestBody OpsConsolePort.ReasonRequest body) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        ProcessController.requireReason(body == null ? null : body.reason());
        console.resendNotification(ctx, id, body.reason());
        return ResponseEntity.noContent().header(Lists.RESOURCE_REF, id).build();
    }
}
