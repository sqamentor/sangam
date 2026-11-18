// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.packs.port.VerifiedPayeeLookup;
import org.sangam.packs.port.VerifiedPayees;

/**
 * The verified payee of a payment ([CONTRACT] SANGAM-PAYEE-VERIFIED v2, JOINT-LEDGER-SANGAM-IFMS; E-31 §6, SD-107).
 * Runs right before the payment's core execution and asks {@link VerifiedPayees} for the payee version in force on the
 * payment date: keyed on {@code employeeId} when the case carries one (GPF, advances), else on the party id
 * ({@code partyId}, else the party registry answer {@code payeePartyId}). The date is {@code paymentDate} when set,
 * else the tenant's business date.
 * <ul>
 *   <li>Verified: records {@code payeeVersionId} on the case (audit {@code PAYEE_VERSION_RECORDED}) and sets the
 *       payment payload: {@code billId} (the business key), {@code paymentAmount} (major units, from the minor-unit
 *       {@code amount}), {@code beneficiaryName}, {@code accountNo}, {@code payeeIfsc}; {@code executionPayloadFields}
 *       names them under the connector's parameter names. Bank details come only from the port, never from the form.</li>
 *   <li>NotVerified: {@code payeeVerified=false} and {@code payeeNotVerifiedReason} (the reason code); the pack stops
 *       the case at a person. Nothing is paid.</li>
 * </ul>
 * An exception from the adapter (or a null answer) is not a verification: it propagates, the job fails and the case
 * holds as an incident (fail closed).
 * Fields: {@code currency} (ISO 4217, the pack's currency).
 */
public class PayeeResolutionDelegate implements JavaDelegate {

    /** The payment payload for the rail connector (variable{@code >}parameter, {@link Vars#payloadOf}). */
    public static final String PAYLOAD_FIELDS =
            "billId,paymentAmount>amount,beneficiaryName,accountNo,payeeIfsc>ifsc,payeeVersionId";

    private Expression currency;

    @Override
    public void execute(DelegateExecution ex) {
        String employeeId = blankToNull(Vars.str(ex, "employeeId"));
        String payeeId = null;
        if (employeeId == null) {
            payeeId = blankToNull(Vars.str(ex, "partyId"));
            if (payeeId == null) {
                payeeId = blankToNull(Vars.str(ex, "payeePartyId"));
            }
        }
        String tenant = Vars.tenant(ex);
        Object pd = ex.getVariable("paymentDate");
        LocalDate asOf = pd == null || pd.toString().isBlank() ? Vars.today(tenant)
                : LocalDate.parse(pd.toString().length() > 10 ? pd.toString().substring(0, 10) : pd.toString());
        VerifiedPayeeLookup.Result r;
        if (employeeId == null && payeeId == null) {
            r = new VerifiedPayeeLookup.NotVerified(VerifiedPayeeLookup.Reason.ONBOARDING_INCOMPLETE,
                    "the case names no employeeId and no payee party");
        } else {
            r = VerifiedPayees.resolve(tenant, employeeId, payeeId, asOf);
        }
        if (r == null) {
            throw new IllegalStateException("PAYEE_LOOKUP_NO_ANSWER: the verified-payee adapter returned null");
        }
        switch (r) {
            case VerifiedPayeeLookup.Verified v -> {
                ex.setVariable("payeeVerified", true);
                ex.setVariable("payeeNotVerifiedReason", null);
                ex.setVariable("payeeVersionId", v.payeeVersionId());
                ex.setVariable("billId", ex.getProcessBusinessKey());
                ex.setVariable("beneficiaryName", v.name());
                ex.setVariable("accountNo", v.accountNo());
                ex.setVariable("payeeIfsc", v.ifsc());
                ex.setVariable("paymentAmount", major(ex.getVariable("amount"),
                        requiredCurrency(ex)).toPlainString());
                ex.setVariable("executionPayloadFields", PAYLOAD_FIELDS);
                bindPaidAccount(ex, MoneyFieldsDelegate.accountOf(v.accountNo(), v.ifsc()), v.payeeVersionId());
                Vars.audit(ex, "PAYEE_VERSION_RECORDED", Vars.map("payeeVersionId", v.payeeVersionId(),
                        "employeeId", employeeId, "payeeId", payeeId, "asOf", asOf.toString()));
            }
            case VerifiedPayeeLookup.NotVerified n -> {
                ex.setVariable("payeeVerified", false);
                ex.setVariable("payeeNotVerifiedReason", n.reason().name());
                ex.setVariable("payeeVersionId", null);
                Vars.audit(ex, "PAYEE_NOT_VERIFIED", Vars.map("reason", n.reason().name(), "detail", n.detail(),
                        "employeeId", employeeId, "payeeId", payeeId, "asOf", asOf.toString()));
            }
        }
    }

    /**
     * Integrator ruling R1 (owner rules E1-E3): the account paid is the one resolved on the payment date, and the
     * approval bound {@code payeeAccount}. The resolved account is written to {@code payeeAccount}, so when the registry
     * changed the account after the approval the material hash no longer matches: core execution refuses with
     * APPROVAL_INVALIDATED (nothing is paid) and the pack routes back for a fresh approval. A refund to source
     * ({@code capture:<ref>}) is not a registry account and is left as approved.
     */
    private static void bindPaidAccount(DelegateExecution ex, String resolved, String version) {
        String approved = blankToNull(Vars.str(ex, "payeeAccount"));
        if (approved == null || approved.startsWith("capture:") || approved.equals(resolved)) {
            return;
        }
        ex.setVariable("payeeAccount", resolved);
        Vars.audit(ex, "PAYEE_ACCOUNT_CHANGED_AFTER_APPROVAL", Vars.map("approvedAccount", approved,
                "resolvedAccount", resolved, "payeeVersionId", version));
    }

    private String requiredCurrency(DelegateExecution ex) {
        String c = Vars.field(currency, ex);
        if (c == null || c.isBlank()) {
            throw new IllegalStateException("PayeeResolutionDelegate needs field 'currency' (ISO 4217, the pack's "
                    + "currency)");
        }
        return c;
    }

    private static BigDecimal major(Object amount, String currency) {
        if (amount == null) {
            throw new IllegalStateException("PAYMENT_AMOUNT_MISSING: the case has no amount");
        }
        return LedgerDelegate.minorToMajor(Vars.decimal(amount), currency);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
