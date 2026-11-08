// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.ports;

import java.sql.Connection;
import java.util.Optional;

/**
 * Unique business start (VI.2.6 rule 4, FLOWABLE-INTO-OPERATON FC-04, R38) on WS-INTEGRATION's {@code UNIQUE_START} table. Added by WS-PORTS
 * so the event registry can use the integration table without a module cycle (sangam-integration depends on
 * sangam-flowable-ports). Method shapes are those of {@code org.sangam.integration.inbox.UniqueStartGuard}; the
 * integrator binds it ({@code UniqueStartGuard implements UniqueStartPort}, or a one-line adapter).
 * All calls run in the caller's transaction.
 */
public interface UniqueStartPort {

    /** Claims the start; false when this (tenant, definition key, business key) already started. */
    boolean claim(Connection c, String tenantId, String definitionKey, String businessKey, String source, String messageId);

    /** Records the started instance id on the claim row (same transaction as the start). */
    void bind(Connection c, String tenantId, String definitionKey, String businessKey, String instanceId);

    Optional<String> existingInstance(Connection c, String tenantId, String definitionKey, String businessKey);
}
