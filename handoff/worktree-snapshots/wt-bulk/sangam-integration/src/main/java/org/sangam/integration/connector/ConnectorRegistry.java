// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.SangamConnector;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service registry of connector instances (R71, PF-39): id → connector, with its declared capability class (R36).
 * Connectors may be registered per tenant ({@code tenantId:connectorId}) to override a platform-wide instance, so a
 * State can point the same model at its own treasury endpoint.
 */
public final class ConnectorRegistry {

    private final Map<String, SangamConnector> connectors = new ConcurrentHashMap<>();

    /** Registers a platform-wide connector under its descriptor id. */
    public ConnectorRegistry register(SangamConnector connector) {
        connectors.put(connector.descriptor().id(), connector);
        return this;
    }

    /** Registers a tenant-specific connector that overrides the platform instance of the same id for that tenant. */
    public ConnectorRegistry register(String tenantId, SangamConnector connector) {
        connectors.put(tenantId + ":" + connector.descriptor().id(), connector);
        return this;
    }

    public ConnectorRegistry registerAll(Collection<? extends SangamConnector> all) {
        all.forEach(this::register);
        return this;
    }

    public Optional<SangamConnector> find(String tenantId, String connectorId) {
        SangamConnector c = tenantId == null ? null : connectors.get(tenantId + ":" + connectorId);
        return Optional.ofNullable(c != null ? c : connectors.get(connectorId));
    }

    public SangamConnector require(String tenantId, String connectorId) {
        return find(tenantId, connectorId)
                .orElseThrow(() -> new IllegalArgumentException("no connector '" + connectorId + "' for tenant " + tenantId));
    }

    public List<ConnectorDescriptor> descriptors() {
        return connectors.values().stream().map(SangamConnector::descriptor).distinct().toList();
    }
}
