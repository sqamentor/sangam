// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.admin;

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
 * Tenant administration (AJ-01, EC-17 multi-tenant administration, R56 residency profile, R28 quotas).
 *
 * <p>Lifecycle {@code CREATED → CONFIGURED → ACTIVE ⇄ SUSPENDED}. Creation is a platform-admin action (role
 * {@value #PLATFORM_ADMIN_ROLE} from the IdP). Activation is blocked until every mandatory go-live checklist item has
 * evidence (AJ-01 step 10). Requests for a suspended or unknown tenant are refused by {@link #requireActive}.
 */
public final class TenantAdminService {

    public static final String PLATFORM_ADMIN_ROLE = "platform-admin";
    public static final String TENANT_ADMIN_ROLE = "tenant-admin";

    /** AJ-01 step 10. */
    public static final List<String> MANDATORY_CHECKLIST = List.of("CERT_IN_AUDIT_CLEARANCE", "GIGW_CONFORMANCE_MATRIX",
            "DATA_PROCESSOR_AGREEMENT", "SMOKE_TEST_PER_PACK", "ISOLATION_CHECK");

    public record Tenant(String tenantId, String nameEn, String nameHi, String nameLocal, String hostingProfile, String status,
                         Map<String, Object> config, Map<String, Object> quotas, boolean fourEyes) {
    }

    private final SecurityDialect dialect;
    private final AuthorizationService authz;
    private final SecurityAuditService audit;

    public TenantAdminService(SecurityDialect dialect, AuthorizationService authz, SecurityAuditService audit) {
        this.dialect = dialect;
        this.authz = authz;
        this.audit = audit;
    }

    public Tenant createTenant(Connection c, SecurityIdentity platformAdmin, String tenantId, String nameEn, String nameHi,
                               String nameLocal, String hostingProfile, Map<String, Object> quotas) {
        requirePlatformAdmin(platformAdmin);
        if (!tenantId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,59}")) {
            throw new SangamSecurityException.AdminConflict("INVALID_TENANT_ID", "tenant id must be 1-60 safe characters");
        }
        if (!Sql.insertIfAbsent(c, dialect, "INSERT INTO SEC_TENANT(tenant_id, name_en, name_hi, name_local, hosting_profile, status,"
                        + " config, quotas, four_eyes, created_at, activated_at) VALUES (?,?,?,?,?,'CREATED','{}',?,1,?,NULL)",
                tenantId, nameEn, nameHi, nameLocal, hostingProfile, Sql.toJson(quotas == null ? Map.of() : quotas),
                Sql.dbNow(c, dialect))) {
            throw new SangamSecurityException.AdminConflict("TENANT_EXISTS", "tenant " + tenantId + " exists");
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tenant", tenantId);
        p.put("name_en", nameEn);
        p.put("hosting_profile", hostingProfile);
        audit.securityIn(c, tenantId, platformAdmin, SecurityEventType.TENANT_CREATED, p);
        return find(c, tenantId).orElseThrow();
    }

    /** Languages, calendar, time zone and other settings (AJ-01 step 3). */
    public void configure(Connection c, SecurityIdentity admin, Map<String, Object> config, Map<String, Object> quotas, Boolean fourEyes) {
        authz.require(c, admin, ResourceRegistry.TENANT, Permission.ADMIN, admin.tenantId());
        Tenant t = find(c, admin.tenantId()).orElseThrow(() -> unknown(admin.tenantId()));
        Map<String, Object> merged = new LinkedHashMap<>(t.config());
        if (config != null) {
            merged.putAll(config);
        }
        Map<String, Object> q = new LinkedHashMap<>(t.quotas());
        if (quotas != null) {
            q.putAll(quotas);
        }
        boolean fe = fourEyes == null ? t.fourEyes() : fourEyes;
        Jdbc.update(c, "UPDATE SEC_TENANT SET config=?, quotas=?, four_eyes=?, status=CASE WHEN status='CREATED' THEN 'CONFIGURED'"
                        + " ELSE status END WHERE tenant_id=?",
                Sql.toJson(merged), Sql.toJson(q), fe ? 1 : 0, admin.tenantId());
        audit.security(c, admin, SecurityEventType.TENANT_CONFIGURED, Map.of("keys", merged.keySet().stream().sorted().toList(),
                "four_eyes", fe));
    }

    public void recordChecklistItem(Connection c, SecurityIdentity admin, String item, String evidenceRef) {
        authz.require(c, admin, ResourceRegistry.TENANT, Permission.ADMIN, admin.tenantId());
        Jdbc.update(c, "DELETE FROM SEC_TENANT_CHECKLIST WHERE tenant_id=? AND item=?", admin.tenantId(), item);
        Jdbc.update(c, "INSERT INTO SEC_TENANT_CHECKLIST(tenant_id, item, evidence_ref, recorded_by, recorded_at) VALUES (?,?,?,?,?)",
                admin.tenantId(), item, evidenceRef, admin.personId(), Sql.dbNow(c, dialect));
    }

    /** Activation (AJ-01 step 10): blocked until every mandatory item has evidence. */
    public void activate(Connection c, SecurityIdentity admin) {
        authz.require(c, admin, ResourceRegistry.TENANT, Permission.ADMIN, admin.tenantId());
        List<String> have = Jdbc.query(c, "SELECT item FROM SEC_TENANT_CHECKLIST WHERE tenant_id=? AND evidence_ref IS NOT NULL",
                rs -> rs.getString(1), admin.tenantId());
        List<String> missing = MANDATORY_CHECKLIST.stream().filter(i -> !have.contains(i)).toList();
        if (!missing.isEmpty()) {
            throw new SangamSecurityException.AdminConflict("ACTIVATION_BLOCKED", "missing go-live evidence: " + missing);
        }
        Jdbc.update(c, "UPDATE SEC_TENANT SET status='ACTIVE', activated_at=? WHERE tenant_id=?", Sql.dbNow(c, dialect), admin.tenantId());
        audit.security(c, admin, SecurityEventType.TENANT_ACTIVATED, Map.of("checklist", have));
    }

    public void suspend(Connection c, SecurityIdentity platformAdmin, String tenantId, String reason) {
        requirePlatformAdmin(platformAdmin);
        Jdbc.update(c, "UPDATE SEC_TENANT SET status='SUSPENDED' WHERE tenant_id=?", tenantId);
        audit.securityIn(c, tenantId, platformAdmin, SecurityEventType.TENANT_SUSPENDED, Map.of("reason", reason == null ? "" : reason));
    }

    public void resume(Connection c, SecurityIdentity platformAdmin, String tenantId) {
        requirePlatformAdmin(platformAdmin);
        Jdbc.update(c, "UPDATE SEC_TENANT SET status='ACTIVE' WHERE tenant_id=? AND status='SUSPENDED'", tenantId);
        audit.securityIn(c, tenantId, platformAdmin, SecurityEventType.TENANT_ACTIVATED, Map.of("resumed", true));
    }

    public Optional<Tenant> find(Connection c, String tenantId) {
        return Jdbc.one(c, "SELECT tenant_id, name_en, name_hi, name_local, hosting_profile, status, config, quotas, four_eyes"
                        + " FROM SEC_TENANT WHERE tenant_id=?",
                rs -> new Tenant(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), Sql.jsonMap(rs.getString(7)), Sql.jsonMap(rs.getString(8)), rs.getInt(9) == 1),
                tenantId);
    }

    public List<Tenant> list(Connection c, SecurityIdentity platformAdmin) {
        requirePlatformAdmin(platformAdmin);
        return Jdbc.query(c, "SELECT tenant_id, name_en, name_hi, name_local, hosting_profile, status, config, quotas, four_eyes"
                        + " FROM SEC_TENANT ORDER BY tenant_id",
                rs -> new Tenant(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), Sql.jsonMap(rs.getString(7)), Sql.jsonMap(rs.getString(8)), rs.getInt(9) == 1));
    }

    /** Requests are served only for tenants that exist and are not suspended (CREATED/CONFIGURED allow admin set-up). */
    public void requireActive(Connection c, String tenantId) {
        String status = find(c, tenantId).map(Tenant::status).orElse(null);
        if (status == null || "SUSPENDED".equals(status)) {
            throw new SangamSecurityException.Unauthenticated("TENANT_UNAVAILABLE", "tenant is unknown or suspended");
        }
    }

    public boolean fourEyes(Connection c, String tenantId) {
        return find(c, tenantId).map(Tenant::fourEyes).orElse(false);
    }

    /** Integer quota from the tenant's quota map (R28 per-tenant quotas), or the default. */
    public long quota(Connection c, String tenantId, String name, long defaultValue) {
        Object v = find(c, tenantId).map(t -> t.quotas().get(name)).orElse(null);
        return v == null ? defaultValue : Long.parseLong(v.toString());
    }

    static void requirePlatformAdmin(SecurityIdentity who) {
        if (!who.hasRole(PLATFORM_ADMIN_ROLE)) {
            throw new SangamSecurityException.AccessDenied("platform administrator role required", 0);
        }
    }

    private static SangamSecurityException unknown(String t) {
        return new SangamSecurityException.AdminConflict("UNKNOWN_TENANT", "tenant " + t + " not found");
    }
}
