// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * <b>Exclusive (XOR) routing policy, ADR-D-25</b>: one engine-neutral rule, applied by every engine adapter and
 * every model tool, so a gateway routes the same way everywhere. It deliberately differs from the native BPMN
 * engines, which take the first true flow in document order.
 *
 * <ul>
 *   <li>{@link Mode#STRICT} (the default): every condition is evaluated. Exactly one true: take it. More than one
 *       true: {@link RouteDecision.Outcome#AMBIGUOUS_ROUTE}. None true: the explicit default flow, else
 *       {@link RouteDecision.Outcome#NO_MATCHING_ROUTE}. Model order is never used.</li>
 *   <li>{@link Mode#PRIORITY}: first true flow by the explicit {@code sangam:priority} of each conditional flow
 *       (ascending; validated unique and complete at deployment), then the default, else NO_MATCHING_ROUTE.</li>
 *   <li>An evaluation error is never "false": the exception of the condition propagates unchanged, and the default
 *       flow is not taken.</li>
 * </ul>
 * Model attributes (namespace {@link #NS_URI}): gateway {@code sangam:routingPolicy="strict|priority"}, optional
 * {@code sangam:routingPolicyVersion="1"}; sequence flow {@code sangam:priority="<int>"}.
 */
public final class RoutingPolicy {

    /** Sangam BPMN extension namespace (same URI as {@code org.sangam.ports.bpmn.SangamBpmn.NS_URI}). */
    public static final String NS_URI = "http://sangam.org/schema/1.0/bpmn";
    public static final String ATTR_POLICY = "routingPolicy";
    public static final String ATTR_POLICY_VERSION = "routingPolicyVersion";
    public static final String ATTR_PRIORITY = "priority";
    /** Version of this rule set; recorded in every decision. */
    public static final int VERSION = 1;

    public enum Mode { STRICT, PRIORITY }

    /** One outgoing flow of the gateway; {@code condition} is null for a flow without a condition. */
    public record Candidate(String flowId, Integer priority, BooleanSupplier condition) {
    }

    /** Static description of an outgoing flow, for deploy-time validation. */
    public record FlowSpec(String flowId, boolean hasCondition, String priority) {
    }

    private RoutingPolicy() {
    }

    /** Parses {@code sangam:routingPolicy}; null or blank is STRICT. Unknown values are rejected. */
    public static Mode parseMode(String value) {
        if (value == null || value.isBlank()) {
            return Mode.STRICT;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "strict" -> Mode.STRICT;
            case "priority" -> Mode.PRIORITY;
            default -> throw new IllegalArgumentException("unknown sangam:routingPolicy '" + value
                    + "' (allowed: strict, priority)");
        };
    }

    /**
     * Deploy-time validation (R44). Returns the problems; empty means valid.
     *
     * @param defaultFlowId the gateway's {@code default} flow, or null
     */
    public static List<String> validate(String gatewayId, String policy, String policyVersion, List<FlowSpec> flows,
                                        String defaultFlowId) {
        List<String> problems = new ArrayList<>();
        Mode mode;
        try {
            mode = parseMode(policy);
        } catch (IllegalArgumentException e) {
            problems.add(gatewayId + ": " + e.getMessage());
            return problems;
        }
        if (policyVersion != null && !policyVersion.isBlank() && !String.valueOf(VERSION).equals(policyVersion.trim())) {
            problems.add(gatewayId + ": sangam:routingPolicyVersion '" + policyVersion + "' is not supported (current "
                    + VERSION + ")");
        }
        Set<Integer> seen = new HashSet<>();
        for (FlowSpec f : flows) {
            boolean isDefault = f.flowId().equals(defaultFlowId);
            if (isDefault) {
                if (f.priority() != null) {
                    problems.add(gatewayId + ": default flow " + f.flowId() + " must not have sangam:priority");
                }
                continue;
            }
            if (flows.size() > 1 && !f.hasCondition()) {
                problems.add(gatewayId + ": flow " + f.flowId() + " has no condition and is not the default flow "
                        + "(it would always be true)");
            }
            if (mode == Mode.STRICT) {
                if (f.priority() != null) {
                    problems.add(gatewayId + ": flow " + f.flowId() + " has sangam:priority but the gateway is not "
                            + "sangam:routingPolicy=\"priority\"");
                }
                continue;
            }
            if (f.priority() == null || f.priority().isBlank()) {
                problems.add(gatewayId + ": priority policy needs sangam:priority on flow " + f.flowId());
                continue;
            }
            int p;
            try {
                p = Integer.parseInt(f.priority().trim());
            } catch (NumberFormatException e) {
                problems.add(gatewayId + ": flow " + f.flowId() + " has non-integer sangam:priority '" + f.priority() + "'");
                continue;
            }
            if (!seen.add(p)) {
                problems.add(gatewayId + ": duplicate sangam:priority " + p + " (flow " + f.flowId() + ")");
            }
        }
        if (defaultFlowId != null && flows.stream().noneMatch(f -> f.flowId().equals(defaultFlowId))) {
            problems.add(gatewayId + ": default flow " + defaultFlowId + " is not an outgoing flow");
        }
        return problems;
    }

    /**
     * Run-time decision. Condition exceptions propagate (an error is never false and never selects the default).
     */
    public static RouteDecision decide(String gatewayId, Mode mode, List<Candidate> candidates, String defaultFlowId) {
        List<Candidate> conditional = candidates.stream().filter(c -> !c.flowId().equals(defaultFlowId)).toList();
        if (mode == Mode.PRIORITY) {
            List<Candidate> ordered = new ArrayList<>(conditional);
            ordered.sort(Comparator.comparing(c -> c.priority() == null ? Integer.MAX_VALUE : c.priority()));
            for (Candidate c : ordered) {
                if (isTrue(c)) {
                    return new RouteDecision(gatewayId, RouteDecision.Outcome.ROUTE, c.flowId(), List.of(c.flowId()),
                            mode, VERSION);
                }
            }
        } else {
            List<String> matched = new ArrayList<>();
            for (Candidate c : conditional) {
                if (isTrue(c)) {
                    matched.add(c.flowId());
                }
            }
            if (matched.size() == 1) {
                return new RouteDecision(gatewayId, RouteDecision.Outcome.ROUTE, matched.get(0), matched, mode, VERSION);
            }
            if (matched.size() > 1) {
                return new RouteDecision(gatewayId, RouteDecision.Outcome.AMBIGUOUS_ROUTE, null, List.copyOf(matched),
                        mode, VERSION);
            }
        }
        if (defaultFlowId != null) {
            return new RouteDecision(gatewayId, RouteDecision.Outcome.ROUTE, defaultFlowId, List.of(), mode, VERSION);
        }
        return new RouteDecision(gatewayId, RouteDecision.Outcome.NO_MATCHING_ROUTE, null, List.of(), mode, VERSION);
    }

    private static boolean isTrue(Candidate c) {
        return c.condition() == null || c.condition().getAsBoolean();
    }
}
