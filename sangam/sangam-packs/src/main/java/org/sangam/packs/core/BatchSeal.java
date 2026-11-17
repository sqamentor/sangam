// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;

/**
 * Owner decision E1: one recorded human or delegated-power sanction of a batch parent binds the batch's item list
 * (count, total amount and an item fingerprint hash); a child payment is released only for an item of the approved
 * batch that is unchanged. Adding or changing an item afterwards needs a fresh sanction.
 *
 * <p>{@code SEAL} (BatchDelegate op, before the parent's sanction) computes the seal and makes it the parent's
 * material payload, so the sanction's approval hash binds it. {@code FAN_OUT} then refuses to start any child unless
 * that approval is intact and human, and hands each child the approved fingerprint of its own item; the child's
 * {@link BatchItemReleaseDelegate} binds the payment only when the item it carries still has that fingerprint.
 */
public final class BatchSeal {

    public static final String FINGERPRINTS = "batchItemFingerprints";
    public static final String COUNT = "batchItemCount";
    public static final String TOTAL = "batchTotalAmount";
    public static final String HASH = "batchItemsHash";
    public static final String PAYEE_FIELD = "batchPayeeField";
    /** The parent's material fields once sealed: the sanction binds exactly these. */
    public static final String SEALED_FIELDS = COUNT + "," + TOTAL + "," + HASH + ",currency,purpose";

    private BatchSeal() {
    }

    static ProcessEngineException held(String why) {
        return new ProcessEngineException(MoneyOutApproval.HELD + ": " + why);
    }

    /** Fingerprint of one item: its fields in the form a child process carries them. */
    static String fingerprint(Map<String, Object> item) {
        Map<String, Object> m = new TreeMap<>();
        item.forEach((k, v) -> m.put(k, Vars.canonical(Vars.engineValue(v))));
        return Vars.hash(new LinkedHashMap<>(m));
    }

    static void seal(DelegateExecution ex, String itemsVar) {
        String payeeField = Vars.str(ex, PAYEE_FIELD, "payeeAccount");
        for (String v : List.of("currency", "purpose")) {
            if (Vars.str(ex, v) == null || Vars.str(ex, v).isBlank()) {
                throw held("the batch has no " + v + " (E3)");
            }
        }
        Map<String, Object> fps = new TreeMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> it : Vars.jsonList(ex, itemsVar)) {
            Object key = it.get("key");
            Object amount = it.get("amount");
            Object payee = it.get(payeeField);
            if (key == null || amount == null || payee == null || payee.toString().isBlank()) {
                throw held("batch item " + key + " lacks key, amount or " + payeeField + " (E3)");
            }
            BigDecimal a = new BigDecimal(amount.toString());
            if (a.signum() <= 0) {
                throw held("batch item " + key + " has no positive amount");
            }
            total = total.add(a);
            if (fps.put(key.toString(), fingerprint(it)) != null) {
                throw held("batch item key " + key + " appears twice");
            }
        }
        ex.setVariable(COUNT, fps.size());
        ex.setVariable(TOTAL, total.toPlainString());
        ex.setVariable(HASH, Vars.hash(new LinkedHashMap<>(fps)));
        Vars.setJsonDocument(ex, FINGERPRINTS, fps);
        ex.setVariable(Vars.MATERIAL_FIELDS, SEALED_FIELDS);
        // any earlier approval of this case bound another payload; the batch needs its own sanction
        ex.setVariable(Vars.APPROVED_HASH, null);
        Vars.audit(ex, "BATCH_SEALED", Vars.map("items", fps.size(), "total", total.toPlainString(),
                "itemsHash", Vars.str(ex, HASH)));
    }

    /**
     * Null for a batch that was never sealed (no money moves); else the sealed fingerprints, after checking that the
     * parent's sanction is recorded, human and still binds the current seal.
     */
    static Map<String, String> approvedItems(DelegateExecution ex, List<Map<String, Object>> items) {
        if (ex.getVariable(FINGERPRINTS) == null) {
            return null;
        }
        String approved = Vars.str(ex, Vars.APPROVED_HASH);
        if (approved == null) {
            throw held("the batch has no recorded sanction");
        }
        if (MoneyOutApproval.DEEMED_PRINCIPAL.equals(Vars.str(ex, "approvedBy"))) {
            throw held("a deemed sanction never releases money out (E2); escalate to the competent authority");
        }
        if (!approved.equals(Vars.materialHash(ex))) {
            throw held("the batch seal changed after its sanction; a fresh sanction is needed");
        }
        Map<String, String> out = new LinkedHashMap<>();
        Vars.jsonMap(ex, FINGERPRINTS).forEach((k, v) -> out.put(k, String.valueOf(v)));
        return out;
    }

    /** Hands one child the approval of its item, or holds when the item was added or changed after the sanction. */
    static void passApproval(DelegateExecution ex, Map<String, Object> item, Map<String, String> approved,
                             Map<String, Object> vars) {
        String key = String.valueOf(item.get("key"));
        String fp = approved.get(key);
        if (fp == null) {
            throw held("batch item " + key + " was added after the sanction; a fresh sanction is needed");
        }
        if (!fp.equals(fingerprint(item))) {
            throw held("batch item " + key + " changed after the sanction; a fresh sanction is needed");
        }
        String payeeField = Vars.str(ex, PAYEE_FIELD, "payeeAccount");
        vars.put("payeeAccount", String.valueOf(item.get(payeeField)));
        vars.put("currency", Vars.str(ex, "currency"));
        vars.put("purpose", Vars.str(ex, "purpose"));
        vars.put("batchApprovedBy", Vars.str(ex, "approvedBy"));
        vars.put("batchApprovedHash", Vars.str(ex, Vars.APPROVED_HASH));
        vars.put("batchApprovedItemFingerprint", fp);
        vars.put("batchItemFields", String.join(",", new TreeSet<>(item.keySet())));
    }

    /** The current fingerprint of the item a child carries, from its variables. */
    static String childFingerprint(java.util.function.Function<String, Object> variable) {
        Object f = variable.apply("batchItemFields");
        Map<String, Object> m = new TreeMap<>();
        for (String k : new ArrayList<>(Vars.csv(f == null ? null : f.toString()))) {
            m.put(k, Vars.canonical(variable.apply(k)));
        }
        return Vars.hash(new LinkedHashMap<>(m));
    }
}
