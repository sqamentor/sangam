// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.Locale;
import java.util.Map;

import org.sangam.integration.broker.BrokerWakeupBus;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.relay.WakeupListener;
import org.sangam.rest.support.RestProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * M-058: the relay wake-up over a broker, opt-in. With {@code sangam.wakeup.bus=nats} or {@code redis} every node
 * subscribes to one shared subject/channel and the relay publishes a payload-free hint after each cycle that published
 * (appended to the existing notifier in {@code IntegrationWiring.sangamOutboxRelayService}). This gives Oracle, SQL Server
 * and MySQL nodes the cluster-wide wake-up PostgreSQL nodes get from {@code LISTEN/NOTIFY}. Without the property no
 * bean exists and nothing changes; the poll stays authoritative either way (R1).
 *
 * <p>Settings: {@code sangam.wakeup.nats.*} ({@code serverUri}, {@code username} + {@code passwordRef} or
 * {@code tokenRef}, {@code subject}) or {@code sangam.wakeup.redis.*} ({@code host}, {@code port}, {@code tls},
 * {@code username}, {@code passwordRef}, {@code channel}). Credentials are references resolved through the platform
 * {@link SecretResolver} (or its environment fallback); a literal secret is never read from these keys. The broker
 * clients are optional dependencies: opting in without the client on the classpath fails start-up with a clear
 * message instead of a late {@code NoClassDefFoundError}. Owner decision 232: jnats is not in the server; {@code nats}
 * needs the NATS add-on ({@link AddonGuardWiring#NATS_ADDON}).
 */
@Configuration(proxyBeanMethods = false)
public class BrokerWakeupWiring {

    static final String PREFIX = "sangam.wakeup";

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(prefix = PREFIX, name = "bus")
    public BrokerWakeupBus sangamBrokerWakeupBus(Environment env, ObjectProvider<SecretResolver> secrets,
            RestProperties rest, WakeupListener sangamWakeupListener) {
        return bus(env, secrets.getIfAvailable(SecretResolver::fromEnvironment), rest.getNode(), sangamWakeupListener);
    }

    /** The configured bus, not yet started. */
    static BrokerWakeupBus bus(Environment env, SecretResolver secrets, String node, WakeupListener listener) {
        String kind = env.getProperty(PREFIX + ".bus", "").trim().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "nats" -> {
                requireClient(AddonGuardWiring.NATS_CLASS, "nats", AddonGuardWiring.NATS_ADDON);
                yield BrokerWakeupBus.nats(settings(env, "nats"), secrets::resolve, node, listener);
            }
            case "redis" -> {
                requireClient("redis.clients.jedis.Jedis", "redis", "redis.clients:jedis");
                yield BrokerWakeupBus.redis(settings(env, "redis"), secrets::resolve, node, listener);
            }
            default -> throw new IllegalStateException(PREFIX + ".bus must be nats or redis (got '" + kind
                    + "'); remove the property to keep the database wake-up only");
        };
    }

    static Map<String, String> settings(Environment env, String kind) {
        return Binder.get(env).bind(PREFIX + "." + kind, Bindable.mapOf(String.class, String.class)).orElse(Map.of());
    }

    static void requireClient(String className, String kind, String artifact) {
        try {
            Class.forName(className, false, BrokerWakeupWiring.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IllegalStateException(PREFIX + ".bus=" + kind + " needs " + (artifact.startsWith("the ")
                    ? artifact : "the optional client " + artifact) + " on the server classpath (" + className
                    + " not found)", e);
        }
    }
}
