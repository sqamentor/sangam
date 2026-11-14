// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.sangam.integration.dsc.DscPreparedStore;
import org.sangam.integration.dsc.DscSigningFlow;
import org.sangam.integration.signature.SignatureVerification;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.web.Lists;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner decision 165: DSC signing of a task's PDF through the signer's loopback agent (contract W4-M, board INFO
 * 2026-11-13T00:37:14Z; integrator ruling 00:50:58Z). Both steps need {@code task.write}; the task must be assigned to
 * the caller and not suspended; the document must be a PDF attached to the task or its process instance, not infected
 * and not waiting for its scan.
 *
 * <ul>
 *   <li>{@code POST /api/v1/tasks/{id}/dsc/prepare} {@code {documentId, certificateChain}} (Base64 DER, signer first)
 *       answers {@code {prepareId, signedAttributes, preparedDigestSha256, algorithm, expiresAt, documentId,
 *       documentVersion}}. The signer certificate must be the caller's own enrolment approved by an admin (owner
 *       decision 227, {@code DscCertificateController}; 422 {@code CERTIFICATE_NOT_ENROLLED} otherwise). The server
 *       keeps the prepared PDF, bound to tenant, task, document version, signer principal, signer certificate and
 *       prepareId.</li>
 *   <li>{@code POST /api/v1/tasks/{id}/dsc/complete} {@code {prepareId, signature, algorithm, certificateChain}}
 *       consumes the prepare (409 {@code DSC_PREPARE_NOT_USABLE} when unknown, expired, used or someone else's),
 *       verifies under decision 140 and answers {@code {outcome, reasonCode, retryable, evidence, documentId,
 *       signedDocumentVersion}}. Only VERIFIED_VALID stores the signed PDF as the document's next version, and only
 *       while the document is still at the signed version (owner decision 228, compare-and-set; otherwise 409
 *       {@code DOCUMENT_CHANGED} and the signature has to be redone); the outcome is recorded either way. Completing the task stays a separate step, gated on the recorded outcome.</li>
 * </ul>
 *
 * Without the opt-in wiring ({@code sangam.dsc.enabled}, which needs the optional module sangam-esign) both answer 501
 * {@code CAPABILITY_NOT_INSTALLED}: nothing is prepared and nothing is accepted.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class DscSigningController {

    /** Largest PDF that is prepared (the whole file is held while it is prepared). */
    public static final long MAX_DOCUMENT_BYTES = 50L * 1024 * 1024;

    public record PrepareRequest(String documentId, List<String> certificateChain) {
    }

    public record PrepareResponse(String prepareId, String signedAttributes, String preparedDigestSha256,
                                  String algorithm, Instant expiresAt, String documentId, int documentVersion) {
    }

    public record CompleteRequest(String prepareId, String signature, String algorithm, List<String> certificateChain) {
    }

    public record CompleteResponse(String outcome, String reasonCode, boolean retryable, Map<String, Object> evidence,
                                   String documentId, Integer signedDocumentVersion) {
    }

    private final ObjectProvider<DscSigningFlow> flows;
    private final TaskPort tasks;
    private final DocumentPort documents;
    private final PermissionPort permissions;

    public DscSigningController(ObjectProvider<DscSigningFlow> flows, TaskPort tasks, DocumentPort documents,
                                PermissionPort permissions) {
        this.flows = flows;
        this.tasks = tasks;
        this.documents = documents;
        this.permissions = permissions;
    }

    @PostMapping("/{id}/dsc/prepare")
    public ResponseEntity<PrepareResponse> prepare(RequestContext ctx, @PathVariable String id,
                                                   @RequestBody(required = false) PrepareRequest body) {
        permissions.require(ctx, Permissions.TASK_WRITE);
        permissions.require(ctx, Permissions.DOCUMENT_READ);
        DscSigningFlow flow = flow();
        if (body == null || blank(body.documentId()) || body.certificateChain() == null || body.certificateChain().isEmpty()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "documentId and certificateChain are required");
        }
        TaskPort.Task task = assignedTask(ctx, id);
        DocumentPort.Document d = documents.get(ctx, body.documentId());
        requireSignable(task, d);
        byte[] pdf = content(ctx, d);
        DscSigningFlow.PrepareResult r;
        try {
            r = flow.prepare(new DscSigningFlow.Binding(ctx.tenantId(), task.id(), d.id(), d.version(),
                    d.sha256().toLowerCase(Locale.ROOT), ctx.principalId()), pdf, body.certificateChain());
        } catch (DscSigningFlow.Rejected e) {
            throw ApiProblem.unprocessable(e.code(), e.getMessage());
        }
        return Lists.okRef(r.prepareId(), new PrepareResponse(r.prepareId(), r.signedAttributes(),
                r.preparedDigestSha256(), r.algorithm(), r.expiresAt(), d.id(), d.version()));
    }

    @PostMapping("/{id}/dsc/complete")
    public ResponseEntity<CompleteResponse> complete(RequestContext ctx, @PathVariable String id,
                                                     @RequestBody(required = false) CompleteRequest body) {
        permissions.require(ctx, Permissions.TASK_WRITE);
        permissions.require(ctx, Permissions.DOCUMENT_WRITE);
        DscSigningFlow flow = flow();
        if (body == null || blank(body.prepareId()) || blank(body.signature()) || blank(body.algorithm())
                || body.certificateChain() == null || body.certificateChain().isEmpty()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "prepareId, signature, algorithm and certificateChain are required");
        }
        TaskPort.Task task = assignedTask(ctx, id);
        DscSigningFlow.CompleteResult r;
        try {
            r = flow.complete(ctx.tenantId(), task.id(), ctx.principalId(), body.prepareId().trim(), body.signature(),
                    body.algorithm().trim(), body.certificateChain());
        } catch (DscSigningFlow.NotUsable e) {
            throw ApiProblem.conflict("DSC_PREPARE_NOT_USABLE", e.getMessage());
        }
        DscPreparedStore.Entry e = r.entry();
        SignatureVerification v = r.verification();
        if (v.outcome() != SignatureVerification.Outcome.VERIFIED_VALID) {
            return Lists.okRef(e.prepareId(), response(v, e, null));
        }
        DocumentPort.Document current = documents.get(ctx, e.documentId());
        if (current.version() != e.documentVersion() || current.sha256() == null
                || !current.sha256().equalsIgnoreCase(e.documentSha256())) {
            throw documentChanged(flow, e);
        }
        DocumentPort.Document signed;
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("signatureType", "DSC");
            meta.put("dscPrepareId", e.prepareId());
            meta.put("signedVersionOf", e.documentVersion());
            meta.put("signatureVerification", v.evidence());
            // owner decision 228: saved only while the document is still at the signed version (atomic in the port)
            signed = documents.newVersion(ctx, e.documentId(), e.documentVersion(), new DocumentPort.UploadRequest(
                    current.name(), "application/pdf", r.signedPdf(), null, null, null, meta));
        } catch (ApiProblem p) {
            if (DocumentPort.VERSION_CONFLICT.equals(p.code())) {
                throw documentChanged(flow, e);
            }
            flow.recordNotStored(e, "SIGNED_DOCUMENT_NOT_STORED");
            throw p;
        } catch (RuntimeException ex) {
            flow.recordNotStored(e, "SIGNED_DOCUMENT_NOT_STORED");
            throw ex;
        }
        flow.recordSigned(e, signed.version());
        return Lists.okRef(e.prepareId(), response(v, e, signed.version()));
    }

    /** Decision 228: a newer version exists; the signature is not stored and has to be redone from that version. */
    private static ApiProblem documentChanged(DscSigningFlow flow, DscPreparedStore.Entry e) {
        flow.recordNotStored(e, "DOCUMENT_CHANGED");
        return ApiProblem.conflict("DOCUMENT_CHANGED", "the document has a newer version than the one that was signed;"
                + " the signature was not stored, prepare and sign again");
    }

    private static CompleteResponse response(SignatureVerification v, DscPreparedStore.Entry e, Integer version) {
        return new CompleteResponse(v.outcome().name(), v.reasonCode(), v.retryable(), v.evidence(), e.documentId(),
                version);
    }

    private DscSigningFlow flow() {
        DscSigningFlow f = flows.getIfAvailable();
        if (f == null) {
            throw ApiProblem.notInstalled("DSC signing", "sangam-esign");
        }
        return f;
    }

    /** The live task, assigned to the caller and not suspended. */
    private TaskPort.Task assignedTask(RequestContext ctx, String id) {
        TaskPort.Task t = tasks.get(ctx, id).task();
        if (t.assignee() == null || !Objects.equals(t.assignee(), ctx.principalId())) {
            throw ApiProblem.forbidden("NOT_ASSIGNEE", "only the task's assignee can sign it");
        }
        if ("SUSPENDED".equalsIgnoreCase(t.state())) {
            throw ApiProblem.conflict("TASK_SUSPENDED", "the task is suspended");
        }
        return t;
    }

    static void requireSignable(TaskPort.Task task, DocumentPort.Document d) {
        boolean attached = Objects.equals(d.taskId(), task.id())
                || (d.processInstanceId() != null && Objects.equals(d.processInstanceId(), task.processInstanceId()));
        if (!attached) {
            throw ApiProblem.unprocessable("DOCUMENT_NOT_OF_TASK", "the document is not attached to this task or its"
                    + " process instance");
        }
        if (d.contentType() == null || !d.contentType().toLowerCase(Locale.ROOT).startsWith("application/pdf")) {
            throw ApiProblem.unprocessable("DOCUMENT_NOT_PDF", "only a PDF can be signed with a DSC");
        }
        if ("INFECTED".equalsIgnoreCase(d.scanStatus()) || "PENDING".equalsIgnoreCase(d.scanStatus())) {
            throw ApiProblem.conflict("DOCUMENT_NOT_CLEAN", "the document's malware scan is " + d.scanStatus());
        }
        if (d.sha256() == null || d.sha256().isBlank()) {
            throw ApiProblem.unprocessable("DOCUMENT_NO_CHECKSUM", "the document has no SHA-256");
        }
        if (d.size() > MAX_DOCUMENT_BYTES) {
            throw ApiProblem.unprocessable("DOCUMENT_TOO_LARGE", "the document is larger than " + MAX_DOCUMENT_BYTES
                    + " bytes");
        }
    }

    /** The exact version's bytes, checked against its recorded SHA-256. */
    private byte[] content(RequestContext ctx, DocumentPort.Document d) {
        byte[] pdf;
        try (InputStream in = documents.content(ctx, d.id(), d.version()).body().open()) {
            pdf = in.readNBytes((int) MAX_DOCUMENT_BYTES + 1);
        } catch (IOException e) {
            throw ApiProblem.unavailable("DOCUMENT_UNREADABLE", "the document content cannot be read", 5);
        }
        if (pdf.length > MAX_DOCUMENT_BYTES) {
            throw ApiProblem.unprocessable("DOCUMENT_TOO_LARGE", "the document is larger than " + MAX_DOCUMENT_BYTES
                    + " bytes");
        }
        if (!sha256Hex(pdf).equalsIgnoreCase(d.sha256())) {
            throw ApiProblem.conflict("DOCUMENT_CHANGED", "the document content does not match its recorded SHA-256");
        }
        return pdf;
    }

    static String sha256Hex(byte[] b) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
