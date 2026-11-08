// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.rights;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nomination (DPDP s.14; Rule 14(4): one or more individuals; CMP-040, CMP-079). Table {@code CMP_NOMINATION}.
 * A nominee may exercise the principal's rights only after the nomination is invoked on the principal's death or
 * incapacity (unsoundness of mind or infirmity of body), on verified evidence (death certificate, medical or court
 * certificate) recorded by an officer.
 */
public final class NominationService {

    public enum Status { ACTIVE, REVOKED, INVOKED }

    public enum Event { DEATH, INCAPACITY }

    public record Nomination(String nominationId, String principalRef, String nomineeRef, String nomineeName, String relationship,
                             Status status, Instant registeredAt, Instant invokedAt, Event event) {
    }

    private final Dialect dialect;
    private final ComplianceAudit audit;

    public NominationService(Dialect dialect, ComplianceAudit audit) {
        this.dialect = dialect;
        this.audit = audit;
    }

    public Nomination nominate(Connection c, String tenantId, String principalRef, String nomineeRef, String nomineeName,
                               String relationship, String contactRef, String actor) {
        Sql.requireText(principalRef, "principalRef");
        Sql.requireText(nomineeRef, "nomineeRef");
        if (principalRef.equals(nomineeRef)) {
            throw ComplianceException.invalid("a principal cannot nominate herself");
        }
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO CMP_NOMINATION(tenant_id, nomination_id, principal_ref, nominee_ref, nominee_name, relationship,"
                        + " contact_ref, status, registered_at, revoked_at, invoked_at, invocation_event, evidence_ref, verified_by)"
                        + " VALUES (?,?,?,?,?,?,?,?," + dialect.now() + ",NULL,NULL,NULL,NULL,NULL)",
                tenantId, id, principalRef, nomineeRef, nomineeName, relationship, contactRef, Status.ACTIVE);
        event(c, tenantId, principalRef, id, "NOMINATED", actor);
        return get(c, tenantId, id);
    }

    public void revoke(Connection c, String tenantId, String principalRef, String nominationId, String actor) {
        Nomination n = get(c, tenantId, nominationId);
        if (!n.principalRef().equals(principalRef) || n.status() != Status.ACTIVE) {
            throw new ComplianceException(Code.INVALID_STATE, "nomination is not active for this principal");
        }
        Jdbc.update(c, "UPDATE CMP_NOMINATION SET status='REVOKED', revoked_at=" + dialect.now() + " WHERE tenant_id=? AND nomination_id=?",
                tenantId, nominationId);
        event(c, tenantId, principalRef, nominationId, "REVOKED", actor);
    }

    /** Invokes a nomination on verified death or incapacity of the principal. */
    public Nomination invoke(Connection c, String tenantId, String nominationId, Event event, String evidenceRef, String verifiedBy) {
        Sql.requireText(evidenceRef, "evidenceRef");
        Sql.requireText(verifiedBy, "verifiedBy");
        Nomination n = get(c, tenantId, nominationId);
        if (n.status() != Status.ACTIVE) {
            throw new ComplianceException(Code.INVALID_STATE, "nomination is " + n.status());
        }
        Jdbc.update(c, "UPDATE CMP_NOMINATION SET status='INVOKED', invoked_at=" + dialect.now() + ", invocation_event=?, evidence_ref=?,"
                + " verified_by=? WHERE tenant_id=? AND nomination_id=?", event, evidenceRef, verifiedBy, tenantId, nominationId);
        event(c, tenantId, n.principalRef(), nominationId, "INVOKED:" + event, verifiedBy);
        return get(c, tenantId, nominationId);
    }

    /** The nominee may act for the principal only through an invoked nomination. */
    public Nomination requireInvoked(Connection c, String tenantId, String principalRef, String nomineeRef) {
        return Jdbc.one(c, SELECT + " WHERE tenant_id=? AND principal_ref=? AND nominee_ref=? AND status='INVOKED'",
                NominationService::map, tenantId, principalRef, nomineeRef).orElseThrow(() ->
                new ComplianceException(Code.NOMINATION_NOT_ACTIVE, "no invoked nomination for this nominee (s.14)"));
    }

    public List<Nomination> of(Connection c, String tenantId, String principalRef) {
        return Jdbc.query(c, SELECT + " WHERE tenant_id=? AND principal_ref=? ORDER BY registered_at", NominationService::map,
                tenantId, principalRef);
    }

    public Nomination get(Connection c, String tenantId, String nominationId) {
        return Jdbc.one(c, SELECT + " WHERE tenant_id=? AND nomination_id=?", NominationService::map, tenantId, nominationId)
                .orElseThrow(() -> ComplianceException.notFound("nomination", nominationId));
    }

    private void event(Connection c, String tenantId, String principalRef, String nominationId, String step, String actor) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("nomination", nominationId);
        ev.put("step", step);
        ev.put("actor", actor == null ? null : ComplianceAudit.principalToken(tenantId, actor));
        audit.principal(c, tenantId, principalRef, ComplianceAudit.NOMINATION_CHANGED, ev);
    }

    private static final String SELECT = "SELECT nomination_id, principal_ref, nominee_ref, nominee_name, relationship, status,"
            + " registered_at, invoked_at, invocation_event FROM CMP_NOMINATION";

    private static Nomination map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String ev = rs.getString(9);
        return new Nomination(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                Status.valueOf(rs.getString(6)), Jdbc.instant(rs, "registered_at"), Jdbc.instant(rs, "invoked_at"),
                ev == null ? null : Event.valueOf(ev));
    }
}
