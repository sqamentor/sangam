// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.ports;

import org.sangam.compliance.jurisdiction.JurisdictionPack.Language;

import java.net.URI;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Outbound ports of the compliance module. The module never duplicates a capability another module owns: erasure,
 * tombstones and legal holds come from {@code sangam-integration} ({@code adapters.*} wrap them), messages go through
 * the integration connectors (DLT SMS, gov.in e-mail) that the integrator binds to {@link PrincipalNotifier}, and
 * workflow starts go through the engine adapter. Each port has a "not configured" default so the module runs
 * air-gapped (ARCHITECTURE §1.9); the evidence report shows a gap for every port left at its default.
 */
public final class CompliancePorts {

    private CompliancePorts() {
    }

    // ---------------------------------------------------------------- reuse of sangam-integration (VI.5)

    /** Legal holds (R61, VI.5.1). Implemented by {@code adapters.IntegrationLegalHoldAdapter} over {@code LegalHoldStore}. */
    public interface LegalHoldPort {
        boolean isHeld(Connection c, String tenantId, String rootId);

        List<String> activeHoldIds(Connection c, String tenantId, String rootId);

        void place(Connection c, String tenantId, String rootId, String holdId, String reason, String actor);

        boolean release(Connection c, String tenantId, String rootId, String holdId, String actor);
    }

    /** Outcome of an erasure through the integration module. */
    public enum ErasureStatus { ERASED, REFUSED_LEGAL_HOLD, FAILED }

    public record ErasureOutcome(ErasureStatus status, Map<String, Integer> scrubbed, String detail) {
    }

    /**
     * Erasure of an instance tree with tombstones and audit-payload scrubbing (VI.5.1). Implemented by
     * {@code adapters.IntegrationErasureAdapter} over {@code ErasureService}; it opens its own engine transaction.
     */
    public interface ErasurePort {
        ErasureOutcome erase(String tenantId, String rootId, List<String> correlationKeys, String actor, String reason);
    }

    // ---------------------------------------------------------------- principals

    /** A message to a data principal (pre-erasure notice, breach intimation, rights response, legacy notice). */
    public record PrincipalMessage(String tenantId, String principalRef, String kind, Language language,
                                   String subject, String body, Map<String, String> attributes) {
    }

    /**
     * Sends to the principal's user account or registered communication mode (breach intimation, pre-erasure notice). The
     * integrator binds it to the outbox with the integration connectors (SMS, e-mail; class (b)/(c), exactly once).
     */
    public interface PrincipalNotifier {
        /** @return a delivery reference; the call happens inside the caller's transaction (outbox write). */
        String send(Connection c, PrincipalMessage message);

        PrincipalNotifier NONE = (c, m) -> {
            throw new IllegalStateException("PrincipalNotifier is not configured");
        };
    }

    /** The principal's facts the module needs: age category and the language she chose. */
    public enum PrincipalCategory { ADULT, CHILD, PERSON_WITH_DISABILITY_WITH_GUARDIAN, UNKNOWN }

    public interface PrincipalDirectory {
        PrincipalCategory category(Connection c, String tenantId, String principalRef);

        Optional<Language> preferredLanguage(Connection c, String tenantId, String principalRef);

        PrincipalDirectory NONE = new PrincipalDirectory() {
            @Override
            public PrincipalCategory category(Connection c, String tenantId, String principalRef) {
                return PrincipalCategory.UNKNOWN;
            }

            @Override
            public Optional<Language> preferredLanguage(Connection c, String tenantId, String principalRef) {
                return Optional.empty();
            }
        };
    }

    /**
     * Authenticates a requester against the identifier the controller published (username, application reference,
     * enrolment id, e-mail, mobile, licence number ...), e.g. OTP or SSO.
     */
    public interface PrincipalAuthenticator {
        /** @return the principal reference, or empty when the proof does not match. */
        Optional<String> authenticate(Connection c, String tenantId, String identifierType, String identifierValue,
                                      Map<String, String> proof);
    }

    /** The personal data a workflow holds about a principal (owned by the packs / engine variables / document store). */
    public interface PersonalDataPort {
        /** Summary of the personal data processed, as label → value description (masked where sensitive). */
        Map<String, Object> summarise(Connection c, String tenantId, String principalRef);

        /** Applies accepted corrections; returns the fields changed. */
        List<String> correct(Connection c, String tenantId, String principalRef, Map<String, Object> corrections, String actor);

        /** Roots (process instance trees) that hold the principal's data, for erasure. */
        List<String> rootsOf(Connection c, String tenantId, String principalRef);

        /** Correlation / business keys of those roots (inbox scrubbing in {@code ErasureService}). */
        List<String> correlationKeysOf(Connection c, String tenantId, String principalRef);
    }

    // ---------------------------------------------------------------- guardian verification

    public record AdultVerification(boolean identifiable, boolean adult, String issuer, String tokenRef, String detail) {
    }

    /**
     * Verifies that the person consenting for a child is an identifiable adult. {@code methodCode} is one of the pack's
     * {@code verification.child} methods (IN: DPDP r.10 — details held, voluntary details, virtual token, DigiLocker).
     */
    public interface AdultIdentityVerifier {
        AdultVerification verifyAdult(String tenantId, String personRef, String methodCode, Map<String, String> evidence);
    }

    public record GuardianshipVerification(boolean valid, String authority, String orderRef, LocalDate validUntil, String detail) {
    }

    /** Verifies a lawful guardian's appointment; {@code appointedBy} is one of the pack's {@code verification.guardian} codes. */
    public interface GuardianshipVerifier {
        GuardianshipVerification verify(String tenantId, String guardianRef, String principalRef, String appointedBy,
                                        String orderRef, Map<String, String> evidence);
    }

    // ---------------------------------------------------------------- processors and workflows

    /** Causes processors to cease processing / erase on withdrawal or purpose completion. */
    public interface ProcessorNotifier {
        void cease(Connection c, String tenantId, String principalRef, Set<String> purposeCodes, String reason);

        ProcessorNotifier NONE = (c, t, p, s, r) -> {
        };
    }

    /** Starts a compliance BPMN process (engine adapter); returns the process instance id. */
    public interface WorkflowStarter {
        String start(Connection c, String tenantId, String processKey, String businessKey, Map<String, Object> variables);

        WorkflowStarter NONE = (c, t, k, b, v) -> null;
    }

    // ---------------------------------------------------------------- regulators

    public record AuthoritySubmission(String tenantId, String authorityCode, String caseId, String kind, Map<String, Object> content) {
    }

    /** Breach notifications and reports to the supervisory authority named by the pack (IN: Data Protection Board). */
    public interface SupervisoryAuthorityGateway {
        /** @return the authority's acknowledgement reference. */
        String submit(AuthoritySubmission submission);
    }

    public record IncidentSubmission(String authorityCode, String tenantId, String incidentId, String recipient, boolean followUp, Map<String, Object> form) {
    }

    /** Incident reporting to an incident authority of the pack (IN: CERT-In, state CERTs) by e-mail or portal. */
    public interface IncidentAuthorityGateway {
        String submit(IncidentSubmission submission);
    }

    // ---------------------------------------------------------------- logs, geography, evidence

    /** Archive store for sealed log segments; must be located in the jurisdiction's home country when the pack requires log residency. */
    public interface LogArchiveStore {
        /** @return storage URI. */
        URI store(String tenantId, String segmentId, byte[] content, String region);

        byte[] read(URI uri);

        void delete(URI uri);

        /** ISO 3166 alpha-2 country of the store (verified against the store's own metadata). */
        String country();
    }

    /** Resolves the country of a host / endpoint (connector egress, transfer checks). */
    public interface GeoResolver {
        Optional<String> countryOf(URI endpoint);

        GeoResolver NONE = u -> Optional.empty();
    }

    /** Vault for national identifiers (IN: Aadhaar Data Vault): returns a reference key; the number stays outside Sangam. */
    public interface NationalIdVault {
        String referenceKey(String tenantId, String identifierType, String number);
    }

    /** Facts about the deployment for the pack's security checklist. */
    public interface SecurityEvidence {
        /** Value of a named evidence fact, e.g. {@code tls.enabled}, {@code mfa.enforced}, {@code session.idle-minutes}. */
        Optional<String> fact(String tenantId, String name);

        /** Date of the last occurrence of a named activity, e.g. {@code vapt}, {@code backup.restore-test}. */
        Optional<Instant> lastDone(String tenantId, String activity);

        SecurityEvidence NONE = new SecurityEvidence() {
            @Override
            public Optional<String> fact(String tenantId, String name) {
                return Optional.empty();
            }

            @Override
            public Optional<Instant> lastDone(String tenantId, String activity) {
                return Optional.empty();
            }
        };
    }
}
