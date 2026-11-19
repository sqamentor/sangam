// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import org.sangam.packs.sim.DemoSandbox;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.port.VerifiedPayeeLookup;
import org.sangam.packs.port.VerifiedPayees;

/**
 * Owner option (b) 2026-11-19: {@code sangam.demo.sandbox-tenants} is allowed while {@code sangam.security.production}
 * stays true, and it relaxes no production guard. Each guard is asserted with the setting on, in both production forms
 * (the Helm property and a prod profile).
 */
class DemoSandboxProductionGuardsTest {

    private static MockEnvironment helm() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.security.production", "true")
                .withProperty(PacksWiring.SANDBOX_TENANTS_PROPERTY, "t-demo, t-demo2");
        env.setActiveProfiles("postgres", "oidc");
        return env;
    }

    private static MockEnvironment prodProfile() {
        MockEnvironment env = new MockEnvironment().withProperty(PacksWiring.SANDBOX_TENANTS_PROPERTY, "t-demo");
        env.setActiveProfiles("postgres", "prod");
        return env;
    }

    private static List<MockEnvironment> production() {
        return List.of(helm(), prodProfile());
    }

    @AfterEach
    void reset() {
        DemoSandbox.install(null);
        PackRuntime.reset();
    }

    @Test
    void theSettingIsInstalledInProductionAndNamesItsTenants() {
        assertEquals(Set.of("t-demo", "t-demo2"), PacksWiring.installDemoSandbox(helm()));
        assertTrue(DemoSandbox.isSandbox("t-demo"));
        assertFalse(DemoSandbox.isSandbox("t-real"));
        assertEquals(Set.of(), PacksWiring.installDemoSandbox(new MockEnvironment()), "empty by default");
        assertFalse(DemoSandbox.active());
    }

    @Test
    void productionModeStaysOn() {
        for (MockEnvironment env : production()) {
            assertTrue(ProductionEnvironment.isProduction(env));
            assertFalse(ProductionEnvironment.developmentDefaultsAllowed(env));
            assertTrue(PacksWiring.withProductionMode(PackRuntime.ports(), env).production());
        }
    }

    @Test
    void oidcValidationStaysComplete() {
        for (MockEnvironment env : production()) {
            assertThrows(IllegalStateException.class, () -> OidcConfig.requireCompleteValidation(
                    ProductionEnvironment.isProduction(env), null, null));
        }
    }

    @Test
    void trustedProxiesStayRequired() {
        for (MockEnvironment env : production()) {
            List<String> v = HostingWiring.trustedProxyViolations(env);
            assertEquals(1, v.size());
            assertTrue(v.get(0).startsWith(HostingWiring.TRUSTED_PROXIES_REQUIRED), v.get(0));
        }
    }

    @Test
    void auditSigningStaysRequired() {
        for (MockEnvironment env : production()) {
            String m = assertThrows(IllegalStateException.class, () -> AuditCheckpointWiring.requireInProduction(env))
                    .getMessage();
            assertTrue(m.contains("signed audit checkpoints are required"), m);
        }
    }

    @Test
    void theMalwareScannerStaysRequired() {
        for (MockEnvironment env : production()) {
            assertThrows(IllegalStateException.class, () -> MalwareScanWiring.requireScannerInProduction(env, false));
        }
    }

    @Test
    void thePackSimulatorFallbackStaysRefused() {
        for (MockEnvironment env : production()) {
            PackBindingsProperties props =
                    new PackBindingsProperties();
            props.setSimulatorFallback(true);
            String m = assertThrows(IllegalStateException.class, () -> new IntegrationWiring()
                    .sangamIntegrationConnectorGateway(null, null, null, null, null, null, null, null, null, null,
                            props, env)).getMessage();
            assertTrue(m.contains("simulator-fallback must not be enabled"), m);
            assertFalse(IntegrationWiring.simulatorFallback(null, env), "the default stays off");
        }
    }

    @Test
    void theDevTokenStaysRefused() {
        SangamSecurityProperties props =
                new SangamSecurityProperties();
        props.getDevToken().setEnabled(true);
        props.getDevToken().setToken("a-development-token-long-enough-for-the-check");
        for (MockEnvironment env : production()) {
            assertThrows(IllegalStateException.class, () -> SecurityConfig.devTokenFilter(props, env));
        }
    }

    @Test
    void otherTenantsKeepTheFailClosedPayeeLookup() {
        PacksWiring.installDemoSandbox(helm());
        PackRuntime.install(PacksWiring.withProductionMode(PackRuntime.ports(), helm()));
        VerifiedPayeeLookup.Result r = VerifiedPayees.resolve("t-real", "EMP-1", null, java.time.LocalDate.of(2026,
                11, 19));
        assertEquals(VerifiedPayeeLookup.Reason.PAYEE_NOT_VERIFIED, ((VerifiedPayeeLookup.NotVerified) r).reason());
        assertTrue(VerifiedPayees.resolve("t-demo", "EMP-1", null, java.time.LocalDate.of(2026, 11, 19))
                instanceof VerifiedPayeeLookup.Verified);
    }
}
