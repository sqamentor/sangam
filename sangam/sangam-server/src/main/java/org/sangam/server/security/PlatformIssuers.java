// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.rest.support.Permissions;
import org.sangam.security.admin.TenantAdminService;

/**
 * SL-70 (owner decision 2026-11-10, "Platform issuer"): which identities may act as the <b>platform</b> (every
 * tenant) rather than as one tenant. The decision is bound to the token's verified issuer and is taken once, where
 * the identity is built (JWT converter, dev token, API token, resolved identity), so every later check of a group
 * ({@code ctx.inGroup("sangam-admin")}, URL authorities, sangam-security roles) sees only what the issuer may grant.
 * <ul>
 *   <li>Only a token whose issuer is listed in {@code sangam.security.platform-issuer} is a platform identity. It
 *       keeps its groups and gets the marker group {@value Permissions#GROUP_PLATFORM}; with {@code sangam-admin} it is
 *       the platform administrator (also the sangam-security role {@code platform-admin}).</li>
 *   <li>Every other identity is a tenant identity: the platform-only groups and roles ({@link #PLATFORM_ONLY}) are
 *       removed, and {@code sangam-admin} becomes {@value Permissions#GROUP_TENANT_ADMIN}, the administrator of the
 *       token's own tenant.</li>
 *   <li>No platform issuer configured: nobody is a platform identity (fail closed).</li>
 * </ul>
 * Issuers are compared exactly (after trimming), as OIDC requires. API tokens ({@code sgm_...}) are always tenant
 * identities.
 */
public final class PlatformIssuers {

    /** Issuer of the development static token ({@code sangam.security.dev-token.issuer} default). */
    public static final String DEV_TOKEN_ISSUER = "urn:sangam:dev-token";

    /**
     * Groups and roles that grant power over other tenants or the whole engine. A tenant identity never holds them:
     * {@code sangam-admin} (platform administrator), the sangam-security role {@code platform-admin}, the platform
     * marker {@code sangam-platform} and the internal system group {@code sangam-system} (untenanted runtime objects,
     * every tenant's admin records). SL-95: also the cell service role ({@link #CELL_ADMIN_GROUPS}), which exports,
     * imports and moves any tenant of the cell ({@code /api/v1/admin/cell/**}).
     */
    public static final Set<String> PLATFORM_ONLY;

    /**
     * SL-95: the cell service roles: tenant admin of the cell ({@code /api/v1/admin/cell/**}, SecurityConfig) and the
     * cross-cell message role {@code XCELL} ({@code CellCrossCellInboxFilter}; the target tenant of such a message is
     * the {@code X-Sangam-Tenant} header, so the role reaches every tenant of the cell).
     */
    public static final Set<String> CELL_ADMIN_GROUPS = Set.of("CELL_ADMIN", "sangam-cell-admin",
            org.sangam.server.bridge.CellCrossCellInboxFilter.ROLE);

    static {
        Set<String> s = new LinkedHashSet<>(List.of(Permissions.GROUP_ADMIN, TenantAdminService.PLATFORM_ADMIN_ROLE,
                Permissions.GROUP_PLATFORM, Permissions.GROUP_SYSTEM,
                // decision 197: the platform deployer writes the shared-deployment registry; never from a tenant token
                org.sangam.server.bridge.SecurityPermissionBridge.GROUP_PLATFORM_DEPLOYER));
        s.addAll(CELL_ADMIN_GROUPS);
        PLATFORM_ONLY = Set.copyOf(s);
    }

    private static final PlatformIssuers NONE = new PlatformIssuers(List.of());

    private final Set<String> issuers;

    public PlatformIssuers(Collection<String> issuers) {
        Set<String> s = new LinkedHashSet<>();
        if (issuers != null) {
            for (String i : issuers) {
                if (i != null && !i.isBlank()) {
                    s.add(i.trim());
                }
            }
        }
        this.issuers = Set.copyOf(s);
    }

    /** No platform issuer: every identity is a tenant identity. */
    public static PlatformIssuers none() {
        return NONE;
    }

    /** The configured platform issuers (trimmed, no blanks). */
    public Set<String> issuers() {
        return issuers;
    }

    public boolean configured() {
        return !issuers.isEmpty();
    }

    /** True only when {@code issuer} is one of the configured platform issuers. */
    public boolean isPlatform(String issuer) {
        return issuer != null && issuers.contains(issuer.trim());
    }

    /** The groups an identity issued by {@code issuer} holds (see the class). */
    public Set<String> groups(String issuer, Collection<String> groups) {
        return isPlatform(issuer) ? platform(groups) : tenant(groups);
    }

    /** A platform identity's groups: unchanged, plus the platform marker (and {@code platform-admin} for an admin). */
    static Set<String> platform(Collection<String> groups) {
        Set<String> out = new LinkedHashSet<>(groups == null ? Set.of() : groups);
        out.add(Permissions.GROUP_PLATFORM);
        if (out.contains(Permissions.GROUP_ADMIN)) {
            out.add(TenantAdminService.PLATFORM_ADMIN_ROLE);
        }
        return Set.copyOf(out);
    }

    /** A tenant identity's groups or roles: no platform-only entry; {@code sangam-admin} = tenant administrator. */
    public static Set<String> tenant(Collection<String> groups) {
        Set<String> out = new LinkedHashSet<>(groups == null ? Set.of() : groups);
        boolean admin = out.contains(Permissions.GROUP_ADMIN);
        out.removeAll(PLATFORM_ONLY);
        if (admin) {
            out.add(Permissions.GROUP_TENANT_ADMIN);
        }
        return Set.copyOf(out);
    }

    /** A tenant identity: groups and roles (also those from the organisation model) without platform-only entries. */
    public static SecurityIdentity tenant(SecurityIdentity id) {
        if (id == null || id.system()) {
            return id;
        }
        return id.withGroupsAndRoles(tenant(id.groups()), tenant(id.roles()));
    }

    /** True when the (already confined) groups are those of a platform identity. */
    public static boolean isPlatformIdentity(Collection<String> groups) {
        return groups != null && groups.contains(Permissions.GROUP_PLATFORM);
    }
}
