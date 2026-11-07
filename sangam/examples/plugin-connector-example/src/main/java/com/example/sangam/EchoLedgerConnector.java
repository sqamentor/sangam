// SPDX-License-Identifier: Apache-2.0
package com.example.sangam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.plugin.PluginAware;

/**
 * A connector to an imaginary system. It shows the rules: one effect per invoke, operationId as the idempotency key,
 * outcomes mapped to results, a lookup because it declares capability class (a).
 */
public final class EchoLedgerConnector implements SangamConnector, PluginAware {

    private final Map<String, ConnectorResult.Success> done = new ConcurrentHashMap<>();
    private String prefix = "echo";

    @Override
    public void init(PluginContext context) {
        prefix = context.property("reference-prefix", prefix);
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return new ConnectorDescriptor("example.echo-ledger", "example", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                List.of("post"), ConnectorProfile.SIMULATOR);
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest request) {
        if (!"post".equals(request.operation())) {
            return ConnectorResult.BusinessFailure.of("UNKNOWN_OPERATION", request.operation());
        }
        return done.computeIfAbsent(request.operationId().toString(), id -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("reference", prefix + "-" + id);
            return ConnectorResult.Success.of(out, prefix + "-" + id);
        });
    }

    @Override
    public LookupResult lookup(ConnectorRequest request) {
        ConnectorResult.Success s = done.get(request.operationId().toString());
        return s == null ? new LookupResult.NotFound() : new LookupResult.Found(true, s.output(), s.providerRef(), null);
    }
}
