// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.compliance;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Request bodies of {@code /api/v1/compliance}. Where a compliance record cannot be bound directly from JSON (it holds
 * a resolved {@code JurisdictionPack.Language}, or the API method takes loose parameters), the body is one of these.
 */
public final class ComplianceRequests {

    private ComplianceRequests() {
    }

    public record PublishNoticeRequest(Set<String> purposes) {
    }

    /** {@code language} is a language code of the tenant's jurisdiction pack (default: the pack default). */
    public record GiveConsentRequest(String principalRef, String noticeKey, Integer noticeVersion, String language,
                                     Set<String> purposeCodes, boolean affirmativeAction, String channel,
                                     String guardianVerificationId, String consentManagerId, String cmArtefactId) {
    }

    public record WithdrawConsentRequest(Set<String> purposes) {
    }

    public record BreachRequest(Map<String, String> facts, Instant awareAt, Set<String> subjects, Set<String> roots,
                                String incidentType) {
    }

    public record IncidentRequest(String authority, String type, Instant occurredAt, Instant noticedAt,
                                  List<String> systems, String description) {
    }

    public record SubmitIncidentRequest(String actionsTaken) {
    }

    public record LawfulRequestBody(String authority, String officerName, String officerDesignation, String basis,
                                    String reference, String scope, Instant dueAt) {
    }

    public record VerifyLawfulRequest(boolean rankAdequate) {
    }

    public record RespondLawfulRequest(String responseRef, List<String> principalRefs, Set<String> dataCategories) {
    }

    public record ReasonBody(String reason) {
    }

    public record AuditEvidenceRequest(String kind, Instant periodFrom, Instant periodTo, Instant completedAt,
                                       String auditor, String accreditationRef, boolean independent,
                                       List<String> findings, String reportHash, String authorityReportRef) {
    }

    public record ImpactAssessmentRequest(String activityId, String scope, List<String> risks, List<String> measures,
                                          String residualRisk, String assessor, boolean independent,
                                          Instant assessedAt, String authorityReportRef) {
    }

    public record SealLogsRequest(Instant from, Instant to) {
    }

    /** {@code POST /lawful-requests} response. */
    public record Created(String id) {
    }
}
