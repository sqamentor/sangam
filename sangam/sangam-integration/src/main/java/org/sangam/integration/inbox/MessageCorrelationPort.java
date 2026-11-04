// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import java.sql.Connection;
import java.util.Map;

/**
 * Engine port used by inbox processing (R38; Flowable FC-02 "inbox → createMessageCorrelation /
 * startProcessInstanceByMessage"). Implemented for Operaton by
 * {@code org.sangam.integration.bridge.operaton.OperatonMessageCorrelationPort}. Every method runs inside the inbox
 * processing transaction, which must be the engine transaction ({@code Transactions.of(engineAdapter)}), so the
 * engine change and the inbox {@code PROCESSED} mark commit together.
 */
public interface MessageCorrelationPort {

    /** True when at least one waiting instance (or a message start event) would accept the message now. */
    boolean hasSubscription(Connection c, String tenantId, String messageName, String correlationKey);

    /**
     * Correlates to the waiting instance(s) identified by {@code correlationKey} (business key, or a process variable
     * {@code sangamCorrelationKey}). Returns the number of executions that received it (0 = no subscription).
     */
    int correlate(Connection c, String tenantId, String messageName, String correlationKey, Map<String, Object> variables);

    /** Starts a new instance of {@code definitionKey}; returns its id. */
    String start(Connection c, String tenantId, String definitionKey, String businessKey, Map<String, Object> variables);
}
