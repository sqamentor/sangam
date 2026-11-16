// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sangam.assembly.integration.OutboxDestinations;
import org.sangam.core.layer.Outbox;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.RetryPolicy;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.s3.S3ObjectClient;
import org.sangam.integration.publish.KafkaPublisher;
import org.sangam.integration.publish.history.DataLakeHistoryPublisher;
import org.sangam.integration.publish.history.SearchIndexPublisher;
import org.sangam.integration.relay.PublisherRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * M-057 external history projector (owner decision 88: with {@code sangam.engine.history-external=true} the engine keeps
 * {@code ACT_HI_*} at audit level and the history detail travels only as {@code sangam.history.*} outbox events). Each
 * configured sink is an outbox destination fed every {@code sangam.history.*} event ({@code IntegrationWiring}
 * {@code sangamOutboxWriter} routes them, as it does for the roll-up stream), delivered at least once by the relay and
 * idempotent per outbox event id:
 *
 * <ul>
 *   <li><b>{@value SearchIndexPublisher#DESTINATION}</b>: OpenSearch or Elasticsearch,
 *       {@code PUT {url}/{index}/_doc/{event_id}}. Keys under {@code sangam.history.projection.search.}: {@code url},
 *       {@code index} (default {@code sangam-history}; {@code {tenant}} for one index per tenant), {@code username} +
 *       {@code password-ref} or {@code api-key-ref} (secret references), {@code allow-private} (default false). The
 *       egress rule admits exactly the configured URL; production profiles require https.</li>
 *   <li><b>{@value #KAFKA_DESTINATION}</b>: Kafka, record key = event id, idempotent producer. Keys under
 *       {@code sangam.history.projection.kafka.}: {@code topic}, {@code bootstrap-servers}, {@code properties.*} (producer
 *       settings; a value starting with {@code secret://} is resolved through the platform {@link SecretResolver}).</li>
 *   <li><b>{@value DataLakeHistoryPublisher#DESTINATION}</b>: an S3-compatible bucket, one NDJSON object per event at
 *       {@code {prefix}/tenant={t}/date={day}/{event_id}.json}. Keys under {@code sangam.history.projection.datalake.}:
 *       {@code endpoint}, {@code bucket}, {@code region} (default {@code us-east-1}), {@code path-style} (default true),
 *       {@code prefix} (default {@code sangam-history}), {@code access-key-ref}, {@code secret-key-ref},
 *       {@code session-token-ref} (secret references), {@code allow-private} (default false). The egress rule admits
 *       exactly the bucket's origin; production profiles require https.</li>
 * </ul>
 * Nothing is configured by default: no sink, no routing, no outbound call.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.integration.enabled", havingValue = "true", matchIfMissing = true)
public class HistoryProjectionWiring {

    private static final Logger LOG = LoggerFactory.getLogger(HistoryProjectionWiring.class);
    static final String PREFIX = "sangam.history.projection";
    public static final String KAFKA_DESTINATION = "history-kafka";
    /** Event types every projection sink receives. */
    public static final String HISTORY_TYPES = OutboxDestinations.HISTORY_TYPES;

    /** The sinks this host delivers, keyed by the setting that turns each on ({@link OutboxDestinations#HISTORY_SINK_KEYS}). */
    static final OutboxDestinations.Routes SINKS = OutboxDestinations.Routes.none()
            .withHistorySink(OutboxDestinations.HISTORY_SEARCH, Outbox.Destination.unordered(SearchIndexPublisher.DESTINATION))
            .withHistorySink(OutboxDestinations.HISTORY_KAFKA, Outbox.Destination.unordered(KAFKA_DESTINATION))
            .withHistorySink(OutboxDestinations.HISTORY_DATALAKE,
                    Outbox.Destination.unordered(DataLakeHistoryPublisher.DESTINATION));

    /**
     * The destinations of the configured sinks, in the routing order of {@link OutboxDestinations#HISTORY_SINK_KEYS}
     * (none = no history routing beyond what was there before).
     */
    public static List<Outbox.Destination> destinations(Environment env) {
        List<Outbox.Destination> out = new ArrayList<>();
        for (String key : OutboxDestinations.HISTORY_SINK_KEYS) {
            if (!env.getProperty(key, "").isBlank()) {
                out.add(SINKS.historySinks().get(key));
            }
        }
        return List.copyOf(out);
    }

    static SearchIndexPublisher.Settings searchSettings(Environment env, boolean production) {
        String p = PREFIX + ".search.";
        URI url = URI.create(env.getProperty(p + "url", "").trim());
        if (production && !"https".equalsIgnoreCase(url.getScheme())) {
            throw new IllegalStateException(p + "url: production profiles require https");
        }
        return new SearchIndexPublisher.Settings(url, env.getProperty(p + "index", "sangam-history"),
                blankToNull(env.getProperty(p + "username")), blankToNull(env.getProperty(p + "password-ref")),
                blankToNull(env.getProperty(p + "api-key-ref")));
    }

    /** One egress rule: the configured URL's scheme, host, port and path, any tenant. */
    static HttpTransport searchTransport(SearchIndexPublisher.Settings s, boolean allowPrivate) {
        URI u = s.url();
        EgressPolicy.Rule rule = new EgressPolicy.Rule("*", u.getScheme(), u.getHost(), u.getPort(),
                u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath(), allowPrivate);
        return new HttpTransport(EgressPolicy.of(List.of(rule), 0, 1024 * 1024), null, Duration.ofSeconds(10),
                Duration.ofSeconds(30), RetryPolicy.none());
    }

    /** M-057 data-lake bucket settings; production profiles require https. */
    static S3ObjectClient.Settings dataLakeSettings(Environment env, boolean production) {
        String p = PREFIX + ".datalake.";
        String endpoint = env.getProperty(p + "endpoint", "").trim();
        if (endpoint.isEmpty()) {
            throw new IllegalStateException(p + "endpoint is required with " + p + "bucket");
        }
        URI url = URI.create(endpoint);
        if (production && !"https".equalsIgnoreCase(url.getScheme())) {
            throw new IllegalStateException(p + "endpoint: production profiles require https");
        }
        return new S3ObjectClient.Settings(url, env.getProperty(p + "bucket", "").trim(),
                env.getProperty(p + "region", "us-east-1"), env.getProperty(p + "path-style", Boolean.class, true),
                blankToNull(env.getProperty(p + "access-key-ref")), blankToNull(env.getProperty(p + "secret-key-ref")),
                blankToNull(env.getProperty(p + "session-token-ref")));
    }

    /** One egress rule: the bucket's origin, any tenant (the relay publishes for every tenant). */
    static HttpTransport dataLakeTransport(S3ObjectClient.Settings s, boolean allowPrivate) {
        EgressPolicy.Rule rule = EgressPolicy.Rule.parse("*", s.origin(), allowPrivate);
        return new HttpTransport(EgressPolicy.of(List.of(rule), 0, 1024 * 1024), null, Duration.ofSeconds(10),
                Duration.ofSeconds(30), RetryPolicy.none());
    }

    /** Producer settings: bootstrap servers and pass-through properties, {@code secret://} values resolved. */
    static Map<String, Object> kafkaConfig(Environment env, SecretResolver secrets) {
        String p = PREFIX + ".kafka.";
        String servers = env.getProperty(p + "bootstrap-servers", "");
        if (servers.isBlank()) {
            throw new IllegalStateException(p + "bootstrap-servers is required with " + p + "topic");
        }
        Map<String, Object> config = new LinkedHashMap<>();
        Binder.get(env).bind(p + "properties", Bindable.mapOf(String.class, String.class)).orElse(Map.of())
                .forEach((k, v) -> config.put(k, v != null && v.startsWith("secret://") ? secrets.require(v) : v));
        config.put("bootstrap.servers", servers.trim());
        return config;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = PREFIX + ".kafka", name = "topic")
    public KafkaPublisher sangamHistoryKafkaPublisher(Environment env, ObjectProvider<SecretResolver> secrets) {
        String topic = env.getProperty(PREFIX + ".kafka.topic", "").trim();
        return new KafkaPublisher(kafkaConfig(env, secrets.getIfAvailable(SecretResolver::fromEnvironment)), d -> topic,
                "urn:sangam:" + env.getProperty("sangam.installation.id", "sangam"), Duration.ofSeconds(30),
                KafkaPublisher.Key.EVENT_ID);
    }

    @Bean
    public InitializingBean sangamHistoryProjectionRegistration(PublisherRegistry publishers, Environment env,
            ObjectProvider<SecretResolver> secrets, ObjectProvider<KafkaPublisher> kafka) {
        return () -> {
            List<String> sinks = new ArrayList<>();
            if (!env.getProperty(PREFIX + ".search.url", "").isBlank()) {
                SearchIndexPublisher.Settings s = searchSettings(env, ProductionEnvironment.isProduction(env));
                publishers.register(SearchIndexPublisher.DESTINATION, new SearchIndexPublisher(
                        searchTransport(s, env.getProperty(PREFIX + ".search.allow-private", Boolean.class, false)), s,
                        secrets.getIfAvailable(SecretResolver::fromEnvironment)));
                sinks.add(SearchIndexPublisher.DESTINATION);
            }
            if (!env.getProperty(PREFIX + ".datalake.bucket", "").isBlank()) {
                S3ObjectClient.Settings s = dataLakeSettings(env, ProductionEnvironment.isProduction(env));
                S3ObjectClient client = new S3ObjectClient(s, dataLakeTransport(s,
                        env.getProperty(PREFIX + ".datalake.allow-private", Boolean.class, false)),
                        secrets.getIfAvailable(SecretResolver::fromEnvironment), java.time.Clock.systemUTC());
                publishers.register(DataLakeHistoryPublisher.DESTINATION, new DataLakeHistoryPublisher(client,
                        env.getProperty(PREFIX + ".datalake.prefix", "sangam-history")));
                sinks.add(DataLakeHistoryPublisher.DESTINATION);
            }
            KafkaPublisher k = kafka.getIfAvailable();
            if (k != null) {
                publishers.register(KAFKA_DESTINATION, k);
                sinks.add(KAFKA_DESTINATION);
            }
            if (!sinks.isEmpty()) {
                LOG.info("M-057 history projection sinks registered: {}", sinks);
            }
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
