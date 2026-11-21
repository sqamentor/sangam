// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.sangam.packs.spi.ConnectorMoneyMovements;
import org.sangam.packs.spi.MoneyMovement;

/**
 * Owner decisions A1, E2, E3: the one approval rule every path applies before money leaves. A connector declared
 * {@link MoneyMovement#OUT}, or not declared at all, needs
 * <ul>
 *   <li>an approval hash bound by a person or a delegated power ({@code ApprovalBindingListener}, or a batch sanction
 *       released per item by {@code BatchItemReleaseDelegate}); a deemed approval ({@code approvedBy=system}) never
 *       releases money (E2);</li>
 *   <li>the material fields {@link #MONEY_FIELDS} (amount, payee or beneficiary account, currency, purpose) bound in
 *       that hash and not empty (E3);</li>
 *   <li>an unchanged material payload since the approval.</li>
 * </ul>
 * No variable switches this off.
 */
public final class MoneyOutApproval {

    /** BPMN error code ExternalOperationDelegate throws (core execution catches it). */
    public static final String ERROR = "APPROVAL_INVALIDATED";

    /** Prefix of the message when a submit outside core execution is held (an incident, never a silent end). */
    public static final String HELD = "MONEY_OUT_HELD";

    /** E3: the canonical case variables every money-out approval and payment must bind. */
    public static final List<String> MONEY_FIELDS = List.of("amount", "payeeAccount", "currency", "purpose");

    /** The principal a deemed approval is recorded under (CaseRecordDelegate BIND_DEEMED). */
    public static final String DEEMED_PRINCIPAL = "system";

    private MoneyOutApproval() {
    }

    /** True when the connector moves money out (undeclared counts as out). */
    public static boolean moneyOut(String connector) {
        return ConnectorMoneyMovements.enforced(connector) == MoneyMovement.OUT;
    }

    /** E3: the money fields missing from the declared material fields or empty; empty list when complete. */
    public static List<String> missingMoneyFields(Function<String, Object> variable) {
        Object declared = variable.apply(Vars.MATERIAL_FIELDS);
        List<String> material = Vars.csv(declared == null ? null : declared.toString());
        List<String> missing = new ArrayList<>();
        for (String f : MONEY_FIELDS) {
            Object v = variable.apply(f);
            if (!material.contains(f) || v == null || v.toString().isBlank()) {
                missing.add(f);
            }
        }
        return missing;
    }

    /** Null when the submit may go ahead, else the reason it is held. */
    public static String refusal(String connector, Function<String, Object> variable) {
        if (!moneyOut(connector)) {
            return null;
        }
        Object approved = variable.apply(Vars.APPROVED_HASH);
        if (approved == null) {
            return "no approval recorded for money out on " + connector;
        }
        if (DEEMED_PRINCIPAL.equals(String.valueOf(variable.apply("approvedBy")))) {
            return "a deemed approval never releases money out (" + connector + "); escalate to the competent authority";
        }
        List<String> missing = missingMoneyFields(variable);
        if (!missing.isEmpty()) {
            return "money out on " + connector + " without the material fields " + missing;
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
