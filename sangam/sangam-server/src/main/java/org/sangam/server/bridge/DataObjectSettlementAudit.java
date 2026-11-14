// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.sangam.core.layer.AuditChain;
import org.sangam.integration.packs.PackOperationStore;
import org.sangam.integration.packs.PackSettlementListener;

/**
 * Owner decision 289: the data-object side of a REST-backed record create's settlement. Registered on
 * {@code PackOperationStore.onSettled} for the kind {@value #KIND} (business key {@code data-object:<key>}), so
 * sangam-integration keeps no low-code knowledge. The store calls it exactly once per operation, in the settling
 * transaction on that connection, when the operation first reaches COMPLETED, FAILED or CANCELLED; UNKNOWN,
 * RETRY_REQUESTED and a re-dispatch (decision 290) are not settlements and write nothing.
 *
 * <p>It appends {@value #EVENT} on the operation's tenant, chain {@code data:<key>} (the chain of
 * {@code DATA_OBJECT_RECORD_CREATE_ACCEPTED}), payload as agreed with W4-H (board ACK 2026-11-13T07:06:14Z):
 * {@code dataObject}, {@code operationId}, {@code status} (COMPLETED or FAILED; CANCELLED is FAILED with its reason, as
 * the operation GET reports it), {@code recordId} ({@code output.record.id}, else null), {@code failureCode} (the
 * reason, else {@value #DOWNSTREAM_FAILED}; null when COMPLETED), {@code settledBy} and {@code actor} (= settledBy). An
 * exception rolls the settlement back with it.
 */
public final class DataObjectSettlementAudit implements PackSettlementListener {

    /** The operation kind: the business key prefix. */
    public static final String KIND = "data-object";
    /** The audit event (SANGAM.md X.4.0.3). */
    public static final String EVENT = "DATA_OBJECT_RECORD_SETTLED";
    /** The failure code of a FAILED settlement that names none. */
    public static final String DOWNSTREAM_FAILED = "DOWNSTREAM_FAILED";

    private final AuditChain audit;

    public DataObjectSettlementAudit(AuditChain audit) {
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @Override
    public void settled(Connection c, Settlement s) {
        String key = dataObjectKey(s.businessKey());
        audit.append(c, s.tenantId(), "data:" + key, EVENT, payload(key, s));
    }

    /** The data object's key from {@code data-object:<key>}; anything else is refused (it is not this kind). */
    static String dataObjectKey(String businessKey) {
        String prefix = KIND + ":";
        if (businessKey == null || !businessKey.startsWith(prefix) || businessKey.length() == prefix.length()) {
            throw new IllegalStateException("not a data-object operation: business key " + businessKey);
        }
        return businessKey.substring(prefix.length());
    }

    /** The agreed payload for one settlement. */
    static Map<String, Object> payload(String key, Settlement s) {
        boolean completed = s.status() == PackOperationStore.Status.COMPLETED;
        if (!completed && s.status() != PackOperationStore.Status.FAILED
                && s.status() != PackOperationStore.Status.CANCELLED) {
            throw new IllegalStateException("not a settlement: status " + s.status());
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("dataObject", key);
        p.put("operationId", s.operationId());
        p.put("status", completed ? "COMPLETED" : "FAILED");
        p.put("recordId", completed ? recordId(s.output()) : null);
        p.put("failureCode", completed ? null : failureCode(s));
        p.put("settledBy", s.settledBy());
        p.put("actor", s.settledBy());
        return p;
    }

    private static String recordId(Map<String, Object> output) {
        Object record = output == null ? null : output.get("record");
        Object id = record instanceof Map<?, ?> m ? m.get("id") : null;
        return id == null ? null : String.valueOf(id);
    }

    private static String failureCode(Settlement s) {
        if (s.reasonCode() != null && !s.reasonCode().isBlank()) {
            return s.reasonCode();
        }
        return s.status() == PackOperationStore.Status.CANCELLED ? "CANCELLED" : DOWNSTREAM_FAILED;
    }
}
