// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import java.sql.Connection;
import java.util.Map;

/**
 * Port through which the engine's history hook feeds the class-B audit hash chain (R42, X.2.3, EC-09). Called inside
 * the engine command, on the engine connection, so audit rows commit or roll back with the engine state (L5 inserts).
 * The default implementation in sangam-engine appends to {@code org.sangam.core.layer.AuditChain}.
 */
@FunctionalInterface
public interface AuditSink {

    void append(Connection connection, String tenantId, String rootId, String eventType, Map<String, Object> payload);

    AuditSink NONE = (c, t, r, e, p) -> {
    };
}
