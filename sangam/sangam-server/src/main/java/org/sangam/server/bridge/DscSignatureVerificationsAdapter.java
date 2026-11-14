// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.Objects;

import org.sangam.integration.dsc.DscSigningFlow;
import org.sangam.packs.port.SignatureVerifications;

/**
 * Owner decisions 236 and 237: the packs' DSC completion gate asks the server's DSC signing flow. The pack passes the
 * task's engine tenant, the task id, the document captured from the step's slot and the completing person
 * ({@code actingPerson}); the flow answers true only for a stored {@code VERIFIED_VALID} signature of exactly that
 * document, made at that task by that principal under their still-approved enrolment (decision 241).
 *
 * <p>No identifier is mapped: the DSC routes record {@code RequestContext.tenantId()} and
 * {@code RequestContext.principalId()}, the task route sets {@code actingPerson} to the same
 * {@code RequestContext.principalId()}, and the engine tenant of a task the caller can see is
 * {@code RequestContext.tenantId()} (the task queries filter {@code tenantIdIn(ctx.tenantId())}). A blank argument is
 * never asked and answers false, so nothing here can widen the check.
 *
 * <p>Decision 241: {@link #status} maps {@link DscSigningFlow#signatureStatus} name for name (VERIFIED,
 * ENROLMENT_REVOKED, NOT_SIGNED), so the pack gate can answer {@code 422 DSC_ENROLMENT_REVOKED} when the officer signed
 * but the signing enrolment was revoked afterwards; a blank argument answers NOT_SIGNED without asking.
 */
public final class DscSignatureVerificationsAdapter implements SignatureVerifications {

    private final SignatureVerifications flow;

    public DscSignatureVerificationsAdapter(DscSigningFlow flow) {
        this(over(Objects.requireNonNull(flow, "flow")));
    }

    /** The flow as the port: both questions go to the flow, the status mapped name for name. */
    static SignatureVerifications over(DscSigningFlow flow) {
        return new SignatureVerifications() {
            @Override
            public boolean hasVerifiedSignature(String tenantId, String taskId, String documentId, String principal) {
                return flow.hasVerifiedSignature(tenantId, taskId, documentId, principal);
            }

            @Override
            public Status status(String tenantId, String taskId, String documentId, String principal) {
                return map(flow.signatureStatus(tenantId, taskId, documentId, principal));
            }
        };
    }

    /** Name for name; a null answer is NOT_SIGNED (nothing verified). */
    static Status map(DscSigningFlow.SignatureStatus s) {
        if (s == null) {
            return Status.NOT_SIGNED;
        }
        return switch (s) {
            case VERIFIED -> Status.VERIFIED;
            case ENROLMENT_REVOKED -> Status.ENROLMENT_REVOKED;
            case NOT_SIGNED -> Status.NOT_SIGNED;
        };
    }

    /** Over any query with the flow's contract (tests). */
    DscSignatureVerificationsAdapter(SignatureVerifications flow) {
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    @Override
    public boolean hasVerifiedSignature(String tenantId, String taskId, String documentId, String principal) {
        if (blank(tenantId) || blank(taskId) || blank(documentId) || blank(principal)) {
            return false;
        }
        return flow.hasVerifiedSignature(tenantId, taskId, documentId, principal);
    }

    @Override
    public Status status(String tenantId, String taskId, String documentId, String principal) {
        if (blank(tenantId) || blank(taskId) || blank(documentId) || blank(principal)) {
            return Status.NOT_SIGNED;
        }
        return flow.status(tenantId, taskId, documentId, principal);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
