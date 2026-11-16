// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Jdbc;
import org.sangam.packs.port.DocumentSlotBindings;
import org.sangam.ports.support.EngineTx;
import org.sangam.rest.support.TxSupport;

/**
 * Owner decision 385 (2), layer 1, on the server: a slot's document is bound only when the document route
 * ({@code org.sangam.rest.support.DocumentSlots}, decisions 267 and 268) wrote it. The route's store writes the audit
 * event {@code DOCUMENT_UPLOADED} or {@code DOCUMENT_VERSION_ADDED} on the process instance's chain with
 * {@code document_id}, {@code slot} and {@code document_type} (only the route sets the slot metadata), in the transaction
 * that writes the slot. So the binding of a slot on an instance is the latest such event that names the slot. The DSC
 * task's own instance is asked first, then each calling instance upwards, as the listener passes them (a call copies
 * the slot with {@code in variables="all"}); the nearest instance with a binding of the slot decides. Anything else (no
 * event, an erased or unreadable payload, another document or type, another tenant) is not bound, fail closed. The audit
 * events themselves are tamper-evident (hash chain).
 *
 * <p>Owner decisions 387 (9) and 390, option (a): a gate that completes over a verified signature
 * {@linkplain #consume consumes} the binding: {@value #CONSUMED} is appended on the chain of the instance whose binding
 * it signed, naming the slot, the document, its type, the gate instance (the DSC task's process instance) and the task,
 * in the engine command of the completion. Reading a chain newest first, a consumption by the asking gate instance (the
 * first instance passed) is passed over, so the levels of one gate instance still find the binding; a consumption by
 * any other gate instance ends the binding, so a binding older than it is not bound and the slot needs a new upload or
 * version (a newer binding).
 */
public final class AuditDocumentSlotBindings implements DocumentSlotBindings {

    static final String UPLOADED = "DOCUMENT_UPLOADED";
    static final String VERSION_ADDED = "DOCUMENT_VERSION_ADDED";
    /** Decision 390: a gate instance signed the binding this event names. */
    public static final String CONSUMED = "DOCUMENT_SLOT_CONSUMED";
    static final List<String> EVENTS = List.of(UPLOADED, VERSION_ADDED, CONSUMED);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TxSupport tx;
    private final AuditChain chain;

    public AuditDocumentSlotBindings(TxSupport tx, AuditChain chain) {
        this.tx = java.util.Objects.requireNonNull(tx, "tx");
        this.chain = java.util.Objects.requireNonNull(chain, "chain");
    }

    /**
     * What the newest deciding event of a slot on one instance says: a binding ({@code readable}, the document and its
     * type), or nothing usable ({@code readable} false: an erased or unparsable payload, or a consumption by another
     * gate instance).
     */
    record Binding(boolean readable, String documentId, String documentType) {
        static final Binding UNUSABLE = new Binding(false, null, null);

        boolean matches(String documentId, String documentType) {
            return readable && documentId.equals(this.documentId) && documentType.equals(this.documentType);
        }
    }

    /** The instance of the chain whose events decided the slot, and what they say. */
    record Found(String instance, Binding binding) {
    }

    @Override
    public boolean isBound(String tenantId, List<String> processInstances, String slot, String documentType,
                           String documentId) {
        if (!valid(tenantId, processInstances, slot, documentType, documentId)) {
            return false;
        }
        return tx.withConnection(c -> nearest(c, tenantId, processInstances, slot))
                .map(found -> found.binding().matches(documentId, documentType)).orElse(false);
    }

    @Override
    public void consume(String tenantId, List<String> processInstances, String slot, String documentType,
                        String documentId, String taskId) {
        if (!valid(tenantId, processInstances, slot, documentType, documentId) || blank(taskId)) {
            throw new IllegalArgumentException("decision 390: a consumption names the tenant, the gate's instance chain,"
                    + " the slot, the type, the document and the task");
        }
        if (EngineTx.inCommand()) {
            // the completion's own command: the record commits or rolls back with it
            consume(EngineTx.connection(), tenantId, processInstances, slot, documentType, documentId, taskId);
        } else {
            tx.withConnection(c -> {
                consume(c, tenantId, processInstances, slot, documentType, documentId, taskId);
                return null;
            });
        }
    }

    private void consume(Connection c, String tenantId, List<String> processInstances, String slot,
                         String documentType, String documentId, String taskId) {
        Found found = nearest(c, tenantId, processInstances, slot)
                .filter(f -> f.binding().matches(documentId, documentType))
                .orElseThrow(() -> new IllegalStateException("decision 390: " + slot + " does not bind document "
                        + documentId + " for gate instance " + processInstances.get(0) + "; nothing to consume"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slot", slot);
        payload.put("document_id", documentId);
        payload.put("document_type", documentType);
        payload.put("gate_instance", processInstances.get(0));
        payload.put("task_id", taskId);
        chain.append(c, tenantId, found.instance(), CONSUMED, payload);
    }

    /** The nearest instance of the chain with an event that decides the slot for the asking gate instance. */
    private Optional<Found> nearest(Connection c, String tenantId, List<String> processInstances, String slot) {
        String gate = processInstances.get(0);
        for (String instance : processInstances) {
            if (blank(instance)) {
                return Optional.of(new Found(instance, Binding.UNUSABLE));
            }
            Optional<Binding> latest = latest(c, tenantId, instance, slot, gate);
            if (latest.isPresent()) {
                return Optional.of(new Found(instance, latest.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * What decides {@code slot} on the chain of {@code instance}, newest first: a consumption by {@code gate} is passed
     * over, a consumption by another gate instance makes the binding unusable, a route binding is the answer.
     */
    private Optional<Binding> latest(Connection c, String tenantId, String instance, String slot, String gate) {
        List<String[]> rows = Jdbc.query(c,
                "SELECT e.event_type, p.payload, p.erased_at FROM AUDIT_EVENT e LEFT JOIN AUDIT_PAYLOAD p"
                        + " ON p.event_id = e.event_id WHERE e.tenant_id = ? AND e.chain_id = ?"
                        + " AND e.event_type IN (?, ?, ?) ORDER BY e.seq DESC",
                rs -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, tenantId, instance,
                EVENTS.get(0), EVENTS.get(1), EVENTS.get(2));
        for (String[] row : rows) {
            if (row[1] == null || row[2] != null) {
                // an erased payload cannot show which slot it bound or consumed: if this one, the answer is unknown
                return Optional.of(Binding.UNUSABLE);
            }
            Map<?, ?> p;
            try {
                p = JSON.readValue(row[1], Map.class);
            } catch (Exception e) {
                return Optional.of(Binding.UNUSABLE);
            }
            if (!slot.equals(str(p.get("slot")))) {
                continue;
            }
            if (CONSUMED.equals(row[0])) {
                if (gate.equals(str(p.get("gate_instance")))) {
                    continue; // this gate instance's own levels: look further back for the binding it signed
                }
                return Optional.of(Binding.UNUSABLE);
            }
            return Optional.of(new Binding(true, str(p.get("document_id")), str(p.get("document_type"))));
        }
        return Optional.empty();
    }

    private static boolean valid(String tenantId, List<String> processInstances, String slot, String documentType,
                                 String documentId) {
        return !blank(tenantId) && processInstances != null && !processInstances.isEmpty() && !blank(slot)
                && !blank(documentType) && !blank(documentId);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
