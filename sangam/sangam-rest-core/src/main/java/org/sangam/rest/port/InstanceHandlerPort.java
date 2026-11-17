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

    /** A declared handler argument (contract schema {@code HandlerParameter}). */
    record Parameter(String name, String type, boolean required, String description) {
    }

    /**
     * A handler the deployed model declares (contract schema {@code InstanceHandler}): kind {@code QUERY} or
     * {@code UPDATE}; {@code changes} lists the variables an update may change (empty for a query).
     */
    record Handler(String name, String kind, String description, List<Parameter> parameters, String permission,
                   String resultType, List<String> changes) {
    }

    /**
     * {@code GET /process-definitions/by-id/handlers}: the queries then the updates of the definition, each sorted by
     * name; 404 for an unknown definition or one of another tenant, 422 when the declaration no longer passes the
     * deploy rules.
     */
    List<Handler> list(RequestContext ctx, String processDefinitionId);

    /** Read-only; 404 for an unknown instance or handler, 422 VALIDATION_FAILED for bad arguments. */
    QueryResult query(RequestContext ctx, String processInstanceId, String name, QueryRequest request);

    /**
     * One transaction: version and payload binding checked (409 REVISION_CONFLICT / APPROVAL_INVALIDATED), arguments
     * validated (422), the declared assignments applied, the case version raised and a class-B
     * {@code INSTANCE_UPDATED} event recorded.
     */
    UpdateResult update(RequestContext ctx, String processInstanceId, String name, UpdateRequest request);
}
