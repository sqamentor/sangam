// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.rights;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Restrictions of processing or disclosure agreed with (or required for) a data subject, table {@code CMP_RESTRICTION}.
 * {@link #blocking} is checked by the disclosure register before every disclosure. US-HIPAA: 164.522(a)(1)(vi)
 * (no disclosure to a health plan for items paid out of pocket in full; US-HLT-043); EU-GDPR: Art. 18.
 * A recipient pattern is an exact recipient or a prefix ending in {@code *}.
 */
public final class RestrictionService {

    public record Restriction(String restrictionId, String principalRef, String recipientPattern, String purposeCode, String reason,
                              String legalRef, boolean active) {
        boolean matches(String recipient, String purpose) {
            String r = recipient == null ? "" : recipient.toLowerCase(Locale.ROOT);
            String p = recipientPattern.toLowerCase(Locale.ROOT);
            boolean rec = p.equals("*") || (p.endsWith("*") ? r.startsWith(p.substring(0, p.length() - 1)) : r.equals(p));
            return rec && (purposeCode == null || purposeCode.equals(purpose));
        }
    }

    private final Dialect dialect;
    private final ComplianceAudit audit;

    public RestrictionService(Dialect dialect, ComplianceAudit audit) {
        this.dialect = dialect;
        this.audit = audit;
    }

    public Restriction add(Connection c, String tenantId, String principalRef, String recipientPattern, String purposeCode, String reason,
                           String legalRef, String actor) {
        Sql.requireText(recipientPattern, "recipientPattern");
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO CMP_RESTRICTION(tenant_id, restriction_id, principal_ref, recipient_pattern, purpose_code, reason, legal_ref,"
                        + " active, created_by, created_at, lifted_at) VALUES (?,?,?,?,?,?,?,1,?," + dialect.now() + ",NULL)",
                tenantId, id, principalRef, recipientPattern, purposeCode, reason, legalRef, actor);
        audit.principal(c, tenantId, principalRef, ComplianceAudit.RESTRICTION_CHANGED, Sql.kv("restriction", id, "step", "ADDED",
                "purpose", purposeCode, "legalRef", legalRef, "actor", actor));
        return get(c, tenantId, id);
    }

    public void lift(Connection c, String tenantId, String restrictionId, String reason, String actor) {
        Restriction r = get(c, tenantId, restrictionId);
        Jdbc.update(c, "UPDATE CMP_RESTRICTION SET active=0, lifted_at=" + dialect.now() + " WHERE tenant_id=? AND restriction_id=?",
                tenantId, restrictionId);
        audit.principal(c, tenantId, r.principalRef(), ComplianceAudit.RESTRICTION_CHANGED, Sql.kv("restriction", restrictionId,
                "step", "LIFTED", "reason", reason, "actor", actor));
    }

    /** The active restriction that blocks this disclosure, if any. */
    public Optional<Restriction> blocking(Connection c, String tenantId, String principalRef, String recipient, String purposeCode) {
        return active(c, tenantId, principalRef).stream().filter(r -> r.matches(recipient, purposeCode)).findFirst();
    }

    public List<Restriction> active(Connection c, String tenantId, String principalRef) {
        return Jdbc.query(c, SELECT + " WHERE tenant_id=? AND principal_ref=? AND active=1", RestrictionService::map, tenantId, principalRef);
    }

    public Restriction get(Connection c, String tenantId, String id) {
        return Jdbc.one(c, SELECT + " WHERE tenant_id=? AND restriction_id=?", RestrictionService::map, tenantId, id)
                .orElseThrow(() -> ComplianceException.notFound("restriction", id));
    }

    private static final String SELECT = "SELECT restriction_id, principal_ref, recipient_pattern, purpose_code, reason, legal_ref, active"
            + " FROM CMP_RESTRICTION";

    private static Restriction map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Restriction(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                Sql.bool(rs.getInt(7)));
    }
}
