// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sangam.server.config.ProductionDatabaseCredentials.PASSWORD;
import static org.sangam.server.config.ProductionDatabaseCredentials.USERNAME;

/** SL-103: production refuses database credentials that were not injected, naming the variable, never the value. */
class ProductionDatabaseCredentialsTest {

    private static final String SHIPPED_PASSWORD = "Sangam_Passw0rd";

    /** A database profile as shipped: credentials are placeholders with defaults. */
    private static MockEnvironment profile(String userDefault, String passwordDefault) {
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addLast(new MapPropertySource("application-db", Map.of(
                USERNAME, "${SANGAM_DB_USER:" + userDefault + "}",
                PASSWORD, "${SANGAM_DB_PASSWORD:" + passwordDefault + "}")));
        return env;
    }

    @Test
    void notProductionNothingIsChecked() {
        assertTrue(ProductionDatabaseCredentials.violations(profile("sangam", "sangam")).isEmpty());
    }

    @Test
    void unsetVariablesAreRefusedAndNamed() {
        MockEnvironment env = profile("sangam", SHIPPED_PASSWORD).withProperty("sangam.security.production", "true");
        List<String> bad = ProductionDatabaseCredentials.violations(env);
        assertEquals(2, bad.size(), bad::toString);
        assertTrue(bad.get(0).startsWith("SANGAM_DB_USER is unset"), bad::toString);
        assertTrue(bad.get(1).startsWith("SANGAM_DB_PASSWORD is unset"), bad::toString);
        assertFalse(bad.toString().contains(SHIPPED_PASSWORD), "never prints a value");
    }

    @Test
    void passwordEqualToTheShippedDefaultIsRefusedWithoutPrintingIt() {
        MockEnvironment env = profile("sangam", SHIPPED_PASSWORD).withProperty("sangam.security.production", "true")
                .withProperty("SANGAM_DB_USER", "app").withProperty("SANGAM_DB_PASSWORD", SHIPPED_PASSWORD);
        List<String> bad = ProductionDatabaseCredentials.violations(env);
        assertEquals(List.of("SANGAM_DB_PASSWORD equals the default shipped in the database profile"), bad);
    }

    @Test
    void emptyDefaultIsADefaultToo() {
        MockEnvironment env = profile("sa", "").withProperty("sangam.security.production", "true")
                .withProperty("SANGAM_DB_USER", "app").withProperty("SANGAM_DB_PASSWORD", "");
        assertEquals(List.of("SANGAM_DB_PASSWORD equals the default shipped in the database profile"),
                ProductionDatabaseCredentials.violations(env));
    }

    @Test
    void injectedCredentialsPassAndTheDocumentedUserNameIsAllowed() {
        MockEnvironment env = profile("sangam", SHIPPED_PASSWORD).withProperty("sangam.security.production", "true")
                .withProperty("SANGAM_DB_USER", "sangam").withProperty("SANGAM_DB_PASSWORD", "from-the-secret");
        assertTrue(ProductionDatabaseCredentials.violations(env).isEmpty());
    }

    @Test
    void aDirectHigherPrecedenceValueWinsAndMustNotBeBlank() {
        MockEnvironment ok = profile("sangam", SHIPPED_PASSWORD).withProperty("sangam.security.production", "true")
                .withProperty(USERNAME, "app").withProperty(PASSWORD, "from-the-secret");
        assertTrue(ProductionDatabaseCredentials.violations(ok).isEmpty(), () -> ProductionDatabaseCredentials.violations(ok).toString());

        MockEnvironment blank = profile("sangam", SHIPPED_PASSWORD).withProperty("sangam.security.production", "true")
                .withProperty(USERNAME, "app").withProperty(PASSWORD, " ");
        assertEquals(List.of(PASSWORD + " is unset or blank"), ProductionDatabaseCredentials.violations(blank));
    }

    @Test
    void everyShippedDatabaseProfileIsCoveredByTheRule() throws IOException {
        for (String db : List.of("postgres", "oracle", "sqlserver", "mysql", "h2")) {
            MockEnvironment env = new MockEnvironment().withProperty("sangam.security.production", "true");
            for (PropertySource<?> ps : new YamlPropertySourceLoader().load("application-" + db,
                    new ClassPathResource("application-" + db + ".yaml"))) {
                env.getPropertySources().addLast(ps);
            }
            List<String> bad = ProductionDatabaseCredentials.violations(env);
            assertTrue(bad.stream().anyMatch(b -> b.startsWith("SANGAM_DB_USER is unset")), db + ": " + bad);
            assertTrue(bad.stream().anyMatch(b -> b.startsWith("SANGAM_DB_PASSWORD is unset")), db + ": " + bad);
        }
    }

    @Test
    void theProductionGuardFailsStartUpWithTheCredentialMessage() {
        MockEnvironment env = profile("sangam", SHIPPED_PASSWORD).withProperty("sangam.security.production", "true")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.internal:5432/s");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> HostingWiring.sangamProductionUrlGuard(env).postProcessBeanFactory(null));
        assertTrue(e.getMessage().contains("database credentials are not injected (SL-103)"), e.getMessage());
        assertTrue(e.getMessage().contains("SANGAM_DB_PASSWORD is unset"), e.getMessage());
        assertFalse(e.getMessage().contains("loopback host"), e.getMessage());
    }
}
