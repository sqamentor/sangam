// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Citizen / applicant portal (C01–C06, PER-8, UP-22). The applicant is an external principal bound to the tenant by
 * the portal channel; they see only their own applications, and officer identities only as seat designations.
 * UNKNOWN_OUTCOME is never shown as "failed" (X.4.2.1).
 */
public interface PortalPort {

    record PortalService(String key, String name, String nameHi, String description, String descriptionHi,
                         Integer slaDays, String fee, String formKey, List<String> documentsRequired) {
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

    record ApplyRequest(String serviceKey, Map<String, Object> variables, boolean submit) {
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

    Page<Application> applications(RequestContext ctx, Page.PageRequest page);

    Application application(RequestContext ctx, String applicationId);

    Application reply(RequestContext ctx, String applicationId, String clarificationId, ReplyRequest request);

    Application withdraw(RequestContext ctx, String applicationId, WithdrawRequest request);

    Application appeal(RequestContext ctx, String applicationId, AppealRequest request);

    Download outcome(RequestContext ctx, String applicationId);

    List<Grievance> grievances(RequestContext ctx);

    GrievanceReceipt fileGrievance(RequestContext ctx, GrievanceRequest request);
}
