// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.integration.broker.BrokerWakeupBus;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.relay.WakeupListener;
import org.sangam.integration.relay.WakeupNotifier;
import org.springframework.mock.env.MockEnvironment;

/**
 * M-058 wiring: the bus is built only for {@code nats} or {@code redis}, takes its settings from its own prefix, is not
 * started by the factory (Spring starts and closes it), and a wrong kind or missing required setting fails start-up.
 * The redis case needs the optional client on the sangam-server classpath (W3-A REQUEST to the integrator for
 * sangam-server/pom.xml). Owner decision 232: jnats left the server, so {@code nats} without the NATS add-on is a clear
 * start-up refusal; building the NATS bus itself is covered in sangam-integration, where jnats is optional.
 */
class BrokerWakeupWiringTest {

    static final SecretResolver SECRETS = SecretResolver.of(Map.of());

    static WakeupListener listener() {
        return WakeupListener.forDialect(Dialect.MYSQL, null, WakeupNotifier.CHANNEL);
    }

    @Test
    void natsSettingsComeFromTheirOwnPrefixAndNatsWithoutTheAddOnRefusesStartUp() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("sangam.wakeup.bus", "NATS")
                .withProperty("sangam.wakeup.nats.serverUri", "nats://broker-a:4222")
                .withProperty("sangam.wakeup.nats.subject", "sangam_outbox_x")
                .withProperty("sangam.wakeup.redis.host", "not-used");
        Map<String, String> s = BrokerWakeupWiring.settings(env, "nats");
        assertEquals("nats://broker-a:4222", s.get("serverUri"));
        assertEquals("sangam_outbox_x", s.get("subject"));
        assertFalse(s.containsKey("host"), "the redis keys do not leak into the nats settings");

        assertFalse(AddonGuardWiring.natsPresent(BrokerWakeupWiring.class.getClassLoader()),
                "decision 232: the server class path has no jnats");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BrokerWakeupWiring.bus(env, SECRETS, "node-1", listener()));
        assertTrue(e.getMessage().contains("sangam.wakeup.bus=nats"), e.getMessage());
        assertTrue(e.getMessage().contains("NATS add-on"), e.getMessage());
        assertTrue(e.getMessage().contains(AddonGuardWiring.NATS_CLASS), e.getMessage());
    }

    @Test
    void redisNeedsAHost() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.wakeup.bus", "redis");
        assertThrows(IllegalArgumentException.class, () -> BrokerWakeupWiring.bus(env, SECRETS, "node-1", listener()));

        env.setProperty("sangam.wakeup.redis.host", "cache-a");
        BrokerWakeupBus bus = BrokerWakeupWiring.bus(env, SECRETS, "node-1", listener());
        assertFalse(bus.running());
        bus.close();
    }

    @Test
    void anUnknownKindFailsStartUp() {
        for (String kind : new String[] {"kafka", "", "none"}) {
            MockEnvironment env = new MockEnvironment().withProperty("sangam.wakeup.bus", kind);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> BrokerWakeupWiring.bus(env, SECRETS, "node-1", listener()), kind);
            assertTrue(e.getMessage().contains("nats or redis"), e.getMessage());
        }
    }

    @Test
    void aMissingClientClassIsAClearStartUpError() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BrokerWakeupWiring.requireClient("org.sangam.absent.BrokerClient", "nats", "io.nats:jnats"));
        assertTrue(e.getMessage().contains("io.nats:jnats"), e.getMessage());
    }
}
