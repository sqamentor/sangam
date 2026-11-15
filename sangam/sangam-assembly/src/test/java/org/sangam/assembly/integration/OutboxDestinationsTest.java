// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.assembly.host.SangamProperties;
import org.sangam.core.layer.Outbox;
import org.sangam.integration.publish.history.DataLakeHistoryPublisher;
import org.sangam.integration.publish.history.SearchIndexPublisher;
import org.sangam.integration.relay.OutboxWriter;

/**
 * Extraction phase 3, blocker 2: the outbox destination rule as plain Java with the host's routes handed in. The
 * routing cases are those of the Spring host's {@code HistoryProjectionWiringTest} (SCALE IR-3 roll-up, M-057 sinks);
 * a setting that asks for a destination the host has no publisher for refuses by name.
 */
class OutboxDestinationsTest {

    static final Outbox.Destination ROLLUP = Outbox.Destination.unordered("rollup:national");
    static final Outbox.Destination SEARCH = Outbox.Destination.unordered(SearchIndexPublisher.DESTINATION);
    static final Outbox.Destination KAFKA = Outbox.Destination.unordered("history-kafka");
    static final Outbox.Destination LAKE = Outbox.Destination.unordered(DataLakeHistoryPublisher.DESTINATION);

    /** The routes of a host with every publisher (the Spring host's set). */
    static final OutboxDestinations.Routes ALL = OutboxDestinations.Routes.none().withRollup(ROLLUP)
            .withHistorySink(OutboxDestinations.HISTORY_SEARCH, SEARCH)
            .withHistorySink(OutboxDestinations.HISTORY_KAFKA, KAFKA)
            .withHistorySink(OutboxDestinations.HISTORY_DATALAKE, LAKE);

    static List<String> names(OutboxWriter.SubscriptionResolver r, String type) {
        return r.destinations("T1", type).stream().map(Outbox.Destination::name).toList();
    }

    static OutboxDestinations of(Map<String, String> config, OutboxDestinations.Routes routes) {
        Map<String, String> m = new HashMap<>(config);
        m.putIfAbsent(OutboxDestinations.DEFAULT_DESTINATIONS, "readmodel");
        return OutboxDestinations.of("test", SangamProperties.of(m), routes);
    }

    @Test
    void theFixedListIsParsedWithOrderedEntriesAndBlanksDropped() {
        List<Outbox.Destination> fixed = OutboxDestinations.fixed(SangamProperties.of(Map.of(
                OutboxDestinations.DEFAULT_DESTINATIONS, " readmodel , ,payments:ordered ")));
        assertEquals(List.of(Outbox.Destination.unordered("readmodel"), Outbox.Destination.ordered("payments")), fixed);
        assertEquals(List.of(), OutboxDestinations.fixed(SangamProperties.empty()));
    }

    @Test
    void noSinkConfiguredMeansEveryEventGoesToTheFixedListOnly() {
        OutboxWriter.SubscriptionResolver r = of(Map.of(), ALL).resolver();
        assertEquals(List.of("readmodel"), names(r, "sangam.history.activity.start"));
        assertEquals(List.of("readmodel"), names(r, "sangam.rollup.metric"));
    }

    @Test
    void historyEventsAndOnlyThemReachTheConfiguredSinks() {
        OutboxWriter.SubscriptionResolver r = of(Map.of(OutboxDestinations.HISTORY_SEARCH, "https://search.example",
                OutboxDestinations.HISTORY_KAFKA, "sangam-history"), ALL).resolver();
        assertEquals(List.of("readmodel", "history-search", "history-kafka"), names(r, "sangam.history.task.create"));
        assertEquals(List.of("readmodel"), names(r, "sangam.xcell.moved"));
        assertEquals(List.of("readmodel"), names(r, "payments.ack"));
        assertEquals(List.of("readmodel"), names(r, null));
    }

    @Test
    void theRollupRoutingCombinesWithTheSinks() {
        Map<String, String> cell = Map.of(OutboxDestinations.CELL_ID, "c1",
                OutboxDestinations.ROLLUP_INGEST_URL, "https://rollup.example");
        OutboxWriter.SubscriptionResolver before = of(cell, ALL).resolver();
        assertEquals(List.of("readmodel", ROLLUP.name()), names(before, "sangam.history.process.end"));
        assertEquals(List.of("readmodel", ROLLUP.name()), names(before, "sangam.rollup.metric"));
        assertEquals(List.of("readmodel"), names(before, "payments.ack"));

        Map<String, String> both = new HashMap<>(cell);
        both.put(OutboxDestinations.HISTORY_SEARCH, "https://search.example");
        OutboxWriter.SubscriptionResolver r = of(both, ALL).resolver();
        assertEquals(List.of("readmodel", ROLLUP.name(), "history-search"), names(r, "sangam.history.process.end"));
        assertEquals(List.of("readmodel", ROLLUP.name()), names(r, "sangam.xcell.moved"));
    }

    @Test
    void theRollupNeedsBothTheCellAndTheIngestUrlAndIsNotAddedTwice() {
        assertEquals(List.of("readmodel"), names(of(Map.of(OutboxDestinations.CELL_ID, "c1"), ALL).resolver(),
                "sangam.rollup.metric"));
        OutboxWriter.SubscriptionResolver named = of(Map.of(OutboxDestinations.CELL_ID, "c1",
                OutboxDestinations.ROLLUP_INGEST_URL, "https://rollup.example",
                OutboxDestinations.DEFAULT_DESTINATIONS, "readmodel," + ROLLUP.name()), ALL).resolver();
        assertEquals(List.of("readmodel", ROLLUP.name()), names(named, "sangam.history.process.end"));
    }

    @Test
    void aDestinationNamedTwiceIsDeliveredOnce() {
        List<Outbox.Destination> merged = OutboxDestinations.merge(List.of(SEARCH), List.of(SEARCH, KAFKA));
        assertEquals(List.of("history-search", "history-kafka"), merged.stream().map(Outbox.Destination::name).toList());
    }

    @Test
    void aSettingWithoutAPublisherOnTheHostIsRefusedByName() {
        IllegalStateException rollup = assertThrows(IllegalStateException.class, () -> of(Map.of(
                OutboxDestinations.CELL_ID, "c1", OutboxDestinations.ROLLUP_INGEST_URL, "https://rollup.example"),
                OutboxDestinations.Routes.none()));
        assertTrue(rollup.getMessage().startsWith(OutboxDestinations.CODE + ": the test host"), rollup.getMessage());
        assertTrue(rollup.getMessage().contains(OutboxDestinations.ROLLUP_INGEST_URL), rollup.getMessage());

        IllegalStateException sinks = assertThrows(IllegalStateException.class, () -> of(Map.of(
                OutboxDestinations.HISTORY_KAFKA, "t", OutboxDestinations.HISTORY_DATALAKE, "b"),
                OutboxDestinations.Routes.none().withHistorySink(OutboxDestinations.HISTORY_KAFKA, KAFKA)));
        assertTrue(sinks.getMessage().contains(OutboxDestinations.HISTORY_DATALAKE), sinks.getMessage());
        assertTrue(!sinks.getMessage().contains(OutboxDestinations.HISTORY_KAFKA), "a delivered sink is not named");

        // blank values ask for nothing
        assertEquals(List.of("readmodel"), names(of(Map.of(OutboxDestinations.HISTORY_SEARCH, " ",
                OutboxDestinations.CELL_ID, " "), OutboxDestinations.Routes.none()).resolver(), "sangam.history.x"));
    }

    @Test
    void routesNameOnlyKnownSinkSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> OutboxDestinations.Routes.none().withHistorySink("sangam.history.projection.other", SEARCH));
    }
}
