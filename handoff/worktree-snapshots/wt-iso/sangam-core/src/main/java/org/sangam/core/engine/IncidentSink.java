// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import java.sql.Connection;

/**
 * Port through which engine incidents feed reconciliation (VI.3, R21; table SANGAM_ENGINE_INCIDENT). Called inside
 * the engine command that creates, resolves or deletes the incident, on the engine connection.
 */
public interface IncidentSink {

    /**
     * @param engineWorkId the external task id for {@code failedExternalTask} incidents, the job id for
     *                     {@code failedJob}, else null
     */
    record Incident(String incidentId, String type, String tenantId, String rootId, String processInstanceId,
                    String executionId, String activityId, String processDefinitionId, String configuration,
                    String engineWorkId, String message) {
    }

    void opened(Connection connection, Incident incident);

    /** @param reason {@code RESOLVED} or {@code DELETED} */
    void closed(Connection connection, String incidentId, String reason);

    IncidentSink NONE = new IncidentSink() {
        @Override
        public void opened(Connection connection, Incident incident) {
        }

        @Override
        public void closed(Connection connection, String incidentId, String reason) {
        }
    };
}
