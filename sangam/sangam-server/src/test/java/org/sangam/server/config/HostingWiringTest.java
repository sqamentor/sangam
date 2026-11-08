// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SL-18 (1): on a production profile no externally used URL may be unset or point at a loopback host. */
class HostingWiringTest {

    @Test
    void nonProductionAllowsLoopbackDefaults() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/s");
        assertTrue(HostingWiring.loopbackViolations(env).isEmpty());
    }

    @Test
    void productionRefusesLoopbackOrUnsetUrls() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.security.production", "true")
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/s")
                .withProperty("sangam.cell.public-url", "http://127.0.0.1:8080")
                .withProperty("sangam.ai.mcp.rest-base-url", "http://127.0.0.1:8080");
        List<String> bad = HostingWiring.loopbackViolations(env);
        assertTrue(bad.stream().anyMatch(b -> b.startsWith("spring.datasource.url=")), bad::toString);
        assertTrue(bad.stream().anyMatch(b -> b.startsWith("sangam.cell.public-url=")), bad::toString);
        assertFalse(bad.stream().anyMatch(b -> b.startsWith("sangam.ai.mcp")), "self-call URL is allowed");
        assertThrows(IllegalStateException.class,
                () -> HostingWiring.sangamProductionUrlGuard(env).postProcessBeanFactory(null));

        MockEnvironment unset = new MockEnvironment().withProperty("sangam.security.production", "true");
        assertFalse(HostingWiring.loopbackViolations(unset).isEmpty());
    }

    @Test
    void productionAcceptsConfiguredHosts() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.security.production", "true")
                .withProperty("spring.datasource.url", "jdbc:oracle:thin:@//ora-scan.dc.example:1521/SANGAM")
                .withProperty("sangam.security.issuer-uri", "https://sso.example.org/realms/sangam");
        assertTrue(HostingWiring.loopbackViolations(env).isEmpty(), () -> HostingWiring.loopbackViolations(env).toString());
    }

    @Test
    void loopbackDetection() {
        assertTrue(HostingWiring.isLoopback("jdbc:sqlserver://localhost:1433;databaseName=s"));
        assertTrue(HostingWiring.isLoopback("jdbc:oracle:thin:@//localhost:1521/FREEPDB1"));
        assertTrue(HostingWiring.isLoopback("http://[::1]:8080/x"));
        assertFalse(HostingWiring.isLoopback("https://localhost.example.org/"));
        assertFalse(HostingWiring.isLoopback("jdbc:postgresql://db.internal:5432/s"));
    }
}
