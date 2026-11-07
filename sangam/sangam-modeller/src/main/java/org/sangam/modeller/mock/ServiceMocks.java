// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.mock;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service mocks for model tests, stored with test models: fixed or conditional response, error, delay, per connector operation, with a
 * global toggle and per-service toggles. {@link #wrap} refuses the production profile when it builds the wrapper, so a mock cannot serve
 * a production call. A mocked service that matches no rule fails instead of calling the real service.
 */
public final class ServiceMocks {

    /** A rule. {@code whenInput} matches by equal string values of the named inputs; empty matches everything. */
    public record Rule(String service, String operation, Map<String, String> whenInput, Map<String, Object> output, String errorCode, Duration delay) {
    }

    private final boolean productionProfile;
    private final List<Rule> rules;
    private volatile java.util.function.Consumer<String> onNewService = s -> { };
    private volatile boolean globalEnabled = true;
    private final Set<String> disabledServices = ConcurrentHashMap.newKeySet();

    public ServiceMocks(boolean productionProfile, List<Rule> rules) {
        this.productionProfile = productionProfile;
        this.rules = new java.util.concurrent.CopyOnWriteArrayList<>(rules);
    }

    public void setGlobalEnabled(boolean on) {
        globalEnabled = on;
    }

    public void setServiceEnabled(String service, boolean on) {
        if (on) {
            disabledServices.remove(service);
        } else {
            disabledServices.add(service);
        }
    }

    /** Wraps a real connector. Rejected outright on the production profile. */
    public SangamConnector wrap(SangamConnector real) {
        if (productionProfile) {
            throw new IllegalStateException("MOCKS_FORBIDDEN_ON_PRODUCTION");
        }
        String service = real.descriptor().id();
        return new SangamConnector() {
            @Override
            public ConnectorDescriptor descriptor() {
                return real.descriptor();
            }

            @Override
            public ConnectorResult invoke(ConnectorRequest r) {
                if (!globalEnabled || disabledServices.contains(service)) {
                    return real.invoke(r);
                }
                Rule rule = rules.stream().filter(x -> x.service().equals(service) && x.operation().equals(r.operation()) && matches(x, r)).findFirst().orElse(null);
                if (rule == null) {
                    return ConnectorResult.BusinessFailure.of("MOCK_NOT_DEFINED", "no mock rule for " + service + "." + r.operation());
                }
                if (rule.delay() != null) {
                    try {
                        Thread.sleep(rule.delay());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return ConnectorResult.TransientFailure.noEffect("interrupted during mock delay");
                    }
                }
                return rule.errorCode() != null ? ConnectorResult.BusinessFailure.of(rule.errorCode(), "mocked error") : ConnectorResult.Success.of(rule.output(), "mock:" + r.operationId());
            }

            @Override
            public LookupResult lookup(ConnectorRequest r) {
                return globalEnabled && !disabledServices.contains(service) ? new LookupResult.NotFound() : real.lookup(r);
            }
        };
    }

    private static boolean matches(Rule rule, ConnectorRequest r) {
        return rule.whenInput().entrySet().stream().allMatch(e -> Objects.equals(e.getValue(), r.str(e.getKey())));
    }

    public boolean globalEnabled() {
        return globalEnabled;
    }

    public boolean serviceEnabled(String service) {
        return !disabledServices.contains(service);
    }

    public List<Rule> rules() {
        return List.copyOf(rules);
    }

    /** Called with a rule's service when a rule is added, so the binding can wrap that connector. */
    public void onNewService(java.util.function.Consumer<String> hook) {
        onNewService = hook;
    }

    /** Adds a rule for this process's lifetime. Refused on the production profile. */
    public void addRule(Rule rule) {
        if (productionProfile) {
            throw new IllegalStateException("MOCKS_FORBIDDEN_ON_PRODUCTION");
        }
        rules.add(rule);
        onNewService.accept(rule.service());
    }
}
