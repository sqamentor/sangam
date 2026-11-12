// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.server.config.SangamSecurityProperties;
import org.springframework.security.oauth2.jwt.Jwt;

/** SL-70 unit level: the issuer-bound group confinement of every identity source. */
class PlatformIssuersTest {

    static final String PLATFORM = "https://idp.example/realms/platform";
    static final String TENANT = "https://idp.example/realms/tenants";

    @Test
    void aTenantIdentityNeverHoldsAPlatformOnlyGroupAndSangamAdminIsTheTenantAdministrator() {
        Set<String> g = new PlatformIssuers(List.of(PLATFORM)).groups(TENANT,
                List.of("sangam-admin", "platform-admin", "sangam-platform", "sangam-system", "sangam-user"));
        assertEquals(Set.of("sangam-tenant-admin", "sangam-user"), g);
        assertFalse(PlatformIssuers.isPlatformIdentity(g));
    }

    @Test
    void aPlatformIssuerIdentityKeepsItsGroupsAndIsMarked() {
        Set<String> g = new PlatformIssuers(List.of(" " + PLATFORM + " ")).groups(PLATFORM, List.of("sangam-admin"));
        assertEquals(Set.of("sangam-admin", "platform-admin", "sangam-platform"), g);
        Set<String> op = new PlatformIssuers(List.of(PLATFORM)).groups(PLATFORM, List.of("sangam-operator"));
        assertEquals(Set.of("sangam-operator", "sangam-platform"), op, "no platform-admin without sangam-admin");
    }

    @Test
    void withoutAConfiguredPlatformIssuerNobodyIsPlatform() {
        for (PlatformIssuers p : List.of(PlatformIssuers.none(), new PlatformIssuers(List.of("", " ")))) {
            assertFalse(p.configured());
            assertFalse(p.isPlatform(PLATFORM));
            assertFalse(p.isPlatform(null));
            assertEquals(Set.of("sangam-tenant-admin"), p.groups(PLATFORM, List.of("sangam-admin")));
        }
    }

    @Test
    void issuersAreComparedExactly() {
        PlatformIssuers p = new PlatformIssuers(List.of(PLATFORM));
        assertFalse(p.isPlatform(PLATFORM + "/"));
        assertFalse(p.isPlatform(PLATFORM.toUpperCase()));
        assertTrue(p.isPlatform(PLATFORM));
    }

    @Test
    void resolvedTenantIdentityLosesPlatformRolesFromTheOrganisationModel() {
        SecurityIdentity id = new SecurityIdentity("t1", "p1", "p1", Set.of("sangam-admin", "clerks"),
                Set.of("platform-admin", "tenant-admin"), Set.of(), Set.of(), "OIDC", null, null, false, Map.of());
        SecurityIdentity t = PlatformIssuers.tenant(id);
        assertEquals(Set.of("sangam-tenant-admin", "clerks"), t.groups());
        assertEquals(Set.of("tenant-admin"), t.roles());
        SecurityIdentity sys = SecurityIdentity.system("t1", "job");
        assertEquals(sys, PlatformIssuers.tenant(sys), "system identities are internal, not tokens");
    }

    @Test
    void jwtConverterConfinesByTheVerifiedIssuer() {
        JwtPrincipalConverter c = new JwtPrincipalConverter(new SangamSecurityProperties.Claims(),
                new PlatformIssuers(List.of(PLATFORM)));
        assertEquals(Set.of("sangam-tenant-admin"), principal(c, TENANT).groups());
        assertEquals(Set.of("sangam-admin", "platform-admin", "sangam-platform"), principal(c, PLATFORM).groups());
        JwtPrincipalConverter failClosed = new JwtPrincipalConverter(new SangamSecurityProperties.Claims());
        assertEquals(Set.of("sangam-tenant-admin"), principal(failClosed, PLATFORM).groups());
    }

    @Test
    void devTokenIsPlatformOnlyWhenItsIssuerIsListed() {
        SangamSecurityProperties.DevToken cfg = new SangamSecurityProperties.DevToken();
        cfg.setToken("0123456789abcdef-sl70");
        cfg.setGroups(List.of("sangam-admin"));
        assertEquals(Set.of("sangam-tenant-admin"), devPrincipal(new DevTokenFilter(cfg)).groups());
        assertEquals(Set.of("sangam-tenant-admin"),
                devPrincipal(new DevTokenFilter(cfg, new PlatformIssuers(List.of(PLATFORM)))).groups());
        assertTrue(devPrincipal(new DevTokenFilter(cfg, new PlatformIssuers(List.of(PlatformIssuers.DEV_TOKEN_ISSUER))))
                .groups().contains("sangam-platform"));
    }

    private static SangamPrincipal principal(JwtPrincipalConverter c, String issuer) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").issuer(issuer).subject("alice")
                .claim("tenant", "t1").claim("roles", List.of("sangam-admin"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return ((SangamAuthenticationToken) c.convert(jwt)).sangamPrincipal();
    }

    private static SangamPrincipal devPrincipal(DevTokenFilter f) {
        return f.principal();
    }

    @Test
    void sl95CellAdminRoleOnlyFromAPlatformIssuer() {
        PlatformIssuers p = new PlatformIssuers(List.of(PLATFORM));
        assertEquals(Set.of("sangam-user"), p.groups(TENANT, List.of("CELL_ADMIN", "sangam-cell-admin", "sangam-user")),
                "a tenant IdP cannot grant the cell service role (it exports, imports and moves any tenant)");
        assertEquals(Set.of("CELL_ADMIN", "sangam-cell-admin", "sangam-platform"),
                p.groups(PLATFORM, List.of("CELL_ADMIN", "sangam-cell-admin")));
        assertEquals(Set.of(), PlatformIssuers.none().groups(PLATFORM, List.of("CELL_ADMIN")), "fail closed");
        SangamSecurityProperties.DevToken cfg = new SangamSecurityProperties.DevToken();
        cfg.setToken("0123456789abcdef-sl95");
        cfg.setGroups(List.of("CELL_ADMIN"));
        assertEquals(Set.of(), new DevTokenFilter(cfg).principal().groups());
    }

    @Test
    void sl95CrossCellRoleOnlyFromAPlatformIssuer() {
        PlatformIssuers p = new PlatformIssuers(List.of(PLATFORM));
        assertEquals(Set.of("sangam-worker"), p.groups(TENANT, List.of("XCELL", "sangam-worker")),
                "a tenant IdP cannot grant XCELL (a cross-cell message names any tenant in X-Sangam-Tenant)");
        assertEquals(Set.of("XCELL", "sangam-platform"), p.groups(PLATFORM, List.of("XCELL")));
        assertEquals(Set.of(), PlatformIssuers.none().groups(PLATFORM, List.of("XCELL")), "fail closed");
    }
}
