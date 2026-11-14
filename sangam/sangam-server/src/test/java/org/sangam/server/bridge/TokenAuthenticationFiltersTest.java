// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.persistence.SangamSchema;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.spring.SangamAuthenticationToken;
import org.sangam.security.oidc.SecurityProperties;
import org.sangam.security.spring.TokenAuthenticationFilters;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

/**
 * SL-107 (conformance audit): direct unit tests of {@link TokenAuthenticationFilters}. The API-token filter
 * ({@code sgm_...}) and the dev static-token filter authenticate, refuse, or pass through, and both store the
 * authentication for the whole request (APPROVE-401: a later error dispatch of the request must not be anonymous).
 */
class TokenAuthenticationFiltersTest {

    static final String T = "t-sl107";

    private SangamSecurity security;
    private Transactions tx;

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:taf" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        tx = Transactions.of(ds);
        Instant past = Instant.parse("2020-01-01T00:00:00Z");
        tx.inTransaction(c -> {
            Jdbc.update(c, "INSERT INTO SEC_TENANT(tenant_id, name_en, status, config, quotas, four_eyes, created_at)"
                    + " VALUES (?,?,?,?,?,?,?)", T, "t", "ACTIVE", "{}", "{}", 0, past);
            Jdbc.update(c, "INSERT INTO SEC_PERSON(tenant_id, person_id, display_name, external_subject, status, created_at)"
                    + " VALUES (?,?,?,?,?,?)", T, "owner", "Owner", "sub-owner", "ACTIVE", past);
            return null;
        });
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private String issueToken() {
        SecurityIdentity owner = new SecurityIdentity(T, "owner", "sub-owner", Set.of(), Set.of(), Set.of(), Set.of(),
                "OIDC", null, null, false, Map.of());
        return tx.inTransaction(c -> security.tokens().issue(c, owner, "owner", "sl107", Set.of(),
                Instant.now().plusSeconds(3600))).plaintext();
    }

    private static MockHttpServletRequest request(String bearer) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/tasks");
        if (bearer != null) {
            r.addHeader("Authorization", "Bearer " + bearer);
        }
        return r;
    }

    /** The authentication stored for the request (what a later error dispatch of the same request sees). */
    private static Authentication stored(MockHttpServletRequest r) {
        return new RequestAttributeSecurityContextRepository().loadDeferredContext(r).get().getAuthentication();
    }

    @Test
    void aValidApiTokenAuthenticatesItsOwnerForTheWholeRequest() throws Exception {
        TokenAuthenticationFilters.ApiTokenFilter f = new TokenAuthenticationFilters.ApiTokenFilter(tx, security.tokens());
        MockHttpServletRequest req = request(issueToken());
        boolean[] passed = {false};
        f.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> passed[0] = true);
        assertTrue(passed[0]);
        SangamAuthenticationToken a = assertInstanceOf(SangamAuthenticationToken.class,
                SecurityContextHolder.getContext().getAuthentication());
        assertEquals(T, a.identity().tenantId());
        assertEquals("owner", a.identity().personId());
        assertEquals("API_KEY", a.identity().authMethod());
        assertFalse(a.resolved());
        assertEquals(a, stored(req), "stored for the request (APPROVE-401)");
    }

    @Test
    void anInvalidApiTokenIs401AndNothingRuns() throws Exception {
        TokenAuthenticationFilters.ApiTokenFilter f = new TokenAuthenticationFilters.ApiTokenFilter(tx, security.tokens());
        for (String bad : new String[] {"sgm_nosuchtoken.secret", "sgm_nodot", issueToken() + "x"}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            boolean[] passed = {false};
            f.doFilter(request(bad), res, (rq, rs) -> passed[0] = true);
            assertEquals(401, res.getStatus(), bad);
            assertTrue(res.getContentAsString().contains("UNAUTHENTICATED"), res.getContentAsString());
            assertFalse(passed[0], bad);
            assertNull(SecurityContextHolder.getContext().getAuthentication(), bad);
        }
    }

    @Test
    void otherBearersAndNoTokenPassThroughUntouched() throws Exception {
        TokenAuthenticationFilters.ApiTokenFilter f = new TokenAuthenticationFilters.ApiTokenFilter(tx, security.tokens());
        for (String other : new String[] {null, "eyJhbGciOiJSUzI1NiJ9.e30.sig"}) {
            boolean[] passed = {false};
            f.doFilter(request(other), new MockHttpServletResponse(), (rq, rs) -> passed[0] = true);
            assertTrue(passed[0]);
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }

    @Test
    void theDevStaticTokenFilterRefusesAProductionProfile() {
        SecurityProperties p = new SecurityProperties();
        p.setProduction(true);
        assertThrows(IllegalStateException.class, () -> new TokenAuthenticationFilters.DevStaticTokenFilter(p));
    }

    @Test
    void aConfiguredDevStaticTokenAuthenticatesItsIdentityForTheWholeRequest() throws Exception {
        SecurityProperties p = new SecurityProperties();
        p.setDevMode(true);
        p.setDevTokens(Map.of("dev-static-1", T + "|alice|tenant-admin|section-a"));
        TokenAuthenticationFilters.DevStaticTokenFilter f = new TokenAuthenticationFilters.DevStaticTokenFilter(p);
        MockHttpServletRequest req = request("dev-static-1");
        f.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> { });
        SangamAuthenticationToken a = assertInstanceOf(SangamAuthenticationToken.class,
                SecurityContextHolder.getContext().getAuthentication());
        assertEquals(T, a.identity().tenantId());
        assertEquals("alice", a.identity().personId());
        assertEquals(Set.of("tenant-admin"), a.identity().roles());
        assertEquals(Set.of("section-a"), a.identity().groups());
        assertEquals("DEV_STATIC", a.identity().authMethod());
        assertEquals(a, stored(req), "stored for the request (APPROVE-401)");
    }

    @Test
    void anUnknownDevTokenOrDevModeOffAuthenticatesNobody() throws Exception {
        SecurityProperties on = new SecurityProperties();
        on.setDevMode(true);
        on.setDevTokens(Map.of("dev-static-1", T + "|alice"));
        SecurityProperties off = new SecurityProperties();
        off.setDevTokens(Map.of("dev-static-1", T + "|alice"));
        for (Object[] c : new Object[][] {{on, "dev-static-2"}, {off, "dev-static-1"}}) {
            boolean[] passed = {false};
            new TokenAuthenticationFilters.DevStaticTokenFilter((SecurityProperties) c[0])
                    .doFilter(request((String) c[1]), new MockHttpServletResponse(), (rq, rs) -> passed[0] = true);
            assertTrue(passed[0]);
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }
}
