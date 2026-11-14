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
 *
 * <p><b>Numbers and retries (SL-130, SL-131).</b> Invoice and receipt numbers come from {@link DocumentNumbers},
 * keyed by the document identity (invoice: tenant + ledger + consumer + cycle; receipt: tenant + paymentRef): a
 * retried step or a re-invoice gets the number the document already has and draws none. A receipt stores its
 * allocation plan with its number, and a retried collection replays that plan (each leg's posting reference is
 * {@code rcp:<paymentRef>:<account id>}, so an applied leg is a ledger duplicate) instead of re-planning over the
 * arrears the first attempt already reduced.
 *
 * <p><b>Payment confirmation fails closed (RM-02, RM-03, owner decisions 136 and 141).</b> {@link #collect} records
 * only an amount its rail confirmed through the {@link PaymentPort} ({@link PaymentConfirmationPolicy#confirmedByRail});
 * without a bound payment port every collection is refused. A channel the server configuration trusts is refused here
 * on every path (connector, pack step, job): its payments are recorded only by {@link UnverifiedPayments}, pending
 * ({@code PAYMENT_ACCEPTED_UNVERIFIED}) with nothing posted, and allocated through {@link #allocateVerified} once
 * VERIFIED.
 */
public final class BillingService {

    /** Collection rail (payment gateway, bank, counter, aggregator). Implemented over ConnectorGateway. */
    public interface PaymentPort {
        /**
         * Verifies a payment reported by a channel through its rail; returns the confirmed amount, or null when the
         * rail did not confirm it ({@link PaymentConfirmationPolicy#confirmedByRail}: success status and amount).
         */
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
    private final DocumentNumbers numbers;
    private final PaymentPort payments;
    private final PaymentConfirmationPolicy confirmation;
    private final AccrualPauses pauses;

    private final BusinessClock businessClock;

    /** Business dates in UTC (tests / single-zone tools); production wiring passes the tenant business clock. */
    public BillingService(LedgerService ledger, TariffEngine tariffs, NumberSeries series, PaymentPort payments) {
        this(ledger, tariffs, series, payments, BusinessClock.utc(java.time.Clock.systemUTC()));
    }

    /**
     * Over a config number series with a node-local document map ({@link DocumentNumbers#overSeries}): single node,
     * tests and tools. Production passes a durable {@link DocumentNumbers} ({@code numbering.RegistryDocumentNumbers}).
     */
    public BillingService(LedgerService ledger, TariffEngine tariffs, NumberSeries series, PaymentPort payments,
                          BusinessClock businessClock) {
        this(ledger, tariffs, DocumentNumbers.overSeries(series), payments, businessClock);
    }

    /** With no trusted channel ({@link PaymentConfirmationPolicy#verifiedOnly}): every collection is rail-verified. */
    public BillingService(LedgerService ledger, TariffEngine tariffs, DocumentNumbers numbers, PaymentPort payments,
                          BusinessClock businessClock) {
        this(ledger, tariffs, numbers, payments, PaymentConfirmationPolicy.verifiedOnly(), businessClock);
    }

    /** Without accrual pauses ({@link AccrualPauses#NONE}): a host that records no unverified payments. */
    public BillingService(LedgerService ledger, TariffEngine tariffs, DocumentNumbers numbers, PaymentPort payments,
                          PaymentConfirmationPolicy confirmation, BusinessClock businessClock) {
        this(ledger, tariffs, numbers, payments, confirmation, AccrualPauses.NONE, businessClock);
    }

    /**
     * @param payments     the collection rails; null: every collection is refused (RM-02), refunds reach no rail
     * @param confirmation the trusted channels (decisions 136 and 141: refused by {@link #collect})
     * @param pauses       the pending unverified payments for penalty accrual (decision 195); {@link UnverifiedPayments}
     *                     requires it to be its own store
     */
    public BillingService(LedgerService ledger, TariffEngine tariffs, DocumentNumbers numbers, PaymentPort payments,
                          PaymentConfirmationPolicy confirmation, AccrualPauses pauses, BusinessClock businessClock) {
        this.businessClock = java.util.Objects.requireNonNull(businessClock, "businessClock");
        this.ledger = ledger;
        this.tariffs = tariffs;
        this.numbers = java.util.Objects.requireNonNull(numbers, "numbers");
        this.payments = payments;
        this.confirmation = java.util.Objects.requireNonNull(confirmation, "confirmation");
        this.pauses = java.util.Objects.requireNonNull(pauses, "pauses");
    }

    /** The accrual pauses this service levies against (checked by {@link UnverifiedPayments}). */
    AccrualPauses accrualPauses() {
        return pauses;
    }

    /**
     * Raises the invoice of one consumer for one cycle; idempotent per (ledger, consumer, cycle): a repeat returns the
     * invoice's existing number (SL-130) and posts nothing new (each line's reference is per consumer, cycle, component).
     */
    public Invoice invoice(String tenant, String ledgerId, String period, String consumer, String cycle,
                           Map<String, Object> tariff, Map<String, BigDecimal> measures, Map<String, Object> attrs,
                           LocalDate dueDate, String actor) {
        List<TariffEngine.Line> lines = tariffs.compute(tenant, tariff, measures, attrs);
        String no = numbers.issue(new DocumentNumbers.Request(tenant, DocumentNumbers.Kind.INVOICE,
                DocumentNumbers.invoiceKey(ledgerId, consumer, cycle), cycle, businessClock.today(tenant), actor),
                Map::of).number();
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
     * demand oldest due date first (arrears before current), each allocation guarded by the ledger. The receipt of a
     * paymentRef is planned once: a retry replays the stored plan under the same receipt number (SL-131).
     */
    public Receipt collect(String tenant, String ledgerId, String consumer, String channel, String paymentRef,
                           Money amount, String actor) {
        Money confirmed = confirm(tenant, channel, paymentRef, amount);
        if (confirmed != null && !confirmed.currencyCode().equals(amount.currencyCode())) {
            throw new IllegalStateException("payment " + paymentRef + " confirmed in " + confirmed.currencyCode());
        }
        if (confirmed == null || confirmed.signum() <= 0) {
            throw new IllegalStateException("payment not confirmed: " + paymentRef);
        }
        return allocate(tenant, ledgerId, consumer, channel, paymentRef, confirmed, actor);
    }

    /**
     * Allocates a payment that {@link UnverifiedPayments} resolved VERIFIED (decision 141): the same receipt path as a
     * rail-confirmed collection, idempotent per payment reference (SL-131). Package-private on purpose: no host, pack
     * or connector can allocate a payment without its confirmation.
     */
    Receipt allocateVerified(String tenant, String ledgerId, String consumer, String channel, String paymentRef,
                             Money verified, String actor) {
        if (verified == null || verified.signum() <= 0) {
            throw new IllegalStateException("payment " + paymentRef + " has no verified amount");
        }
        return allocate(tenant, ledgerId, consumer, channel, paymentRef, verified, actor);
    }

    private Receipt allocate(String tenant, String ledgerId, String consumer, String channel, String paymentRef,
                             Money confirmed, String actor) {
        String receiptKey = DocumentNumbers.receiptKey(paymentRef);
        Money paid = confirmed;
        DocumentNumbers.Issued issued = numbers.issue(new DocumentNumbers.Request(tenant, DocumentNumbers.Kind.RECEIPT,
                receiptKey, channel, businessClock.today(tenant), actor), () -> plan(tenant, ledgerId, consumer, paid));
        String no = issued.number();
        Plan plan = Plan.from(issued.plan());
        if (!plan.amount().equals(confirmed) || !consumer.equals(plan.consumer()) || !ledgerId.equals(plan.ledger())) {
            // the payment is receipted once: a call for another amount, consumer or ledger is not a retry of it
            throw new IllegalStateException("payment " + paymentRef + " is already receipted as " + no + " ("
                    + plan.amount().toPlainString() + " " + plan.amount().currencyCode() + " to " + plan.consumer()
                    + " on " + plan.ledger() + ")");
        }
        Map<String, Money> alloc = new LinkedHashMap<>();
        List<LedgerService.Result> posts = new ArrayList<>();
        for (Leg leg : plan.legs()) {
            posts.add(ledger.apply(new LedgerRequest("CONSUME", leg.account(), null, leg.amount().toMajor(),
                    leg.amount().currencyCode(), null, "rcp:" + paymentRef + ":" + leg.account().id(), no, actor, null,
                    false, false, false, Map.of("receipt", no))));
            alloc.put(leg.account().line() + "/" + leg.account().sub(), leg.amount());
        }
        if (plan.advance().signum() > 0) {
            alloc.put("ADVANCE", plan.advance()); // excess kept as an advance (credit) on the consumer
        }
        return new Receipt(no, consumer, confirmed, alloc, posts);
    }

    /**
     * The confirmed amount of a reported collection (RM-02, RM-03): a trusted channel is refused (decision 141: only
     * {@link UnverifiedPayments} records it, pending); no payment port refuses; every other channel needs its rail's
     * confirmation (null: not confirmed).
     */
    private Money confirm(String tenant, String channel, String paymentRef, Money amount) {
        java.util.Objects.requireNonNull(amount, "amount");
        if (confirmation.trusts(channel)) {
            throw new IllegalStateException("channel " + channel + " cannot confirm payment " + paymentRef
                    + ": it is recorded only as " + UnverifiedPayments.PAYMENT_ACCEPTED_UNVERIFIED + " by an authorised"
                    + " member of staff (unverified-payment service), never by a collection call, pack step or job");
        }
        if (payments == null) {
            throw new IllegalStateException("no payment port is bound: payment " + paymentRef
                    + " cannot be confirmed and is not recorded");
        }
        return payments.confirm(tenant, channel, paymentRef, amount);
    }

    /** One planned consumption: the demand account and the amount taken from it. */
    record Leg(AccountKey account, Money amount) {
    }

    /** A receipt's allocation plan: ledger, consumer, confirmed amount, legs oldest due first, advance left over. */
    record Plan(String ledger, String consumer, Money amount, List<Leg> legs, Money advance) {

        /** The stored form: strings only (decimals as strings, R33), the legs as a list of account maps. */
        Map<String, Object> toMap() {
            List<Map<String, Object>> l = new ArrayList<>();
            for (Leg g : legs) {
                AccountKey k = g.account();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("tenant", k.tenant());
                m.put("ledger", k.ledger());
                m.put("period", k.period());
                m.put("line", k.line());
                m.put("holder", k.holder());
                m.put("stage", k.stage());
                m.put("sub", k.sub());
                m.put("amount", g.amount().toPlainString());
                l.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ledger", ledger);
            out.put("consumer", consumer);
            out.put("currency", amount.currencyCode());
            out.put("amount", amount.toPlainString());
            out.put("legs", l);
            out.put("advance", advance.toPlainString());
            return out;
        }

        /** Reads a plan written by {@link #toMap} (as stored or as parsed back from JSON). */
        static Plan from(Map<String, Object> m) {
            Object ccyValue = m.get("currency");
            Object amountValue = m.get("amount");
            if (ccyValue == null || amountValue == null) {
                throw new IllegalStateException("stored receipt plan has no amount: " + m);
            }
            String ccy = ccyValue.toString();
            List<Leg> legs = new ArrayList<>();
            if (m.get("legs") instanceof List<?> list) {
                for (Object o : list) {
                    Map<?, ?> g = (Map<?, ?>) o;
                    AccountKey k = new AccountKey(str(g, "tenant"), str(g, "ledger"), str(g, "period"), str(g, "line"),
                            str(g, "holder"), str(g, "stage"), str(g, "sub"));
                    legs.add(new Leg(k, Money.parse(str(g, "amount"), ccy)));
                }
            }
            Object adv = m.get("advance");
            return new Plan(str(m, "ledger"), str(m, "consumer"), Money.parse(amountValue.toString(), ccy), List.copyOf(legs),
                    adv == null ? Money.zero(ccy) : Money.parse(adv.toString(), ccy));
        }

        private static String str(Map<?, ?> m, String k) {
            Object v = m.get(k);
            return v == null ? null : v.toString();
        }
    }

    /** Plans a collection over the consumer's current outstanding demand, oldest due date first. */
    private Map<String, Object> plan(String tenant, String ledgerId, String consumer, Money confirmed) {
        List<Balance> open = new ArrayList<>();
        for (Balance b : ledger.arrears(tenant, ledgerId, LocalDate.MAX)) {
            if (b.key().holder().equals(consumer)) {
                open.add(b);
            }
        }
        open.sort(java.util.Comparator.comparing(b -> String.valueOf(b.attrs().get("dueDate"))));
        Money left = confirmed;
        List<Leg> legs = new ArrayList<>();
        for (Balance b : open) {
            if (left.signum() <= 0) {
                break;
            }
            Money take = b.availableMoney().min(left);
            if (take.signum() <= 0) {
                continue;
            }
            legs.add(new Leg(b.key(), take));
            left = left.minus(take);
        }
        return new Plan(ledgerId, consumer, confirmed, legs, left.signum() > 0 ? left : Money.zero(confirmed.currencyCode())).toMap();
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

    /**
     * Raises penalty / interest on every overdue account of a consumer, per tariff rules.
     *
     * <p><b>Pending payments (owner decision 195).</b> While a payment of the consumer is pending
     * ({@code PAYMENT_ACCEPTED_UNVERIFIED}), the part of the arrears it covers (oldest due date first, the receipt plan's
     * order) stops accruing from the business date of its acceptance. What that leaves unlevied is recorded per payment
     * ({@link AccrualPauses#recordPauses}, under the payment's row lock, all of this levy or none) before anything is
     * posted; a payment resolved meanwhile is never paused (the levy is computed again). VERIFIED forgives the paused
     * accrual (the payment counts from acceptance); REJECTED and REVERSED levy it after all ({@link #resumeAccrual}).
     * Levied plus paused equals the unpaused penalty rounded once. The first levy of a date decides an account: a
     * repeated levy of that date posts nothing new (ledger reference {@code pen:<account>:<date>}) and leaves an account
     * paused on that date as it is.
     */
    public List<LedgerService.Result> levyPenalties(String tenant, String ledgerId, String period, String consumer,
                                                    Map<String, Object> tariff, LocalDate asOf, String actor) {
        Map<String, Object> pen = org.sangam.config.common.Tree.map(tariff.get("penalty"));
        String head = org.sangam.config.common.Tree.str(pen.get("head"), "PENALTY");
        for (int attempt = 1; ; attempt++) {
            Levy levy = levy(tenant, ledgerId, period, consumer, tariff, head, asOf);
            if (pauses.recordPauses(tenant, ledgerId, consumer, asOf, levy.paymentIds(), levy.pauses())) {
                List<LedgerService.Result> out = new ArrayList<>();
                for (Charge ch : levy.charges()) {
                    out.add(ledger.apply(new LedgerRequest("ALLOCATE", ch.account(), null, ch.amount().toMajor(), null,
                            null, "pen:" + ch.baseAccountId() + ":" + asOf, null, actor, asOf, false, false, false,
                            Map.of("penaltyOn", ch.baseAccountId()))));
                    ledger.setAttributes(ch.account(), Map.of("dueDate", asOf.toString()));
                }
                return out;
            }
            if (attempt >= 5) {
                throw new IllegalStateException("the pending payments of consumer " + consumer + " on " + ledgerId
                        + " kept changing during the penalty levy of " + asOf + "; nothing was levied, retry");
            }
        }
    }

    /**
     * Levies the accrual a REJECTED or REVERSED payment had paused (decision 195), each on its penalty account at its
     * levy date, idempotent per (payment, account, date): reference {@code pen-resume:<payment>:<account>:<date>}.
     */
    List<LedgerService.Result> resumeAccrual(String tenant, String paymentId, List<AccrualPauses.Pause> paused,
                                             String actor) {
        List<LedgerService.Result> out = new ArrayList<>();
        for (AccrualPauses.Pause p : paused) {
            if (!tenant.equals(p.penaltyAccount().tenant()) || !paymentId.equals(p.paymentId())) {
                throw new IllegalArgumentException("pause " + p + " is not of payment " + paymentId + " in " + tenant);
            }
            out.add(ledger.apply(new LedgerRequest("ALLOCATE", p.penaltyAccount(), null, p.suppressed().toMajor(),
                    null, null, "pen-resume:" + paymentId + ":" + p.baseAccountId() + ":"
                    + p.asOf(), null, actor, p.asOf(), false, false, false,
                    Map.of("penaltyOn", p.baseAccountId(), "resumedFrom", paymentId))));
            ledger.setAttributes(p.penaltyAccount(), Map.of("dueDate", p.asOf().toString()));
        }
        return out;
    }

    /** One penalty posting of a levy. */
    private record Charge(String baseAccountId, AccountKey account, Money amount) {
    }

    /** A levy: what is posted, and what is paused by which pending payments. */
    private record Levy(List<Charge> charges, List<String> paymentIds, List<AccrualPauses.Pause> pauses) {
    }

    /** The part of an account a pending payment covers, and the business date it stopped accruing from. */
    private record Piece(String paymentId, BigDecimal amount, LocalDate from) {
    }

    private Levy levy(String tenant, String ledgerId, String period, String consumer, Map<String, Object> tariff,
                      String head, LocalDate asOf) {
        Map<String, List<Piece>> cover = coverage(tenant, ledgerId, consumer, pauses.pending(tenant, ledgerId, consumer));
        Map<String, Money> decided = pauses.pausedOn(tenant, ledgerId, consumer, asOf);
        String ccy = TariffEngine.currency(tariff);
        java.math.RoundingMode mode = TariffEngine.roundingMode(tariff);
        List<Charge> charges = new ArrayList<>();
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        List<AccrualPauses.Pause> paused = new ArrayList<>();
        for (Balance b : ledger.arrears(tenant, ledgerId, asOf)) {
            if (!b.key().holder().equals(consumer) || b.key().line().equals(head)) {
                continue;
            }
            LocalDate due = LocalDate.parse(String.valueOf(b.attrs().get("dueDate")));
            AccountKey k = new AccountKey(tenant, ledgerId, period, head, consumer, "DEMAND", b.key().sub());
            Money left = Money.round(tariffs.penaltyExact(tenant, tariff, b.available(), due, asOf), ccy, mode);
            Money already = decided.get(b.key().id());
            if (already != null) {    // decided by an earlier levy of this date: no new pause, complete its posting
                Money rest = left.minus(already.min(left));
                if (rest.signum() > 0) {
                    charges.add(new Charge(b.key().id(), k, rest));
                }
                continue;
            }
            BigDecimal accruing = b.available();
            List<Piece> pieces = new ArrayList<>(cover.getOrDefault(b.key().id(), List.of()));
            pieces.removeIf(p -> !p.from().isBefore(asOf));
            pieces.sort(java.util.Comparator.comparing(Piece::from));
            for (Piece p : pieces) {
                // the marginal accrual of the covered part after its pause date: exact for the per-day rate kinds; a
                // flat charge is paused only when the payment covers the whole remainder and was accepted before the
                // charge was incurred, a once-only percent in proportion when accepted before it was incurred
                BigDecimal after = accruing.subtract(p.amount()).max(BigDecimal.ZERO);
                BigDecimal suppressed = since(tenant, tariff, accruing, due, p.from(), asOf)
                        .subtract(since(tenant, tariff, after, due, p.from(), asOf));
                accruing = after;
                if (suppressed.signum() <= 0) {
                    continue;
                }
                Money s = Money.round(suppressed, ccy, mode).min(left);
                if (s.signum() > 0) {
                    left = left.minus(s);
                    ids.add(p.paymentId());
                    paused.add(new AccrualPauses.Pause(p.paymentId(), b.key().id(), asOf, p.from(), k, s));
                }
            }
            if (left.signum() > 0) {
                charges.add(new Charge(b.key().id(), k, left));
            }
        }
        return new Levy(charges, List.copyOf(ids), paused);
    }

    /** Accrual on {@code overdue} between {@code from} and {@code asOf} (exact, before rounding). */
    private BigDecimal since(String tenant, Map<String, Object> tariff, BigDecimal overdue, LocalDate due,
                             LocalDate from, LocalDate asOf) {
        return tariffs.penaltyExact(tenant, tariff, overdue, due, asOf)
                .subtract(tariffs.penaltyExact(tenant, tariff, overdue, due, from));
    }

    /**
     * Which part of which account each pending payment covers: the payments, oldest acceptance first, over the
     * consumer's outstanding demand, oldest due date first (the order a receipt is planned in).
     */
    private Map<String, List<Piece>> coverage(String tenant, String ledgerId, String consumer,
                                              List<AccrualPauses.Pending> pending) {
        if (pending.isEmpty()) {
            return Map.of();
        }
        List<Balance> open = new ArrayList<>();
        for (Balance b : ledger.arrears(tenant, ledgerId, LocalDate.MAX)) {
            if (b.key().holder().equals(consumer)) {
                open.add(b);
            }
        }
        open.sort(java.util.Comparator.comparing(b -> String.valueOf(b.attrs().get("dueDate"))));
        java.time.ZoneId zone = businessClock.zone(tenant);
        Map<String, List<Piece>> out = new java.util.HashMap<>();
        int i = 0;
        BigDecimal rest = pending.get(0).amount().toMajor();
        for (Balance b : open) {
            BigDecimal avail = b.available();
            while (avail.signum() > 0 && i < pending.size()) {
                if (rest.signum() <= 0) {
                    i++;
                    rest = i < pending.size() ? pending.get(i).amount().toMajor() : BigDecimal.ZERO;
                    continue;
                }
                BigDecimal take = avail.min(rest);
                AccrualPauses.Pending p = pending.get(i);
                out.computeIfAbsent(b.key().id(), x -> new ArrayList<>()).add(new Piece(p.paymentId(), take,
                        p.acceptedAt().atZone(zone).toLocalDate()));
                avail = avail.subtract(take);
                rest = rest.subtract(take);
            }
        }
        return out;
    }
}
