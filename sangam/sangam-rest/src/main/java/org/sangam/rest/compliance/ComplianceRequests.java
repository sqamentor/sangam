// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.compliance;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sangam.compliance.consent.ChildSafeguards.ProcessingFlag;
import org.sangam.compliance.retention.RetentionSchedule;

/**
 * Request bodies of {@code /api/v1/compliance}. Where a compliance record cannot be bound directly from JSON (it holds
 * a resolved {@code JurisdictionPack.Language}, or the API method takes loose parameters), the body is one of these.
 */
public final class ComplianceRequests {

    private ComplianceRequests() {
    }

    public record PublishNoticeRequest(Set<String> purposes) {
    }

    /**
     * The body of {@code PUT /purposes/{code}} (integrator finding on CI run 43717963671, 2026-11-15): the input shape of
     * {@code PurposeDefinition}. The two legal attestations, {@code standardsAttested} and each data item's
     * {@code sensitive}, and {@code active} (whether processing under the purpose may start: consent, notices and
     * purpose binding refuse an inactive purpose) are explicit choices of the client: boxed here so that an omitted value
     * is refused by name ({@code VALIDATION_FAILED}, the field named) and never defaulted, and required in the contract.
     * {@code version} is not an input: the registry assigns it (previous + 1).
     */
    public record DefinePurposeRequest(String code, Map<String, String> names, Map<String, String> descriptions,
                                       List<PurposeDataItem> dataItems, String basis, String legalRef, String subGround,
                                       String registerRef, Boolean standardsAttested, Set<String> exemptions,
                                       String exemptionRef, Set<String> childExemptions, Set<ProcessingFlag> flags,
                                       RetentionSchedule.Trigger retentionTrigger, Duration retention, String retentionClass,
                                       String recordClass, Duration recordRetention, String recordRetentionRef,
                                       Boolean active, String specialCondition) {
    }

    /** One itemised personal-data item of a purpose; {@code sensitive} is an explicit attestation (see above). */
    public record PurposeDataItem(String code, Map<String, String> names, Boolean sensitive) {
    }

    /**
     * The body of {@code POST /contacts/{role}}: {@code ContactDirectory.Contact} with {@code resident} boxed. The
     * residency attestation is given by the client; an omitted one is "not attested" (never assumed), which
     * {@code ContactDirectory.set} refuses for a role the pack marks {@code mustBeResident} (IN: the DPO, DPDP s.10(2)(a)).
     * {@code role} is taken from the path.
     */
    public record ContactRequest(String role, String name, String designation, String organisation, String email,
                                 String phone, String mobile, String address, Boolean resident, Instant validFrom) {
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

    /**
     * Owner decision 338: the body of {@code POST /consents/search} and {@code POST /rights-requests/search}. The
     * principal reference travels in the body, never in the URL (access logs, proxies and browser history keep URLs).
     * A blank {@code subject} is the caller's own records.
     */
    public record SubjectSearch(String subject) {
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
