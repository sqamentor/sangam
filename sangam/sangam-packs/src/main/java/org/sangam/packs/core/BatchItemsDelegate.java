// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.packs.port.VerifiedPayeeLookup;
import org.sangam.packs.port.VerifiedPayees;

/**
 * Owner decisions E1, E3 and M2 for a batch whose items are paid to registry parties: builds the batch item list a
 * {@code SEAL} binds, one item per record of a frozen register, before the batch sanction.
 * <ul>
 *   <li>{@code source}: the variable holding the register (a JSON array of records, {@link Vars#jsonList}).</li>
 *   <li>{@code keyField}: the record field that identifies an item (for example {@code employeeId}); missing or
 *       repeated: held.</li>
 *   <li>{@code amount}: a formula over the record's own fields ({@link DecimalFormula}, exact, no {@code round(...)}),
 *       for example {@code grossWages - pfEmployee - esiEmployee - otherDeductions}. A field the formula names that
 *       the record lacks, a result that is not whole minor units, or a result that is not positive: held. Never
 *       rounded, never defaulted (rule C4).</li>
 *   <li>{@code payeeKey}: the record field the verified-payee registry ({@link VerifiedPayees}) resolves;
 *       {@code employeeId} is looked up as an employee, any other field as a party. The item's {@code payeeAccount}
 *       is the registry's verified account, never a typed account (M2); not verified: held.</li>
 *   <li>{@code captureField} (instead of {@code payeeKey}): refund to source (owner decision M5). The item's
 *       {@code payeeAccount} is {@code capture:<ref>} of the record's capture reference. With {@code recordCapture=true}
 *       (at the step where the money was captured, for example bids received with their EMD) each record's capture is
 *       posted to the capture-record ledger ({@link CaptureRefunds#LEDGER}, idempotent per reference), so a later
 *       refund ({@link CaptureRefunds.Release}) is released only up to that capture, once, to that source, in that
 *       currency; a record without a capture reference: held.</li>
 *   <li>{@code excludeKeyVar} (optional): the variable holding one item key left out (for example the awarded bidder,
 *       whose EMD is not refunded with the unsuccessful bidders); named but empty: held.</li>
 *   <li>{@code carry} (optional): record fields copied onto the item as they are (for example the period).</li>
 *   <li>{@code itemsVar}: where the items go ({@code key}, {@code amount}, {@code payeeAccount},
 *       {@code payeeVersionId}, the key field and the carried fields).</li>
 * </ul>
 * An empty or missing register is held: no batch, nothing to sanction. A hold is a {@link ProcessEngineException}
 * {@code MONEY_OUT_HELD: <reason>} (audit {@code BATCH_ITEMS_HELD}), so nothing reaches the sanction.
 */
public class BatchItemsDelegate implements JavaDelegate {

    private Expression source;
    private Expression keyField;
    private Expression amount;
    private Expression payeeKey;
    private Expression carry;
    private Expression itemsVar;
    private Expression captureField;
    private Expression recordCapture;
    private Expression excludeKeyVar;

    @Override
    public void execute(DelegateExecution ex) {
        String src = need(ex, source, "source");
        String keyName = need(ex, keyField, "keyField");
        String out = need(ex, itemsVar, "itemsVar");
        String payeeName = Vars.field(payeeKey, ex);
        String captureName = Vars.field(captureField, ex);
        if ((payeeName == null || payeeName.isBlank()) == (captureName == null || captureName.isBlank())) {
            throw new IllegalStateException("BatchItemsDelegate needs exactly one of 'payeeKey', 'captureField'");
        }
        boolean record = Vars.fieldBool(recordCapture, ex, false);
        String exclude = null;
        String excludeVar = Vars.field(excludeKeyVar, ex);
        if (excludeVar != null && !excludeVar.isBlank()) {
            exclude = Vars.str(ex, excludeVar.trim());
            if (exclude == null || exclude.isBlank()) {
                throw hold(ex, "the key to leave out ('" + excludeVar.trim() + "') is not on the case");
            }
            exclude = exclude.trim();
        }
        String ccy = captureName != null && !captureName.isBlank() ? MoneyFieldsDelegate.currencyOf(ex) : null;
        DecimalFormula f = DecimalFormula.parse(need(ex, amount, "amount"));
        if (f.roundsComponents()) {
            throw new IllegalStateException("BatchItemsDelegate: the amount formula never rounds (rule C4)");
        }
        List<String> carried = Vars.csv(Vars.field(carry, ex));
        List<Map<String, Object>> register;
        try {
            register = ex.getVariable(src) == null ? null : Vars.jsonList(ex, src);
        } catch (RuntimeException e) {
            throw hold(ex, "the register '" + src + "' is not a list of records");
        }
        if (register == null || register.isEmpty()) {
            throw hold(ex, "the register '" + src + "' is absent or empty; no batch to sanction");
        }
        String tenant = Vars.tenant(ex);
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> rec : register) {
            Object k = rec.get(keyName);
            if (k == null || k.toString().isBlank()) {
                throw hold(ex, "a record of '" + src + "' has no " + keyName);
            }
            String key = k.toString().trim();
            if (!seen.add(key)) {
                throw hold(ex, keyName + " " + key + " appears twice in '" + src + "'");
            }
            for (String v : f.variables()) {
                if (rec.get(v) == null) {
                    throw hold(ex, "record " + key + " has no " + v + " for the amount " + f.text());
                }
            }
            BigDecimal a;
            try {
                a = f.evaluate(name -> Vars.decimal(rec.get(name)), x -> x);
            } catch (RuntimeException e) {
                throw hold(ex, "record " + key + ": the amount " + f.text() + " cannot be computed: " + e.getMessage());
            }
            if (a.signum() != 0 && a.stripTrailingZeros().scale() > 0) {
                throw hold(ex, "record " + key + ": the amount " + a.toPlainString() + " is not whole minor units");
            }
            if (a.signum() <= 0) {
                throw hold(ex, "record " + key + ": the amount " + a.toPlainString() + " is not positive");
            }
            if (key.equals(exclude)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            if (captureName != null && !captureName.isBlank()) {
                Object ref = rec.get(captureName);
                if (ref == null || ref.toString().isBlank()) {
                    throw hold(ex, "record " + key + " has no capture reference " + captureName + " (M5)");
                }
                item.put("payeeAccount", "capture:" + ref);
                item.put(captureName, ref.toString());
                if (record) {
                    org.sangam.packs.port.LedgerStore.Posting posted = org.sangam.packs.port.PackRuntime.ledgers().post(
                            tenant, CaptureRefunds.LEDGER, ref.toString(), ccy,
                            org.sangam.packs.port.LedgerStore.amount(a.setScale(0).longValueExact(), ccy),
                            CaptureRefunds.CAPTURE_REF, false);
                    if (!posted.applied() && !"DUPLICATE_REFERENCE".equals(posted.reason())) {
                        throw hold(ex, "capture record of " + ref + " refused: " + posted.reason());
                    }
                }
                addItem(items, item, key, a, keyName, carried, rec);
                total = total.add(a);
                continue;
            }
            Object pk = rec.get(payeeName);
            if (pk == null || pk.toString().isBlank()) {
                throw hold(ex, "record " + key + " has no payee key " + payeeName + " (M2)");
            }
            String employeeId = "employeeId".equals(payeeName) ? pk.toString() : null;
            String partyId = employeeId == null ? pk.toString() : null;
            VerifiedPayeeLookup.Result r = VerifiedPayees.resolve(tenant, employeeId, partyId, Vars.today(tenant));
            if (r == null) {
                throw new IllegalStateException("PAYEE_LOOKUP_NO_ANSWER: the verified-payee adapter returned null");
            }
            switch (r) {
                case VerifiedPayeeLookup.Verified v -> {
                    item.put("payeeAccount", v.accountNo() + (v.ifsc().isBlank() ? "" : "@" + v.ifsc()));
                    item.put("payeeVersionId", v.payeeVersionId());
                }
                case VerifiedPayeeLookup.NotVerified n -> throw hold(ex, "record " + key + ": payee " + pk
                        + " not verified: " + n.reason() + (n.detail() == null ? "" : " (" + n.detail() + ")"));
            }
            addItem(items, item, key, a, keyName, carried, rec);
            total = total.add(a);
        }
        Vars.setJsonDocument(ex, out, items);
        Vars.audit(ex, "BATCH_ITEMS_BUILT", Vars.map("source", src, "itemsVar", out, "items", items.size(),
                "total", total.toPlainString(), "amount", f.text(), "payeeKey", payeeName, "captureField", captureName,
                "capturesRecorded", record, "excluded", exclude));
    }

    private static void addItem(List<Map<String, Object>> items, Map<String, Object> item, String key, BigDecimal a,
                                String keyName, List<String> carried, Map<String, Object> rec) {
        item.put("key", key);
        item.put("amount", a.setScale(0).longValueExact());
        item.put(keyName, key);
        for (String c : carried) {
            item.put(c, rec.get(c));
        }
        items.add(item);
    }

    private static String need(DelegateExecution ex, Expression e, String name) {
        String v = Vars.field(e, ex);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("BatchItemsDelegate needs field '" + name + "'");
        }
        return v.trim();
    }

    private static ProcessEngineException hold(DelegateExecution ex, String why) {
        Vars.audit(ex, "BATCH_ITEMS_HELD", Vars.map("activity", ex.getCurrentActivityId(), "reason", why));
        return BatchSeal.held(why);
    }
}
