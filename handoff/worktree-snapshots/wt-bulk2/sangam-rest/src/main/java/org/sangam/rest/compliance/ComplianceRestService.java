// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.compliance;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.sangam.compliance.accountability.AccountabilityService;
import org.sangam.compliance.accountability.ContactDirectory;
import org.sangam.compliance.api.ComplianceApi;
import org.sangam.compliance.breach.IncidentReportingService;
import org.sangam.compliance.lawful.LawfulRequestService;
import org.sangam.compliance.lawful.PurposeDefinition;
import org.sangam.compliance.logs.LogArchiveService;
import org.sangam.compliance.rights.DataSubjectRightsService;
import org.sangam.core.security.SecurityIdentity;

/**
 * {@link ComplianceApi} plus the endpoints its javadoc names but its methods do not carry: lawful-request maker-checker
 * steps (verify / approve / respond / reject), impact-assessment evidence, log sealing, and the read endpoints the UI
 * needs (purposes, contacts, a principal's rights requests, one incident).
 */
public interface ComplianceRestService extends ComplianceApi {

    List<PurposeDefinition> purposes(SecurityIdentity id);

    /** Builds the module's GiveConsent with the language code resolved against the tenant's jurisdiction pack. */
    org.sangam.compliance.consent.ConsentService.GiveConsent toGiveConsent(SecurityIdentity id,
                                                                           ComplianceRequests.GiveConsentRequest r);

    /** The data principal of a consent (owner check for withdrawal). */
    String consentPrincipal(SecurityIdentity id, String consentId);

    List<ContactDirectory.Contact> contacts(SecurityIdentity id);

    List<DataSubjectRightsService.RightsRequest> rightsRequestsOf(SecurityIdentity id, String principalRef);

    IncidentReportingService.Incident incident(SecurityIdentity id, String incidentId);

    LawfulRequestService.LawfulRequest receiveLawfulRequest(SecurityIdentity id, ComplianceRequests.LawfulRequestBody body);

    LawfulRequestService.LawfulRequest lawfulRequest(SecurityIdentity id, String requestId);

    LawfulRequestService.LawfulRequest verifyLawfulRequest(SecurityIdentity id, String requestId, boolean rankAdequate);

    LawfulRequestService.LawfulRequest approveLawfulRequest(SecurityIdentity id, String requestId);

    LawfulRequestService.LawfulRequest respondLawfulRequest(SecurityIdentity id, String requestId, String responseRef,
                                                            List<String> principalRefs, Set<String> dataCategories);

    LawfulRequestService.LawfulRequest rejectLawfulRequest(SecurityIdentity id, String requestId, String reason);

    AccountabilityService.AuditEvidence recordAudit(SecurityIdentity id, ComplianceRequests.AuditEvidenceRequest r);

    AccountabilityService.ImpactAssessment recordImpactAssessment(SecurityIdentity id,
                                                                  ComplianceRequests.ImpactAssessmentRequest r);

    LogArchiveService.Segment sealLogs(SecurityIdentity id, Instant from, Instant to);
}
