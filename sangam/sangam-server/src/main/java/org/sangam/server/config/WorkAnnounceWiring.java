// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Duration;

import org.sangam.integration.relay.WakeupNotifier;
import org.sangam.ports.events.work.ExternalWorkAnnouncer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * M-066 broker task mode as decided for 0.1.0 (owner decision 101): new external work is <b>announced</b> on the one
 * wake-up path (M-005 {@code pg_notify}, in-JVM, and the M-058 NATS/Redis bus when {@code sangam.wakeup.bus} is set) and
 * workers still take it only through the REST lease service. The engine plugin ({@link ExternalWorkAnnouncer}) sends the
 * payload-free hint after each committed command that created external tasks, coalesced over
 * {@code sangam.work.announce.coalesce} (default 20 ms). On by default (the hint is the same one the relay sends);
 * {@code sangam.work.announce.enabled=false} turns it off, leaving the waiters' authoritative poll (R1).
 */
@Configuration(proxyBeanMethods = false)
public class WorkAnnounceWiring {

    static final String PREFIX = "sangam.work.announce";
    static final Duration DEFAULT_COALESCE = Duration.ofMillis(20);

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
    public ExternalWorkAnnouncer sangamExternalWorkAnnouncer(Environment env, ObjectProvider<WakeupNotifier> notifier) {
        // resolved at send time: the engine (and so this plugin) is built before the relay's notifier bean may exist
        return new ExternalWorkAnnouncer(hint(notifier::getIfAvailable), coalesce(env));
    }

    /** One hint through the current notifier; none yet (start-up) = nothing to wake. */
    static Runnable hint(java.util.function.Supplier<WakeupNotifier> notifier) {
        return () -> {
            WakeupNotifier n = notifier.get();
            if (n != null) {
                n.cycleCompleted(1);
            }
        };
    }

    static Duration coalesce(Environment env) {
        Duration d = Binder.get(env).bind(PREFIX + ".coalesce", Duration.class).orElse(DEFAULT_COALESCE);
        if (d.isNegative()) {
            throw new IllegalStateException(PREFIX + ".coalesce must be zero or positive: " + d);
        }
        return d;
    }
}
