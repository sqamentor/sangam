// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.integration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.sangam.assembly.host.SangamProperties;
import org.sangam.core.layer.Outbox;
import org.sangam.integration.relay.OutboxWriter;

/**
 * Where every outbox event is delivered (extraction phase 3, blocker 2): the rule the Spring host's
 * {@code IntegrationWiring.sangamOutboxWriter} applies, as plain Java over the config-source seam, with the
 * destinations a host can deliver handed in ({@link Routes}) instead of read from Spring statics.
 *
 * <ul>
 *   <li>Every event goes to the fixed destinations of {@value #DEFAULT_DESTINATIONS} ({@code name} or
 *       {@code name:ordered}, comma-separated).</li>
 *   <li>SCALE IR-3: in a cell with a roll-up ingest URL ({@value #CELL_ID} and {@value #ROLLUP_INGEST_URL} both set),
 *       history, cross-cell and roll-up events also go to the host's roll-up destination, unless the fixed list
 *       already names it.</li>
 *   <li>M-057: every configured history projection sink ({@link #HISTORY_SINK_KEYS}) also receives the
 *       {@value #HISTORY_TYPES}* events.</li>
 * </ul>
 *
 * <p><b>Fail closed.</b> A setting that asks for a destination the host has no publisher for would leave its events in
 * the outbox forever. {@link #of} refuses it instead, with {@value #CODE} naming every such key and the host: the
 * Quarkus host delivers neither the roll-up stream nor the projection sinks in 0.1.0, so those settings refuse its start.
 */
public final class OutboxDestinations {

    /** The stable code of a refusal. */
    public static final String CODE = "OUTBOX_DESTINATION_NOT_DELIVERED";

    /** The destinations every event is delivered to. */
    public static final String DEFAULT_DESTINATIONS = "sangam.integration.default-destinations";

    /** The cell this node belongs to (SCALE IR-3). */
    public static final String CELL_ID = "sangam.cell.id";

    /** The national roll-up ingest of the cell (SCALE IR-3). */
    public static final String ROLLUP_INGEST_URL = "sangam.cell.rollup.ingest-url";

    /** The event types the history projection sinks receive. */
    public static final String HISTORY_TYPES = "sangam.history.";

    /** M-057: the setting that turns on each external history projection sink, in routing order. */
    public static final String HISTORY_SEARCH = "sangam.history.projection.search.url";
    public static final String HISTORY_KAFKA = "sangam.history.projection.kafka.topic";
    public static final String HISTORY_DATALAKE = "sangam.history.projection.datalake.bucket";
    public static final List<String> HISTORY_SINK_KEYS = List.of(HISTORY_SEARCH, HISTORY_KAFKA, HISTORY_DATALAKE);

    private static final String ORDERED_SUFFIX = ":ordered";

    private final List<Outbox.Destination> fixed;
    private final OutboxWriter.SubscriptionResolver resolver;

    private OutboxDestinations(List<Outbox.Destination> fixed, OutboxWriter.SubscriptionResolver resolver) {
        this.fixed = fixed;
        this.resolver = resolver;
    }

    /**
     * The destinations of {@code properties} on a host that delivers {@code routes}.
     *
     * @throws IllegalStateException starting with {@value #CODE} when a setting asks for a destination {@code routes}
     *                               does not deliver
     */
    public static OutboxDestinations of(String hostName, SangamProperties properties, Routes routes) {
        Objects.requireNonNull(hostName, "hostName");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(routes, "routes");
        List<Outbox.Destination> fixed = fixed(properties);
        List<String> undelivered = new ArrayList<>();
        Outbox.Destination rollup = null;
        if (set(properties, CELL_ID) && set(properties, ROLLUP_INGEST_URL)) {
            if (routes.rollup().isEmpty()) {
                undelivered.add(CELL_ID + " with " + ROLLUP_INGEST_URL + " (the roll-up stream)");
            } else if (fixed.stream().noneMatch(d -> d.name().equals(routes.rollup().get().name()))) {
                rollup = routes.rollup().get();
            }
        }
        List<Outbox.Destination> projection = new ArrayList<>();
        for (String key : HISTORY_SINK_KEYS) {
            if (!set(properties, key)) {
                continue;
            }
            Outbox.Destination sink = routes.historySinks().get(key);
            if (sink == null) {
                undelivered.add(key + " (a history projection sink)");
            } else {
                projection.add(sink);
            }
        }
        if (!undelivered.isEmpty()) {
            throw new IllegalStateException(CODE + ": the " + hostName + " host has no publisher for "
                    + String.join(", ", undelivered) + "; its events would never leave the outbox, so the start is "
                    + "refused (unset the setting or run the Spring host)");
        }
        return new OutboxDestinations(fixed, subscriptions(fixed, Optional.ofNullable(rollup), List.copyOf(projection)));
    }

    /** The fixed destinations of {@value #DEFAULT_DESTINATIONS}, in the configured order. */
    public static List<Outbox.Destination> fixed(SangamProperties properties) {
        List<Outbox.Destination> out = new ArrayList<>();
        for (String spec : properties.getList(DEFAULT_DESTINATIONS)) {
            out.add(spec.endsWith(ORDERED_SUFFIX)
                    ? Outbox.Destination.ordered(spec.substring(0, spec.length() - ORDERED_SUFFIX.length()))
                    : Outbox.Destination.unordered(spec));
        }
        return List.copyOf(out);
    }

    /**
     * Every event goes to {@code fixed}; history, cross-cell and roll-up events also to {@code rollup} when present;
     * history events also to the {@code projection} sinks.
     */
    public static OutboxWriter.SubscriptionResolver subscriptions(List<Outbox.Destination> fixed,
                                                                  Optional<Outbox.Destination> rollup,
                                                                  List<Outbox.Destination> projection) {
        List<Outbox.Destination> base = List.copyOf(fixed);
        if (rollup.isEmpty() && projection.isEmpty()) {
            return OutboxWriter.SubscriptionResolver.fixed(base);
        }
        List<Outbox.Destination> rollupSet = rollup.map(r -> merge(base, List.of(r))).orElse(base);
        List<Outbox.Destination> historySet = merge(rollupSet, projection);
        return (tenant, eventType) -> eventType == null ? base
                : eventType.startsWith(HISTORY_TYPES) ? historySet
                : eventType.startsWith("sangam.xcell.") || eventType.startsWith("sangam.rollup.") ? rollupSet
                : base;
    }

    /** {@code a} then the destinations of {@code b} not already named in {@code a}. */
    public static List<Outbox.Destination> merge(List<Outbox.Destination> a, List<Outbox.Destination> b) {
        List<Outbox.Destination> out = new ArrayList<>(a);
        for (Outbox.Destination d : b) {
            if (out.stream().noneMatch(x -> x.name().equals(d.name()))) {
                out.add(d);
            }
        }
        return List.copyOf(out);
    }

    public List<Outbox.Destination> fixed() {
        return fixed;
    }

    /** The resolver the outbox writer is built with. */
    public OutboxWriter.SubscriptionResolver resolver() {
        return resolver;
    }

    private static boolean set(SangamProperties properties, String key) {
        String v = properties.get(key);
        return v != null && !v.isBlank();
    }

    /**
     * The destinations beyond the fixed list that a host has a publisher for: its roll-up destination, and the
     * destination of each history projection sink it delivers, keyed by the setting that turns it on
     * ({@link #HISTORY_SINK_KEYS}).
     */
    public record Routes(Optional<Outbox.Destination> rollup, Map<String, Outbox.Destination> historySinks) {

        public Routes {
            Objects.requireNonNull(rollup, "rollup");
            Map<String, Outbox.Destination> copy = new LinkedHashMap<>(historySinks);
            for (String key : copy.keySet()) {
                if (!HISTORY_SINK_KEYS.contains(key)) {
                    throw new IllegalArgumentException(key + " is not a history projection sink setting "
                            + HISTORY_SINK_KEYS);
                }
            }
            historySinks = Map.copyOf(copy);
        }

        /** A host with no publisher beyond the fixed destinations. */
        public static Routes none() {
            return new Routes(Optional.empty(), Map.of());
        }

        /** These routes plus the roll-up destination. */
        public Routes withRollup(Outbox.Destination destination) {
            return new Routes(Optional.of(destination), historySinks);
        }

        /** These routes plus the sink turned on by {@code key}. */
        public Routes withHistorySink(String key, Outbox.Destination destination) {
            Map<String, Outbox.Destination> next = new LinkedHashMap<>(historySinks);
            next.put(key, Objects.requireNonNull(destination, "destination"));
            return new Routes(rollup, next);
        }
    }
}
