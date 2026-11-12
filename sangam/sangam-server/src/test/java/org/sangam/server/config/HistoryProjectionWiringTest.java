// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.cell.rollup.RollupPublisher;
import org.sangam.core.layer.Outbox;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.publish.history.SearchIndexPublisher;
import org.sangam.integration.relay.OutboxWriter;
import org.springframework.mock.env.MockEnvironment;

/**
 * M-057 wiring: nothing is routed or registered unless a sink is configured; history events (and only they) reach the
 * configured sinks, next to the roll-up routing that existed before; search settings are validated (https in
 * production, whole credentials); Kafka producer secrets come from references.
 */
class HistoryProjectionWiringTest {

    static final List<Outbox.Destination> FIXED = List.of(Outbox.Destination.unordered("readmodel"));

    static List<String> names(OutboxWriter.SubscriptionResolver r, String type) {
        return r.destinations("T1", type).stream().map(Outbox.Destination::name).toList();
    }

    @Test
    void noSinkConfiguredMeansNoDestination() {
        assertTrue(HistoryProjectionWiring.destinations(new MockEnvironment()).isEmpty());
        OutboxWriter.SubscriptionResolver r = IntegrationWiring.subscriptions(FIXED, false, List.of());
        assertEquals(List.of("readmodel"), names(r, "sangam.history.activity.start"));
    }

    @Test
    void historyEventsAndOnlyThemReachTheConfiguredSinks() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("sangam.history.projection.search.url", "https://search.example")
                .withProperty("sangam.history.projection.kafka.topic", "sangam-history");
        List<Outbox.Destination> sinks = HistoryProjectionWiring.destinations(env);
        assertEquals(List.of(SearchIndexPublisher.DESTINATION, HistoryProjectionWiring.KAFKA_DESTINATION),
                sinks.stream().map(Outbox.Destination::name).toList());
        assertTrue(sinks.stream().noneMatch(Outbox.Destination::ordered), "idempotent per event id: unordered");

        OutboxWriter.SubscriptionResolver r = IntegrationWiring.subscriptions(FIXED, false, sinks);
        assertEquals(List.of("readmodel", "history-search", "history-kafka"), names(r, "sangam.history.task.create"));
        assertEquals(List.of("readmodel"), names(r, "sangam.xcell.moved"));
        assertEquals(List.of("readmodel"), names(r, "payments.ack"));
        assertEquals(List.of("readmodel"), names(r, null));
    }

    @Test
    void theRollupRoutingIsUnchangedAndCombinesWithTheSinks() {
        OutboxWriter.SubscriptionResolver before = IntegrationWiring.subscriptions(FIXED, true, List.of());
        assertEquals(List.of("readmodel", RollupPublisher.DESTINATION), names(before, "sangam.history.process.end"));
        assertEquals(List.of("readmodel", RollupPublisher.DESTINATION), names(before, "sangam.rollup.metric"));
        assertEquals(List.of("readmodel"), names(before, "payments.ack"));

        OutboxWriter.SubscriptionResolver both = IntegrationWiring.subscriptions(FIXED, true,
                List.of(Outbox.Destination.unordered(SearchIndexPublisher.DESTINATION)));
        assertEquals(List.of("readmodel", RollupPublisher.DESTINATION, "history-search"),
                names(both, "sangam.history.process.end"));
        assertEquals(List.of("readmodel", RollupPublisher.DESTINATION), names(both, "sangam.xcell.moved"));
    }

    @Test
    void aDestinationNamedTwiceIsDeliveredOnce() {
        List<Outbox.Destination> merged = IntegrationWiring.merge(
                List.of(Outbox.Destination.unordered("history-search")),
                List.of(Outbox.Destination.unordered("history-search"), Outbox.Destination.unordered("history-kafka")));
        assertEquals(List.of("history-search", "history-kafka"), merged.stream().map(Outbox.Destination::name).toList());
    }

    @Test
    void searchSettingsRequireHttpsInProductionAndDefaultTheIndex() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.history.projection.search.url", "http://search.internal:9200");
        assertThrows(IllegalStateException.class, () -> HistoryProjectionWiring.searchSettings(env, true));
        SearchIndexPublisher.Settings s = HistoryProjectionWiring.searchSettings(env, false);
        assertEquals("sangam-history", s.index());
        assertEquals(null, s.apiKeyRef());

        env.setProperty("sangam.history.projection.search.username", "projector");
        assertThrows(IllegalArgumentException.class, () -> HistoryProjectionWiring.searchSettings(env, false),
                "a user name without a password reference is refused at start-up");
    }

    @Test
    void kafkaSecretsComeFromReferencesAndBootstrapServersAreRequired() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("sangam.history.projection.kafka.topic", "sangam-history")
                .withProperty("sangam.history.projection.kafka.properties.security.protocol", "SASL_SSL")
                .withProperty("sangam.history.projection.kafka.properties.sasl.jaas.config", "secret://kafka/jaas");
        SecretResolver secrets = SecretResolver.of(Map.of("secret://kafka/jaas", "resolved-jaas"));
        assertThrows(IllegalStateException.class, () -> HistoryProjectionWiring.kafkaConfig(env, secrets));

        env.setProperty("sangam.history.projection.kafka.bootstrap-servers", "broker-a:9093");
        Map<String, Object> c = HistoryProjectionWiring.kafkaConfig(env, secrets);
        assertEquals("broker-a:9093", c.get("bootstrap.servers"));
        assertEquals("SASL_SSL", c.get("security.protocol"));
        assertEquals("resolved-jaas", c.get("sasl.jaas.config"));
        assertFalse(c.containsValue("secret://kafka/jaas"));
    }
}
