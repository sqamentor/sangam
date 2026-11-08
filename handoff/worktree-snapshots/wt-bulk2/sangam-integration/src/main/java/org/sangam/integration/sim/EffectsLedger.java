// SPDX-License-Identifier: Apache-2.0
// Ported from spike/simulators org.sangam.spike.sim.EffectsLedger. Modified by Sangam.
package org.sangam.integration.sim;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Append-only record of the external effects a simulator actually executed (a payment made, a
 * document signed, an SMS sent). Experiments assert on it, e.g. "no operationId was paid twice"
 * (R36, R58: no duplicate payment).
 */
public final class EffectsLedger {

    /** One executed effect. {@code key} is the business key the effect is deduplicated on. */
    public record Effect(long seq, Instant at, String type, String key, Map<String, String> attributes) { }

    private final AtomicLong seq = new AtomicLong();
    private final List<Effect> effects = new ArrayList<>();

    public Effect record(String type, String key, Map<String, String> attributes) {
        Effect e = new Effect(seq.incrementAndGet(), Instant.now(), type, key,
                attributes == null ? Map.of() : Map.copyOf(attributes));
        synchronized (effects) {
            effects.add(e);
        }
        return e;
    }

    public List<Effect> all() {
        synchronized (effects) {
            return List.copyOf(effects);
        }
    }

    public List<Effect> ofType(String type) {
        return all().stream().filter(e -> e.type().equals(type)).toList();
    }

    public long count(String type, String key) {
        return all().stream().filter(e -> e.type().equals(type) && e.key().equals(key)).count();
    }

    public long count(String type) {
        return ofType(type).size();
    }

    /** Keys of {@code type} recorded more than once, with their counts. */
    public Map<String, Long> duplicates(String type) {
        return ofType(type).stream()
                .collect(Collectors.groupingBy(Effect::key, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().filter(en -> en.getValue() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    public boolean hasDuplicates(String type) {
        return !duplicates(type).isEmpty();
    }

    public void clear() {
        synchronized (effects) {
            effects.clear();
        }
    }
}
