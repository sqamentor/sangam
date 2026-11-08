// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.math.BigDecimal;

import org.operaton.bpm.engine.delegate.BpmnError;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.packs.port.LedgerStore;
import org.sangam.packs.port.PackRuntime;

/**
 * Ledger carried across instances and years (GP-48) and commitment control (GP-11). Field {@code op}:
 * <ul>
 *   <li>{@code ENCUMBER}: debits {@code amount} from the account (budget head, leave type, roster point) with the case
 *       as reference; insufficient balance throws BPMN error {@code errorCode} (default
 *       {@code INSUFFICIENT_BALANCE}, J-P1-E11);</li>
 *   <li>{@code RELEASE}: reverses the case's posting (cancel, withdrawal, rejection: GP-11 release on cancel,
 *       GP-13 refund);</li>
 *   <li>{@code CREDIT}: credits {@code amount} (leave credit, refund receipt);</li>
 *   <li>{@code BALANCE}: reads the balance into {@code resultVar}.</li>
 * </ul>
 * Fields {@code ledger} (budget, leave, roster, sanction ...), {@code account} (expression), {@code amount}
 * (expression), {@code unit} (REQUIRED for ENCUMBER / CREDIT: an ISO 4217 currency for money, e.g. {@code INR}, stored
 * in minor units; or a quantity unit such as {@code DAYS} / {@code POINTS}; SD-398: never implicit), {@code amount}
 * (expression), {@code reference} (default: business key + ledger), {@code resultVar}.
 */
public class LedgerDelegate implements JavaDelegate {

    private Expression op;
    private Expression ledger;
    private Expression account;
    private Expression amount;
    private Expression reference;
    private Expression resultVar;
    private Expression errorCode;
    private Expression unit;

    @Override
    public void execute(DelegateExecution ex) {
        LedgerStore store = PackRuntime.ledgers();
        String l = Vars.field(ledger, ex, "budget");
        String acct = Vars.field(account, ex, "default");
        String ref = Vars.field(reference, ex, (ex.getProcessBusinessKey() == null ? ex.getProcessInstanceId()
                : ex.getProcessBusinessKey()) + ":" + l);
        String rv = Vars.field(resultVar, ex, l + "Balance");
        BigDecimal amt = Vars.decimal(Vars.field(amount, ex, "0"));
        LedgerStore.Posting p;
        String op0 = Vars.field(op, ex, "BALANCE");
        String u = Vars.field(unit, ex, null);
        if (("ENCUMBER".equals(op0) || "CREDIT".equals(op0)) && (u == null || u.isBlank())) {
            throw new IllegalStateException("LedgerDelegate " + op0 + " on ledger " + l + " needs field 'unit' (ISO 4217 "
                    + "currency or a quantity unit such as DAYS)");
        }
        switch (Vars.field(op, ex, "BALANCE")) {
            case "ENCUMBER" -> {
                p = store.post(Vars.tenant(ex), l, acct, u, amt.negate(), ref, false);
                if (!p.applied()) {
                    ex.setVariable(rv, Vars.engineValue(p.balanceAfter()));
                    Vars.audit(ex, "RULE_EVALUATED", Vars.map("ledger", l, "account", acct, "result",
                            p.reason(), "available", p.balanceAfter().toPlainString(), "required",
                            amt.toPlainString()));
                    throw new BpmnError(Vars.field(errorCode, ex, "INSUFFICIENT_BALANCE"),
                            l + " " + acct + ": available " + p.balanceAfter().toPlainString() + " < "
                                    + amt.toPlainString());
                }
                ex.setVariable(l + "EncumbranceRef", ref);
            }
            case "RELEASE" -> p = store.reverse(Vars.tenant(ex), l, acct, ref);
            case "CREDIT" -> p = store.post(Vars.tenant(ex), l, acct, u, amt, ref, true);
            default -> p = new LedgerStore.Posting(true, null, store.balance(Vars.tenant(ex), l, acct));
        }
        ex.setVariable(rv, Vars.engineValue(p.balanceAfter()));
        Vars.audit(ex, "LEDGER_POSTED", Vars.map("ledger", l, "account", acct, "op", Vars.field(op, ex, "BALANCE"),
                "amount", amt.toPlainString(), "balanceAfter", p.balanceAfter().toPlainString(),
                "note", p.reason()));
    }
}
