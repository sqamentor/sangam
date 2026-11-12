// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.rest.modules.connectorshealth.CdsClientVerifier;
import org.sangam.rest.support.TxSupport;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

/**
 * SL-138 / M-006 server wiring: the CDS Hooks routes are open at the filter chain because the controller checks the
 * CDS client's own JWT, so the Sangam JWT decoder must never see a token from those routes (it would answer 401 before
 * the controller); every other route keeps its bearer token. The {@link CdsClientVerifier} bean comes from
 * {@code sangam.connectors-health.cds-service}.
 */
class CdsServicesSecurityTest {

    private static MockHttpServletRequest request(String path, String bearer) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
        r.addHeader("Authorization", "Bearer " + bearer);
        return r;
    }

    @Test
    void theCdsRoutesAreTheTwoHl7Paths() {
        assertArrayEquals(new String[] {"/cds-services", "/cds-services/**"}, SecurityConfig.CDS_SERVICES_PATHS);
    }

    @Test
    void noTokenFromTheCdsRoutesReachesTheSangamDecoder() {
        BearerTokenResolver resolver = SecurityConfig.bearerResolver(null);
        assertNull(resolver.resolve(request("/cds-services", "ehr.signed.jwt")), "discovery");
        assertNull(resolver.resolve(request("/cds-services/order-sign-crd", "ehr.signed.jwt")), "a service call");
        assertNull(resolver.resolve(request("/cds-services/order-sign-crd/feedback", "ehr.signed.jwt")), "deeper path");
    }

    @Test
    void everyOtherRouteKeepsItsBearerToken() {
        BearerTokenResolver resolver = SecurityConfig.bearerResolver(null);
        assertEquals("tenant.jwt", resolver.resolve(request("/api/v1/me", "tenant.jwt")));
        assertEquals("tenant.jwt", resolver.resolve(request("/cds-servicesx", "tenant.jwt")), "not a CDS Hooks path");
        assertEquals("tenant.jwt", resolver.resolve(request("/api/v1/cds-services", "tenant.jwt")), "not at the root");
        assertNull(resolver.resolve(request("/api/v1/me", SecurityConfig.API_TOKEN_PREFIX + "abc")),
                "API tokens still go to sangam-security's filter, as before");
    }

    /** H2 product pool with the SEC_CLIENT_JTI shape of generate_changelogs.py 0038-02 (H2 types). */
    static TxSupport tx() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cdsjti" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUser("sa");
        ds.setPassword("");
        TxSupport tx = new TxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds));
        tx.newTransactions().inTransaction(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE SEC_CLIENT_JTI (replay_key VARBINARY(32) NOT NULL, issuer VARCHAR(1000) NOT NULL,"
                        + " expires_at TIMESTAMP WITH TIME ZONE NOT NULL, first_used_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                        + " CONSTRAINT PK_SEC_CLIENT_JTI PRIMARY KEY (replay_key))");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        return tx;
    }

    @Test
    void noRegisteredClientMeansNoVerifierInstance() {
        assertNull(new HealthConnectorsWiring().sangamCdsClientVerifier(new HealthConnectorsProperties(), tx()));
    }

    /** SL-138: the bean's verifier uses the cluster-wide jti guard: a replayed token is refused (JdbcReplayGuard). */
    @Test
    void aRegisteredClientGivesTheVerifierOfThoseSettingsWithTheClusterReplayGuard() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
        HealthConnectorsProperties props = new HealthConnectorsProperties();
        HealthConnectorsProperties.CdsService configured =
                HealthConnectorsPropertiesCdsTest.service(key, "https://sangam.example.org");
        props.getCdsService().setBaseUrl(configured.getBaseUrl());
        props.getCdsService().setClients(configured.getClients());
        TxSupport tx = tx();
        CdsClientVerifier v = new HealthConnectorsWiring().sangamCdsClientVerifier(props, tx);
        assertNotNull(v);
        String token = HealthConnectorsPropertiesCdsTest.token(key, "https://sangam.example.org/cds-services");
        assertEquals(HealthConnectorsPropertiesCdsTest.EHR, v.verify(token, "/cds-services"));
        CdsClientVerifier.Rejected replayed = assertThrows(CdsClientVerifier.Rejected.class,
                () -> v.verify(token, "/cds-services"));
        assertEquals("REPLAYED", replayed.reason());
        int rows = tx.newTransactions().inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM SEC_CLIENT_JTI",
                rs -> rs.getInt(1)).orElse(0));
        assertEquals(1, rows, "the jti is in the shared table, not in a node-local map");
        CdsClientVerifier otherNode = new HealthConnectorsWiring().sangamCdsClientVerifier(props, tx);
        assertEquals("REPLAYED", assertThrows(CdsClientVerifier.Rejected.class,
                () -> otherNode.verify(token, "/cds-services")).reason(), "another node's verifier sees the same use");
    }
}