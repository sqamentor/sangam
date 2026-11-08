// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.LinkedHashMap;
import java.util.Map;

import org.sangam.core.layer.AuditChain;
import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackAuditSink;
import org.sangam.ports.support.EngineTx;
import org.sangam.rest.support.TxSupport;

/**
 * {@link PackAuditSink} on the core class-B audit chain (R42, X.4.0.3). Pack listeners and delegates run inside an
 * engine command, so the event is appended on that command's connection and commits or rolls back with the engine
 * change (L5 insert, VI.1). Outside a command a short transaction is used. The chain is the business key, else the
 * process instance id, else the tenant security chain.
 */
public final class PacksAuditSink implements PackAuditSink {

    private final AuditChain chain;
    private final TxSupport tx;
    private final String defaultTenant;

    public PacksAuditSink(AuditChain chain, TxSupport tx, String defaultTenant) {
        this.chain = chain;
        this.tx = tx;
        this.defaultTenant = defaultTenant;
    }

    @Override
    public void record(PackAuditEvent e) {
        String tenant = e.tenantId() == null || e.tenantId().isBlank() ? defaultTenant : e.tenantId();
        String chainId = e.businessKey() != null ? e.businessKey()
                : e.processInstanceId() != null ? e.processInstanceId() : "sec:" + tenant;
        Map<String, Object> payload = new LinkedHashMap<>(e.details());
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("principal_id", e.actorPersonId());
        actor.put("seat_id", e.actorSeatId());
        actor.put("acted_for_seat_id", e.actedForSeatId());
        actor.values().removeIf(java.util.Objects::isNull);
        payload.put("actor", actor);
        put(payload, "authority_basis", e.authorityBasis());
        put(payload, "package_version", e.packageVersion());
        put(payload, "material_payload_hash", e.payloadHash());
        put(payload, "operation_id", e.operationId());
        put(payload, "process_instance_id", e.processInstanceId());
        put(payload, "business_key", e.businessKey());
        if (e.at() != null) {
            put(payload, "reported_at", e.at().toString());
        }
        Map<String, Object> safe = jsonSafe(payload);
        if (EngineTx.inCommand()) {
            chain.append(EngineTx.connection(), tenant, chainId, e.type(), safe);
        } else {
            tx.withConnection(c -> chain.append(c, tenant, chainId, e.type(), safe));
        }
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    /** Core canonical JSON accepts only integral numbers: decimals become strings (R33), dates ISO strings. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonSafe(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k, safe(v)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object safe(Object v) {
        if (v instanceof Map<?, ?> m) {
            return jsonSafe((Map<String, Object>) m);
        }
        if (v instanceof java.util.Collection<?> c) {
            return c.stream().map(PacksAuditSink::safe).toList();
        }
        if (v instanceof java.math.BigDecimal d) {
            return d.toPlainString();
        }
        if (v instanceof Double || v instanceof Float) {
            return new java.math.BigDecimal(v.toString()).toPlainString();
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant().toString();
        }
        if (v instanceof java.time.temporal.TemporalAccessor t) {
            return t.toString();
        }
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Integer || v instanceof Long
                || v instanceof Short || v instanceof Byte || v instanceof java.math.BigInteger) {
            return v;
        }
        return String.valueOf(v);
    }
}
