// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.repository.ProcessDefinition;

import org.sangam.packs.port.TenantCurrencies;
import org.sangam.packs.port.VerifiedPayeeLookup;
import org.sangam.packs.port.VerifiedPayees;

/**
 * Owner decisions E3, M1 and M2: binds the money fields of a money-out step ({@link MoneyOutApproval#MONEY_FIELDS}:
 * {@code amount}, {@code payeeAccount}, {@code currency}, {@code purpose}) on the case <em>before</em> its approval, so
 * the approval hash covers them. It runs as the start listener of the approval (a user task with
 * {@code ApprovalBindingListener} or the core sanction call), or as a service task right before it.
 * <ul>
 *   <li>{@code amount}: the variable holding the amount this step pays (integer minor units, default {@code amount});
 *       copied into {@code amount}. Missing, not a number, or not positive: held.</li>
 *   <li>Payee (exactly one):
 *     <ul>
 *       <li>{@code payeeKeys}: bank transfer (M2). The first non-blank of these case variables is the party key the
 *           verified-payee registry ({@link VerifiedPayees}) resolves; {@code employeeId} is looked up as an employee.
 *           {@code payeeAccount} is the registry's verified account, never a typed name or account. Not resolved
 *           or not verified: held.</li>
 *       <li>{@code captureVar}: refund to source (M2). {@code payeeAccount} is {@code capture:<ref>}, the reference of
 *           the original capture the refund returns money to. No capture: held (nothing to refund to).</li>
 *       <li>{@code statutoryParty}: a single statutory payee (M4: EPFO, ESIC, GSTN, RBI); the literal party key is
 *           resolved in the same registry and must be verified.</li>
 *     </ul></li>
 *   <li>{@code currency} (M1): the tenant / jurisdiction profile's currency ({@link TenantCurrencies}), else the
 *       {@code money.currency} the deployed pack declares; neither: held. Never a silent default.</li>
 *   <li>{@code purpose}: required literal.</li>
 * </ul>
 * A hold is a {@link ProcessEngineException} {@code MONEY_OUT_HELD: <reason>} (audit {@code MONEY_FIELDS_HELD}): the
 * case does not move to the approval and nothing is paid.
 */
public class MoneyFieldsDelegate implements JavaDelegate, ExecutionListener {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> PACK_CURRENCY = new ConcurrentHashMap<>();
    private static final String NONE = "";

    private Expression amount;
    private Expression payeeKeys;
    private Expression captureVar;
    private Expression statutoryParty;
    private Expression purpose;

    @Override
    public void notify(DelegateExecution ex) {
        execute(ex);
    }

    @Override
    public void execute(DelegateExecution ex) {
        String purposeText = Vars.field(purpose, ex);
        if (purposeText == null || purposeText.isBlank()) {
            throw new IllegalStateException("MoneyFieldsDelegate needs field 'purpose'");
        }
        String amountVar = Vars.field(amount, ex, "amount");
        String keys = Vars.field(payeeKeys, ex);
        String capture = Vars.field(captureVar, ex);
        String statutory = Vars.field(statutoryParty, ex);
        int modes = (blank(keys) ? 0 : 1) + (blank(capture) ? 0 : 1) + (blank(statutory) ? 0 : 1);
        if (modes != 1) {
            throw new IllegalStateException("MoneyFieldsDelegate needs exactly one of 'payeeKeys', 'captureVar', "
                    + "'statutoryParty'");
        }
        BigDecimal paid = paidAmount(ex, amountVar);
        String ccy = currency(ex);
        String account;
        String payeeVersion = null;
        if (!blank(capture)) {
            Object ref = ex.getVariable(capture);
            if (ref == null || ref.toString().isBlank()) {
                throw hold(ex, "refund to source without the original capture reference '" + capture + "'");
            }
            account = "capture:" + ref;
        } else {
            String employeeId = null;
            String partyId = null;
            String keyUsed = null;
            if (!blank(statutory)) {
                partyId = statutory.trim();
                keyUsed = "statutoryParty";
            } else {
                for (String k : Vars.csv(keys)) {
                    String v = Vars.str(ex, k);
                    if (!blank(v)) {
                        keyUsed = k;
                        if ("employeeId".equals(k)) {
                            employeeId = v;
                        } else {
                            partyId = v;
                        }
                        break;
                    }
                }
            }
            if (keyUsed == null) {
                throw hold(ex, "no payee party key on the case (one of " + keys + "); a name is never a payee "
                        + "account (owner decision M2)");
            }
            String tenant = Vars.tenant(ex);
            VerifiedPayeeLookup.Result r = VerifiedPayees.resolve(tenant, employeeId, partyId, Vars.today(tenant));
            if (r == null) {
                throw new IllegalStateException("PAYEE_LOOKUP_NO_ANSWER: the verified-payee adapter returned null");
            }
            switch (r) {
                case VerifiedPayeeLookup.Verified v -> {
                    account = v.accountNo() + (blank(v.ifsc()) ? "" : "@" + v.ifsc());
                    payeeVersion = v.payeeVersionId();
                }
                case VerifiedPayeeLookup.NotVerified n -> throw hold(ex, "payee " + keyUsed + " not verified: "
                        + n.reason() + (n.detail() == null ? "" : " (" + n.detail() + ")"));
            }
        }
        ex.setVariable("amount", paid.longValueExact());
        ex.setVariable("payeeAccount", account);
        ex.setVariable("currency", ccy);
        ex.setVariable("purpose", purposeText.trim());
        if (payeeVersion != null) {
            ex.setVariable("payeeVersionId", payeeVersion);
        }
        List<String> material = Vars.csv(Vars.str(ex, Vars.MATERIAL_FIELDS));
        for (String f : MoneyOutApproval.MONEY_FIELDS) {
            if (!material.contains(f)) {
                material.add(f);
            }
        }
        ex.setVariable(Vars.MATERIAL_FIELDS, Vars.joinCsv(material));
        Vars.audit(ex, "MONEY_FIELDS_BOUND", Vars.map("activity", ex.getCurrentActivityId(), "amount", paid,
                "currency", ccy, "payeeAccount", account, "purpose", purposeText.trim(), "payeeVersionId", payeeVersion));
    }

    private BigDecimal paidAmount(DelegateExecution ex, String var) {
        Object raw = ex.getVariable(var);
        BigDecimal v;
        try {
            v = raw == null ? null : Vars.decimal(raw);
        } catch (RuntimeException e) {
            throw hold(ex, "amount '" + var + "' is not a number: " + raw);
        }
        if (v == null || v.signum() <= 0) {
            throw hold(ex, "no positive amount in '" + var + "'");
        }
        if (v.stripTrailingZeros().scale() > 0) {
            throw hold(ex, "amount '" + var + "' = " + v.toPlainString() + " is not a whole number of minor units");
        }
        return v;
    }

    private String currency(DelegateExecution ex) {
        String c = TenantCurrencies.of(Vars.tenant(ex));
        if (c == null) {
            c = packCurrency(ex);
        }
        if (c == null) {
            throw hold(ex, "no currency: the tenant profile names none and the pack declares no money.currency "
                    + "(owner decision M1)");
        }
        try {
            return java.util.Currency.getInstance(c).getCurrencyCode();
        } catch (IllegalArgumentException e) {
            throw hold(ex, "currency '" + c + "' is not an ISO 4217 code");
        }
    }

    /** The {@code money.currency} of the pack manifest deployed with this process definition, or null. */
    static String packCurrency(DelegateExecution ex) {
        RepositoryService repo = ex.getProcessEngineServices().getRepositoryService();
        ProcessDefinition def = repo.getProcessDefinition(ex.getProcessDefinitionId());
        String dep = def.getDeploymentId();
        String c = PACK_CURRENCY.computeIfAbsent(dep, d -> {
            for (String n : repo.getDeploymentResourceNames(d)) {
                if (n.equals("pack.json") || n.endsWith("/pack.json")) {
                    try (InputStream in = repo.getResourceAsStream(d, n)) {
                        JsonNode m = JSON.readTree(in);
                        String v = m.path("money").path("currency").asText(null);
                        return v == null || v.isBlank() ? NONE : v;
                    } catch (IOException e) {
                        throw new UncheckedIOException("deployed pack manifest " + n + " is unreadable", e);
                    }
                }
            }
            return NONE;
        });
        return NONE.equals(c) ? null : c;
    }

    private static ProcessEngineException hold(DelegateExecution ex, String why) {
        Vars.audit(ex, "MONEY_FIELDS_HELD", Vars.map("activity", ex.getCurrentActivityId(), "reason", why));
        return BatchSeal.held(why);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
