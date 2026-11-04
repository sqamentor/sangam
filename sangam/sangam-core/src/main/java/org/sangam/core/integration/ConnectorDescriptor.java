// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.integration;

import org.sangam.core.layer.CapabilityClass;

import java.util.List;
import java.util.Objects;

/**
 * Static description of a connector instance (R71 service registry entry).
 *
 * @param id              unique connector instance id, e.g. {@code ifms-odisha} or {@code smtp-default}
 * @param type            connector type, e.g. {@code http}, {@code soap}, {@code smtp}, {@code sms-dlt}, {@code sftp},
 *                        {@code esign}, {@code digilocker}, {@code payment-gateway}, {@code pfms}, {@code ifms-jit}
 * @param capabilityClass A-2 class; the A-8 default is (c)
 * @param operations      the operation names {@link ConnectorRequest#operation()} may carry (empty = any)
 * @param profile         live or simulator (air-gapped development)
 */
public record ConnectorDescriptor(String id, String type, CapabilityClass capabilityClass, List<String> operations,
                                  ConnectorProfile profile) {

    public ConnectorDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        capabilityClass = capabilityClass == null ? CapabilityClass.C_NEITHER : capabilityClass;
        operations = operations == null ? List.of() : List.copyOf(operations);
        profile = profile == null ? ConnectorProfile.LIVE : profile;
    }

    public boolean supportsOperation(String operation) {
        return operations.isEmpty() || operations.contains(operation);
    }

    public boolean supportsLookup() {
        return capabilityClass != CapabilityClass.C_NEITHER;
    }
}
