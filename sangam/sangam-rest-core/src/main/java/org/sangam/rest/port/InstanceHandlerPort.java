// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * M-059 (L6; R19, R25, R42, R49; ADR-D-26 strict FEEL): the named query and update handlers a process model declares
 * ({@code sangam:query} / {@code sangam:update}), behind {@code POST /process-instances/{id}/queries/{name}} and
 * {@code POST /process-instances/{id}/updates/{name}} (contract schemas {@code InstanceQueryRequest/Result},
 * {@code InstanceUpdateRequest/Result}).
 */
public interface InstanceHandlerPort {

    record QueryRequest(Map<String, Object> arguments) {
    }

    record QueryResult(String processInstanceId, String name, String definitionId, String packageHash, Object result,
                       Instant evaluatedAt) {
    }

    record UpdateRequest(Map<String, Object> arguments, String reason, String reasonCode, String payloadHash,
                         Long caseVersion) {
    }

    record UpdateResult(String processInstanceId, String name, List<String> changedVariables, long caseVersion,
                        String payloadHash, String auditEventId) {
    }

    /** Read-only; 404 for an unknown instance or handler, 422 VALIDATION_FAILED for bad arguments. */
    QueryResult query(RequestContext ctx, String processInstanceId, String name, QueryRequest request);

    /**
     * One transaction: version and payload binding checked (409 REVISION_CONFLICT / APPROVAL_INVALIDATED), arguments
     * validated (422), the declared assignments applied, the case version raised and a class-B
     * {@code INSTANCE_UPDATED} event recorded.
     */
    UpdateResult update(RequestContext ctx, String processInstanceId, String name, UpdateRequest request);
}
