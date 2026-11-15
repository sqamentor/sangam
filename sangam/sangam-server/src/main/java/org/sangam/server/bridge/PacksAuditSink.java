// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.LinkedHashMap;
import java.util.Map;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.TenantKey;
import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackAuditSink;
import org.sangam.ports.support.EngineTx;
import org.sangam.rest.support.TxSupport;

/**
 * {@link PackAuditSink} on the core class-B audit chain (R42, X.4.0.3). Pack listeners and delegates run inside an
 * engine command, so the event is appended on that command's connection and commits or rolls back with the engine
 * change (L5 insert, VI.1). Outside a command a short transaction is used. The chain is the business key, else the
 * process instance id, else the tenant security chain.
 *
 * <p>RM-49: an event must name its tenant. One without a tenant (null, blank, or the reserved shared key) is refused
 * with an {@link IllegalArgumentException} before anything is appended; it is never charged to a default tenant or
 * chained under an empty tenant. The refusal propagates to the pack delegate or listener, so the engine command rolls
 * back with it. Server pack deployments are always tenant-scoped ({@code PacksTenantDeployer}), so a missing tenant
 * means a broken producer, for example an untenanted definition calling pack delegates.
 */
public final class PacksAuditSink implements PackAuditSink {

    private final AuditChain chain;
    private final TxSupport tx;

    public PacksAuditSink(AuditChain chain, TxSupport tx) {
        this.chain = chain;
        this.tx = tx;
    }

    /** The event's tenant, or an {@link IllegalArgumentException} when it has none (RM-49). */
    static String requireTenant(PackAuditEvent e) {
        String t = e.tenantId();
        if (t == null || t.isBlank() || TenantKey.SHARED.equals(t)) {
            throw new IllegalArgumentException("pack audit event " + e.type() + " refused: it names no tenant"
                    + " (business key " + e.businessKey() + ", process instance " + e.processInstanceId()
                    + "); audit is never charged to a default or empty tenant");
        }
        return t;
    }

    @Override
    public void record(PackAuditEvent e) {
        append(null, e);
    }

    /**
     * Appends on the caller's connection {@code c}, so the event commits or rolls back with the caller's transaction
     * (a connector settlement of the integration relay: decision 313). No engine command is looked for: outside one
     * the engine's context lookup throws, which would roll the settlement back.
     */
    @Override
    public void record(java.sql.Connection c, PackAuditEvent e) {
        append(java.util.Objects.requireNonNull(c, "connection"), e);
    }

    private void append(java.sql.Connection given, PackAuditEvent e) {
        String tenant = requireTenant(e);
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
        if (given != null) {
            chain.append(given, tenant, chainId, e.type(), safe);
        } else if (EngineTx.inCommand()) {
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
