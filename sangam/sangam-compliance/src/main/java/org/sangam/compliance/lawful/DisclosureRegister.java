// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.lawful;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sharing log (table {@code CMP_DISCLOSURE}): every disclosure of a data subject's personal data to another
 * controller, a processor or an authority. Feeds the access response (recipients and data shared) and keeps out of it
 * the disclosures the law lets the controller withhold (IN: s.11(2) offence or cyber-incident requests; CMP-035,
 * CMP-036, CMP-124).
 */
public final class DisclosureRegister {

    public enum RecipientKind { DATA_FIDUCIARY, DATA_PROCESSOR, STATE_AGENCY, LAW_ENFORCEMENT, INCIDENT_AUTHORITY, COURT }

    public record Disclosure(String disclosureId, String principalRef, String recipient, RecipientKind recipientKind,
                             String purposeCode, Set<String> dataCategories, String legalRef, boolean withheldFromAccess,
                             Instant disclosedAt) {
    }

    /** Refuses a disclosure the data subject restricted; installed by the facade. */
    @FunctionalInterface
    public interface RestrictionCheck {
        java.util.Optional<String> blockingRestriction(Connection c, String tenantId, String principalRef, String recipient, String purposeCode);
    }

    private final Dialect dialect;
    private final ComplianceAudit audit;
    private volatile RestrictionCheck restrictions = (c, t, p, r, pc) -> java.util.Optional.empty();

    public void restrictionCheck(RestrictionCheck check) {
        this.restrictions = check;
    }

    public DisclosureRegister(Dialect dialect, ComplianceAudit audit) {
        this.dialect = dialect;
        this.audit = audit;
    }

    public String record(Connection c, String tenantId, String principalRef, String recipient, RecipientKind kind,
                         String purposeCode, Set<String> dataCategories, String legalRef, boolean withheldFromAccess) {
        Sql.requireText(principalRef, "principalRef");
        Sql.requireText(recipient, "recipient");
        if (withheldFromAccess && (legalRef == null || legalRef.isBlank())) {
            throw org.sangam.compliance.ComplianceException.invalid("a disclosure withheld from access needs the written request reference");
        }
        if (!withheldFromAccess && kind != RecipientKind.COURT && kind != RecipientKind.LAW_ENFORCEMENT) {
            java.util.Optional<String> r = restrictions.blockingRestriction(c, tenantId, principalRef, recipient, purposeCode);
            if (r.isPresent()) {
                throw new org.sangam.compliance.ComplianceException(org.sangam.compliance.ComplianceException.Code.PURPOSE_NOT_PERMITTED,
                        "disclosure to " + recipient + " is restricted (" + r.get() + ")");
            }
        }
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO CMP_DISCLOSURE(tenant_id, disclosure_id, principal_ref, recipient, recipient_kind, purpose_code,"
                        + " data_categories_json, legal_ref, withheld_from_access, disclosed_at) VALUES (?,?,?,?,?,?,?,?,?," + dialect.now() + ")",
                tenantId, id, principalRef, recipient, kind, purposeCode, Sql.jsonList(dataCategories), legalRef, withheldFromAccess);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("disclosure", id);
        ev.put("recipientKind", kind.name());
        ev.put("purpose", purposeCode);
        ev.put("withheldFromAccess", withheldFromAccess);
        audit.principal(c, tenantId, principalRef, ComplianceAudit.DISCLOSURE_RECORDED, ev);
        return id;
    }

    /** The disclosures the data subject may see; withheld disclosures are left out. */
    public List<Disclosure> forAccessSummary(Connection c, String tenantId, String principalRef) {
        return query(c, tenantId, principalRef, false);
    }

    public List<Disclosure> all(Connection c, String tenantId, String principalRef) {
        return query(c, tenantId, principalRef, true);
    }

    public long count(Connection c, String tenantId, Instant from, Instant to) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM CMP_DISCLOSURE WHERE tenant_id=? AND disclosed_at>=? AND disclosed_at<?",
                rs -> rs.getLong(1), tenantId, from, to).orElse(0L);
    }

    private List<Disclosure> query(Connection c, String tenantId, String principalRef, boolean includeWithheld) {
        return Jdbc.query(c, "SELECT disclosure_id, principal_ref, recipient, recipient_kind, purpose_code, data_categories_json,"
                        + " legal_ref, withheld_from_access, disclosed_at FROM CMP_DISCLOSURE WHERE tenant_id=? AND principal_ref=?"
                        + (includeWithheld ? "" : " AND withheld_from_access=0") + " ORDER BY disclosed_at",
                rs -> new Disclosure(rs.getString(1), rs.getString(2), rs.getString(3), RecipientKind.valueOf(rs.getString(4)),
                        rs.getString(5), Sql.set(rs.getString(6)), rs.getString(7), Sql.bool(rs.getInt(8)),
                        Jdbc.instant(rs, "disclosed_at")),
                tenantId, principalRef);
    }
}
