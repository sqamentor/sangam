// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.billing.BillingConnector;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.layer.Transactions;
import org.sangam.core.power.ManifestOperationMetadata;
import org.sangam.core.power.OperationMetadata;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.ResourcesContext;
import org.sangam.resources.dialect.ResourcesDialect;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.rest.support.RestProperties;
import org.sangam.security.SangamSecurity;
import org.sangam.security.delegation.DelegationOfPowersService;
import org.sangam.security.tenant.TenantContext;
import org.sangam.server.bridge.RequestActorSource;
import org.sangam.server.bridge.SecurityIdentities;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

/**
 * Server wiring of the hardening merged from the billing, resources and security modules.
 * <ul>
 *   <li>A: the billing connector gets the server's {@link BillingConnector.ActorSource}: the authenticated identity
 *       bound to the calling thread, so a posting made for a signed-in person is recorded for that person and no
 *       longer as the connector's system actor.</li>
 *   <li>C: with the security module off, the resources module is built unguarded only in a validated development
 *       environment; everywhere else the start is refused (before, that configuration failed in every environment).</li>
 *   <li>D: the server binds the trusted operation metadata of power checks (the deployed pack manifests); unbound,
 *       every power check was DENY.</li>
 * </ul>
 */
class ServerActorAndAuthorizationWiringTest {

    private static MockEnvironment development() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.security.production", "false");
        env.setActiveProfiles("h2");
        return env;
    }

    private static ConnectorRequest request(String tenant, Map<String, Object> input) {
        return new ConnectorRequest(tenant, UUID.randomUUID(), "att-1", 1, "root-1", "BK-1", "collect", input, null);
    }

    private static SecurityIdentity person(String tenant, String principal) {
        return SecurityIdentities.fromPrincipal(new SangamPrincipal(tenant, principal, Set.of("sangam-user"), "OIDC", Map.of()), null);
    }

    // ---------------------------------------------------------------- A

    @Test
    void theBillingConnectorRecordsTheAuthenticatedIdentityOfTheCallingThread() {
        BillingConnector.ActorSource actors = BillingWiring.actorSource();
        assertInstanceOf(RequestActorSource.class, actors);

        assertNull(TenantContext.runAs(null, () -> actors.authenticatedActor(request("T1", Map.of()))),
                "no identity on the thread (an engine job): nobody is authenticated");
        assertNull(TenantContext.runAs(SecurityIdentity.system("T1", "retention"),
                () -> actors.authenticatedActor(request("T1", Map.of()))), "a system identity is not a person");

        SecurityIdentity cashier = person("T1", "user:cashier");
        assertEquals("user:cashier", TenantContext.runAs(cashier, () -> actors.authenticatedActor(request("T1", Map.of()))));
        assertEquals("user:cashier", TenantContext.runAs(cashier,
                () -> actors.authenticatedActor(request("T1", Map.of("actor", "user:cashier")))));
        assertEquals("user:cashier", TenantContext.runAs(cashier,
                () -> actors.authenticatedActor(request("T1", Map.of("actor", "user:somebody-else")))),
                "an input actor never becomes the authenticated actor (the connector then answers ACTOR_MISMATCH)");
        assertThrows(IllegalStateException.class, () -> TenantContext.runAs(cashier,
                () -> actors.authenticatedActor(request("T2", Map.of()))), "an identity of another tenant is refused");
    }

    // ---------------------------------------------------------------- C

    @Test
    void withoutTheSecurityModuleResourcesStartOnlyInDevelopment() {
        ResourcesContext.Builder dev = ResourcesContext.builder(ResourcesDialect.H2);
        ResourcesWiring.authorization(dev, null, development());
        assertNotNull(dev.build(), "the development start without the security module (decision 75) builds");

        MockEnvironment helm = new MockEnvironment().withProperty("sangam.security.production", "true");
        helm.setActiveProfiles("postgres", "oidc");
        MockEnvironment prodProfile = new MockEnvironment();
        prodProfile.setActiveProfiles("postgres", "prod");
        IllegalStateException production = assertThrows(IllegalStateException.class,
                () -> ResourcesWiring.authorization(ResourcesContext.builder(ResourcesDialect.H2), null, helm));
        assertTrue(production.getMessage().contains("sangam-resources cannot run without authorization"),
                production.getMessage());
        // an environment that does not state the switch is refused as well (by the environment policy itself)
        for (MockEnvironment env : new MockEnvironment[] {prodProfile, new MockEnvironment()}) {
            assertThrows(IllegalStateException.class,
                    () -> ResourcesWiring.authorization(ResourcesContext.builder(ResourcesDialect.H2), null, env));
        }
    }

    // ---------------------------------------------------------------- D

    @Test
    void theServerBindsTheTrustedOperationMetadataOfPowerChecks() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:powermeta" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SecurityAuditPoolWiring.SecurityAuditPool pool = SecurityAuditPoolWiring.pool(ds, 2);
        SangamSecurity security = new SecurityWiring().sangamSecurity(ds, Transactions.of(ds), new RestProperties(),
                new MockEnvironment(), pool, new StaticListableBeanFactory().getBeanProvider(ProcessEngine.class));

        Field f = DelegationOfPowersService.class.getDeclaredField("metadata");
        f.setAccessible(true);
        OperationMetadata bound = (OperationMetadata) f.get(security.powers());
        assertInstanceOf(ManifestOperationMetadata.class, bound,
                "the deployed pack manifests, not the unbound deny-everything default");
        // without an engine, or for a case type no pack deployment defines, nothing is classified: the check denies
        assertEquals(Optional.empty(), bound.classify("T1", "no-such-case-type", 1, "sanction"));
    }
}
