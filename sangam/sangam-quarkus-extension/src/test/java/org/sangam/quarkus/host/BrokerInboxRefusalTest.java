// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.assembly.host.SangamProperties;

/**
 * Owner decision 317: until extraction phase 5 the Quarkus host refuses to start, by name, while the Kafka or the JMS
 * broker inbox is switched on with the Spring host's own keys; switched off or absent, nothing is refused.
 */
class BrokerInboxRefusalTest {

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

    private QuarkusHost host(Map<String, String> config) {
        return new QuarkusHost(SangamProperties.of(config), jta.dataSource, jta.transactionManager,
                new BoundPrincipal());
    }

    private void hostRefused(Map<String, String> config, String... named) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> host(config));
        assertTrue(e.getMessage().startsWith(BrokerInboxRefusal.CODE + ": start-up refused"), e.getMessage());
        assertTrue(e.getMessage().contains("decision 317"), e.getMessage());
        for (String key : named) {
            assertTrue(e.getMessage().contains(key), e.getMessage());
        }
    }

    @Test
    void theKeysAreTheSpringHostsOwn() {
        assertTrue("sangam.integration.kafka.enabled".equals(BrokerInboxRefusal.KAFKA_ENABLED));
        assertTrue("sangam.integration.jms.enabled".equals(BrokerInboxRefusal.JMS_ENABLED));
    }

    @Test
    void kafkaSwitchedOnRefusesTheHost() {
        hostRefused(Map.of(BrokerInboxRefusal.KAFKA_ENABLED, "true"), BrokerInboxRefusal.KAFKA_ENABLED);
    }

    @Test
    void jmsSwitchedOnRefusesTheHost() {
        hostRefused(Map.of(BrokerInboxRefusal.JMS_ENABLED, "true"), BrokerInboxRefusal.JMS_ENABLED);
    }

    @Test
    void bothSwitchedOnAreBothNamed() {
        hostRefused(Map.of(BrokerInboxRefusal.KAFKA_ENABLED, "true", BrokerInboxRefusal.JMS_ENABLED, "true"),
                BrokerInboxRefusal.KAFKA_ENABLED, BrokerInboxRefusal.JMS_ENABLED);
    }

    @Test
    void everySpellingOfOnIsRefused() {
        for (String on : new String[] {"TRUE", "on", "yes", "1", " true "}) {
            hostRefused(Map.of(BrokerInboxRefusal.KAFKA_ENABLED, on), BrokerInboxRefusal.KAFKA_ENABLED);
        }
    }

    @Test
    void theRefusalNamesOnlyTheSwitchThatIsOn() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> host(Map.of(BrokerInboxRefusal.JMS_ENABLED, "true", BrokerInboxRefusal.KAFKA_ENABLED, "false")));
        assertFalse(e.getMessage().contains(BrokerInboxRefusal.KAFKA_ENABLED), e.getMessage());
    }

    @Test
    void aValueThatIsNotABooleanFailsWithTheKeyNamed() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> host(Map.of(BrokerInboxRefusal.KAFKA_ENABLED, "maybe")));
        assertTrue(e.getMessage().contains(BrokerInboxRefusal.KAFKA_ENABLED), e.getMessage());
    }

    @Test
    void switchedOffOrAbsentNothingIsRefused() {
        assertDoesNotThrow(() -> host(Map.of()));
        assertDoesNotThrow(() -> host(Map.of(BrokerInboxRefusal.KAFKA_ENABLED, "false",
                BrokerInboxRefusal.JMS_ENABLED, "false")));
    }
}
