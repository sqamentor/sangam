// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.List;
import java.util.Map;

/**
 * DMN evaluation ({@code POST /decisions/{key}/evaluate}). ROUTING / AUTHORITY evaluations with a business key are
 * recorded as class-B {@code ROUTING_DECISION} / {@code AUTHORITY_DECISION} events (X.2.3.2).
 */
public interface DecisionPort {

    /** purpose: ROUTING | AUTHORITY | OTHER. */
    record EvaluateRequest(Map<String, Object> variables, Integer version, String purpose, String businessKey) {
    }

    record EvaluateResult(String decisionDefinitionId, String decisionKey, int version,
                          List<Map<String, Object>> result, String outputDigest, String auditEventId) {
    }

    record DecisionDefinition(String id, String key, String name, int version, String deploymentId, String tenantId,
                              String decisionRequirementsDefinitionKey, String versionTag) {
    }

    EvaluateResult evaluate(RequestContext ctx, String decisionKey, EvaluateRequest request);

    Page<DecisionDefinition> definitions(RequestContext ctx, String key, Boolean latest, Page.PageRequest page);

    String definitionXml(RequestContext ctx, String definitionId);
}
