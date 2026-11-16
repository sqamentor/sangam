// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.core.layer.Jdbc;
import org.sangam.packs.port.DocumentSlotBindings;
import org.sangam.rest.support.TxSupport;

/**
 * Owner decision 385 (2), layer 1, on the server: a slot's document is bound only when the document route
 * ({@code org.sangam.rest.support.DocumentSlots}, decisions 267 and 268) wrote it. The route's store writes the audit
 * event {@code DOCUMENT_UPLOADED} or {@code DOCUMENT_VERSION_ADDED} on the process instance's chain with
 * {@code document_id}, {@code slot} and {@code document_type} (only the route sets the slot metadata), in the transaction
 * that writes the slot. So the binding of a slot on an instance is the latest such event that names the slot. The DSC
 * task's own instance is asked first, then each calling instance upwards, as the listener passes them (a call copies
 * the slot with {@code in variables="all"}); the nearest instance with a binding of the slot decides. Anything else (no event, an
 * erased or unreadable payload, another document or type, another tenant) is not bound, fail closed. Reads only; the
 * audit events themselves are tamper-evident (hash chain).
 */
public final class AuditDocumentSlotBindings implements DocumentSlotBindings {

    static final List<String> EVENTS = List.of("DOCUMENT_UPLOADED", "DOCUMENT_VERSION_ADDED");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TxSupport tx;

    public AuditDocumentSlotBindings(TxSupport tx) {
        this.tx = tx;
    }

    /** One binding read from an audit payload; {@code readable} false for an erased or unparsable payload. */
    record Binding(boolean readable, String documentId, String documentType) {
    }

    @Override
    public boolean isBound(String tenantId, List<String> processInstances, String slot, String documentType,
                           String documentId) {
        if (blank(tenantId) || processInstances == null || blank(slot) || blank(documentType) || blank(documentId)) {
            return false;
        }
        for (String instance : processInstances) {
            if (blank(instance)) {
                return false;
            }
            Optional<Binding> latest = latest(tenantId, instance, slot);
            if (latest.isPresent()) {
                Binding b = latest.get();
                return b.readable() && documentId.equals(b.documentId()) && documentType.equals(b.documentType());
            }
        }
        return false;
    }

    /** The latest route binding of {@code slot} on the audit chain of {@code instance}, if any. */
    Optional<Binding> latest(String tenantId, String instance, String slot) {
        List<String[]> rows = tx.withConnection(c -> Jdbc.query(c,
                "SELECT p.payload, p.erased_at FROM AUDIT_EVENT e LEFT JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id"
                        + " WHERE e.tenant_id = ? AND e.chain_id = ? AND e.event_type IN (?, ?) ORDER BY e.seq DESC",
                rs -> new String[] {rs.getString(1), rs.getString(2)}, tenantId, instance, EVENTS.get(0),
                EVENTS.get(1)));
        for (String[] row : rows) {
            if (row[0] == null || row[1] != null) {
                // an erased payload cannot show which slot it bound: if it bound this one, the answer is unknown
                return Optional.of(new Binding(false, null, null));
            }
            Map<?, ?> p;
            try {
                p = JSON.readValue(row[0], Map.class);
            } catch (Exception e) {
                return Optional.of(new Binding(false, null, null));
            }
            if (slot.equals(str(p.get("slot")))) {
                return Optional.of(new Binding(true, str(p.get("document_id")), str(p.get("document_type"))));
            }
        }
        return Optional.empty();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
