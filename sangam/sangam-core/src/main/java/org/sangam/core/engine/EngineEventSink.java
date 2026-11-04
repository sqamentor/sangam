// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import java.sql.Connection;
import java.util.Map;

/**
 * Typed engine events (FC-43, FC-25/D-41): one event per engine lifecycle change, delivered inside the engine command
 * on the engine connection. The integration module implements it by appending an outbox row (VI.2), so history
 * projectors, analytics and webhooks receive the events after commit, in per-root order.
 */
@FunctionalInterface
public interface EngineEventSink {

    /**
     * @param type for example {@code PROCESS_STARTED}, {@code TASK_CREATED}, {@code TASK_COMPLETED},
     *             {@code ACTIVITY_ENDED}, {@code INCIDENT_CREATED}, {@code CASE_TASK_COMPLETED}
     */
    void publish(Connection connection, String tenantId, String rootId, String type, Map<String, Object> data);

    EngineEventSink NONE = (c, t, r, ty, d) -> {
    };
}
