// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import org.sangam.core.layer.CapabilityClass;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.SangamPack;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Maps the connector ids the packs use (as declared in
 * each {@code pack.json}) to connector instances of the integration {@code ConnectorRegistry}, with an optional
 * operation rename. Also keeps each pack connector's declared capability class (A-2), so {@code capabilityOf} answers
 * for connectors that are not bound yet; undeclared means (c) (A-8).
 */
public final class PackConnectorBindings {

    /**
     * @param connectorId registry id of the integration connector
     * @param operation   operation to call; null = the pack's own operation name
     */
    public record Binding(String connectorId, String operation, UnaryOperator<Map<String, Object>> payloadMapper) {
        public Binding {
            payloadMapper = payloadMapper == null ? UnaryOperator.identity() : payloadMapper;
        }
    }

    private final Map<String, Binding> bindings = new ConcurrentHashMap<>();
    private final Map<String, Binding> tenantBindings = new ConcurrentHashMap<>();
    private final Map<String, CapabilityClass> declared = new ConcurrentHashMap<>();

    public PackConnectorBindings bind(String packConnector, String connectorId, String operationOrNull) {
        return bind(packConnector, connectorId, operationOrNull, null);
    }

    /** With a payload mapper from the pack's field names to the connector's input keys (site adapter). */
    public PackConnectorBindings bind(String packConnector, String connectorId, String operationOrNull,
                                      UnaryOperator<Map<String, Object>> payloadMapper) {
        bindings.put(packConnector, new Binding(connectorId, operationOrNull, payloadMapper));
        return this;
    }

    /**
     * Default bindings from pack data (SL-104, ADR-D-20): each pack connector's {@code simulatorBinding} in the
     * discovered packs' manifests, plus their declared capability classes. Several packs may declare the same pack
     * connector with the same binding; different bindings for one pack connector are refused, naming both packs. A
     * site's {@code sangam.packs.bindings.*} configuration is applied afterwards with {@link #bind}, so it wins.
     */
    public static PackConnectorBindings fromPackData(Iterable<SangamPack> packs) {
        PackConnectorBindings b = new PackConnectorBindings().declareFrom(packs);
        Map<String, String> declaredBy = new java.util.HashMap<>();
        Map<String, PackDescriptor.SimulatorBinding> seen = new java.util.HashMap<>();
        for (SangamPack p : packs) {
            for (PackDescriptor.ConnectorUse u : p.descriptor().connectors()) {
                PackDescriptor.SimulatorBinding sb = u.simulatorBinding();
                if (sb == null) {
                    continue;
                }
                PackDescriptor.SimulatorBinding prior = seen.putIfAbsent(u.id(), sb);
                if (prior != null && !prior.equals(sb)) {
                    throw new IllegalStateException("pack connector " + u.id() + ": " + p.descriptor().coordinates()
                            + " binds " + sb + " but " + declaredBy.get(u.id()) + " binds " + prior);
                }
                declaredBy.putIfAbsent(u.id(), p.descriptor().coordinates());
                b.bind(u.id(), sb.connector(), sb.operation());
            }
        }
        return b;
    }

    /** Reads the declared capability classes from the discovered packs. */
    public PackConnectorBindings declareFrom(Iterable<SangamPack> packs) {
        for (SangamPack p : packs) {
            for (PackDescriptor.ConnectorUse u : p.descriptor().connectors()) {
                try {
                    declared.put(u.id(), CapabilityClass.fromDeclaration(u.capabilityClass()));
                } catch (IllegalArgumentException e) {
                    declared.put(u.id(), CapabilityClass.C_NEITHER);
                }
            }
        }
        return this;
    }

    public PackConnectorBindings declare(String packConnector, CapabilityClass cls) {
        declared.put(packConnector, cls);
        return this;
    }

    /** Binds a pack connector for one tenant only; other tenants keep the platform binding or the simulator fallback. */
    public PackConnectorBindings bindForTenant(String tenantId, String packConnector, String connectorId, String operationOrNull) {
        tenantBindings.put(tenantId + "|" + packConnector, new Binding(connectorId, operationOrNull, null));
        return this;
    }

    /** The tenant's own binding, else the platform binding. */
    public Optional<Binding> binding(String tenantId, String packConnector) {
        Binding own = tenantId == null ? null : tenantBindings.get(tenantId + "|" + packConnector);
        return own != null ? Optional.of(own) : binding(packConnector);
    }

    public Optional<Binding> binding(String packConnector) {
        return Optional.ofNullable(bindings.get(packConnector));
    }

    public CapabilityClass declaredClass(String packConnector) {
        return declared.getOrDefault(packConnector, CapabilityClass.C_NEITHER);
    }

}
