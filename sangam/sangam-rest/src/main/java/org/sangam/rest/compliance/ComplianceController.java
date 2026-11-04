// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.compliance;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.compliance.accountability.AccountabilityService;
import org.sangam.compliance.accountability.ContactDirectory;
import org.sangam.compliance.breach.BreachIncidentService;
import org.sangam.compliance.breach.IncidentReportingService;
import org.sangam.compliance.consent.ConsentManagerPort;
import org.sangam.compliance.consent.ConsentNoticeService;
import org.sangam.compliance.consent.ConsentService;
import org.sangam.compliance.lawful.LawfulRequestService;
import org.sangam.compliance.lawful.PurposeDefinition;
import org.sangam.compliance.logs.LogArchiveService;
import org.sangam.compliance.rights.DataSubjectRightsService;
import org.sangam.compliance.transfer.CrossBorderTransferGuard;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.web.Lists;
import org.sangam.rest.web.RequestContexts;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/compliance}: data-protection and incident-reporting compliance (sangam-compliance; DPDP Act 2023 and
 * Rules 2025, CERT-In directions; GOV-4, R53). Colon-style actions of the module API are sub-paths here
 * ({@code /notices/{key}:publish} → {@code POST /notices/{key}/publish}).
 *
 * <p>Permissions (resource type COMPLIANCE): {@code compliance.read} for queries, {@code compliance.modify} for case
 * work, {@code compliance.admin} for configuration, {@code audit.read} for log verification and reports. A data
 * principal may read their own consents and rights requests and withdraw their own consent without those permissions.
 *
 * <p>Public (unauthenticated) endpoints take the tenant from {@code X-Sangam-Tenant} or {@code ?tenant=}:
 * {@code GET /notices/{key}}, {@code GET /privacy-policy}, {@code POST /rights-requests},
 * {@code POST /consent-managers/{cm}/artefacts} (mTLS from the consent manager at the gateway).
 */
@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceController {

    public static final String TENANT_HEADER = "X-Sangam-Tenant";

    private final ComplianceRestService api;
    private final PermissionPort permissions;

    public ComplianceController(ComplianceRestService api, PermissionPort permissions) {
        this.api = api;
        this.permissions = permissions;
    }

    // ---------------------------------------------------------------- identity helpers

    static SecurityIdentity identity(RequestContext ctx) {
        return new SecurityIdentity(ctx.tenantId(), ctx.principalId(), ctx.principalId(), ctx.groups(), Set.of(),
                ctx.seatId() == null ? Set.of() : Set.of(ctx.seatId()),
                ctx.actingForSeatId() == null ? Set.of() : Set.of(ctx.actingForSeatId()), ctx.authMethod(),
                ctx.clientIp(), null, false, Map.of());
    }

    private SecurityIdentity require(RequestContext ctx, String permission) {
        permissions.require(ctx, permission);
        return identity(ctx);
    }

    /** Tenant of a public call: the authenticated tenant if any, else the header or query parameter. */
    private static String publicTenant(HttpServletRequest req, String header, String param) {
        var principal = RequestContexts.principal(req);
        if (principal != null && principal.tenantId() != null) {
            return principal.tenantId();
        }
        String t = header != null && !header.isBlank() ? header : param;
        if (t == null || t.isBlank()) {
            throw ApiProblem.badRequest("TENANT_REQUIRED", "Give the tenant in " + TENANT_HEADER + " or ?tenant=");
        }
        return t.trim();
    }

    // ---------------------------------------------------------------- notices, privacy policy, purposes

    @GetMapping("/notices/{key}")
    public ConsentNoticeService.RenderedNotice notice(HttpServletRequest req, @PathVariable String key,
            @RequestParam(required = false) Integer version, @RequestParam(required = false) String lang,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(required = false) String tenant) {
        return api.notice(publicTenant(req, tenantHeader, tenant), key, version, lang);
    }

    @PostMapping("/notices/{key}/publish")
    public ConsentNoticeService.PublishResult publishNotice(RequestContext ctx, @PathVariable String key,
                                                            @RequestBody ComplianceRequests.PublishNoticeRequest body) {
        SecurityIdentity id = require(ctx, Permissions.COMPLIANCE_ADMIN);
        return api.publishNotice(id, key, body.purposes() == null ? Set.of() : body.purposes());
    }

    @GetMapping("/privacy-policy")
    public ConsentNoticeService.RenderedNotice privacyPolicy(HttpServletRequest req,
            @RequestParam(required = false) String lang,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(required = false) String tenant) {
        return api.privacyPolicy(publicTenant(req, tenantHeader, tenant), lang);
    }

    @GetMapping("/purposes")
    public ResponseEntity<List<PurposeDefinition>> purposes(RequestContext ctx) {
        return Lists.ok(api.purposes(require(ctx, Permissions.COMPLIANCE_READ)));
    }

    @PutMapping("/purposes/{code}")
    public PurposeDefinition definePurpose(RequestContext ctx, @PathVariable String code,
                                           @RequestBody PurposeDefinition body) {
        SecurityIdentity id = require(ctx, Permissions.COMPLIANCE_ADMIN);
        if (body.code() != null && !body.code().equals(code)) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "body code " + body.code() + " differs from path " + code);
        }
        PurposeDefinition p = body.code() == null ? new PurposeDefinition(code, body.names(), body.descriptions(),
                body.dataItems(), body.basis(), body.legalRef(), body.subGround(), body.registerRef(),
                body.standardsAttested(), body.exemptions(), body.exemptionRef(), body.childExemptions(), body.flags(),
                body.retentionTrigger(), body.retention(), body.retentionClass(), body.recordClass(),
                body.recordRetention(), body.recordRetentionRef(), body.version(), body.active()) : body;
        return api.definePurpose(id, p);
    }

    // ---------------------------------------------------------------- consents

    @PostMapping("/consents")
    public ResponseEntity<ConsentService.Consent> giveConsent(RequestContext ctx,
                                                              @RequestBody ComplianceRequests.GiveConsentRequest body) {
        SecurityIdentity id = identity(ctx);
        // A data principal (or their verified guardian) gives their own consent; recording consent for someone else
        // (assisted mode at a counter) needs compliance.modify.
        if (body.principalRef() != null && !body.principalRef().equals(ctx.principalId())) {
            permissions.require(ctx, Permissions.COMPLIANCE_MODIFY);
        }
        ConsentService.Consent k = api.giveConsent(id, api.toGiveConsent(id, body));
        return Lists.created("/api/v1/compliance/consents/" + k.consentId(), k.consentId(), k);
    }

    @PostMapping("/consents/{id}/withdraw")
    public ResponseEntity<ConsentService.Consent> withdrawConsent(RequestContext ctx, @PathVariable String id,
            @RequestBody(required = false) ComplianceRequests.WithdrawConsentRequest body) {
        SecurityIdentity sid = identity(ctx);
        String owner = api.consentPrincipal(sid, id);
        if (!ctx.principalId().equals(owner)) {
            permissions.require(ctx, Permissions.COMPLIANCE_MODIFY);
        }
        Set<String> purposes = body == null || body.purposes() == null ? Set.of() : body.purposes();
        return Lists.okRef(id, api.withdrawConsent(sid, id, purposes));
    }

    @GetMapping("/consents")
    public ResponseEntity<List<ConsentService.Consent>> consents(RequestContext ctx,
                                                                 @RequestParam(required = false) String subject) {
        String principal = subject == null || subject.isBlank() ? ctx.principalId() : subject;
        if (!principal.equals(ctx.principalId())) {
            permissions.require(ctx, Permissions.COMPLIANCE_READ);
        }
        return Lists.ok(api.consents(identity(ctx), principal));
    }

    @PostMapping("/consent-managers/{cm}/artefacts")
    public ResponseEntity<ConsentService.Consent> ingestArtefact(HttpServletRequest req, @PathVariable String cm,
            @RequestBody ConsentManagerPort.ConsentArtefact body,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(required = false) String tenant) {
        if (body.consentManagerId() != null && !body.consentManagerId().equals(cm)) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "artefact consentManagerId differs from the path");
        }
        ConsentService.Consent k = api.ingestArtefact(publicTenant(req, tenantHeader, tenant), body);
        return Lists.created("/api/v1/compliance/consents/" + k.consentId(), k.consentId(), k);
    }

    // ---------------------------------------------------------------- rights requests

    @PostMapping("/rights-requests")
    public ResponseEntity<DataSubjectRightsService.RightsRequest> submitRightsRequest(HttpServletRequest req,
            @RequestBody DataSubjectRightsService.Submission body,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(required = false) String tenant) {
        DataSubjectRightsService.RightsRequest r = api.submitRightsRequest(publicTenant(req, tenantHeader, tenant), body);
        return Lists.created("/api/v1/compliance/rights-requests/" + r.requestId(), r.requestId(), r);
    }

    @GetMapping("/rights-requests")
    public ResponseEntity<List<DataSubjectRightsService.RightsRequest>> rightsRequests(RequestContext ctx,
            @RequestParam(required = false) String subject) {
        String principal = subject == null || subject.isBlank() ? ctx.principalId() : subject;
        if (!principal.equals(ctx.principalId())) {
            permissions.require(ctx, Permissions.COMPLIANCE_READ);
        }
        return Lists.ok(api.rightsRequestsOf(identity(ctx), principal));
    }

    @GetMapping("/rights-requests/{id}")
    public DataSubjectRightsService.RightsRequest rightsRequest(RequestContext ctx, @PathVariable String id) {
        DataSubjectRightsService.RightsRequest r = api.rightsRequest(identity(ctx), id);
        if (!ctx.principalId().equals(r.principalRef()) && !permissions.has(ctx, Permissions.COMPLIANCE_READ)) {
            throw ApiProblem.notFound("rights request", id); // no existence leak to other principals
        }
        return r;
    }

    // ---------------------------------------------------------------- breaches and incidents

    @PostMapping("/breaches")
    public ResponseEntity<BreachIncidentService.Breach> registerBreach(RequestContext ctx,
                                                                       @RequestBody ComplianceRequests.BreachRequest b) {
        SecurityIdentity id = require(ctx, Permissions.COMPLIANCE_MODIFY);
        BreachIncidentService.Breach breach = api.registerBreach(id, b.facts() == null ? Map.of() : b.facts(),
                b.awareAt(), b.subjects(), b.roots(), b.incidentType());
        return Lists.created("/api/v1/compliance/breaches/" + breach.breachId(), breach.breachId(), breach);
    }

    @GetMapping("/breaches/{id}")
    public BreachIncidentService.Breach breach(RequestContext ctx, @PathVariable String id) {
        return api.breach(require(ctx, Permissions.COMPLIANCE_READ), id);
    }

    @PostMapping("/incidents")
    public ResponseEntity<IncidentReportingService.Incident> registerIncident(RequestContext ctx,
            @RequestBody ComplianceRequests.IncidentRequest b) {
        SecurityIdentity id = require(ctx, Permissions.COMPLIANCE_MODIFY);
        IncidentReportingService.Incident i = api.registerIncident(id, b.authority(), b.type(), b.occurredAt(),
                b.noticedAt(), b.systems(), b.description());
        return Lists.created("/api/v1/compliance/incidents/" + i.incidentId(), i.incidentId(), i);
    }

    @GetMapping("/incidents/{id}")
    public IncidentReportingService.Incident incident(RequestContext ctx, @PathVariable String id) {
        return api.incident(require(ctx, Permissions.COMPLIANCE_READ), id);
    }

    @PostMapping("/incidents/{id}/submit")
    public ResponseEntity<List<String>> submitIncident(RequestContext ctx, @PathVariable String id,
                                                       @RequestBody ComplianceRequests.SubmitIncidentRequest b) {
        SecurityIdentity sid = require(ctx, Permissions.COMPLIANCE_MODIFY);
        return Lists.okRef(id, api.submitIncident(sid, id, b.actionsTaken()));
    }

    // ---------------------------------------------------------------- lawful requests (maker-checker)

    @PostMapping("/lawful-requests")
    public ResponseEntity<LawfulRequestService.LawfulRequest> receiveLawfulRequest(RequestContext ctx,
            @RequestBody ComplianceRequests.LawfulRequestBody b) {
        SecurityIdentity id = require(ctx, Permissions.COMPLIANCE_MODIFY);
        LawfulRequestService.LawfulRequest r = api.receiveLawfulRequest(id, b);
        return Lists.created("/api/v1/compliance/lawful-requests/" + r.requestId(), r.requestId(), r);
    }

    @GetMapping("/lawful-requests/{id}")
    public LawfulRequestService.LawfulRequest lawfulRequest(RequestContext ctx, @PathVariable String id) {
        return api.lawfulRequest(require(ctx, Permissions.COMPLIANCE_READ), id);
    }

    @PostMapping("/lawful-requests/{id}/verify")
    public ResponseEntity<LawfulRequestService.LawfulRequest> verifyLawfulRequest(RequestContext ctx,
            @PathVariable String id, @RequestBody ComplianceRequests.VerifyLawfulRequest b) {
        SecurityIdentity sid = require(ctx, Permissions.COMPLIANCE_MODIFY);
        return Lists.okRef(id, api.verifyLawfulRequest(sid, id, b.rankAdequate()));
    }

    @PostMapping("/lawful-requests/{id}/approve")
    public ResponseEntity<LawfulRequestService.LawfulRequest> approveLawfulRequest(RequestContext ctx,
                                                                                   @PathVariable String id) {
        SecurityIdentity sid = require(ctx, Permissions.COMPLIANCE_MODIFY);
        return Lists.okRef(id, api.approveLawfulRequest(sid, id));
    }

    @PostMapping("/lawful-requests/{id}/respond")
    public ResponseEntity<LawfulRequestService.LawfulRequest> respondLawfulRequest(RequestContext ctx,
            @PathVariable String id, @RequestBody ComplianceRequests.RespondLawfulRequest b) {
        SecurityIdentity sid = require(ctx, Permissions.COMPLIANCE_MODIFY);
        return Lists.okRef(id, api.respondLawfulRequest(sid, id, b.responseRef(), b.principalRefs(), b.dataCategories()));
    }

    @PostMapping("/lawful-requests/{id}/reject")
    public ResponseEntity<LawfulRequestService.LawfulRequest> rejectLawfulRequest(RequestContext ctx,
            @PathVariable String id, @RequestBody ComplianceRequests.ReasonBody b) {
        SecurityIdentity sid = require(ctx, Permissions.COMPLIANCE_MODIFY);
        if (b.reason() == null || b.reason().isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "A rejection needs a reason");
        }
        return Lists.okRef(id, api.rejectLawfulRequest(sid, id, b.reason()));
    }

    // ---------------------------------------------------------------- contacts and evidence

    @GetMapping("/contacts")
    public ResponseEntity<List<ContactDirectory.Contact>> contacts(RequestContext ctx) {
        return Lists.ok(api.contacts(require(ctx, Permissions.COMPLIANCE_READ)));
    }

    @PostMapping("/contacts/{role}")
    public ResponseEntity<Void> setContact(RequestContext ctx, @PathVariable String role,
                                           @RequestBody ContactDirectory.Contact body) {
        SecurityIdentity id = require(ctx, Permissions.COMPLIANCE_ADMIN);
        ContactDirectory.Contact c = new ContactDirectory.Contact(role, body.name(), body.designation(),
                body.organisation(), body.email(), body.phone(), body.mobile(), body.address(), body.resident(),
                body.validFrom());
        api.setContact(id, c);
        return ResponseEntity.noContent().header(Lists.RESOURCE_REF, role).build();
    }

    @PostMapping("/evidence/audits")
    public ResponseEntity<AccountabilityService.AuditEvidence> recordAudit(RequestContext ctx,
            @RequestBody ComplianceRequests.AuditEvidenceRequest b) {
        SecurityIdentity id = require(ctx, Permissions.COMPLIANCE_ADMIN);
        AccountabilityService.AuditEvidence e = api.recordAudit(id, b);
        return Lists.created("/api/v1/compliance/evidence/audits/" + e.auditId(), e.auditId(), e);
    }

    @PostMapping("/evidence/impact-assessments")
    public ResponseEntity<AccountabilityService.ImpactAssessment> recordImpactAssessment(RequestContext ctx,
            @RequestBody ComplianceRequests.ImpactAssessmentRequest b) {
        SecurityIdentity id = require(ctx, Permissions.COMPLIANCE_ADMIN);
        AccountabilityService.ImpactAssessment a = api.recordImpactAssessment(id, b);
        return Lists.created("/api/v1/compliance/evidence/impact-assessments/" + a.assessmentId(), a.assessmentId(), a);
    }

    // ---------------------------------------------------------------- transfers, logs, reports, jurisdiction

    @PostMapping("/transfers/evaluate")
    public CrossBorderTransferGuard.Decision evaluateTransfer(RequestContext ctx,
                                                              @RequestBody CrossBorderTransferGuard.TransferRequest body) {
        return api.evaluateTransfer(require(ctx, Permissions.COMPLIANCE_MODIFY), body);
    }

    @PostMapping("/logs/segments/seal")
    public ResponseEntity<LogArchiveService.Segment> sealLogs(RequestContext ctx,
                                                              @RequestBody ComplianceRequests.SealLogsRequest b) {
        SecurityIdentity id = require(ctx, Permissions.COMPLIANCE_ADMIN);
        if (b.from() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "from is required");
        }
        LogArchiveService.Segment s = api.sealLogs(id, b.from(), b.to());
        return Lists.created("/api/v1/compliance/logs/segments/" + s.segmentId(), s.segmentId(), s);
    }

    @GetMapping("/logs/verify")
    public Map<String, Object> verifyLogs(RequestContext ctx) {
        return api.verifyAudit(require(ctx, Permissions.AUDIT_READ));
    }

    @GetMapping("/reports")
    public ResponseEntity<?> report(RequestContext ctx,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "true") boolean verifyChains,
            @RequestParam(required = false, defaultValue = "json") String format) {
        var r = api.report(require(ctx, Permissions.AUDIT_READ), from, to, verifyChains);
        if ("html".equalsIgnoreCase(format)) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).header("X-Sangam-Report-Hash", r.hash())
                    .body(r.html());
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("X-Sangam-Report-Hash", r.hash())
                .body(r);
    }

    @GetMapping("/jurisdiction")
    public Map<String, Object> jurisdiction(RequestContext ctx) {
        return api.jurisdiction(ctx.tenantId());
    }
}
