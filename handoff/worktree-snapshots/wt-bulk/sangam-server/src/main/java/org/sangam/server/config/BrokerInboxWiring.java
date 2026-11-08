// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Destination;
import jakarta.jms.Session;
import jakarta.jms.Topic;

import org.sangam.core.layer.AuditChain;
import org.sangam.integration.inbox.ChannelBinding;
import org.sangam.integration.inbox.InboxService;
import org.sangam.integration.inbox.JmsInboxListener;
import org.sangam.integration.inbox.KafkaInboxConsumer;
import org.sangam.integration.inbox.PoisonInbound;
import org.sangam.integration.relay.DeadLetterStore;
import org.sangam.rest.support.TxSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Broker inbound (R38), both off by default: {@code sangam.integration.kafka.*} and {@code sangam.integration.jms.*}.
 * Every channel names its binding (source, tenant, principal); the tenant comes only from there. Multi-node delivery
 * uses Kafka consumer groups and JMS queues or shared durable topic subscriptions, so no leader election is needed.
 * JMS needs a {@link ConnectionFactory} bean from the site's customer-supplied broker client.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.integration.enabled", havingValue = "true", matchIfMissing = true)
public class BrokerInboxWiring {

    /** One channel. Kafka uses topics and groupId; JMS uses queue, or topic and subscription. */
    public static class Channel {
        public String source;
        public String tenant;
        public String principal = "broker";
        public String eventType = "sangam.inbound";
        public List<String> topics = new ArrayList<>();
        public String groupId;
        public String queue;
        public String topic;
        public String subscription;
        public int concurrency = 1;

        public void setSource(String v) { source = v; }
        public void setTenant(String v) { tenant = v; }
        public void setPrincipal(String v) { principal = v; }
        public void setEventType(String v) { eventType = v; }
        public void setTopics(List<String> v) { topics = v; }
        public void setGroupId(String v) { groupId = v; }
        public void setQueue(String v) { queue = v; }
        public void setTopic(String v) { topic = v; }
        public void setSubscription(String v) { subscription = v; }
        public void setConcurrency(int v) { concurrency = v; }

        ChannelBinding binding() {
            if (source == null || source.isBlank() || tenant == null || tenant.isBlank()) {
                throw new IllegalStateException("A broker inbox channel needs source and tenant");
            }
            return ChannelBinding.of(source, tenant, principal);
        }
    }

    public static class Settings {
        public boolean enabled;
        public Map<String, String> config = new HashMap<>();
        public List<Channel> channels = new ArrayList<>();

        public void setEnabled(boolean v) { enabled = v; }
        public void setConfig(Map<String, String> v) { config = v; }
        public void setChannels(List<Channel> v) { channels = v; }
    }

    @Bean
    @ConfigurationProperties("sangam.integration.kafka")
    public Settings sangamKafkaInboxSettings() {
        return new Settings();
    }

    @Bean
    @ConfigurationProperties("sangam.integration.jms")
    public Settings sangamJmsInboxSettings() {
        return new Settings();
    }

    @Bean
    public PoisonInbound sangamPoisonInbound(TxSupport tx, DeadLetterStore deadLetters, AuditChain sangamIntegrationAuditChain) {
        return new PoisonInbound(tx.dialect(), tx.newTransactions(), deadLetters, sangamIntegrationAuditChain);
    }

    @Bean
    @ConditionalOnProperty(name = "sangam.integration.kafka.enabled", havingValue = "true")
    public SmartLifecycle sangamKafkaInbox(@org.springframework.beans.factory.annotation.Qualifier("sangamKafkaInboxSettings") Settings s,
                                           InboxService inbox, PoisonInbound poison) {
        return new Consumers("sangam-kafka-inbox", () -> {
            List<Runnable> out = new ArrayList<>();
            for (Channel ch : s.channels) {
                if (ch.topics.isEmpty()) {
                    throw new IllegalStateException("Kafka inbox channel " + ch.source + " needs topics");
                }
                Map<String, Object> config = new HashMap<>(s.config);
                // a group per channel: nodes share partitions, so each record is taken by one node
                config.put("group.id", ch.groupId != null ? ch.groupId : "sangam-" + ch.source);
                for (int i = 0; i < ch.concurrency; i++) {
                    out.add(new KafkaInboxConsumer(config, ch.topics, ch.binding(),
                            KafkaInboxConsumer.cloudEventsOrOffset(ch.eventType), inbox, poison));
                }
            }
            return out;
        });
    }

    @Bean
    @ConditionalOnProperty(name = "sangam.integration.jms.enabled", havingValue = "true")
    public SmartLifecycle sangamJmsInbox(@org.springframework.beans.factory.annotation.Qualifier("sangamJmsInboxSettings") Settings s,
                                         ObjectProvider<ConnectionFactory> factory, InboxService inbox, PoisonInbound poison) {
        return new Consumers("sangam-jms-inbox", () -> {
            ConnectionFactory cf = factory.getIfAvailable();
            if (cf == null) {
                throw new IllegalStateException("sangam.integration.jms.enabled=true needs a jakarta.jms.ConnectionFactory bean");
            }
            List<Runnable> out = new ArrayList<>();
            for (Channel ch : s.channels) {
                boolean topic = ch.topic != null && !ch.topic.isBlank();
                if (topic == (ch.queue != null && !ch.queue.isBlank())) {
                    throw new IllegalStateException("JMS inbox channel " + ch.source + " needs exactly one of queue or topic");
                }
                ConnectionFactory use = topic
                        ? sharedSubscription(cf, ch.subscription != null ? ch.subscription : "sangam-" + ch.source) : cf;
                for (int i = 0; i < ch.concurrency; i++) {
                    out.add(new JmsInboxListener(use, sess -> destination(sess, ch), ch.binding(),
                            JmsInboxListener.cloudEventsOrJmsId(ch.eventType), inbox, poison));
                }
            }
            return out;
        });
    }

    private static Destination destination(Session s, Channel ch) {
        try {
            return ch.topic != null && !ch.topic.isBlank() ? s.createTopic(ch.topic) : s.createQueue(ch.queue);
        } catch (jakarta.jms.JMSException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The listener calls {@code session.createConsumer(destination)}; for a topic that becomes a shared durable one. */
    private static ConnectionFactory sharedSubscription(ConnectionFactory cf, String subscription) {
        return proxy(ConnectionFactory.class, cf, (m, args, result) -> m.equals("createConnection")
                ? proxy(Connection.class, (Connection) result, (m2, a2, r2) -> m2.equals("createSession")
                        ? proxy(Session.class, (Session) r2, null, subscription) : r2)
                : result);
    }

    @FunctionalInterface
    private interface After {
        Object apply(String method, Object[] args, Object result);
    }

    private static <T> T proxy(Class<T> type, T target, After after) {
        return proxy(type, target, after, null);
    }

    private static <T> T proxy(Class<T> type, T target, After after, String subscription) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, args) -> {
            try {
                if (subscription != null && m.getName().equals("createConsumer") && args.length == 1
                        && args[0] instanceof Topic t) {
                    return ((Session) target).createSharedDurableConsumer(t, subscription);
                }
                Object r = m.invoke(target, args);
                return after == null ? r : after.apply(m.getName(), args, r);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }));
    }

    /** Runs each consumer on its own daemon thread after the inbox services are up; stops them on shutdown. */
    private static final class Consumers implements SmartLifecycle {
        private final String name;
        private final java.util.function.Supplier<List<Runnable>> factory;
        private final List<AutoCloseable> running = new ArrayList<>();
        private volatile boolean started;

        Consumers(String name, java.util.function.Supplier<List<Runnable>> factory) {
            this.name = name;
            this.factory = factory;
        }

        @Override
        public void start() {
            int n = 0;
            for (Runnable r : factory.get()) {
                running.add((AutoCloseable) r);
                Thread t = new Thread(r, name + "-" + n++);
                t.setDaemon(true);
                t.start();
            }
            started = true;
        }

        @Override
        public void stop() {
            for (AutoCloseable c : running) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // shutting down
                }
            }
            running.clear();
            started = false;
        }

        @Override
        public boolean isRunning() {
            return started;
        }

        @Override
        public int getPhase() {
            return Integer.MAX_VALUE - 50;
        }
    }
}
