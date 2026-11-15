// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Citizen / applicant portal (C01–C06, PER-8, UP-22). The applicant is an external principal bound to the tenant by
 * the portal channel; they see only their own applications, and officer identities only as seat designations.
 * UNKNOWN_OUTCOME is never shown as "failed" (X.4.2.1).
 */
public interface PortalPort {

    /**
     * {@code noticeKey}: the consent notice of the service (field of its {@code services} registry entry). A service that
     * names one takes the applicant's consent on the notice's current published version with every submission (owner
     * decision 339); a service that names none takes no consent through the portal.
     */
    record PortalService(String key, String name, String nameHi, String description, String descriptionHi,
                         Integer slaDays, String fee, String formKey, List<String> documentsRequired, String noticeKey) {
    }

    record Clarification(String id, String question, Instant askedAt, String askedBySeat, Instant dueAt,
                         String reply, Instant repliedAt) {
    }

    record PortalMessage(Instant at, String text) {
    }

    /**
     * status: DRAFT | SUBMITTED | IN_PROGRESS | CLARIFICATION_SOUGHT | APPROVED | REJECTED | WITHDRAWN.
     * paymentStatus: NOT_REQUIRED | DUE | PAID | UNKNOWN_OUTCOME.
     */
    record Application(String id, String reference, String serviceKey, String serviceName, Instant submittedAt,
                       String stage, String stageLabel, String stageLabelHi, Instant slaDate, String status,
                       List<Clarification> clarifications, List<PortalMessage> messages, String outcomeDocumentId,
                       Instant appealDeadline, String paymentStatus) {
    }

    /**
     * A new application. {@code consent}: required on a submission ({@code submit=true}) to a service that names a
     * notice; a draft sends none. Optional on a request (SL-81): an omitted {@code submit} is false, which keeps the
     * application as a draft.
     */
    record ApplyRequest(String serviceKey, Map<String, Object> variables, boolean submit, PortalConsent consent) {

        /** Request binding: an omitted optional flag takes the documented default above, never a guess. */
        @JsonCreator
        public static ApplyRequest fromRequest(@JsonProperty("serviceKey") String serviceKey,
                                               @JsonProperty("variables") Map<String, Object> variables,
                                               @JsonProperty("submit") Boolean submit,
                                               @JsonProperty("consent") PortalConsent consent) {
            return new ApplyRequest(serviceKey, variables, submit != null && submit, consent);
        }
    }

    /**
     * The applicant's consent on the service's published notice (owner decisions 339, 341, 343): the notice key and the
     * version shown, the language it was shown in, the purposes ticked one by one (one checkbox per purpose, never one
     * bundled box), the age declaration ({@code ADULT}, or {@code CHILD} under the jurisdiction pack's age of majority)
     * and, for a declared child, the guardian verification of the parent's verifiable consent.
     */
    record PortalConsent(String noticeKey, Integer noticeVersion, String language, Set<String> purposeCodes,
                         String ageDeclaration, String guardianVerificationId) {
    }

    /** The consent the server recorded with a submission; set by the server, never taken from the request. */
    record ConsentRecord(String consentId, String ageDeclaration, String guardianVerificationId) {
    }

    record ReplyRequest(String text, List<PortalFile> files) {
    }

    record PortalFile(String name, String contentType, byte[] content) {
    }

    record WithdrawRequest(String reason) {
    }

    record AppealRequest(String grounds) {
    }

    /** kind: GRIEVANCE | DATA_RIGHTS (DPDP). */
    record Grievance(String id, String reference, String subject, String status, Instant filedAt, Instant dueAt) {
    }

    record GrievanceRequest(String subject, String text, String kind) {
    }

    record GrievanceReceipt(String reference) {
    }

    List<PortalService> services(RequestContext ctx);

    Application apply(RequestContext ctx, ApplyRequest request);

    /**
     * A submission whose consent the caller recorded in the same transaction ({@code consent} null when the service
     * takes none). An implementation that cannot keep the consent with the application refuses it (fail closed).
     */
    default Application apply(RequestContext ctx, ApplyRequest request, ConsentRecord consent) {
        if (consent != null) {
            throw ApiProblem.notInstalled("portal consent", getClass().getName());
        }
        return apply(ctx, request);
    }

    Page<Application> applications(RequestContext ctx, Page.PageRequest page);

    Application application(RequestContext ctx, String applicationId);

    Application reply(RequestContext ctx, String applicationId, String clarificationId, ReplyRequest request);

    Application withdraw(RequestContext ctx, String applicationId, WithdrawRequest request);

    Application appeal(RequestContext ctx, String applicationId, AppealRequest request);

    Download outcome(RequestContext ctx, String applicationId);

    List<Grievance> grievances(RequestContext ctx);

    GrievanceReceipt fileGrievance(RequestContext ctx, GrievanceRequest request);
}
