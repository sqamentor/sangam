// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance;

import org.sangam.compliance.accountability.AccountabilityService;
import org.sangam.compliance.accountability.ContactDirectory;
import org.sangam.compliance.breach.BreachIncidentService;
import org.sangam.compliance.breach.IncidentReportingService;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.consent.ChildDataGuard;
import org.sangam.compliance.consent.ConsentManagerGateway;
import org.sangam.compliance.consent.ConsentNoticeService;
import org.sangam.compliance.consent.ConsentService;
import org.sangam.compliance.consent.NoticeTemplate;
import org.sangam.compliance.consent.PurposeBindingGuard;
import org.sangam.compliance.consent.VerifiableParentalConsentService;
import org.sangam.compliance.identity.NationalIdGuard;
import org.sangam.compliance.lawful.DisclosureRegister;
import org.sangam.compliance.lawful.LawfulRequestService;
import org.sangam.compliance.lawful.LegitimateUseRegistry;
import org.sangam.compliance.lawful.ProcessingRegister;
import org.sangam.compliance.logs.AuditIntegrityVerifier;
import org.sangam.compliance.logs.LogArchiveService;
import org.sangam.compliance.logs.NtpTimeSourceVerifier;
import org.sangam.compliance.ports.CompliancePorts;
import org.sangam.compliance.report.ComplianceEvidenceReportGenerator;
import org.sangam.compliance.retention.RetentionScheduler;
import org.sangam.compliance.rights.DataSubjectRightsService;
import org.sangam.compliance.rights.NominationService;
import org.sangam.compliance.security.SecurityPracticesChecklist;
import org.sangam.compliance.transfer.CrossBorderTransferGuard;
import org.sangam.compliance.transfer.DataLocalisationPolicy;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;

/**
 * Wiring facade: builds every compliance service for one database. The integrator supplies the ports (the
 * integration adapters for legal holds and erasure, connectors for notifications and authorities, the engine for
 * workflow starts) in {@link Ports}; any port left null runs in its "not configured" mode and shows as a report gap.
 *
 * <pre>{@code
 * JurisdictionRegistry packs = JurisdictionRegistry.discover();
 * ComplianceConfig.Provider cfg = tenant -> ComplianceConfig.fromProperties(packs, propertiesOf(tenant));
 * SangamCompliance cmp = new SangamCompliance(Dialect.POSTGRESQL, Transactions.of(engineAdapter), cfg, ports);
 * ComplianceRuntime.install(cmp);
 * }</pre>
 */
public final class SangamCompliance {

    /** Outbound ports; nulls allowed. */
    public record Ports(CompliancePorts.LegalHoldPort legalHolds, CompliancePorts.ErasurePort erasure,
                        CompliancePorts.PrincipalNotifier notifier, CompliancePorts.PrincipalDirectory principals,
                        CompliancePorts.PrincipalAuthenticator authenticator, CompliancePorts.PersonalDataPort personalData,
                        CompliancePorts.AdultIdentityVerifier adultVerifier, CompliancePorts.GuardianshipVerifier guardianshipVerifier,
                        CompliancePorts.ProcessorNotifier processors, CompliancePorts.WorkflowStarter workflows,
                        CompliancePorts.SupervisoryAuthorityGateway supervisoryAuthority, CompliancePorts.IncidentAuthorityGateway incidentAuthority,
                        CompliancePorts.LogArchiveStore logArchive, CompliancePorts.GeoResolver geo, CompliancePorts.NationalIdVault nationalIdVault,
                        CompliancePorts.SecurityEvidence securityEvidence, NtpTimeSourceVerifier.SntpClient sntp) {
    }

    public final Dialect dialect;
    public final Transactions tx;
    public final ComplianceConfig.Provider config;
    public final ComplianceAudit audit;
    public final NoticeTemplate.Catalogues templates = new NoticeTemplate.Catalogues();
    public final LegitimateUseRegistry purposes;
    public final ProcessingRegister processing;
    public final DisclosureRegister disclosures;
    public final LawfulRequestService lawfulRequests;
    public final ContactDirectory contacts;
    public final AccountabilityService accountability;
    public final ConsentNoticeService notices;
    public final VerifiableParentalConsentService guardians;
    public final ConsentService consents;
    public final ConsentManagerGateway consentManagers;
    public final ChildDataGuard children;
    public final PurposeBindingGuard purposeBinding;
    public final RetentionScheduler retention;
    public final NominationService nominations;
    public final DataSubjectRightsService rights;
    public final IncidentReportingService incidents;
    public final BreachIncidentService breaches;
    public final org.sangam.compliance.breach.BreachNotificationPlanner breachPlans;
    public final org.sangam.compliance.rights.RestrictionService restrictions;
    public final LogArchiveService logs;
    public final NtpTimeSourceVerifier ntp;
    public final AuditIntegrityVerifier integrity;
    public final DataLocalisationPolicy localisation;
    public final CrossBorderTransferGuard transfers;
    public final NationalIdGuard nationalIds;
    public final SecurityPracticesChecklist checklist;
    public final ComplianceEvidenceReportGenerator reports;

    public SangamCompliance(Dialect dialect, Transactions tx, ComplianceConfig.Provider config, Ports p) {
        this.dialect = dialect;
        this.tx = tx;
        this.config = config;
        AuditChain chain = new AuditChain(dialect);
        this.audit = new ComplianceAudit(chain);
        CompliancePorts.LegalHoldPort holds = p.legalHolds() != null ? p.legalHolds() : NO_HOLDS;
        CompliancePorts.ErasurePort erasure = p.erasure() != null ? p.erasure()
                : (t, r, k, a, why) -> new CompliancePorts.ErasureOutcome(CompliancePorts.ErasureStatus.FAILED, java.util.Map.of(), "ErasurePort not configured");
        purposes = new LegitimateUseRegistry(dialect, audit, config);
        processing = new ProcessingRegister(dialect, audit, purposes, config);
        disclosures = new DisclosureRegister(dialect, audit);
        lawfulRequests = new LawfulRequestService(dialect, audit, config, disclosures);
        contacts = new ContactDirectory(dialect, audit, config);
        accountability = new AccountabilityService(dialect, audit, config, contacts, processing);
        notices = new ConsentNoticeService(dialect, audit, config, templates, purposes, contacts, processing);
        guardians = new VerifiableParentalConsentService(dialect, audit, config, p.adultVerifier(), p.guardianshipVerifier());
        consents = new ConsentService(dialect, audit, config, notices, purposes, guardians, p.principals(), p.processors());
        consentManagers = new ConsentManagerGateway(config, consents);
        children = new ChildDataGuard(audit);
        purposeBinding = new PurposeBindingGuard(audit, config, purposes, consents, children, p.principals());
        retention = new RetentionScheduler(dialect, tx, audit, config, purposes, holds, erasure, p.notifier(), p.principals(),
                p.personalData(), templates);
        consents.addWithdrawalListener(retention);
        nominations = new NominationService(dialect, audit);
        rights = new DataSubjectRightsService(dialect, tx, audit, config, p.authenticator(), nominations, consents, purposes, processing,
                disclosures, p.personalData(), retention, holds, erasure, contacts, p.workflows());
        incidents = new IncidentReportingService(dialect, audit, config, contacts, p.incidentAuthority(), p.workflows());
        breaches = new BreachIncidentService(dialect, audit, config, p.supervisoryAuthority(), p.notifier(), p.principals(), holds, incidents,
                contacts, templates, p.workflows());
        breachPlans = new org.sangam.compliance.breach.BreachNotificationPlanner(dialect, audit, config, breaches);
        restrictions = new org.sangam.compliance.rights.RestrictionService(dialect, audit);
        rights.restrictions(restrictions);
        disclosures.restrictionCheck((c, t, pr, rec, pc) -> restrictions.blocking(c, t, pr, rec, pc).map(x -> x.restrictionId()));
        logs = new LogArchiveService(dialect, audit, config, p.logArchive(), holds);
        ntp = new NtpTimeSourceVerifier(dialect, audit, config, p.sntp());
        integrity = new AuditIntegrityVerifier(chain, audit);
        incidents.evidenceContext((c, t) -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>(logs.describe(c, t));
            m.put("timeSource", ntp.latest(c, t).map(x -> (Object) x).orElse("no check recorded"));
            return m;
        });
        localisation = new DataLocalisationPolicy(config);
        transfers = new CrossBorderTransferGuard(dialect, audit, config, localisation, p.geo());
        nationalIds = new NationalIdGuard(config, p.nationalIdVault());
        checklist = new SecurityPracticesChecklist(config, p.securityEvidence());
        reports = new ComplianceEvidenceReportGenerator(dialect, audit, new ComplianceEvidenceReportGenerator.Sources(config, purposes,
                processing, notices, consents, rights, retention, breaches, incidents, lawfulRequests, logs, ntp, integrity, accountability,
                contacts, localisation, transfers, checklist));
    }

    /** Fail-closed default: without the integration legal-hold store every root counts as held, so nothing is erased. */
    static final CompliancePorts.LegalHoldPort NO_HOLDS = new CompliancePorts.LegalHoldPort() {
        @Override
        public boolean isHeld(java.sql.Connection c, String tenantId, String rootId) {
            return true;
        }

        @Override
        public java.util.List<String> activeHoldIds(java.sql.Connection c, String tenantId, String rootId) {
            return java.util.List.of("LEGAL_HOLD_PORT_NOT_CONFIGURED");
        }

        @Override
        public void place(java.sql.Connection c, String tenantId, String rootId, String holdId, String reason, String actor) {
        }

        @Override
        public boolean release(java.sql.Connection c, String tenantId, String rootId, String holdId, String actor) {
            return false;
        }
    };
}
