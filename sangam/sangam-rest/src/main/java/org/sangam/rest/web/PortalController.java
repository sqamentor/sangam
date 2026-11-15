// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.FormPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.PortalCompliancePort;
import org.sangam.rest.port.PortalPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.support.TypedVariables;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Citizen / applicant portal (C01–C06, PER-8, UP-22). Applicants see only their own applications (404 otherwise);
 * officers appear only by seat designation.
 */
@RestController
@RequestMapping("/api/v1/portal")
public class PortalController {

    private static final int MAX_FILES = 10;

    private final PortalPort portal;
    private final PermissionPort permissions;
    private final ObjectProvider<FormPort> forms;
    private final ObjectProvider<PortalCompliancePort> compliance;
    private final TxSupport tx;

    /**
     * @param compliance the data-protection module's portal view (absent when sangam-compliance is not installed)
     * @param tx         the consent and the application commit or roll back together (owner decision 339)
     */
    public PortalController(PortalPort portal, PermissionPort permissions, ObjectProvider<FormPort> forms,
                            ObjectProvider<PortalCompliancePort> compliance, TxSupport tx) {
        this.portal = portal;
        this.permissions = permissions;
        this.forms = forms;
        this.compliance = compliance;
        this.tx = tx;
    }

    private PortalPort.PortalService requireService(RequestContext ctx, String key) {
        return portal.services(ctx).stream().filter(s -> s.key().equals(key)).findFirst()
                .orElseThrow(() -> ApiProblem.notFound("Portal service", key));
    }

    private PortalCompliancePort requireCompliance() {
        PortalCompliancePort c = compliance.getIfAvailable();
        if (c == null) {
            throw ApiProblem.notInstalled("data protection (consent notices)", "sangam-compliance");
        }
        return c;
    }

    /**
     * Owner decisions 339, 341, 343: the current published version of the service's consent notice, with one entry per
     * purpose and the jurisdiction pack's age of majority, for the application form to show before submission.
     */
    @GetMapping("/services/{key}/notice")
    public PortalCompliancePort.PortalNotice serviceNotice(RequestContext ctx, @PathVariable String key,
                                                           @RequestParam(required = false) String lang) {
        permissions.require(ctx, Permissions.PORTAL_USE);
        PortalPort.PortalService service = requireService(ctx, key);
        if (service.noticeKey() == null) {
            throw ApiProblem.notFound("Portal service notice", key);
        }
        return requireCompliance().notice(ctx, service.noticeKey(), lang);
    }

    /** Owner decision 347: the notices sent to the caller's portal account (breach notices), newest first. */
    @GetMapping("/notifications")
    public ResponseEntity<List<PortalCompliancePort.PortalNotification>> notifications(RequestContext ctx) {
        permissions.require(ctx, Permissions.PORTAL_USE);
        PortalCompliancePort c = compliance.getIfAvailable();
        return Lists.ok(c == null ? List.of() : c.notifications(ctx));
    }

    @GetMapping("/services")
    public ResponseEntity<List<PortalPort.PortalService>> services(RequestContext ctx) {
        permissions.require(ctx, Permissions.PORTAL_USE);
        return Lists.ok(portal.services(ctx));
    }

    /**
     * SL-166 f2 / owner decision 63: the application form of one service of the caller's catalogue, with the tenant's
     * custom fields merged into the data schema on the server ({@link FormPort#effective}). Only a service the caller can
     * list is served, so {@code portal.use} never reads an arbitrary form key.
     */
    @GetMapping("/services/{key}/form")
    public FormPort.FormVersion serviceForm(RequestContext ctx, @PathVariable String key) {
        permissions.require(ctx, Permissions.PORTAL_USE);
        PortalPort.PortalService service = requireService(ctx, key);
        if (service.formKey() == null || service.formKey().isBlank()) {
            throw ApiProblem.notFound("Portal service form", key);
        }
        FormPort f = forms.getIfAvailable();
        if (f == null) {
            throw ApiProblem.notInstalled("forms", "sangam-rest forms");
        }
        return f.effective(ctx, service.formKey());
    }

    /**
     * A draft ({@code submit=false}) is kept as before and carries no consent. A submission to a service that names a
     * consent notice records the applicant's consent on the notice's current published version in the same transaction
     * as the application (owner decision 339): a refused consent starts nothing, and a failed start keeps no consent.
     */
    @PostMapping("/applications")
    public ResponseEntity<PortalPort.Application> apply(RequestContext ctx, @RequestBody PortalPort.ApplyRequest body) {
        permissions.require(ctx, Permissions.PORTAL_USE);
        if (body.serviceKey() == null || body.serviceKey().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "serviceKey is required");
        }
        if (!body.submit() && body.consent() != null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "a draft carries no consent; consent is given on submission");
        }
        PortalPort.ApplyRequest request = new PortalPort.ApplyRequest(body.serviceKey(),
                TypedVariables.fromJson(body.variables()), body.submit(), null);
        PortalPort.Application a;
        String noticeKey = body.submit() ? requireService(ctx, body.serviceKey()).noticeKey() : null;
        if (noticeKey == null) {
            if (body.consent() != null) {
                throw ApiProblem.conflict("NOTICE_NOT_CURRENT", "service " + body.serviceKey()
                        + " takes no consent through the portal: it names no consent notice");
            }
            a = portal.apply(ctx, request);
        } else {
            PortalCompliancePort c = requireCompliance();
            a = tx.inTx(() -> portal.apply(ctx, request,
                    c.recordConsent(ctx, body.serviceKey(), noticeKey, body.consent())));
        }
        Telemetry.businessKey(a.reference());
        return Lists.created("/api/v1/portal/applications/" + a.id(), a.id(), a);
    }

    @GetMapping("/applications")
    public ResponseEntity<List<PortalPort.Application>> applications(RequestContext ctx,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.PORTAL_USE);
        return Lists.ok(portal.applications(ctx, Lists.page(page, size)));
    }

    @GetMapping("/applications/{id}")
    public PortalPort.Application application(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.PORTAL_USE);
        return portal.application(ctx, id);
    }

    @PostMapping(value = "/applications/{id}/clarifications/{clarificationId}/reply",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PortalPort.Application> reply(RequestContext ctx, @PathVariable String id,
            @PathVariable String clarificationId, @RequestParam("text") String text,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) throws IOException {
        permissions.require(ctx, Permissions.PORTAL_USE);
        if (text == null || text.isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "text is required");
        }
        List<PortalPort.PortalFile> attached = new ArrayList<>();
        if (files != null) {
            if (files.size() > MAX_FILES) {
                throw ApiProblem.badRequest("VALIDATION_FAILED", "at most " + MAX_FILES + " files");
            }
            for (MultipartFile f : files) {
                attached.add(new PortalPort.PortalFile(f.getOriginalFilename(), f.getContentType(), f.getBytes()));
            }
        }
        return Lists.okRef(id, portal.reply(ctx, id, clarificationId, new PortalPort.ReplyRequest(text, attached)));
    }

    @PostMapping("/applications/{id}/withdraw")
    public ResponseEntity<PortalPort.Application> withdraw(RequestContext ctx, @PathVariable String id,
                                                           @RequestBody PortalPort.WithdrawRequest body) {
        permissions.require(ctx, Permissions.PORTAL_USE);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(id, portal.withdraw(ctx, id, body));
    }

    @PostMapping("/applications/{id}/appeal")
    public ResponseEntity<PortalPort.Application> appeal(RequestContext ctx, @PathVariable String id,
                                                         @RequestBody PortalPort.AppealRequest body) {
        permissions.require(ctx, Permissions.PORTAL_USE);
        if (body.grounds() == null || body.grounds().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "grounds are required");
        }
        return Lists.okRef(id, portal.appeal(ctx, id, body));
    }

    @GetMapping("/applications/{id}/outcome")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> outcome(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.PORTAL_USE);
        return DocumentController.download(portal.outcome(ctx, id));
    }

    @GetMapping("/grievances")
    public ResponseEntity<List<PortalPort.Grievance>> grievances(RequestContext ctx) {
        permissions.require(ctx, Permissions.PORTAL_USE);
        return Lists.ok(portal.grievances(ctx));
    }

    @PostMapping("/grievances")
    public ResponseEntity<PortalPort.GrievanceReceipt> fileGrievance(RequestContext ctx,
                                                                     @RequestBody PortalPort.GrievanceRequest body) {
        permissions.require(ctx, Permissions.PORTAL_USE);
        if (body.subject() == null || body.subject().isBlank() || body.text() == null || body.text().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "subject and text are required");
        }
        if (body.kind() != null && !body.kind().equals("GRIEVANCE") && !body.kind().equals("DATA_RIGHTS")) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "kind must be GRIEVANCE or DATA_RIGHTS");
        }
        PortalPort.GrievanceReceipt r = portal.fileGrievance(ctx, body);
        return Lists.created("/api/v1/portal/grievances", r.reference(), r);
    }
}
