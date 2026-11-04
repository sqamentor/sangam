// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.consent;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.ports.CompliancePorts.AdultIdentityVerifier;
import org.sangam.compliance.ports.CompliancePorts.AdultVerification;
import org.sangam.compliance.ports.CompliancePorts.GuardianshipVerification;
import org.sangam.compliance.ports.CompliancePorts.GuardianshipVerifier;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Verifiable consent of a parent for a child and of a lawful guardian for a person with disability (table
 * {@code CMP_GUARDIAN_VERIFICATION}). The admissible methods are the pack's {@code verification.child} and
 * {@code verification.guardian} lists (IN: DPDP s.9(1), r.10 — details already held, voluntary details, virtual token,
 * DigiLocker; r.11 — court, RPwD Act s.15 authority, National Trust Act s.13 committee; CMP-029, CMP-068 … CMP-070).
 * The check runs through {@link AdultIdentityVerifier} / {@link GuardianshipVerifier}. A child's verification is valid
 * until the pack's age of majority ({@code childAgeYears}) when the date of birth is known. Only references are stored.
 */
public final class VerifiableParentalConsentService {

    public enum Kind { CHILD, PERSON_WITH_DISABILITY }

    public enum Status { VERIFIED, REJECTED, REVOKED }

    public record Verification(String verificationId, String principalRef, String guardianRef, Kind kind, String method,
                               Status status, Instant verifiedAt, LocalDate validUntil) {
        public boolean validAt(Instant at) {
            return status == Status.VERIFIED && (validUntil == null || !at.isAfter(validUntil.atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
    }

    private final Dialect dialect;
    private final ComplianceAudit audit;
    private final AdultIdentityVerifier adults;
    private final GuardianshipVerifier guardianships;
    private final ComplianceConfig.Provider config;

    public VerifiableParentalConsentService(Dialect dialect, ComplianceAudit audit, ComplianceConfig.Provider config,
                                            AdultIdentityVerifier adults, GuardianshipVerifier guardianships) {
        this.config = config;
        this.dialect = dialect;
        this.audit = audit;
        this.adults = adults;
        this.guardianships = guardianships;
    }

    /**
     * Verifies the parent of a child.
     *
     * @param childDateOfBirth optional; when given, the verification ends at the age of majority
     * @param parentDeclaration the parent declared being the child's parent (recorded as part of the evidence)
     */
    public Verification verifyParent(Connection c, String tenantId, String childRef, String parentRef,
                                     String method, Map<String, String> evidence, LocalDate childDateOfBirth,
                                     boolean parentDeclaration, String actor) {
        Sql.requireText(childRef, "childRef");
        Sql.requireText(parentRef, "parentRef");
        JurisdictionPack pack = config.forTenant(tenantId).pack;
        if (method == null || pack.childVerification().stream().noneMatch(m -> m.code().equals(method))) {
            throw ComplianceException.invalid("verification method must be one of " + pack.childVerification().stream()
                    .map(JurisdictionPack.VerificationMethod::code).toList());
        }
        if (adults == null) {
            throw new IllegalStateException("AdultIdentityVerifier is not configured");
        }
        AdultVerification v = adults.verifyAdult(tenantId, parentRef, method, evidence == null ? Map.of() : evidence);
        boolean ok = v != null && v.identifiable() && v.adult() && parentDeclaration;
        LocalDate until = childDateOfBirth == null || !pack.hasLimit(ComplianceConfig.CHILD_AGE) ? null
                : childDateOfBirth.plusYears(pack.limit(ComplianceConfig.CHILD_AGE, 0));
        String reason = v == null ? "no verifier result" : !parentDeclaration ? "no parental declaration" : v.detail();
        return store(c, tenantId, childRef, parentRef, Kind.CHILD, method, v == null ? null : v.issuer(),
                v == null ? null : v.tokenRef(), null, null, ok, until, reason, actor);
    }

    /** Verifies the lawful guardian of a person with disability. */
    public Verification verifyLawfulGuardian(Connection c, String tenantId, String principalRef, String guardianRef,
                                             String appointedBy, String orderRef, Map<String, String> evidence,
                                             String actor) {
        Sql.requireText(principalRef, "principalRef");
        Sql.requireText(guardianRef, "guardianRef");
        Sql.requireText(orderRef, "orderRef");
        JurisdictionPack pack = config.forTenant(tenantId).pack;
        if (appointedBy == null || pack.guardianVerification().stream().noneMatch(m -> m.code().equals(appointedBy))) {
            throw ComplianceException.invalid("the appointing authority must be one of " + pack.guardianVerification().stream()
                    .map(JurisdictionPack.VerificationMethod::code).toList());
        }
        if (guardianships == null) {
            throw new IllegalStateException("GuardianshipVerifier is not configured");
        }
        GuardianshipVerification v = guardianships.verify(tenantId, guardianRef, principalRef, appointedBy, orderRef,
                evidence == null ? Map.of() : evidence);
        boolean ok = v != null && v.valid();
        return store(c, tenantId, principalRef, guardianRef, Kind.PERSON_WITH_DISABILITY, appointedBy,
                v == null ? null : v.authority(), null, appointedBy, orderRef, ok, v == null ? null : v.validUntil(),
                v == null ? "no verifier result" : v.detail(), actor);
    }

    /** The valid verification, or {@code GUARDIAN_CONSENT_REQUIRED}. */
    public Verification requireValid(Connection c, String tenantId, String principalRef, String verificationId) {
        Verification v = find(c, tenantId, verificationId).orElseThrow(() ->
                new ComplianceException(Code.GUARDIAN_CONSENT_REQUIRED, "no guardian verification " + verificationId));
        if (!v.principalRef().equals(principalRef) || !v.validAt(Sql.now(c, dialect))) {
            throw new ComplianceException(Code.GUARDIAN_CONSENT_REQUIRED, "guardian verification is not valid for this principal");
        }
        return v;
    }

    public Optional<Verification> currentFor(Connection c, String tenantId, String principalRef) {
        Instant now = Sql.now(c, dialect);
        return Jdbc.query(c, SELECT + " WHERE tenant_id=? AND principal_ref=? AND status='VERIFIED' ORDER BY verified_at DESC",
                VerifiableParentalConsentService::map, tenantId, principalRef).stream().filter(v -> v.validAt(now)).findFirst();
    }

    public void revoke(Connection c, String tenantId, String verificationId, String reason, String actor) {
        Verification v = find(c, tenantId, verificationId).orElseThrow(() -> ComplianceException.notFound("verification", verificationId));
        Jdbc.update(c, "UPDATE CMP_GUARDIAN_VERIFICATION SET status='REVOKED', detail=? WHERE tenant_id=? AND verification_id=?",
                reason, tenantId, verificationId);
        audit.principal(c, tenantId, v.principalRef(), ComplianceAudit.GUARDIAN_REJECTED,
                Sql.kv("verification", verificationId, "revoked", true, "actor", actor));
    }

    public Optional<Verification> find(Connection c, String tenantId, String verificationId) {
        return Jdbc.one(c, SELECT + " WHERE tenant_id=? AND verification_id=?", VerifiableParentalConsentService::map,
                tenantId, verificationId);
    }

    private Verification store(Connection c, String tenantId, String principalRef, String guardianRef, Kind kind, String method,
                               String issuer, String tokenRef, String appointment, String orderRef, boolean ok,
                               LocalDate validUntil, String detail, String actor) {
        String id = Sql.newId();
        Instant now = Sql.now(c, dialect);
        Jdbc.update(c, "INSERT INTO CMP_GUARDIAN_VERIFICATION(tenant_id, verification_id, principal_ref, guardian_ref, kind, method,"
                        + " issuer, token_ref, appointment, order_ref, status, verified_at, valid_until, verified_by, detail)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, id, principalRef, guardianRef, kind, method, issuer, tokenRef, appointment, orderRef,
                ok ? Status.VERIFIED : Status.REJECTED, now, validUntil == null ? null : validUntil.toString(), actor, detail);
        Map<String, Object> ev = Sql.kv("verification", id, "kind", kind.name(), "method", method, "issuer", issuer,
                "outcome", ok ? "VERIFIED" : "REJECTED", "guardian", ComplianceAudit.principalToken(tenantId, guardianRef), "actor", actor);
        audit.principal(c, tenantId, principalRef, ok ? ComplianceAudit.GUARDIAN_VERIFIED : ComplianceAudit.GUARDIAN_REJECTED, ev);
        return new Verification(id, principalRef, guardianRef, kind, method, ok ? Status.VERIFIED : Status.REJECTED, now, validUntil);
    }

    private static final String SELECT = "SELECT verification_id, principal_ref, guardian_ref, kind, method, status, verified_at,"
            + " valid_until FROM CMP_GUARDIAN_VERIFICATION";

    private static Verification map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String until = rs.getString(8);
        return new Verification(rs.getString(1), rs.getString(2), rs.getString(3), Kind.valueOf(rs.getString(4)), rs.getString(5),
                Status.valueOf(rs.getString(6)), Jdbc.instant(rs, "verified_at"), until == null ? null : LocalDate.parse(until));
    }
}
