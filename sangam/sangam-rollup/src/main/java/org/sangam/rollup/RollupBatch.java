// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * In-memory pre-aggregation of one ingest batch (M-063, SCALE-ARCHITECTURE.md 8.4 b), with no database access.
 *
 * <p>The per-event path writes one counter row per (prefix, delta) of every event: about 4 levels x 2-3 metrics, so
 * ~10 row updates per event. Inside one batch, events of the same tenant subtree share most of those rows (every
 * event of a state touches the state and nation rows), so the batch sums the deltas per (node, metric, day) first and
 * writes each distinct row once. The sums are exact (integer addition commutes). A pending upstream key whose deltas
 * cancel (a process started and ended inside the batch: {@code process.open} +1 -1) ships nothing.
 *
 * <p>Keys are kept sorted, so concurrent batches lock the rows they share in the same order.
 */
final class RollupBatch {

    record Key(String node, String metric, int day) {
    }

    record Node(String path, String parent, int depth) {
    }

    static final Comparator<Key> ORDER = Comparator.comparing(Key::node).thenComparing(Key::metric)
            .thenComparingInt(Key::day);

    private final Map<Key, Long> counters = new TreeMap<>(ORDER);
    private final Map<Key, Long> pending = new TreeMap<>(ORDER);
    private final Map<String, Node> nodes = new TreeMap<>();
    private final Map<String, Long> feedEvents = new LinkedHashMap<>();
    private final int forwardDepth;
    private int events;
    private long naiveRows;

    /** @param forwardDepth {@code upstream.depth} when this instance forwards to an upper tier, else -1 */
    RollupBatch(int forwardDepth) {
        this.forwardDepth = forwardDepth;
    }

    void add(String cell, RollupEvents.Effect effect) {
        events++;
        List<String> prefixes = effect.prefixes();
        for (int i = 0; i < prefixes.size(); i++) {
            String node = prefixes.get(i);
            nodes.putIfAbsent(node, new Node(node, i == 0 ? null : prefixes.get(i - 1), i));
            for (RollupEvents.Delta d : effect.deltas()) {
                counters.merge(new Key(node, d.metric(), effect.day()), d.value(), Long::sum);
                naiveRows++;
            }
        }
        if (forwardDepth >= 0) {
            String cut = RollupEvents.cutNode(prefixes, forwardDepth);
            for (RollupEvents.Delta d : effect.deltas()) {
                pending.merge(new Key(cut, d.metric(), effect.day()), d.value(), Long::sum);
                naiveRows++;
            }
        }
        feedEvents.merge(cell, 1L, Long::sum);
    }

    /**
     * Counter deltas to write, sorted. A key whose deltas cancel is still written (+0), so the rows that exist, and
     * therefore the metrics a query lists, are the same as after the per-event path.
     */
    Map<Key, Long> counters() {
        return counters;
    }

    /** Pending upstream deltas to write, sorted; keys whose deltas cancel are left out. */
    Map<Key, Long> pending() {
        return nonZero(pending);
    }

    /** Hierarchy nodes the batch touches, sorted by path (a parent sorts before its children). */
    Map<String, Node> nodes() {
        return nodes;
    }

    /** Events per publishing cell. */
    Map<String, Long> feedEvents() {
        return feedEvents;
    }

    int events() {
        return events;
    }

    /** Row writes the per-event path would have made for the same events (the SG-15 comparison). */
    long naiveRows() {
        return naiveRows;
    }

    /** Row writes of this batch (counters + pending). */
    int rows() {
        return counters.size() + pending().size();
    }

    private static Map<Key, Long> nonZero(Map<Key, Long> m) {
        Map<Key, Long> out = new TreeMap<>(ORDER);
        m.forEach((k, v) -> {
            if (v != 0L) {
                out.put(k, v);
            }
        });
        return out;
    }
}
