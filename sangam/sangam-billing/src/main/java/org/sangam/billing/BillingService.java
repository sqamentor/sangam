// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.sangam.config.catalogue.NumberSeries;
import org.sangam.core.money.Money;
import org.sangam.core.time.BusinessClock;
import org.sangam.ledger.AccountKey;
import org.sangam.ledger.Balance;
import org.sangam.ledger.LedgerRequest;
import org.sangam.ledger.LedgerService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic billing on a RECEIVABLE ledger: demand raised per invoice line (one account per consumer × head × cycle),
 * collection through a payment connector port, receipts numbered by a series, refunds, waivers / write-offs,
 * penalties and interest. The per-cycle batch (one case per consumer) is a pack (GP-09) calling {@link #invoice}.
 */
public final class BillingService {

    /** Collection rail (payment gateway, bank, counter, aggregator). Implemented over ConnectorGateway. */
    public interface PaymentPort {
        /** Verifies a payment reported by a channel; returns the confirmed amount (or null if not confirmed). */
        Money confirm(String tenant, String channel, String paymentRef, Money amount);

        /** Initiates a refund; returns the rail reference. */
        String refund(String tenant, String channel, String paymentRef, Money amount, String operationId);
    }

    /** {@code total} is the document total rounded once; the line moneys sum to it exactly (SG-20). */
    public record Invoice(String number, String consumer, String cycle, LocalDate dueDate, List<TariffEngine.Line> lines,
                          Money total, List<LedgerService.Result> postings) {
    }

    public record Receipt(String number, String consumer, Money amount, Map<String, Money> allocation,
                          List<LedgerService.Result> postings) {
    }

    private final LedgerService ledger;
    private final TariffEngine tariffs;
    private final NumberSeries series;
    private final PaymentPort payments;

    private final BusinessClock businessClock;

    /** Business dates in UTC (tests / single-zone tools); production wiring passes the tenant business clock. */
    public BillingService(LedgerService ledger, TariffEngine tariffs, NumberSeries series, PaymentPort payments) {
        this(ledger, tariffs, series, payments, BusinessClock.utc(java.time.Clock.systemUTC()));
    }

    public BillingService(LedgerService ledger, TariffEngine tariffs, NumberSeries series, PaymentPort payments,
                          BusinessClock businessClock) {
        this.businessClock = java.util.Objects.requireNonNull(businessClock, "businessClock");
        this.ledger = ledger;
        this.tariffs = tariffs;
        this.series = series;
        this.payments = payments;
    }

    /** Raises the invoice of one consumer for one cycle; idempotent per (consumer, cycle). */
    public Invoice invoice(String tenant, String ledgerId, String period, String consumer, String cycle,
                           Map<String, Object> tariff, Map<String, BigDecimal> measures, Map<String, Object> attrs,
                           LocalDate dueDate, String actor) {
        List<TariffEngine.Line> lines = tariffs.compute(tenant, tariff, measures, attrs);
        String no = "INV/" + cycle + "/" + series.next(tenant, "INVOICE", "-", cycle);
        List<LedgerService.Result> posts = new ArrayList<>();
        Money total = Money.zero(TariffEngine.currency(tariff));
        for (TariffEngine.Line l : lines) {
            if (l.money().isZero()) {
                continue;
            }
            AccountKey k = new AccountKey(tenant, ledgerId, period, l.head() == null ? l.component() : l.head(), consumer,
                    "DEMAND", cycle);
            posts.add(ledger.apply(new LedgerRequest("ALLOCATE", k, null, l.amount(), null, null,
                    "inv:" + consumer + ":" + cycle + ":" + l.component(), no, actor, null, false, false, false,
                    Map.of("invoice", no))));
            ledger.setAttributes(k, Map.of("dueDate", dueDate.toString(), "invoice", no, "tariff", String.valueOf(tariff.get("id"))));
            total = total.plus(l.money());
        }
        return new Invoice(no, consumer, cycle, dueDate, lines, total, posts);
    }

    /**
     * Records a collection: confirms it through the payment port, then allocates it to the consumer's outstanding
     * demand oldest due date first (arrears before current), each allocation guarded by the ledger.
     */
    public Receipt collect(String tenant, String ledgerId, String consumer, String channel, String paymentRef,
                           Money amount, String actor) {
        Money confirmed = payments == null ? amount : payments.confirm(tenant, channel, paymentRef, amount);
        if (confirmed != null && !confirmed.currencyCode().equals(amount.currencyCode())) {
            throw new IllegalStateException("payment " + paymentRef + " confirmed in " + confirmed.currencyCode());
        }
        if (confirmed == null || confirmed.signum() <= 0) {
            throw new IllegalStateException("payment not confirmed: " + paymentRef);
        }
        List<Balance> open = new ArrayList<>();
        for (Balance b : ledger.arrears(tenant, ledgerId, LocalDate.MAX)) {
            if (b.key().holder().equals(consumer)) {
                open.add(b);
            }
        }
        open.sort(java.util.Comparator.comparing(b -> String.valueOf(b.attrs().get("dueDate"))));
        Money left = confirmed;
        Map<String, Money> alloc = new LinkedHashMap<>();
        List<LedgerService.Result> posts = new ArrayList<>();
        String no = "RCP/" + series.next(tenant, "RECEIPT", channel, businessClock.today(tenant).toString());
        for (Balance b : open) {
            if (left.signum() <= 0) {
                break;
            }
            Money take = b.availableMoney().min(left);
            if (take.signum() <= 0) {
                continue;
            }
            posts.add(ledger.apply(new LedgerRequest("CONSUME", b.key(), null, take.toMajor(), take.currencyCode(), null,
                    "rcp:" + paymentRef + ":" + b.key().id(), no, actor, null, false, false, false, Map.of("receipt", no))));
            alloc.put(b.key().line() + "/" + b.key().sub(), take);
            left = left.minus(take);
        }
        if (left.signum() > 0) {
            alloc.put("ADVANCE", left); // excess kept as an advance (credit) on the consumer
        }
        return new Receipt(no, consumer, confirmed, alloc, posts);
    }

    /** Refund of a collection (reverses consumption and calls the rail once per operation id). */
    public String refund(AccountKey demandAccount, String channel, String paymentRef, Money amount, String operationId,
                         String actor) {
        ledger.apply(new LedgerRequest("REVERSE", demandAccount, null, amount.toMajor(), amount.currencyCode(), null, "refund:" + operationId, operationId,
                actor, null, false, false, false, Map.of()));
        return payments == null ? null : payments.refund(demandAccount.tenant(), channel, paymentRef, amount, operationId);
    }

    /** Waiver / write-off of outstanding demand (the approval is a pack; this records it). */
    public LedgerService.Result waive(AccountKey demandAccount, Money amount, String orderRef, String actor) {
        return ledger.apply(new LedgerRequest("WRITE_OFF", demandAccount, null, amount.toMajor(), amount.currencyCode(), null, "waive:" + orderRef,
                orderRef, actor, null, false, false, false, Map.of()));
    }

    /** Raises penalty / interest on every overdue account of a consumer, per tariff rules. */
    public List<LedgerService.Result> levyPenalties(String tenant, String ledgerId, String period, String consumer,
                                                    Map<String, Object> tariff, LocalDate asOf, String actor) {
        List<LedgerService.Result> out = new ArrayList<>();
        Map<String, Object> pen = org.sangam.config.common.Tree.map(tariff.get("penalty"));
        String head = org.sangam.config.common.Tree.str(pen.get("head"), "PENALTY");
        for (Balance b : ledger.arrears(tenant, ledgerId, asOf)) {
            if (!b.key().holder().equals(consumer) || b.key().line().equals(head)) {
                continue;
            }
            LocalDate due = LocalDate.parse(String.valueOf(b.attrs().get("dueDate")));
            Money p = tariffs.penalty(tenant, tariff, b.available(), due, asOf);
            if (p.signum() > 0) {
                AccountKey k = new AccountKey(tenant, ledgerId, period, head, consumer, "DEMAND", b.key().sub());
                out.add(ledger.apply(new LedgerRequest("ALLOCATE", k, null, p.toMajor(), null, null,
                        "pen:" + b.key().id() + ":" + asOf, null, actor, asOf, false, false, false, Map.of("penaltyOn", b.key().id()))));
                ledger.setAttributes(k, Map.of("dueDate", asOf.toString()));
            }
        }
        return out;
    }
}
