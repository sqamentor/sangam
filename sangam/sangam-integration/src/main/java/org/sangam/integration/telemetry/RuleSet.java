// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.telemetry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The threshold rules of one tenant (immutable; replace the whole set to change rules). {@link Registry} holds the
 * current set per tenant and is what {@link TelemetryRuleHandler} and {@link AlarmAcknowledgementService} read.
 *
 * <p>Changing a rule does not touch existing episodes: an episode keeps its {@code rule_id}; if the rule disappears,
 * the episode is no longer advanced by readings (an operator closes it) and its overdue notification is skipped.
 */
public final class RuleSet {

    private final String tenantId;
    private final Map<String, ThresholdRule> rules;

    public RuleSet(String tenantId, Collection<ThresholdRule> rules) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        Map<String, ThresholdRule> m = new LinkedHashMap<>();
        for (ThresholdRule r : rules) {
            if (!tenantId.equals(r.tenantId())) {
                throw new IllegalArgumentException("rule " + r.ruleId() + " belongs to tenant " + r.tenantId() + ", not " + tenantId);
            }
            if (m.put(r.ruleId(), r) != null) {
                throw new IllegalArgumentException("duplicate rule id " + r.ruleId());
            }
        }
        this.tenantId = tenantId;
        this.rules = java.util.Collections.unmodifiableMap(m);
    }

    public static RuleSet empty(String tenantId) {
        return new RuleSet(tenantId, List.of());
    }

    public String tenantId() {
        return tenantId;
    }

    public Collection<ThresholdRule> rules() {
        return rules.values();
    }

    public Optional<ThresholdRule> rule(String ruleId) {
        return Optional.ofNullable(rules.get(ruleId));
    }

    /** Enabled rules for one reading, in definition order. */
    public List<ThresholdRule> matching(String deviceId, String sensor) {
        List<ThresholdRule> out = new ArrayList<>();
        for (ThresholdRule r : rules.values()) {
            if (r.matches(deviceId, sensor)) {
                out.add(r);
            }
        }
        return out;
    }

    /** Copy with one rule added or replaced. */
    public RuleSet with(ThresholdRule rule) {
        Map<String, ThresholdRule> m = new LinkedHashMap<>(rules);
        m.put(rule.ruleId(), rule);
        return new RuleSet(tenantId, m.values());
    }

    /** Copy without {@code ruleId}. */
    public RuleSet without(String ruleId) {
        Map<String, ThresholdRule> m = new LinkedHashMap<>(rules);
        m.remove(ruleId);
        return new RuleSet(tenantId, m.values());
    }

    /** Current rule set per tenant (thread-safe, copy-on-write). */
    public static final class Registry {
        private final Map<String, RuleSet> sets = new ConcurrentHashMap<>();

        public Registry put(RuleSet set) {
            sets.put(set.tenantId(), set);
            return this;
        }

        /** Adds or replaces one rule in its tenant's set. */
        public Registry put(ThresholdRule rule) {
            sets.compute(rule.tenantId(), (t, cur) -> (cur == null ? empty(t) : cur).with(rule));
            return this;
        }

        public Registry remove(String tenantId, String ruleId) {
            sets.computeIfPresent(tenantId, (t, cur) -> cur.without(ruleId));
            return this;
        }

        public RuleSet get(String tenantId) {
            RuleSet s = sets.get(tenantId);
            return s == null ? empty(tenantId) : s;
        }

        public Optional<ThresholdRule> rule(String tenantId, String ruleId) {
            return get(tenantId).rule(ruleId);
        }
    }
}
