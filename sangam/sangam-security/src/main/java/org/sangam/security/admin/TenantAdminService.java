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

    /** Generic go-live items every tenant needs (AJ-01 step 10). Regime items are data; see {@link #requiredChecklist}. */
    public static final List<String> CORE_CHECKLIST = List.of("DATA_PROCESSOR_AGREEMENT", "SMOKE_TEST_PER_PACK", "ISOLATION_CHECK");

    /** Tenant config key: jurisdiction pack codes whose {@code goLiveEvidence} apply (ADR-D-20, data not code). */
    public static final String CONFIG_JURISDICTIONS = "jurisdictions";
    /** Tenant config key: further required items set by a profile. */
    public static final String CONFIG_REQUIRED_EVIDENCE = "goLiveRequiredEvidence";

    /** Items a jurisdiction requires; empty Optional means the pack is not installed (activation then fails closed). */
    @FunctionalInterface
    public interface JurisdictionChecklist {
        Optional<List<String>> itemsOf(String jurisdictionCode);
    }

    /** Reads {@code goLiveEvidence} from the classpath pack {@code compliance/jurisdictions/<code>/pack.json}. */
    public static final JurisdictionChecklist CLASSPATH_PACKS = code -> {
        if (!code.matches("[A-Za-z0-9_-]{1,32}")) {
            return Optional.empty();
        }
        try (java.io.InputStream in = TenantAdminService.class.getClassLoader()
                .getResourceAsStream("compliance/jurisdictions/" + code + "/pack.json")) {
            if (in == null) {
                return Optional.empty();
            }
            Map<String, Object> pack = Sql.jsonMap(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            return Optional.of(strings(pack.get("goLiveEvidence")));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read jurisdiction pack " + code, e);
        }
    };

    /** Jurisdictions that apply to a tenant outside its stored config (the deployment's compliance settings). */
    @FunctionalInterface
    public interface JurisdictionSource {
        List<String> of(String tenantId);
    }

    /** Installs the compliance module's view: the one source of pack items (plugin packs included) and of effective jurisdictions. */
    public void useJurisdictions(JurisdictionChecklist checklist, JurisdictionSource source) {
        this.jurisdictionChecklist = checklist;
        this.jurisdictionSource = source;
    }

    /** Stored config, the deployment's effective jurisdictions, and {@code planned} (not yet stored; may be empty). */
    public List<String> effectiveJurisdictions(Connection c, String tenantId, List<String> planned) {
        java.util.Set<String> codes = new java.util.LinkedHashSet<>(planned);
        codes.addAll(strings(find(c, tenantId).map(Tenant::config).orElse(Map.of()).get(CONFIG_JURISDICTIONS)));
        codes.addAll(jurisdictionSource.of(tenantId));
        return List.copyOf(codes);
    }

    /** {@code isolation}: SHARED, SCHEMA or DATABASE ({@code SEC_TENANT.isolation}, decision 97). */
    public record Tenant(String tenantId, String nameEn, String nameHi, String nameLocal, String hostingProfile, String status,
                         Map<String, Object> config, Map<String, Object> quotas, boolean fourEyes, String isolation) {

        /** A tenant without a stored isolation is SHARED (the column default). */
        public Tenant(String tenantId, String nameEn, String nameHi, String nameLocal, String hostingProfile, String status,
                      Map<String, Object> config, Map<String, Object> quotas, boolean fourEyes) {
            this(tenantId, nameEn, nameHi, nameLocal, hostingProfile, status, config, quotas, fourEyes, SHARED);
        }
    }

    public static final String SHARED = "SHARED";
    public static final String SCHEMA = "SCHEMA";
    public static final String DATABASE = "DATABASE";
    public static final List<String> ISOLATIONS = List.of(SHARED, SCHEMA, DATABASE);

    /** M-016: the one tenant this instance serves when it is a dedicated instance; null on a shared instance. */
    private volatile String dedicatedTenant;

    /**
     * Marks this instance as dedicated to {@code tenantId} ({@code sangam.tenancy.dedicated-tenant}); blank = shared.
     * A dedicated instance creates no tenant but its own, and records that one with isolation DATABASE.
     */
    public void useDedicatedTenant(String tenantId) {
        this.dedicatedTenant = tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
    }

    /** A stored isolation as read; a NULL from a database without the column default is SHARED. */
    private static String isolation(String stored) {
        return stored == null || stored.isBlank() ? SHARED : stored.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** The validated isolation (upper case); null means the default of this instance. */
    String isolationFor(String tenantId, String requested) {
        String iso = requested == null || requested.isBlank() ? null : requested.trim().toUpperCase(java.util.Locale.ROOT);
        if (iso != null && !ISOLATIONS.contains(iso)) {
            throw new SangamSecurityException.AdminConflict("INVALID_ISOLATION", "isolation must be one of " + ISOLATIONS);
        }
        String dedicated = dedicatedTenant;
        if (dedicated == null) {
            return iso == null ? SHARED : iso;
        }
        if (!dedicated.equals(tenantId)) {
            throw new SangamSecurityException.AdminConflict("DEDICATED_INSTANCE", "this instance is dedicated to tenant "
                    + dedicated + "; other tenants are created on a shared instance");
        }
        if (iso != null && !DATABASE.equals(iso)) {
            throw new SangamSecurityException.AdminConflict("INVALID_ISOLATION",
                    "the tenant of a dedicated instance has isolation DATABASE, not " + iso);
        }
        return DATABASE;
    }

    private final SecurityDialect dialect;
    private final AuthorizationService authz;
    private final SecurityAuditService audit;
    private volatile JurisdictionChecklist jurisdictionChecklist;
    private volatile JurisdictionSource jurisdictionSource = tenant -> List.of();

    public TenantAdminService(SecurityDialect dialect, AuthorizationService authz, SecurityAuditService audit) {
        this(dialect, authz, audit, CLASSPATH_PACKS);
    }

    public TenantAdminService(SecurityDialect dialect, AuthorizationService authz, SecurityAuditService audit,
                              JurisdictionChecklist jurisdictionChecklist) {
        this.dialect = dialect;
        this.authz = authz;
        this.audit = audit;
        this.jurisdictionChecklist = jurisdictionChecklist;
    }

    /** Core items, plus the items of the tenant's jurisdiction packs and profile. Fails closed on an unknown pack. */
    public List<String> requiredChecklist(Connection c, String tenantId) {
        return requiredChecklist(c, tenantId, List.of());
    }

    public List<String> requiredChecklist(Connection c, String tenantId, List<String> planned) {
        Map<String, Object> config = find(c, tenantId).map(Tenant::config).orElse(Map.of());
        java.util.Set<String> items = new java.util.LinkedHashSet<>(CORE_CHECKLIST);
        for (String code : effectiveJurisdictions(c, tenantId, planned)) {
            items.addAll(jurisdictionChecklist.itemsOf(code).orElseThrow(() -> new SangamSecurityException.AdminConflict(
                    "ACTIVATION_BLOCKED", "jurisdiction pack " + code + " is not installed")));
        }
        items.addAll(strings(config.get(CONFIG_REQUIRED_EVIDENCE)));
        return List.copyOf(items);
    }

    /** Required items with no recorded evidence. */
    public List<String> missingEvidence(Connection c, String tenantId) {
        return missingEvidence(c, tenantId, List.of());
    }

    /** With jurisdictions a request plans but has not stored yet, so a dry run sees the same requirement. */
    public List<String> missingEvidence(Connection c, String tenantId, List<String> planned) {
        List<String> have = Jdbc.query(c, "SELECT item FROM SEC_TENANT_CHECKLIST WHERE tenant_id=? AND evidence_ref IS NOT NULL",
                rs -> rs.getString(1), tenantId);
        return requiredChecklist(c, tenantId, planned).stream().filter(i -> !have.contains(i)).toList();
    }

    private static List<String> strings(Object v) {
        return v instanceof java.util.Collection<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    public Tenant createTenant(Connection c, SecurityIdentity platformAdmin, String tenantId, String nameEn, String nameHi,
                               String nameLocal, String hostingProfile, Map<String, Object> quotas) {
        return createTenant(c, platformAdmin, tenantId, nameEn, nameHi, nameLocal, hostingProfile, quotas, null);
    }

    /**
     * Creates a tenant with its isolation (SHARED, SCHEMA or DATABASE; null = SHARED, or DATABASE on a dedicated
     * instance). A dedicated instance refuses every other tenant ({@code 409 DEDICATED_INSTANCE}).
     */
    public Tenant createTenant(Connection c, SecurityIdentity platformAdmin, String tenantId, String nameEn, String nameHi,
                               String nameLocal, String hostingProfile, Map<String, Object> quotas, String isolation) {
        requirePlatformAdmin(platformAdmin);
        validateQuotas(quotas);
        if (!tenantId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,59}")) {
            throw new SangamSecurityException.AdminConflict("INVALID_TENANT_ID", "tenant id must be 1-60 safe characters");
        }
        String iso = isolationFor(tenantId, isolation);
        if (!Sql.insertIfAbsent(c, dialect, "INSERT INTO SEC_TENANT(tenant_id, name_en, name_hi, name_local, hosting_profile, status,"
                        + " config, quotas, four_eyes, created_at, activated_at, isolation)"
                        + " VALUES (?,?,?,?,?,'CREATED','{}',?,1,?,NULL,?)",
                tenantId, nameEn, nameHi, nameLocal, hostingProfile, Sql.toJson(quotas == null ? Map.of() : quotas),
                Sql.dbNow(c, dialect), iso)) {
            throw new SangamSecurityException.AdminConflict("TENANT_EXISTS", "tenant " + tenantId + " exists");
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tenant", tenantId);
        p.put("name_en", nameEn);
        p.put("hosting_profile", hostingProfile);
        p.put("isolation", iso);
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
        validateQuotas(quotas);
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
        List<String> missing = missingEvidence(c, admin.tenantId());
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
        return Jdbc.one(c, "SELECT tenant_id, name_en, name_hi, name_local, hosting_profile, status, config, quotas, four_eyes, isolation"
                        + " FROM SEC_TENANT WHERE tenant_id=?",
                rs -> new Tenant(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), Sql.jsonMap(rs.getString(7)), Sql.jsonMap(rs.getString(8)), rs.getInt(9) == 1, isolation(rs.getString(10))),
                tenantId);
    }

    public List<Tenant> list(Connection c, SecurityIdentity platformAdmin) {
        requirePlatformAdmin(platformAdmin);
        return Jdbc.query(c, "SELECT tenant_id, name_en, name_hi, name_local, hosting_profile, status, config, quotas, four_eyes, isolation"
                        + " FROM SEC_TENANT ORDER BY tenant_id",
                rs -> new Tenant(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), Sql.jsonMap(rs.getString(7)), Sql.jsonMap(rs.getString(8)), rs.getInt(9) == 1, isolation(rs.getString(10))));
    }

    /**
     * What a background schedule or a request filter needs of a tenant (SL-117, decision 97): its id, lifecycle status
     * and isolation (SHARED, SCHEMA or DATABASE), no admin data.
     */
    public record SystemTenant(String tenantId, String status, String isolation) {
        /** A tenant on shared isolation (the column default). */
        public SystemTenant(String tenantId, String status) {
            this(tenantId, status, SHARED);
        }
    }

    /**
     * SL-117: the read-only tenant listing for background schedules (compliance, channels, AI worker, field, ops). Only
     * the system principal ({@link SecurityIdentity#system()}) may call it; it returns ids, statuses and isolations
     * only. The platform-admin listing {@link #list} and its guard are unchanged.
     */
    public List<SystemTenant> listForSystem(Connection c, SecurityIdentity system) {
        requireSystem(system);
        return Jdbc.query(c, "SELECT tenant_id, status, isolation FROM SEC_TENANT ORDER BY tenant_id",
                rs -> new SystemTenant(rs.getString(1), rs.getString(2), isolation(rs.getString(3))));
    }

    /**
     * Decision 97: the read-only system view of one tenant (id, status, isolation), for the shared-instance isolation
     * check that runs before any user permission is known. Only the system principal may call it; it reads nothing
     * else and changes nothing. Empty when the tenant does not exist.
     */
    public Optional<SystemTenant> findForSystem(Connection c, SecurityIdentity system, String tenantId) {
        requireSystem(system);
        List<SystemTenant> rows = Jdbc.query(c, "SELECT tenant_id, status, isolation FROM SEC_TENANT WHERE tenant_id = ?",
                rs -> new SystemTenant(rs.getString(1), rs.getString(2), isolation(rs.getString(3))), tenantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
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

    /** SL-43: the quota entries the product enforces must be integers (negative = unlimited). */
    private static void validateQuotas(Map<String, Object> quotas) {
        try {
            org.sangam.security.quota.JdbcTenantQuotas.validate(quotas);
        } catch (IllegalArgumentException e) {
            throw new SangamSecurityException.AdminConflict("INVALID_QUOTA", e.getMessage());
        }
    }

    static void requirePlatformAdmin(SecurityIdentity who) {
        if (!who.hasRole(PLATFORM_ADMIN_ROLE)) {
            throw new SangamSecurityException.AccessDenied("platform administrator role required", 0);
        }
    }

    static void requireSystem(SecurityIdentity who) {
        if (who == null || !who.system()) {
            throw new SangamSecurityException.AccessDenied("system principal required", 0);
        }
    }

    private static SangamSecurityException unknown(String t) {
        return new SangamSecurityException.AdminConflict("UNKNOWN_TENANT", "tenant " + t + " not found");
    }
}
