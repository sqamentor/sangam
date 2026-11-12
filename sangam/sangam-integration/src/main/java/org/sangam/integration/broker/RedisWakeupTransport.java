// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.broker;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPubSub;

/**
 * Redis transport of the wake-up bus (M-058): {@code PUBLISH channel ""} on a publisher connection (reopened after a
 * failure) and a blocking {@code SUBSCRIBE} on a dedicated connection. Settings: {@code host}, {@code port} (default
 * 6379), {@code tls} (true/false, default false), optional {@code username} and {@code passwordRef} (a secret reference),
 * {@code channel} (default {@value BrokerWakeupBus#DEFAULT_CHANNEL}).
 */
final class RedisWakeupTransport implements BrokerWakeupBus.Transport {

    private final HostAndPort address;
    private final JedisClientConfig config;
    private final String channel;
    private Jedis publisher;
    private volatile Jedis subscriber;
    private volatile JedisPubSub subscription;

    RedisWakeupTransport(Map<String, String> settings, Function<String, Optional<String>> secrets, String nodeId) {
        String host = settings.get("host");
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("the Redis wake-up bus needs host");
        }
        this.address = new HostAndPort(host.trim(), Integer.parseInt(settings.getOrDefault("port", "6379").trim()));
        DefaultJedisClientConfig.Builder b = DefaultJedisClientConfig.builder()
                .clientName(nodeId + "-wakeup")
                .ssl(Boolean.parseBoolean(settings.getOrDefault("tls", "false").trim()))
                .connectionTimeoutMillis(5_000)
                .socketTimeoutMillis(5_000)
                .blockingSocketTimeoutMillis(0);
        String user = settings.get("username");
        if (user != null && !user.isBlank()) {
            b.user(user.trim());
        }
        String ref = settings.get("passwordRef");
        if (ref != null && !ref.isBlank()) {
            b.password(secrets.apply(ref.trim()).orElseThrow(() -> new IllegalStateException("secret " + ref + " not found")));
        }
        this.config = b.build();
        String ch = settings.get("channel");
        this.channel = ch == null || ch.isBlank() ? BrokerWakeupBus.DEFAULT_CHANNEL : ch.trim();
    }

    @Override
    public synchronized void publish() {
        try {
            if (publisher == null) {
                publisher = new Jedis(address, config);
            }
            publisher.publish(channel, "");
        } catch (RuntimeException e) {
            if (publisher != null) {
                publisher.close();
                publisher = null;
            }
            throw e;
        }
    }

    @Override
    public void listen(Runnable subscribed, Runnable message) {
        JedisPubSub sub = new JedisPubSub() {
            @Override
            public void onSubscribe(String ch, int count) {
                subscribed.run();
            }

            @Override
            public void onMessage(String ch, String msg) {
                message.run();
            }
        };
        try (Jedis j = new Jedis(address, config)) {
            subscriber = j;
            subscription = sub;
            j.subscribe(sub, channel);   // blocks until unsubscribed or the connection fails
        } finally {
            subscription = null;
            subscriber = null;
        }
    }

    @Override
    public void close() {
        JedisPubSub s = subscription;
        if (s != null && s.isSubscribed()) {
            try {
                s.unsubscribe();
            } catch (RuntimeException ignored) {
                // closing the connection below ends the blocking subscribe anyway
            }
        }
        Jedis j = subscriber;
        if (j != null) {
            j.close();
        }
        synchronized (this) {
            if (publisher != null) {
                publisher.close();
                publisher = null;
            }
        }
    }
}
