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

    /**
     * {@code language} is a language code of the tenant's jurisdiction pack (default: the pack default).
     * {@code declaredCategory} (owner decision 365) is {@code ADULT} or {@code CHILD}: required, an omitted or other
     * value refused by name, never defaulted; a declared child needs {@code guardianVerificationId}.
     * {@code affirmativeAction} (owner decision 369 (h)) is boxed and required: an omitted value is refused by name,
     * never read as false or true.
     */
    public record GiveConsentRequest(String principalRef, String noticeKey, Integer noticeVersion, String language,
                                     Set<String> purposeCodes, Boolean affirmativeAction, String channel,
                                     String guardianVerificationId, String consentManagerId, String cmArtefactId,
                                     String declaredCategory) {
    }

    public record WithdrawConsentRequest(Set<String> purposes) {
    }

    /**
     * Owner decision 360: a one-time code for a rights request, to an identifier the tenant accepts
     * ({@code rights.otp.identifier-types}, e.g. MOBILE or EMAIL); {@code language} a code of the pack (default: the
     * pack default).
     */
    public record OtpChallengeRequest(String identifierType, String identifierValue, String language) {
    }

    /** Owner decision 360: the code the data principal received. */
    public record OtpVerifyRequest(String code) {
    }

    /** Owner decision 361: a legal hold on a process root; {@code kind} RTI | AUDIT | LITIGATION | VIGILANCE | OTHER. */
    public record LegalHoldRequest(String kind, String reference, String reason) {
    }

    /** Owner decision 361: the release reason (the releaser is not the placer). */
    public record LegalHoldRelease(String reason) {
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

    /**
     * Owner decision 362: {@code rankAdequate} is the checker's attestation that the requesting officer's rank is
     * adequate under the cited provision; boxed so that an omitted value is refused by name, never read as false.
     */
    public record VerifyLawfulRequest(Boolean rankAdequate) {
    }

    public record RespondLawfulRequest(String responseRef, List<String> principalRefs, Set<String> dataCategories) {
    }

    public record ReasonBody(String reason) {
    }

    /** Owner decision 362: {@code independent} (the auditor's independence, an attestation) is required, never defaulted. */
    public record AuditEvidenceRequest(String kind, Instant periodFrom, Instant periodTo, Instant completedAt,
                                       String auditor, String accreditationRef, Boolean independent,
                                       List<String> findings, String reportHash, String authorityReportRef) {
    }

    /** Owner decision 362: {@code independent} (the assessor's independence, an attestation) is required, never defaulted. */
    public record ImpactAssessmentRequest(String activityId, String scope, List<String> risks, List<String> measures,
                                          String residualRisk, String assessor, Boolean independent,
                                          Instant assessedAt, String authorityReportRef) {
    }

    /**
     * Owner decision 362: the body of {@code POST /transfers/evaluate}, the input shape of
     * {@code CrossBorderTransferGuard.TransferRequest} with its two attestations boxed:
     * {@code recipientSameProtectionAttested} (the recipient gives the same protection) and
     * {@code recipientConditionMet} (the recipient meets the adequacy condition of the destination). An omitted one is
     * refused by name ({@code VALIDATION_FAILED}), never read as false.
     */
    public record EvaluateTransferRequest(String destinationCountry, String recipient, String purposeCode,
                                          Set<String> dataCategories, String approvalRef,
                                          Boolean recipientSameProtectionAttested, String mechanism,
                                          String impactAssessmentRef, Boolean recipientConditionMet) {
    }

    /**
     * Owner decision 362: the body of {@code POST /consent-managers/{cm}/artefacts}, the input shape of
     * {@code ConsentManagerPort.ConsentArtefact} with {@code noticeVersion} boxed: the artefact names the notice version
     * the principal consented to, and an omitted one is refused by name, never read as version 0.
     */
    public record ConsentArtefactRequest(String consentManagerId, String artefactId, String principalRef,
                                         String fiduciaryId, String noticeKey, Integer noticeVersion,
                                         String languageCode, Set<String> purposeCodes, Instant issuedAt,
                                         Instant expiresAt, String signature) {
    }

    public record SealLogsRequest(Instant from, Instant to) {
    }

    /** {@code POST /lawful-requests} response. */
    public record Created(String id) {
    }
}
