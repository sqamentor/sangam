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
 *
 * <p>Owner decisions 320 and 321: an endpoint that only reads uses {@link #read}, which calls the connector only for a
 * request the connector itself declares read-only and otherwise refuses with 422
 * {@code CONNECTOR_OPERATION_NOT_READ_ONLY}; {@link #call} is for the endpoints that change something.
 */
public final class ModuleConnectors {

    private ModuleConnectors() {
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ModuleConnectors.class);

    public static Map<String, Object> call(SangamConnector connector, RequestContext ctx, String tenantOrScope,
                                           String operation, Map<String, Object> input) {
        return call(connector, ctx, tenantOrScope, operation, input, false);
    }

    /** An API read: as {@link #call}, but never a request the connector does not declare read-only. */
    public static Map<String, Object> read(SangamConnector connector, RequestContext ctx, String tenantOrScope,
                                           String operation, Map<String, Object> input) {
        return call(connector, ctx, tenantOrScope, operation, input, true);
    }

    private static Map<String, Object> call(SangamConnector connector, RequestContext ctx, String tenantOrScope,
                                            String operation, Map<String, Object> input, boolean apiRead) {
        UUID op = ctx.idempotencyKey() == null ? Hashes.uuidV7()
                : Hashes.uuidV5(UUID.nameUUIDFromBytes("sangam-rest-module-connector".getBytes()),
                ctx.tenantId() + "|" + operation + "|" + ctx.idempotencyKey());
        Map<String, Object> in = new java.util.LinkedHashMap<>(input == null ? Map.of() : input);
        in.putIfAbsent("actor", ctx.principalId());
        ConnectorRequest request = new ConnectorRequest(tenantOrScope, op, null, 1, null, null, operation, in, null);
        if (apiRead && !connector.readOnly(request)) {
            LOG.warn("API read refused ({}): tenant {}, connector {}, operation {}", SangamConnector.OPERATION_NOT_READ_ONLY,
                    ctx.tenantId(), connector.descriptor().id(), operation);
            throw ApiProblem.unprocessable(SangamConnector.OPERATION_NOT_READ_ONLY, "connector "
                    + connector.descriptor().id() + " does not declare operation '" + operation + "' read-only");
        }
        ConnectorResult r = connector.invoke(request);
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
