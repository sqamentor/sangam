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
 * Maps the connector ids the packs use ({@code pfms.payment}, {@code esign.sign}, {@code ifms.budget}, ... as declared in
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

    public Optional<Binding> binding(String packConnector) {
        return Optional.ofNullable(bindings.get(packConnector));
    }

    public CapabilityClass declaredClass(String packConnector) {
        return declared.getOrDefault(packConnector, CapabilityClass.C_NEITHER);
    }

    /**
     * Default bindings of the government connectors built by {@code GovConnectors} (simulator ids) to the pack
     * connector names used by the four pilot packs. Sites override them with {@link #bind}.
     */
    public static PackConnectorBindings governmentDefaults() {
        return new PackConnectorBindings()
                .bind("pfms.payment", "pfms-sim", "pay")
                .bind("pfms.dbt", "pfms-sim", "pay")
                .bind("esign.sign", "esign-sim", "sign")
                .bind("digilocker.push", "digilocker-sim", "push")
                .bind("notify.sms", "sms-dlt-sim", "send")
                .bind("notify.email", "smtp", "send")
                .bind("payment.refund", "payment-gateway-sim", "createOrder");
    }
}
