// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.relay.WakeupNotifier;
import org.sangam.ports.events.channel.ChannelAdapters;
import org.springframework.mock.env.MockEnvironment;

/**
 * M-066 wiring (decision 101) and the Q4 channel secret chain: the announcement goes through the relay's one notifier
 * once it exists (none at start-up = no hint, no failure); the window comes from its property; and channel secrets
 * resolve through the platform resolver first, with the static {@code sangam.ports.secrets.*} map only as its last link.
 */
class WorkAnnounceWiringTest {

    @Test
    void theHintGoesThroughTheCurrentNotifierAndIsSkippedBeforeItExists() {
        AtomicReference<WakeupNotifier> current = new AtomicReference<>();
        Runnable hint = WorkAnnounceWiring.hint(current::get);
        hint.run();   // the engine may commit work before the relay's notifier bean exists

        List<Integer> sent = new CopyOnWriteArrayList<>();
        current.set(sent::add);
        hint.run();
        assertEquals(List.of(1), sent);
    }

    @Test
    void theWindowComesFromItsPropertyAndANegativeOneFailsStartUp() {
        assertEquals(WorkAnnounceWiring.DEFAULT_COALESCE, WorkAnnounceWiring.coalesce(new MockEnvironment()));
        assertEquals(Duration.ofMillis(50),
                WorkAnnounceWiring.coalesce(new MockEnvironment().withProperty("sangam.work.announce.coalesce", "50ms")));
        assertEquals(Duration.ZERO,
                WorkAnnounceWiring.coalesce(new MockEnvironment().withProperty("sangam.work.announce.coalesce", "0s")));
        assertThrows(IllegalStateException.class,
                () -> WorkAnnounceWiring.coalesce(new MockEnvironment().withProperty("sangam.work.announce.coalesce", "-1s")));
    }

    @Test
    void channelSecretsResolveThroughThePlatformResolverBeforeTheStaticMap() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("sangam.ports.secret-refs", "secret://mq/password,secret://mq/token")
                .withProperty("sangam.ports.secrets.secret://mq/password", "static-a")
                .withProperty("sangam.ports.secrets.secret://mq/token", "static-b");
        SecretResolver platform = SecretResolver.of(Map.of("secret://mq/password", "from-vault"));
        ChannelAdapters.Environment e = PortsWiring.channelEnvironment(env, platform);

        assertEquals(Optional.of("from-vault"), e.secret("secret://mq/password"), "the platform resolver wins");
        assertEquals(Optional.of("static-b"), e.secret("secret://mq/token"), "the static map is the last link of the chain");
        assertEquals(Optional.empty(), e.secret("secret://mq/unknown"));
        assertEquals(Optional.empty(), e.secret(null));
    }

    @Test
    void aFailingPlatformResolverIsNotSilentlyReplacedByTheStaticMap() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("sangam.ports.secret-refs", "secret://mq/password")
                .withProperty("sangam.ports.secrets.secret://mq/password", "static-a");
        SecretResolver down = ref -> {
            throw new IllegalStateException("vault unreachable");
        };
        ChannelAdapters.Environment e = PortsWiring.channelEnvironment(env, down);
        assertThrows(IllegalStateException.class, () -> e.secret("secret://mq/password"));
    }
}
