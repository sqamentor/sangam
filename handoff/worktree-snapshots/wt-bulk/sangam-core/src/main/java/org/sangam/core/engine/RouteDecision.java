// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import java.util.List;

/**
 * Result of {@link RoutingPolicy#decide}. {@link #code()} is the stable error code of a non-routable decision
 * ({@code AMBIGUOUS_ROUTE}, {@code NO_MATCHING_ROUTE}); it is used as incident and API error code.
 */
public record RouteDecision(String gatewayId, Outcome outcome, String flowId, List<String> matchedFlows,
                            RoutingPolicy.Mode mode, int policyVersion) {

    public enum Outcome { ROUTE, AMBIGUOUS_ROUTE, NO_MATCHING_ROUTE }

    public boolean routable() {
        return outcome == Outcome.ROUTE;
    }

    public String code() {
        return outcome.name();
    }

    /** Human-readable incident message, prefixed with the stable code. */
    public String message() {
        return switch (outcome) {
            case ROUTE -> "ROUTE: " + gatewayId + " -> " + flowId;
            case AMBIGUOUS_ROUTE -> "AMBIGUOUS_ROUTE: exclusive gateway " + gatewayId + " has " + matchedFlows.size()
                    + " true conditions " + matchedFlows + " (policy " + mode + " v" + policyVersion
                    + "); correct the data or the model, then retry";
            case NO_MATCHING_ROUTE -> "NO_MATCHING_ROUTE: exclusive gateway " + gatewayId
                    + " has no true condition and no default flow (policy " + mode + " v" + policyVersion + ")";
        };
    }
}
