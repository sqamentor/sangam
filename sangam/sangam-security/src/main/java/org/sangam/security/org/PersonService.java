// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.org;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.support.Sql;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Users (persons) of a tenant: the user part of the IDM boundary owned by WS-SECURITY (FLOWABLE-INTO-OPERATON FC-42, R67). A person is linked
 * to an IdP subject ({@code external_subject}); seats never come from the IdP (AJ-05 step 3). Just-in-time creation on
 * first login is optional per tenant (R67); an unlinked login otherwise gets {@code 401 NOT_LINKED}.
 */
public final class PersonService {

    private final SecurityDialect dialect;
    private final AuthorizationService authz;
    private final SecurityAuditService audit;

    public PersonService(SecurityDialect dialect, AuthorizationService authz, SecurityAuditService audit) {
        this.dialect = dialect;
        this.authz = authz;
        this.audit = audit;
    }

    public OrgModel.Person createUser(Connection c, SecurityIdentity actor, String personId, String displayName,
                                      String externalSubject, String email) {
        return createUser(c, actor, personId, displayName, externalSubject, email, null);
    }

    /**
     * Creates a person with its provisioning source (decision 154: SCIM passes {@code SCIM}, as directory sync writes
     * {@code LDAP}); {@code null} means {@code LOCAL}, an administrator's creation, audited as before ({@code how=ADMIN}).
     */
    public OrgModel.Person createUser(Connection c, SecurityIdentity actor, String personId, String displayName,
                                      String externalSubject, String email, String source) {
        authz.require(c, actor, ResourceRegistry.USER, Permission.ADMIN, ResourceRegistry.ANY_ID);
        insert(c, actor.tenantId(), personId, displayName, externalSubject, email);
        if (source != null) {
            Jdbc.update(c, "UPDATE SEC_PERSON SET source=? WHERE tenant_id=? AND person_id=?", source, actor.tenantId(),
                    personId);
        }
        audit.security(c, actor, SecurityEventType.USER_PROVISIONED,
                details(personId, externalSubject, source == null ? "ADMIN" : source));
        return find(c, actor.tenantId(), personId).orElseThrow();
    }

    /**
     * Re-enables a disabled person (decision 154: SCIM {@code active=true} after {@code active=false}). Changes only a
     * {@code DISABLED} person; when a row changed, audits {@code USER_UPDATED} with {@code how=ENABLE}. API tokens revoked
     * by the disable stay revoked.
     *
     * @return whether the person was disabled and is now active
     */
    public boolean enableUser(Connection c, SecurityIdentity actor, String personId) {
        authz.require(c, actor, ResourceRegistry.USER, Permission.ADMIN, personId);
        int changed = Jdbc.update(c, "UPDATE SEC_PERSON SET status='ACTIVE' WHERE tenant_id=? AND person_id=?"
                + " AND status='DISABLED'", actor.tenantId(), personId);
        if (changed > 0) {
            audit.security(c, actor, SecurityEventType.USER_UPDATED, Map.of("person_id", personId, "how", "ENABLE"));
        }
        return changed > 0;
    }

    public void updateUser(Connection c, SecurityIdentity actor, String personId, String displayName, String email) {
        authz.require(c, actor, ResourceRegistry.USER, Permission.ADMIN, personId);
        Jdbc.update(c, "UPDATE SEC_PERSON SET display_name=?, email=? WHERE tenant_id=? AND person_id=?",
                displayName, email, actor.tenantId(), personId);
        audit.security(c, actor, SecurityEventType.USER_UPDATED, Map.of("person_id", personId));
    }

    /** Links (or re-links) an IdP subject to a person (AJ-05 step 3). */
    public void linkSubject(Connection c, SecurityIdentity actor, String personId, String externalSubject) {
        authz.require(c, actor, ResourceRegistry.USER, Permission.ADMIN, personId);
        Jdbc.update(c, "UPDATE SEC_PERSON SET external_subject=? WHERE tenant_id=? AND person_id=?",
                externalSubject, actor.tenantId(), personId);
        audit.security(c, actor, SecurityEventType.USER_UPDATED, details(personId, externalSubject, "LINK"));
    }

    /** Disables a user; API tokens are revoked by {@code ApiTokenService.revokeAllOf} in the same transaction. */
    public void disableUser(Connection c, SecurityIdentity actor, String personId, String reason) {
        authz.require(c, actor, ResourceRegistry.USER, Permission.ADMIN, personId);
        if (actor.personId().equals(personId)) {
            throw new SangamSecurityException.AdminConflict("SELF_DISABLE", "an administrator cannot disable themselves");
        }
        Jdbc.update(c, "UPDATE SEC_PERSON SET status='DISABLED' WHERE tenant_id=? AND person_id=?", actor.tenantId(), personId);
        audit.security(c, actor, SecurityEventType.USER_DISABLED, Map.of("person_id", personId, "reason", reason == null ? "" : reason));
    }

    public Optional<OrgModel.Person> find(Connection c, String tenant, String personId) {
        return Jdbc.one(c, "SELECT person_id, display_name, external_subject, email, status FROM SEC_PERSON WHERE tenant_id=? AND person_id=?",
                rs -> new OrgModel.Person(tenant, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                tenant, personId);
    }

    public Optional<OrgModel.Person> findBySubject(Connection c, String tenant, String externalSubject) {
        return Jdbc.one(c, "SELECT person_id, display_name, external_subject, email, status FROM SEC_PERSON WHERE tenant_id=? AND external_subject=?",
                rs -> new OrgModel.Person(tenant, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                tenant, externalSubject);
    }

    public List<OrgModel.Person> list(Connection c, SecurityIdentity actor, int offset, int limit) {
        authz.require(c, actor, ResourceRegistry.USER, Permission.READ, ResourceRegistry.ANY_ID);
        return Jdbc.query(c, dialect.page("SELECT person_id, display_name, external_subject, email, status FROM SEC_PERSON"
                        + " WHERE tenant_id=? ORDER BY person_id", offset, Math.min(limit, 1000)),
                rs -> new OrgModel.Person(actor.tenantId(), rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)), actor.tenantId());
    }

    /**
     * Just-in-time provisioning (R67): creates the person on first login when the tenant allows it. Runs as the system
     * principal of the tenant; the person id is derived from the subject.
     *
     * <p>SL-100: idempotent under concurrency. The first requests of a new identity often arrive together; each saw no
     * person, one inserted it and the others failed the insert (primary key / {@code SEC_PERSON_SUBJECT}) and answered
     * 409 {@code USER_EXISTS}. A lost insert now reads the person the concurrent first request created (committed, so
     * visible to the next statement under READ COMMITTED) and returns it; only that request audits the provisioning.
     * A conflict with a person of another subject still answers {@code USER_EXISTS}.
     */
    public OrgModel.Person provisionJit(Connection c, String tenant, String externalSubject, String displayName, String email) {
        Optional<OrgModel.Person> existing = findBySubject(c, tenant, externalSubject);
        if (existing.isPresent()) {
            return existing.get();
        }
        String personId = externalSubject;
        if (!insertIfAbsent(c, tenant, personId, displayName, externalSubject, email)) {
            return findBySubject(c, tenant, externalSubject).orElseThrow(() ->
                    new SangamSecurityException.AdminConflict("USER_EXISTS", "user " + personId + " already exists"));
        }
        audit.security(c, SecurityIdentity.system(tenant, personId), SecurityEventType.USER_PROVISIONED,
                details(personId, externalSubject, "JIT"));
        return find(c, tenant, personId).orElseThrow();
    }

    private void insert(Connection c, String tenant, String personId, String displayName, String externalSubject, String email) {
        if (!insertIfAbsent(c, tenant, personId, displayName, externalSubject, email)) {
            throw new SangamSecurityException.AdminConflict("USER_EXISTS", "user " + personId + " already exists");
        }
    }

    /** False when the person id or the subject exists already (a concurrent insert included). */
    private boolean insertIfAbsent(Connection c, String tenant, String personId, String displayName, String externalSubject,
                                   String email) {
        return Sql.insertIfAbsent(c, dialect, "INSERT INTO SEC_PERSON(tenant_id, person_id, display_name, external_subject,"
                        + " email, status, created_at) VALUES (?,?,?,?,?,'ACTIVE',?)",
                tenant, personId, displayName, externalSubject, email, Sql.dbNow(c, dialect));
    }

    private static Map<String, Object> details(String personId, String subject, String how) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("person_id", personId);
        m.put("external_subject", subject);
        m.put("how", how);
        return m;
    }
}
