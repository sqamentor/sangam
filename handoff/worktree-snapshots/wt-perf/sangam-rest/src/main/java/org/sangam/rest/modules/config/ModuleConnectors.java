// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.config;

import java.util.Map;
import java.util.UUID;

import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.Hashes;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;

/**
 * Calls an in-process module connector (config, billing) from a REST endpoint, so the endpoint and the pack step share
 * one implementation. The operation id is derived from the Idempotency-Key when present (a retried request maps to the
 * same operation), else a fresh UUIDv7.
 */
public final class ModuleConnectors {

    private ModuleConnectors() {
    }

    public static Map<String, Object> call(SangamConnector connector, RequestContext ctx, String tenantOrScope,
                                           String operation, Map<String, Object> input) {
        UUID op = ctx.idempotencyKey() == null ? Hashes.uuidV7()
                : Hashes.uuidV5(UUID.nameUUIDFromBytes("sangam-rest-module-connector".getBytes()),
                ctx.tenantId() + "|" + operation + "|" + ctx.idempotencyKey());
        Map<String, Object> in = new java.util.LinkedHashMap<>(input == null ? Map.of() : input);
        in.putIfAbsent("actor", ctx.principalId());
        ConnectorResult r = connector.invoke(new ConnectorRequest(tenantOrScope, op, null, 1, null, null, operation, in,
                null));
        return switch (r) {
            case ConnectorResult.Success s -> s.output();
            case ConnectorResult.BusinessFailure f -> throw ApiProblem.unprocessable(f.code(), f.message())
                    .with("output", f.output());
            case ConnectorResult.TransientFailure t -> throw ApiProblem.unavailable("CONNECTOR_UNAVAILABLE", t.message(),
                    t.retryAfter() == null ? 5 : (int) Math.max(1, t.retryAfter().toSeconds()));
            case ConnectorResult.OutcomeUnknown u -> throw ApiProblem.conflict("OUTCOME_UNKNOWN", u.reason());
        };
    }
}
