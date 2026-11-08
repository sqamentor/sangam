// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.support;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryPorts;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes one registry fact to the core {@link AuditChain} (hash chain per root, X.2.3.3; same transaction) and the
 * matching event to the {@link RegistryPorts.EventPort} (outbox). The audit payload always carries the actor,
 * on-behalf-of seat, purpose and operationId.
 */
public final class RegistryAudit {

    private final AuditChain chain;
    private final RegistryPorts ports;

    public RegistryAudit(Dialect dialect, RegistryPorts ports) {
        this.chain = new AuditChain(dialect);
        this.ports = ports;
    }

    /**
     * Appends to the root's chain and publishes the event.
     *
     * @param rootId chain root (an object, book or party id; at most 64 characters)
     * @return the audit event
     */
    public AuditChain.AuditEvent record(Connection c, RegistryContext ctx, String rootId, String eventType,
                                        Map<String, ?> facts) {
        Map<String, Object> payload = new LinkedHashMap<>(Json.canonicalMap(facts));
        payload.put("actor", ctx.actor());
        if (ctx.onBehalfOf() != null) {
            payload.put("onBehalfOf", ctx.onBehalfOf());
        }
        if (ctx.purpose() != null) {
            payload.put("purpose", ctx.purpose());
        }
        if (ctx.operationId() != null) {
            payload.put("operationId", ctx.operationId());
        }
        AuditChain.AuditEvent ev = chain.append(c, ctx.tenant(), rootId, eventType, payload);
        Map<String, Object> event = new LinkedHashMap<>(payload);
        event.put("auditEventId", ev.eventId());
        event.put("auditSeq", ev.seq());
        ports.events().publish(c, ctx, rootId, eventType, event);
        return ev;
    }

    public AuditChain.VerifyResult verify(Connection c, String tenant, String rootId) {
        return chain.verify(c, tenant, rootId);
    }

    public AuditChain chain() {
        return chain;
    }
}
