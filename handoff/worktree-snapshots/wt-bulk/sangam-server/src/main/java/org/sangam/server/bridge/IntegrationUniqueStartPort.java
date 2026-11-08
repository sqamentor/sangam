// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.util.Optional;

import org.sangam.core.ports.UniqueStartPort;
import org.sangam.integration.inbox.UniqueStartGuard;

/**
 * sangam-core {@link UniqueStartPort} (consumed by sangam-flowable-ports' {@code EventDispatcher}, FLOWABLE-INTO-OPERATON FC-04 / R38
 * {@code uniqueStart}) on sangam-integration's {@link UniqueStartGuard}: one table, one guard for inbox and event
 * registry starts.
 */
public final class IntegrationUniqueStartPort implements UniqueStartPort {

    private final UniqueStartGuard guard;

    public IntegrationUniqueStartPort(UniqueStartGuard guard) {
        this.guard = guard;
    }

    @Override
    public boolean claim(Connection c, String tenantId, String definitionKey, String businessKey, String source,
                         String messageId) {
        return guard.claim(c, tenantId, definitionKey, businessKey, source, messageId);
    }

    @Override
    public void bind(Connection c, String tenantId, String definitionKey, String businessKey, String instanceId) {
        guard.bind(c, tenantId, definitionKey, businessKey, instanceId);
    }

    @Override
    public Optional<String> existingInstance(Connection c, String tenantId, String definitionKey, String businessKey) {
        return guard.existingInstance(c, tenantId, definitionKey, businessKey);
    }
}
