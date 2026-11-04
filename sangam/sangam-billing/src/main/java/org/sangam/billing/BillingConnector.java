// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.sangam.config.common.Tree;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.config.profile.ResolvedProfile;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.ledger.AccountKey;
import org.sangam.ledger.Balance;
import org.sangam.ledger.LedgerService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-process connector for the billing pack ({@code universal.billing-cycle}): connector ids {@code billing.accounts},
 * {@code billing.invoice}, {@code billing.collect}, {@code billing.arrears}, {@code billing.penalty},
 * {@code billing.waive}. Tariffs come from the tenant profile ({@code tariffs:}); the account list of a cycle comes
 * from an {@link AccountSource} (the consumer / property / licence register of the deployment).
 *
 * <p>Common inputs: {@code ledgerId} (default {@code DEMAND}), {@code period}, {@code consumer}, {@code cycle},
 * {@code tariffId}, {@code measures} (map) or {@code units}, {@code dueDays} (default 15), {@code amount},
 * {@code channel}, {@code paymentRef}, {@code head}, {@code orderRef}. Postings use the operationId-derived references of
 * {@link BillingService}, so retries are idempotent.
 */
public final class BillingConnector implements SangamConnector {

    /** Accounts (consumers) billed in a cycle. */
    @FunctionalInterface
    public interface AccountSource {
        List<Map<String, Object>> accounts(String tenant, String cycle);
    }

    private final BillingService billing;
    private final LedgerService ledger;
    private final ProfileResolver profiles;
    private final AccountSource accounts;
    private final Clock clock;

    public BillingConnector(BillingService billing, LedgerService ledger, ProfileResolver profiles, AccountSource accounts,
                            Clock clock) {
        this.billing = billing;
        this.ledger = ledger;
        this.profiles = profiles;
        this.accounts = accounts;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return new ConnectorDescriptor("billing", "in-process", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                List.of("accounts", "invoice", "collect", "arrears", "penalty", "waive"), ConnectorProfile.LIVE);
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest r) {
        try {
            String op = r.operation().contains(".") ? r.operation().substring(r.operation().lastIndexOf('.') + 1) : r.operation();
            String tenant = r.tenantId();
            LocalDate today = LocalDate.now(clock);
            String ledgerId = Tree.str(r.input().get("ledgerId"), "DEMAND");
            String period = Tree.str(r.input().get("period"), today.toString().substring(0, 4));
            String consumer = r.str("consumer");
            Map<String, Object> out = new LinkedHashMap<>();
            switch (op) {
                case "accounts" -> {
                    List<Map<String, Object>> items = accounts.accounts(tenant, r.str("cycle"));
                    out.put("items", items);
                    out.put("count", items.size());
                }
                case "invoice" -> {
                    Map<String, Object> tariff = tariff(tenant, r.str("tariffId"), today);
                    Map<String, BigDecimal> measures = new LinkedHashMap<>();
                    Tree.map(r.input().get("measures")).forEach((k, v) -> measures.put(k, Tree.decimal(v, BigDecimal.ZERO)));
                    if (measures.isEmpty() && r.input().get("units") != null) {
                        measures.put("units", Tree.decimal(r.input().get("units"), BigDecimal.ZERO));
                    }
                    BillingService.Invoice inv = billing.invoice(tenant, ledgerId, period, consumer, r.str("cycle"), tariff,
                            measures, r.input(), today.plusDays(Tree.integer(r.input().get("dueDays"), 15)), r.str("actor"));
                    out.put("invoiceNo", inv.number());
                    out.put("total", inv.total().toPlainString());
                    out.put("dueDate", inv.dueDate().toString());
                }
                case "collect" -> {
                    BillingService.Receipt rc = billing.collect(tenant, ledgerId, consumer, r.str("channel"), r.str("paymentRef"),
                            new BigDecimal(r.str("amount")), r.str("actor"));
                    out.put("receiptNo", rc.number());
                    out.put("collected", rc.amount().toPlainString());
                    out.put("outstanding", outstanding(tenant, ledgerId, consumer).toPlainString());
                }
                case "arrears" -> {
                    BigDecimal due = BigDecimal.ZERO;
                    for (Balance b : ledger.arrears(tenant, ledgerId, today)) {
                        if (b.key().holder().equals(consumer)) {
                            due = due.add(b.available());
                        }
                    }
                    out.put("arrears", due.toPlainString());
                    out.put("inArrears", due.signum() > 0);
                    out.put("outstanding", outstanding(tenant, ledgerId, consumer).toPlainString());
                }
                case "penalty" -> {
                    List<LedgerService.Result> res = billing.levyPenalties(tenant, ledgerId, period, consumer,
                            tariff(tenant, r.str("tariffId"), today), today, r.str("actor"));
                    out.put("penalties", res.size());
                }
                case "waive" -> {
                    AccountKey k = new AccountKey(tenant, ledgerId, period, r.str("head"), consumer, "DEMAND", r.str("cycle"));
                    LedgerService.Result res = billing.waive(k, new BigDecimal(r.str("amount")), r.str("orderRef"), r.str("actor"));
                    out.put("applied", res.applied());
                }
                default -> {
                    return ConnectorResult.BusinessFailure.of("OPERATION_UNKNOWN", op);
                }
            }
            return ConnectorResult.Success.of(out, r.operationId().toString());
        } catch (RuntimeException e) {
            return ConnectorResult.BusinessFailure.of("BILLING_ERROR", e.getMessage());
        }
    }

    private BigDecimal outstanding(String tenant, String ledgerId, String consumer) {
        BigDecimal due = BigDecimal.ZERO;
        for (Balance b : ledger.arrears(tenant, ledgerId, LocalDate.MAX)) {
            if (b.key().holder().equals(consumer)) {
                due = due.add(b.available());
            }
        }
        return due;
    }

    private Map<String, Object> tariff(String tenant, String tariffId, LocalDate today) {
        ResolvedProfile p = profiles.resolve(tenant, today);
        return TariffEngine.tariff(p.list("tariffs"), tariffId, today)
                .orElseThrow(() -> new IllegalArgumentException("tariff '" + tariffId + "' not in the profile of " + tenant));
    }
}
