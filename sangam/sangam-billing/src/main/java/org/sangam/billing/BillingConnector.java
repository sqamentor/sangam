// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.sangam.config.common.Tree;
import org.sangam.config.locale.FiscalYearNotConfiguredException;
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
import org.sangam.ledger.LedgerPeriods;
import org.sangam.ledger.LedgerService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * In-process connector for the billing pack ({@code universal.billing-cycle}): connector ids {@code billing.accounts},
 * {@code billing.invoice}, {@code billing.collect}, {@code billing.arrears}, {@code billing.penalty},
 * {@code billing.waive}. Tariffs come from the tenant profile ({@code tariffs:}); the account list of a cycle comes
 * from an {@link AccountSource} (the consumer / property / licence register of the deployment).
 *
 * <p>Common inputs: {@code ledgerId} (default {@code DEMAND}), {@code period} or its alias {@code fiscalYear} (invoice,
 * penalty and waive; the same keys as the ledger connector, {@link LedgerPeriods}; missing or {@code current}: the
 * tenant's current fiscal period from the period resolver, which refuses when no fiscal year is configured: the step
 * fails with {@code FISCAL_YEAR_NOT_CONFIGURED}, SL-127; never an invented calendar year), {@code consumer}, {@code cycle},
 * {@code tariffId}, {@code measures} (map) or {@code units}, {@code dueDays} (default 15), {@code amount},
 * {@code channel}, {@code paymentRef}, {@code head}, {@code orderRef}. Postings use the operationId-derived references of
 * {@link BillingService}, so retries are idempotent.
 *
 * <p><b>Who acted (RM-37; owner decisions 141 and 142).</b> The actor recorded with an invoice, a receipt, a penalty
 * and a waiver is the authenticated server-side identity the host states through its {@link ActorSource}, never a
 * value of the step input: a process (or anything else that can set step variables or a request body) cannot choose
 * who collected or waived. With an authenticated actor, an input {@code actor} that names somebody else is refused
 * ({@code ACTOR_MISMATCH}, nothing posted). Without one (an engine job, a host that wired no source) the posting is
 * recorded as {@value #SYSTEM_ACTOR} and an input {@code actor} is not used.
 *
 * <p><b>References.</b> {@code paymentRef} is a business input: the reference the payment has on its channel. It is
 * required, confirmed with the channel's rail before anything is recorded and receipted once per tenant
 * ({@link DocumentNumbers#receiptKey}: a second collection of the same reference for another amount, consumer or
 * ledger is refused); the receipt <i>number</i> is issued by the server. {@code orderRef} (the waiver order) is
 * required and makes the waiver idempotent.
 */
public final class BillingConnector implements SangamConnector {

    /** Accounts (consumers) billed in a cycle. */
    @FunctionalInterface
    public interface AccountSource {
        List<Map<String, Object>> accounts(String tenant, String cycle);
    }

    /**
     * The authenticated server-side actor of a connector call. The host answers from its own authentication state (the
     * principal of the request it is serving, the engine's authenticated user); it never reads
     * {@link ConnectorRequest#input}.
     */
    @FunctionalInterface
    public interface ActorSource {
        /**
         * @return the authenticated principal this call is made for, or null when no person is authenticated (an engine
         *         job, another system execution)
         */
        String authenticatedActor(ConnectorRequest request);
    }

    /** The recorded actor of a posting that no authenticated person made (an engine job, an unwired host). */
    public static final String SYSTEM_ACTOR = "system:billing-connector";

    /** The operations that post (owner decision 320: the others are read-only); each records an actor. */
    private static final java.util.Set<String> POSTING = java.util.Set.of("invoice", "collect", "penalty", "waive");

    /** Refusal code: the step input names an actor other than the authenticated one. */
    public static final String ACTOR_MISMATCH = "ACTOR_MISMATCH";

    /** Refusal code: the host's {@link ActorSource} failed, so nobody can be recorded as having acted. */
    public static final String ACTOR_UNRESOLVED = "ACTOR_UNRESOLVED";

    private final BillingService billing;
    private final LedgerService ledger;
    private final ProfileResolver profiles;
    private final AccountSource accounts;
    private final BiFunction<String, LocalDate, String> periodResolver;
    private final Clock clock;
    private final ActorSource actors;

    /**
     * A host that states no authenticated actor: every posting is recorded as {@value #SYSTEM_ACTOR}, whatever the step
     * input says. A host that serves authenticated callers passes its {@link ActorSource}.
     */
    public BillingConnector(BillingService billing, LedgerService ledger, ProfileResolver profiles, AccountSource accounts,
                            BiFunction<String, LocalDate, String> periodResolver, Clock clock) {
        this(billing, ledger, profiles, accounts, periodResolver, clock, request -> null);
    }

    /**
     * @param periodResolver tenant and date to the current period label (the tenant profile's fiscal calendar, the same
     *                       resolver as the ledger connector's); it throws {@link FiscalYearNotConfiguredException} when
     *                       no fiscal year is configured. Null: a step without an explicit {@code period} is refused.
     * @param actors         the host's authenticated actor of a call (RM-37); required
     */
    public BillingConnector(BillingService billing, LedgerService ledger, ProfileResolver profiles, AccountSource accounts,
                            BiFunction<String, LocalDate, String> periodResolver, Clock clock, ActorSource actors) {
        this.actors = java.util.Objects.requireNonNull(actors, "actors");
        this.billing = billing;
        this.ledger = ledger;
        this.profiles = profiles;
        this.accounts = accounts;
        this.periodResolver = periodResolver;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * The step's {@code period} (or its alias {@code fiscalYear}, as the ledger connector reads it), else the tenant's
     * current fiscal period; never an assumed calendar year (SL-127). One rule with the ledger: {@link LedgerPeriods}
     * (X1-08).
     */
    private String period(ConnectorRequest r, String tenant, LocalDate today) {
        return LedgerPeriods.periodOr(r.input(), tenant, today, periodResolver);
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return new ConnectorDescriptor("billing", "in-process", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                List.of("accounts", "invoice", "collect", "arrears", "penalty", "waive"), ConnectorProfile.LIVE);
    }

    /**
     * Owner decision 320: {@code accounts} (the accounts billed in a cycle) and {@code arrears} (balances read from the
     * ledger) are read-only, resolved as {@link #invoke} resolves the operation; invoice, collect, penalty and waive post.
     */
    @Override
    public boolean readOnly(ConnectorRequest r) {
        String op = r.operation().contains(".") ? r.operation().substring(r.operation().lastIndexOf('.') + 1) : r.operation();
        return "accounts".equals(op) || "arrears".equals(op);
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest r) {
        try {
            String op = r.operation().contains(".") ? r.operation().substring(r.operation().lastIndexOf('.') + 1) : r.operation();
            String tenant = r.tenantId();
            LocalDate today = clock.instant().atZone(ledger.zone(tenant)).toLocalDate(); // tenant business date
            String ledgerId = Tree.str(r.input().get("ledgerId"), "DEMAND");
            String consumer = r.str("consumer");
            Map<String, Object> out = new LinkedHashMap<>();
            // who acts is settled before anything else is read or asked (RM-37); reads record nobody
            String actor = POSTING.contains(op) ? actor(r) : null;
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
                    BillingService.Invoice inv = billing.invoice(tenant, ledgerId, period(r, tenant, today), consumer, r.str("cycle"), tariff,
                            measures, r.input(), today.plusDays(Tree.integer(r.input().get("dueDays"), 15)), actor);
                    out.put("invoiceNo", inv.number());
                    out.put("total", inv.total().toPlainString());
                    out.put("dueDate", inv.dueDate().toString());
                }
                case "collect" -> {
                    BillingService.Receipt rc = billing.collect(tenant, ledgerId, consumer, r.required("channel"),
                            r.required("paymentRef"), money(tenant, ledgerId, r.str("amount")), actor);
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
                    List<LedgerService.Result> res = billing.levyPenalties(tenant, ledgerId, period(r, tenant, today), consumer,
                            tariff(tenant, r.str("tariffId"), today), today, actor);
                    out.put("penalties", res.size());
                }
                case "waive" -> {
                    String orderRef = r.required("orderRef");
                    AccountKey k = new AccountKey(tenant, ledgerId, period(r, tenant, today), r.str("head"), consumer, "DEMAND",
                            r.str("cycle"));
                    LedgerService.Result res = billing.waive(k, money(tenant, ledgerId, r.str("amount")), orderRef, actor);
                    out.put("applied", res.applied());
                }
                default -> {
                    return ConnectorResult.BusinessFailure.of("OPERATION_UNKNOWN", op);
                }
            }
            return ConnectorResult.Success.of(out, r.operationId().toString());
        } catch (ActorRefused e) {
            return ConnectorResult.BusinessFailure.of(e.code, e.getMessage());
        } catch (FiscalYearNotConfiguredException e) {
            return ConnectorResult.BusinessFailure.of(FiscalYearNotConfiguredException.CODE, e.getMessage());
        } catch (RuntimeException e) {
            return ConnectorResult.BusinessFailure.of("BILLING_ERROR", e.getMessage());
        }
    }

    /**
     * The actor recorded with a posting (RM-37): the host's authenticated actor, else {@value #SYSTEM_ACTOR}. The step
     * input never supplies it; with an authenticated actor, an input {@code actor} naming somebody else is refused
     * before anything is posted.
     */
    private String actor(ConnectorRequest r) {
        String authenticated;
        try {
            authenticated = actors.authenticatedActor(r);
        } catch (RuntimeException e) {
            throw new ActorRefused(ACTOR_UNRESOLVED, "the authenticated actor of operation " + r.operation()
                    + " could not be established; nothing is posted", e);
        }
        if (authenticated == null || authenticated.isBlank()) {
            return SYSTEM_ACTOR;
        }
        String claimed = r.str("actor");
        if (claimed != null && !claimed.isBlank() && !claimed.equals(authenticated)) {
            throw new ActorRefused(ACTOR_MISMATCH, "the input names actor " + claimed + " but the authenticated actor is "
                    + authenticated + "; nothing is posted", null);
        }
        return authenticated;
    }

    /** A posting refused because of who is acting; carries the connector failure code. */
    private static final class ActorRefused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        ActorRefused(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
    }

    /** API boundary (SD-398): a decimal string in the ledger currency, rejected if finer than its minor unit. */
    private org.sangam.core.money.Money money(String tenant, String ledgerId, String amount) {
        String ccy = ledger.definition(tenant, ledgerId).currency();
        if (ccy == null) {
            throw new IllegalArgumentException("ledger " + ledgerId + " has no currency");
        }
        return org.sangam.core.money.Money.parse(amount, ccy);
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
