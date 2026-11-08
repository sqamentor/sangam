// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.Map;

import org.sangam.core.integration.SangamConnector;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.packs.PackConnectorBindings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Registers in-process module connectors (config, ledger, billing, payee, health) on the integration
 * {@link ConnectorRegistry} and binds their pack connector ids in {@link PackConnectorBindings}. Site bindings under
 * {@code sangam.packs.bindings.*} win: an id the site already bound is not re-bound here. Without sangam-integration
 * (no registry bean) this does nothing, and the packs keep their simulator gateway.
 */
public final class ConfigConnectorBinder {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigConnectorBinder.class);

    private final ObjectProvider<ConnectorRegistry> registry;
    private final ObjectProvider<PackConnectorBindings> bindings;

    public ConfigConnectorBinder(ObjectProvider<ConnectorRegistry> registry, ObjectProvider<PackConnectorBindings> bindings) {
        this.registry = registry;
        this.bindings = bindings;
    }

    /** Platform-wide connector (all tenants). */
    public boolean register(SangamConnector connector) {
        ConnectorRegistry r = registry.getIfAvailable();
        if (r == null) {
            return false;
        }
        r.register(connector);
        return true;
    }

    /** Tenant-specific connector (overrides the platform one of the same id for that tenant). */
    public boolean register(String tenant, SangamConnector connector) {
        ConnectorRegistry r = registry.getIfAvailable();
        if (r == null) {
            return false;
        }
        r.register(tenant, connector);
        return true;
    }

    /**
     * Binds pack connector ids to a registry connector: {@code packId → operation} (null operation = the request's
     * own operation). Returns the number of ids bound.
     */
    public int bind(String connectorId, Map<String, String> packIdToOperation) {
        PackConnectorBindings b = bindings.getIfAvailable();
        if (b == null) {
            return 0;
        }
        int n = 0;
        for (Map.Entry<String, String> e : packIdToOperation.entrySet()) {
            if (b.binding(e.getKey()).isPresent() && !connectorId.equals(b.binding(e.getKey()).get().connectorId())) {
                LOG.info("pack connector {} keeps its site binding to {}", e.getKey(), b.binding(e.getKey()).get().connectorId());
                continue;
            }
            b.bind(e.getKey(), connectorId, e.getValue());
            n++;
        }
        return n;
    }
}
