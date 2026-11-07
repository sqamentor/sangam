// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.sangam.core.layer.Hashes;
import org.sangam.integration.connector.ConnectorInvoker;

/**
 * Exactly-once connector calls for the compliance ports (R36): every call is a {@link ConnectorInvoker} invocation
 * whose operation identity is derived from a stable key (root + activity), so a retry or a second node never sends a
 * message or an authority report twice.
 */
public final class ComplianceConnectors {

    public record Result(boolean ok, String reference, String code, String message, boolean retry) {
    }

    private final ConnectorInvoker invoker;
    private final Duration timeout;

    public ComplianceConnectors(ConnectorInvoker invoker, Duration timeout) {
        this.invoker = invoker;
        this.timeout = timeout == null ? Duration.ofMinutes(2) : timeout;
    }

    public Result call(String tenantId, String rootId, String activityKey, String connectorId, String operation,
                       Map<String, Object> input) {
        ConnectorInvoker.Invocation inv = new ConnectorInvoker.Invocation(tenantId, id64("cmp:", rootId),
                id64("cmp:", activityKey), 0, null, null, connectorId, operation, input, UUID.randomUUID().toString(), 0L,
                Instant.now().plus(timeout),
                org.sangam.core.layer.OperationLedger.operationId(tenantId, id64("cmp:", rootId), id64("cmp:", activityKey), 0));
        ConnectorInvoker.Outcome o = invoker.invoke(inv);
        return switch (o) {
            case ConnectorInvoker.Completed c -> new Result(true,
                    c.providerRef() != null ? c.providerRef() : c.operationId().toString(), null, null, false);
            case ConnectorInvoker.Failed f -> new Result(false, null, f.code(), f.message(), false);
            case ConnectorInvoker.Retry r -> new Result(false, null, "RETRY", r.message(), true);
            case ConnectorInvoker.Unknown u -> new Result(false, u.operationId().toString(), "OUTCOME_UNKNOWN",
                    "outcome unknown; reconciliation item " + u.reconciliationItemId(), false);
            case ConnectorInvoker.Stale s -> new Result(false, null, "STALE", s.message(), false);
        };
    }

    /** Keys stored in VARCHAR(64) ledger/outbox columns: long keys become a stable hash. */
    public static String id64(String prefix, String value) {
        String v = prefix + (value == null ? "" : value);
        return v.length() <= 64 ? v : prefix + Hashes.hex(Hashes.sha256(value)).substring(0, 48);
    }
}
