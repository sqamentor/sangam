// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.custody;

import org.sangam.registry.support.Maps;

import java.time.Instant;
import java.util.Map;

/** Request shapes of the custody service, readable from REST bodies and process variables. */
public final class CustodyRequests {

    private CustodyRequests() {
    }

    static Boolean optBool(Map<String, ?> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null || "".equals(v)) {
            return null;
        }
        return v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString().trim());
    }

    /** Registration of a tracked item. */
    public record Register(String typeKey, String label, String labelKind, String description, String caseRef,
                           String custodianRef, String locationRef, String sealNo, boolean container,
                           String containerId, Map<String, Object> attributes, String displayName,
                           String holderPartyId, String externalRef, String signatureRef, String witnessRef) {
        public static Register from(Map<String, Object> m) {
            return new Register(Maps.required(m, "typeKey"), Maps.required(m, "label"), Maps.str(m, "labelKind", "OTHER"),
                    Maps.str(m, "description"), Maps.str(m, "caseRef"), Maps.str(m, "custodianRef"),
                    Maps.str(m, "locationRef"), Maps.str(m, "sealNo"), Maps.bool(m, "container", false),
                    Maps.str(m, "containerId"), Maps.map(m, "attributes"), Maps.str(m, "displayName"),
                    Maps.str(m, "holderPartyId"), Maps.str(m, "externalRef"), Maps.str(m, "signatureRef"),
                    Maps.str(m, "witnessRef"));
        }
    }

    /** Start of a two-step transfer. */
    public record Transfer(String toCustodian, String toLocation, String reason, String fromSignatureRef) {
        public static Transfer from(Map<String, Object> m) {
            return new Transfer(Maps.required(m, "toCustodian"), Maps.str(m, "toLocation"), Maps.str(m, "reason"),
                    Maps.str(m, "fromSignatureRef"));
        }
    }

    /** Acceptance (receipt) of a pending transfer by the receiver. */
    public record Accept(String toSignatureRef, String conditionText, Boolean sealIntact, String observedSealNo,
                         String locationRef, String witnessRef) {
        public static Accept from(Map<String, Object> m) {
            return new Accept(Maps.str(m, "toSignatureRef"), Maps.str(m, "conditionText"), optBool(m, "sealIntact"),
                    Maps.str(m, "observedSealNo"), Maps.str(m, "locationRef"), Maps.str(m, "witnessRef"));
        }
    }

    /** Single-step handover signed by both parties. */
    public record Handover(String toCustodian, String toLocation, String reason, String fromSignatureRef,
                           String toSignatureRef, String conditionText, Boolean sealIntact, String observedSealNo,
                           String witnessRef, Instant occurredAt) {
        public static Handover from(Map<String, Object> m) {
            return new Handover(Maps.required(m, "toCustodian"), Maps.str(m, "toLocation"), Maps.str(m, "reason"),
                    Maps.str(m, "fromSignatureRef"), Maps.str(m, "toSignatureRef"), Maps.str(m, "conditionText"),
                    optBool(m, "sealIntact"), Maps.str(m, "observedSealNo"), Maps.str(m, "witnessRef"),
                    Maps.instant(m, "occurredAt"));
        }
    }

    /** Sealing or resealing. */
    public record Seal(String sealNo, String witnessRef, String reason) {
        public static Seal from(Map<String, Object> m) {
            return new Seal(Maps.required(m, "sealNo"), Maps.str(m, "witnessRef"), Maps.str(m, "reason"));
        }
    }

    /** A disposal order. */
    public record Disposal(String method, String authorityRef, Map<String, Object> details) {
        public static Disposal from(Map<String, Object> m) {
            return new Disposal(Maps.required(m, "method"), Maps.required(m, "authorityRef"), Maps.map(m, "details"));
        }
    }
}
