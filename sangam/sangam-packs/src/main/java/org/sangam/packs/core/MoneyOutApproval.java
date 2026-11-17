// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.sangam.packs.spi.ConnectorMoneyMovements;
import org.sangam.packs.spi.MoneyMovement;

/**
 * Owner decision A1: the one approval check every submit path applies before money leaves (ConnectorCallDelegate in
 * submit mode, the pack external-task workers; ExternalOperationDelegate PREPARE keeps its own audited form of the same
 * rule). A connector declared {@link MoneyMovement#OUT}, or not declared at all, needs the approval hash bound by
 * {@code ApprovalBindingListener} and an unchanged material payload. No variable switches this off.
 */
public final class MoneyOutApproval {

    /** BPMN error code, the same one ExternalOperationDelegate throws. */
    public static final String ERROR = "APPROVAL_INVALIDATED";

    private MoneyOutApproval() {
    }

    /** Null when the submit may go ahead, else the reason it is held. */
    public static String refusal(String connector, Function<String, Object> variable) {
        if (ConnectorMoneyMovements.enforced(connector) != MoneyMovement.OUT) {
            return null;
        }
        Object approved = variable.apply(Vars.APPROVED_HASH);
        if (approved == null) {
            return "no approval recorded for money out on " + connector;
        }
        Object fields = variable.apply(Vars.MATERIAL_FIELDS);
        Map<String, Object> material = new LinkedHashMap<>();
        for (String f : Vars.csv(fields == null ? null : fields.toString())) {
            material.put(f, Vars.canonical(variable.apply(f)));
        }
        if (!approved.toString().equals(Vars.hash(material))) {
            return "material payload changed after approval (" + connector + ")";
        }
        return null;
    }
}
