// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.assembly.host.Capabilities;
import org.sangam.assembly.host.Capability;
import org.sangam.assembly.host.CapabilityMissingException;
import org.sangam.assembly.host.SangamProperties;

/**
 * Decision 279: each Quarkus seam is an optional bean; without its bean the host does not hand the seam over, its
 * capability is missing, and the engine (before any database access) and the runtime refuse the start naming it.
 * Decision 278: the host never claims HTTP or OIDC over HTTP. Written for the test phase (not run).
 */
class QuarkusSeamsTest {

    @TempDir
    Path objectStore;

    private JtaFixture jta;

    @BeforeEach
    void start() throws SQLException {
        jta = JtaFixture.start(objectStore);
    }

    @AfterEach
    void stop() {
        jta.close();
    }

    private QuarkusHost host(UnaryOperator<QuarkusHost.Seams> remove) {
        QuarkusHost.Seams all = QuarkusHost.Seams.all(SangamProperties.of(Map.of()), new BoundPrincipal());
        return new QuarkusHost(remove.apply(all), jta.dataSource, jta.transactionManager);
    }

    private void refusedFor(QuarkusHost host, Capability missing) {
        assertFalse(host.seams().capabilities().contains(missing), missing.id() + " is still claimed");
        String marker = missing.id() + " (" + missing.whenMissing() + ")";
        CapabilityMissingException atEngine = assertThrows(CapabilityMissingException.class,
                () -> new EngineCapabilityGuard(host).refuseHostGaps());
        assertTrue(atEngine.getMessage().contains(marker), atEngine.getMessage());
        CapabilityMissingException atStart = assertThrows(CapabilityMissingException.class, host.runtime()::start);
        assertTrue(atStart.getMessage().contains(marker), atStart.getMessage());
    }

    @Test
    void withoutTheConfigSourceBean() {
        refusedFor(host(s -> new QuarkusHost.Seams(null, s.lifecycle(), s.scheduling(), s.security())),
                Capabilities.CONFIG_SOURCE);
    }

    @Test
    void withoutTheLifecycleBean() {
        refusedFor(host(s -> new QuarkusHost.Seams(s.config(), false, s.scheduling(), s.security())),
                Capabilities.LIFECYCLE);
    }

    @Test
    void withoutTheSchedulingBeanThereIsNoStandaloneFallback() {
        QuarkusHost host = host(s -> new QuarkusHost.Seams(s.config(), s.lifecycle(), false, s.security()));
        refusedFor(host, Capabilities.SCHEDULING);
        assertThrows(CapabilityMissingException.class, () -> host.seams().scheduler());
    }

    @Test
    void withoutTheSecurityContextBean() {
        refusedFor(host(s -> new QuarkusHost.Seams(s.config(), s.lifecycle(), s.scheduling(), null)),
                Capabilities.SECURITY_CONTEXT);
    }

    @Test
    void withEverySeamTheFourAreSupplied() {
        QuarkusHost host = host(UnaryOperator.identity());
        for (Capability c : List.of(Capabilities.CONFIG_SOURCE, Capabilities.LIFECYCLE, Capabilities.SCHEDULING,
                Capabilities.SECURITY_CONTEXT)) {
            assertTrue(host.seams().capabilities().contains(c), c.id());
        }
    }

    @Test
    void theHostNeverClaimsTheSpringOnlyConveniences() {
        QuarkusHost host = host(UnaryOperator.identity());
        for (Capability c : Capabilities.SPRING_ONLY) {
            assertFalse(host.seams().capabilities().contains(c), c.id());
        }
        assertTrue(host.seams().http().isEmpty());
    }
}
