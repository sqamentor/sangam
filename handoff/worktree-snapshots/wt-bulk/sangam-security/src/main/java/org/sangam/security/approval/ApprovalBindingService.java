// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.approval;

import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.support.Sql;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Approval bound to the payload hash and task version (VI.4 rule 5, K4, CX-2, T16).
 *
 * <ul>
 *   <li>The <b>material payload</b> is the set of fields whose change must invalidate an approval (amount,
 *       beneficiary, account, case type, package version ...). It is normalised (decimals as plain strings without
 *       trailing zeros, so {@code 100.0} and {@code 100.00} hash alike; instants as ISO-8601 UTC) and hashed as
 *       {@code SHA-256(JCS(payload))} with {@link CanonicalJson} (RFC 8785 style).</li>
 *   <li>{@link #verifySubmittedHash}: the client sends the {@code payloadHash} it displayed with the complete command;
 *       a mismatch with the server-side payload rejects the command ({@code 409 APPROVAL_INVALIDATED}).</li>
 *   <li>{@link #recordApproval} stores the approval with the hash, task version, approver seat and rule/power
 *       reference and writes {@code APPROVAL_RECORDED}.</li>
 *   <li>{@link #onMaterialChange} invalidates every valid approval of the case whose hash differs, listing the changed
 *       fields ({@code APPROVAL_INVALIDATED}).</li>
 *   <li>{@link #requireValidApproval}: before execution (payment, sanction order), a valid approval with the current
 *       hash must exist.</li>
 * </ul>
 */
public final class ApprovalBindingService implements SecurityPorts.ApprovalBinding {

    public record Approval(String approvalId, String caseId, String taskId, long taskVersion, String payloadHash,
                           String approverPersonId, String approverSeatId, String status, Instant createdAt) {
    }

    private final SecurityDialect dialect;
    private final SecurityAuditService audit;

    public ApprovalBindingService(SecurityDialect dialect, SecurityAuditService audit) {
        this.dialect = dialect;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ hashing

    @Override
    public String payloadHash(Map<String, ?> materialPayload) {
        return Hashes.hex(Hashes.sha256(CanonicalJson.write(normalise(materialPayload))));
    }

    /** Hash bound to a task version as well: {@code H(JCS({payload, taskId, taskVersion}))}. */
    public String boundHash(Map<String, ?> materialPayload, String taskId, long taskVersion) {
        Map<String, Object> m = new TreeMap<>();
        m.put("payload", normalise(materialPayload));
        m.put("taskId", taskId);
        m.put("taskVersion", taskVersion);
        return Hashes.hex(Hashes.sha256(CanonicalJson.write(m)));
    }

    /** CX-2/K4: the hash the client displayed must equal the hash of the current server-side payload. */
    public void verifySubmittedHash(String submittedHash, Map<String, ?> currentMaterialPayload) {
        String current = payloadHash(currentMaterialPayload);
        if (submittedHash == null || !current.equalsIgnoreCase(submittedHash)) {
            throw new SangamSecurityException.ApprovalInvalidated(
                    "the case changed since you opened it; review the changes and decide again");
        }
    }

    // ------------------------------------------------------------------ approvals

    @Override
    public String recordApproval(Connection c, SecurityIdentity approver, String caseId, String taskId, long taskVersion,
                                 Map<String, ?> materialPayload, String approverSeatId, String ruleRef, BigDecimal powerLimit) {
        String t = approver.tenantId();
        String hash = payloadHash(materialPayload);
        String id = Sql.newId();
        String actedFor = approverSeatId != null && approver.actsForSeat(approverSeatId) ? approverSeatId : null;
        Jdbc.update(c, "INSERT INTO SEC_APPROVAL(tenant_id, approval_id, case_id, task_id, task_version, payload_hash, material,"
                        + " approver_person_id, approver_seat_id, acted_for_seat_id, rule_ref, power_limit, status, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'VALID',?)",
                t, id, caseId, taskId, taskVersion, hash, CanonicalJson.write(normalise(materialPayload)), approver.personId(),
                approverSeatId, actedFor, ruleRef, Sql.decimal(powerLimit), Sql.dbNow(c, dialect));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("approval_id", id);
        p.put("task_id", taskId);
        p.put("task_version", taskVersion);
        p.put("material_payload_hash", hash);
        p.put("rule_ref", ruleRef);
        p.put("power_limit", powerLimit);
        p.put("seat_id", approverSeatId);
        p.put("acted_for_seat_id", actedFor);
        p.put("outcome", "SUCCESS");
        audit.record(c, approver, SecurityEventType.APPROVAL_RECORDED, caseId, p);
        if (actedFor != null) {
            audit.record(c, approver, SecurityEventType.ACTED_FOR, caseId, Map.of("seat_id", actedFor,
                    "acted_for_seat_id", actedFor, "approval_id", id));
        }
        return id;
    }

    /**
     * The material payload of the case changed: invalidates valid approvals whose hash differs and returns their ids.
     * Call in the same transaction as the change (K4).
     */
    public List<String> onMaterialChange(Connection c, SecurityIdentity actor, String caseId, Map<String, ?> newMaterialPayload) {
        String t = actor.tenantId();
        String hash = payloadHash(newMaterialPayload);
        Map<String, Object> now = normalise(newMaterialPayload);
        List<String[]> valid = Jdbc.query(c, "SELECT approval_id, payload_hash, material FROM SEC_APPROVAL WHERE tenant_id=? AND case_id=?"
                + " AND status='VALID'", rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, t, caseId);
        List<String> invalidated = new ArrayList<>();
        Instant ts = Sql.dbNow(c, dialect);
        for (String[] a : valid) {
            if (a[1].equalsIgnoreCase(hash)) {
                continue;
            }
            Set<String> changed = changedFields(Sql.jsonMap(a[2]), now);
            Jdbc.update(c, "UPDATE SEC_APPROVAL SET status='INVALIDATED', invalidated_at=?, invalidated_reason=? WHERE tenant_id=? AND approval_id=?",
                    ts, "changed: " + String.join(",", changed), t, a[0]);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("approval_id", a[0]);
            p.put("changed_fields", List.copyOf(changed));
            p.put("old_payload_hash", a[1]);
            p.put("material_payload_hash", hash);
            audit.record(c, actor, SecurityEventType.APPROVAL_INVALIDATED, caseId, p);
            invalidated.add(a[0]);
        }
        return invalidated;
    }

    @Override
    public void requireValidApproval(Connection c, String tenantId, String caseId, Map<String, ?> currentMaterialPayload) {
        String hash = payloadHash(currentMaterialPayload);
        long n = Jdbc.one(c, "SELECT COUNT(*) FROM SEC_APPROVAL WHERE tenant_id=? AND case_id=? AND status='VALID' AND payload_hash=?",
                rs -> rs.getLong(1), tenantId, caseId, hash).orElse(0L);
        if (n == 0) {
            throw new SangamSecurityException.ApprovalInvalidated("no valid approval matches the current payload of case " + caseId);
        }
    }

    public List<Approval> approvals(Connection c, String tenant, String caseId) {
        return Jdbc.query(c, "SELECT approval_id, case_id, task_id, task_version, payload_hash, approver_person_id, approver_seat_id,"
                        + " status, created_at FROM SEC_APPROVAL WHERE tenant_id=? AND case_id=? ORDER BY created_at, approval_id",
                rs -> new Approval(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), Jdbc.instant(rs, "created_at")), tenant, caseId);
    }

    // ------------------------------------------------------------------ normalisation

    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalise(Map<String, ?> in) {
        Map<String, Object> out = new TreeMap<>();
        if (in == null) {
            return out;
        }
        in.forEach((k, v) -> out.put(String.valueOf(k), value(v)));
        return out;
    }

    private static Object value(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Integer || v instanceof Long
                || v instanceof Short || v instanceof Byte || v instanceof java.math.BigInteger) {
            return v;
        }
        if (v instanceof BigDecimal bd) {
            return bd.signum() == 0 ? "0" : bd.stripTrailingZeros().toPlainString();
        }
        if (v instanceof Double || v instanceof Float) {
            BigDecimal bd = new BigDecimal(v.toString());
            return bd.signum() == 0 ? "0" : bd.stripTrailingZeros().toPlainString();
        }
        if (v instanceof Instant i) {
            return i.toString();
        }
        if (v instanceof TemporalAccessor ta) {
            return ta.toString();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> o = new TreeMap<>();
            m.forEach((k, x) -> o.put(String.valueOf(k), value(x)));
            return o;
        }
        if (v instanceof Collection<?> coll) {
            List<Object> l = new ArrayList<>();
            coll.forEach(x -> l.add(value(x)));
            return l;
        }
        return v.toString();
    }

    private static Set<String> changedFields(Map<String, Object> before, Map<String, Object> after) {
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        Set<String> changed = new LinkedHashSet<>();
        for (String k : keys) {
            if (!Objects.equals(CanonicalJson.write(normaliseAny(before.get(k))), CanonicalJson.write(normaliseAny(after.get(k))))) {
                changed.add(k);
            }
        }
        return changed;
    }

    private static Object normaliseAny(Object v) {
        return value(v);
    }
}
