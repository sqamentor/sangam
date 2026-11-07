// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import org.sangam.config.common.Finding;
import org.sangam.config.common.Tree;
import org.sangam.ledger.Balance.Bucket;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The generic limit / fund / quantity / receivable ledger service. Domain rules come from the ledger definition
 * (configuration) and from decisions (DMN through {@link LedgerPorts.DecisionPort}); this class only knows buckets,
 * stages, periods and guards.
 */
public final class LedgerService {

    /** Outcome of an operation. {@code held} is true when the request was parked in the hold queue. */
    public record Result(boolean applied, boolean duplicate, boolean held, String reason, List<Finding> findings,
                         List<Balance> balances, List<LedgerPorts.AccountingEntry> accounting) {
    }

    private final LedgerStore store;
    private final Map<String, LedgerDefinition> definitions = new ConcurrentHashMap<>();
    private final LedgerControlStore control;
    private final Clock clock;
    private LedgerPorts.RatePort rates = (f, t, type, d) -> f.equals(t) ? Optional.of(BigDecimal.ONE) : Optional.empty();
    private LedgerPorts.DecisionPort decisions;
    private LedgerPorts.ExternalLedgerPort external;
    private LedgerPorts.AccountingSink accountingSink = e -> { };
    private LedgerPorts.AuditLink audit = (t, r, o, a, act, s) -> { };
    private LedgerPorts.BreachSink breaches = (b, r) -> { };

    /** Production constructor: balances, holds and freeze orders are all durable (nothing lost on restart). */
    public LedgerService(LedgerStore store, LedgerControlStore control, Clock clock) {
        this.store = store;
        this.control = control;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Development constructor with an in-memory control store (holds and freeze orders are not durable). */
    public LedgerService(LedgerStore store, Clock clock) {
        this(store, new LedgerControlStore.InMemory(), clock);
    }

    public LedgerService withRates(LedgerPorts.RatePort r) {
        this.rates = r;
        return this;
    }

    public LedgerService withDecisions(LedgerPorts.DecisionPort d) {
        this.decisions = d;
        return this;
    }

    public LedgerService withExternal(LedgerPorts.ExternalLedgerPort e) {
        this.external = e;
        return this;
    }

    public LedgerService withAccounting(LedgerPorts.AccountingSink s) {
        this.accountingSink = s;
        return this;
    }

    /** Receives limit breaches (an account below zero after a SOFT-checked posting): a statutory event in some
     *  jurisdictions (Antideficiency Act report, Excess Vote, PFM-039); the integrator opens a case of the configured type. */
    public LedgerService withBreaches(LedgerPorts.BreachSink b) {
        this.breaches = b;
        return this;
    }

    public LedgerService withAudit(LedgerPorts.AuditLink a) {
        this.audit = a;
        return this;
    }

    /** Registers the ledger definitions of a tenant (from its resolved profile section {@code ledgers}). */
    public void define(String tenant, List<LedgerDefinition> defs) {
        for (LedgerDefinition d : defs) {
            definitions.put(tenant + "|" + d.id(), d);
        }
    }

    public LedgerDefinition definition(String tenant, String ledger) {
        LedgerDefinition d = definitions.get(tenant + "|" + ledger);
        if (d == null) {
            // a department tenant inherits its government's definitions
            int dot = tenant.lastIndexOf('.');
            if (dot > 0) {
                return definition(tenant.substring(0, dot), ledger);
            }
            throw new IllegalArgumentException("ledger '" + ledger + "' not defined for " + tenant);
        }
        return d;
    }

    // ------------------------------------------------------------------------------------------------ queries
    public BigDecimal available(AccountKey key) {
        LedgerDefinition def = definition(key.tenant(), key.ledger());
        if (def.mode() == LedgerDefinition.Mode.SHADOW && external != null) {
            Optional<BigDecimal> ext = external.available(key);
            if (ext.isPresent()) {
                return ext.get();
            }
        }
        if (def.stripes() > 1 && "-".equals(key.sub())) {
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 0; i < def.stripes(); i++) {
                sum = sum.add(store.get(key.withSub("S" + i)).map(Balance::available).orElse(BigDecimal.ZERO));
            }
            return sum;
        }
        return store.get(key).map(Balance::available).orElse(BigDecimal.ZERO);
    }

    public Optional<Balance> balance(AccountKey key) {
        return store.get(key);
    }

    /** RECEIVABLE: accounts with outstanding demand past their {@code dueDate} attribute (arrears). */
    public List<Balance> arrears(String tenant, String ledger, LocalDate asOf) {
        List<Balance> out = new ArrayList<>();
        for (Balance b : store.find(tenant, ledger, null, null, null, null)) {
            LocalDate due = Tree.date(b.attrs().get("dueDate"), null);
            if (due != null && due.isBefore(asOf) && b.available().signum() > 0) {
                out.add(b);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ operations
    public Result apply(LedgerRequest r) {
        LedgerDefinition def = definition(r.account().tenant(), r.account().ledger());
        List<Finding> findings = new ArrayList<>(policies(def, r));
        if (Finding.anyError(findings)) {
            return new Result(false, false, false, findings.stream().filter(Finding::isError).findFirst().get().code(),
                    findings, List.of(), List.of());
        }
        BigDecimal amount = convert(def, r);
        List<Movement> legs = legs(def, r, amount);
        if (def.stripes() > 1 && "-".equals(r.account().sub()) && legs.size() == 1) {
            return striped(def, r, legs.get(0), findings);
        }
        return post(def, r, legs, findings);
    }

    private Result striped(LedgerDefinition def, LedgerRequest r, Movement leg, List<Finding> findings) {
        int n = def.stripes();
        int first = Math.floorMod(r.reference().hashCode(), n);
        if ("ALLOCATE".equals(r.operation()) || "ADJUST".equals(r.operation())) {
            // spread across stripes so each stripe can serve concurrent consumers
            List<Movement> legs = new ArrayList<>();
            BigDecimal part = leg.deltas().getOrDefault(Bucket.TOTAL, BigDecimal.ZERO).divide(BigDecimal.valueOf(n), 6, RoundingMode.DOWN);
            BigDecimal rest = leg.deltas().getOrDefault(Bucket.TOTAL, BigDecimal.ZERO).subtract(part.multiply(BigDecimal.valueOf(n)));
            for (int i = 0; i < n; i++) {
                legs.add(new Movement(leg.key().withSub("S" + i), leg.currency(),
                        Map.of(Bucket.TOTAL, i == 0 ? part.add(rest) : part), leg.guardAvailable(), leg.requireOpen()));
            }
            return post(def, r, legs, findings);
        }
        Result last = null;
        for (int k = 0; k < n; k++) {
            int i = (first + k) % n;
            Movement m = new Movement(leg.key().withSub("S" + i), leg.currency(), leg.deltas(), leg.guardAvailable(), leg.requireOpen());
            last = post(def, r.withReference(k == 0 ? r.reference() : r.reference() + "#S" + i), List.of(m), findings);
            if (last.applied()) {
                return last;
            }
        }
        return last;
    }

    private Result post(LedgerDefinition def, LedgerRequest r, List<Movement> legs, List<Finding> findings) {
        Map<String, Object> info = new LinkedHashMap<>(r.attrs());
        info.put("amount", r.amount() == null ? null : r.amount().toPlainString());
        LedgerStore.PostResult pr = store.post(new LedgerStore.Entry(r.account().tenant(), r.reference(), r.operation(),
                r.auditRef(), r.actor(), clock.instant(), legs, info));
        if (!pr.applied()) {
            if (r.holdIfShort() && def.holdQueue() && "INSUFFICIENT_AVAILABLE".equals(pr.reason())) {
                control.hold(r, clock.instant());
                findings.add(Finding.info("HELD_FOR_FUNDS", r.account().toString(), "held until funds are allocated", null));
                return new Result(false, false, true, pr.reason(), findings, pr.after(), List.of());
            }
            findings.add(Finding.error(pr.reason(), r.account().toString(), r.operation() + " refused", null));
            return new Result(false, false, false, pr.reason(), findings, pr.after(), List.of());
        }
        List<LedgerPorts.AccountingEntry> acc = pr.duplicate() ? List.of() : accounting(def, r);
        if (!pr.duplicate()) {
            for (Balance b : pr.after()) {
                if (b.available().signum() < 0 && !def.allowNegative()) {
                    breaches.breach(b, r);
                }
            }
            if (!acc.isEmpty()) {
                accountingSink.accept(acc);
            }
            audit.posted(r.account().tenant(), r.reference(), r.operation(), r.auditRef(), r.actor(), info);
        }
        return new Result(true, pr.duplicate(), false, null, findings, pr.after(), acc);
    }

    /** Requests held for funds on an account, oldest first, retried after an allocation. Returns the applied ones. */
    public List<Result> releaseHolds(AccountKey key) {
        List<Result> out = new ArrayList<>();
        for (LedgerControlStore.Held h : control.holds(key)) {
            LedgerRequest r = h.request();
            Result res = apply(new LedgerRequest(r.operation(), r.account(), r.target(), r.amount(), r.currency(),
                    r.rateType(), r.reference(), r.auditRef(), r.actor(), r.asOf(), r.fromCommitment(), r.fromEncumbrance(),
                    false, r.attrs()));
            if (res.applied()) {
                control.released(key.tenant(), r.reference(), clock.instant());
                out.add(res);
            }
        }
        return out;
    }

    public List<LedgerRequest> held(AccountKey key) {
        return control.holds(key).stream().map(LedgerControlStore.Held::request).toList();
    }

    private List<Movement> legs(LedgerDefinition def, LedgerRequest r, BigDecimal a) {
        AccountKey k = r.account();
        String ccy = r.currency() != null ? r.currency() : def.currency();
        BigDecimal guard = guard(def, k.stage());
        List<Movement> out = new ArrayList<>();
        switch (r.operation()) {
            case "ALLOCATE", "ADJUST" -> out.add(mv(k, ccy, Map.of(Bucket.TOTAL, a), "ADJUST".equals(r.operation()) ? guard : null, false));
            case "DISTRIBUTE" -> {
                out.add(mv(k, ccy, Map.of(Bucket.DISTRIBUTED, a), guard, true));
                out.add(mv(target(r), ccy, Map.of(Bucket.TOTAL, a), null, false));
            }
            case "WITHDRAW" -> {
                out.add(mv(k, ccy, Map.of(Bucket.TOTAL, a.negate()), guard, false));
                out.add(mv(target(r), ccy, Map.of(Bucket.DISTRIBUTED, a.negate()), null, false));
            }
            case "COMMIT" -> out.add(mv(k, ccy, Map.of(Bucket.COMMITTED, a), guard, true));
            case "UNCOMMIT" -> out.add(mv(k, ccy, Map.of(Bucket.COMMITTED, a.negate()), null, false));
            case "ENCUMBER" -> out.add(mv(k, ccy, r.fromCommitment() ? Map.of(Bucket.COMMITTED, a.negate(), Bucket.ENCUMBERED, a)
                    : Map.of(Bucket.ENCUMBERED, a), guard, true));
            case "DISENCUMBER" -> out.add(mv(k, ccy, r.fromCommitment() ? Map.of(Bucket.ENCUMBERED, a.negate(), Bucket.COMMITTED, a)
                    : Map.of(Bucket.ENCUMBERED, a.negate()), null, false));
            case "CONSUME" -> out.add(mv(k, ccy, r.fromEncumbrance() ? Map.of(Bucket.ENCUMBERED, a.negate(), Bucket.CONSUMED, a)
                    : r.fromCommitment() ? Map.of(Bucket.COMMITTED, a.negate(), Bucket.CONSUMED, a)
                    : Map.of(Bucket.CONSUMED, a), def.kind() == LedgerDefinition.Kind.RECEIVABLE ? BigDecimal.ZERO : guard, true));
            case "REVERSE" -> out.add(mv(k, ccy, Map.of(Bucket.CONSUMED, a.negate()), null, false));
            case "WRITE_OFF" -> out.add(mv(k, ccy, def.kind() == LedgerDefinition.Kind.RECEIVABLE
                    ? Map.of(Bucket.ENCUMBERED, a) : Map.of(Bucket.TOTAL, a.negate()), BigDecimal.ZERO, false));
            case "TRANSFER" -> {
                out.add(mv(k, ccy, Map.of(Bucket.TOTAL, a.negate()), guard, true));
                out.add(mv(target(r), ccy, Map.of(Bucket.TOTAL, a), null, true));
            }
            case "LAPSE" -> out.add(mv(k, ccy, Map.of(Bucket.TOTAL, a.negate()), null, false));
            default -> throw new IllegalArgumentException("unknown ledger operation " + r.operation());
        }
        return out;
    }

    private BigDecimal guard(LedgerDefinition def, String stage) {
        if (def.allowNegative() || def.check(stage) != LedgerDefinition.Check.HARD) {
            return null;
        }
        return BigDecimal.ZERO;
    }

    private static AccountKey target(LedgerRequest r) {
        if (r.target() == null) {
            throw new IllegalArgumentException(r.operation() + " needs a target account");
        }
        return r.target();
    }

    private static Movement mv(AccountKey k, String ccy, Map<Bucket, BigDecimal> d, BigDecimal guard, boolean open) {
        return new Movement(k, ccy, new EnumMap<>(d), guard, open);
    }

    private BigDecimal convert(LedgerDefinition def, LedgerRequest r) {
        BigDecimal a = r.amount() == null ? BigDecimal.ZERO : r.amount();
        String accountCcy = store.get(r.account()).map(Balance::currency).orElse(def.currency());
        if (r.currency() == null || accountCcy == null || r.currency().equals(accountCcy)) {
            return a;
        }
        BigDecimal rate = rates.rate(r.currency(), accountCcy, r.rateType() == null ? "OFFICIAL" : r.rateType(),
                r.asOf() == null ? LocalDate.now(clock) : r.asOf())
                .orElseThrow(() -> new IllegalArgumentException("no rate " + r.currency() + "->" + accountCcy));
        return a.multiply(rate).setScale(6, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------------------------------------ policies
    private List<Finding> policies(LedgerDefinition def, LedgerRequest r) {
        List<Finding> out = new ArrayList<>();
        boolean reducing = List.of("COMMIT", "ENCUMBER", "CONSUME", "DISTRIBUTE", "TRANSFER").contains(r.operation());
        if (reducing) {
            for (FreezeOrder f : control.freezes(r.account().tenant())) {
                if (f.matches(r.account())) {
                    out.add(Finding.error("FROZEN", r.account().toString(), "frozen by order " + f.id() + ": " + f.reason(), f.id()));
                }
            }
            Optional<Balance> b = store.get(r.account());
            if (b.isPresent()) {
                for (String c : Tree.strings(b.get().attrs().get("conditions"))) {
                    if (!Tree.strings(r.attrs().get("satisfiedConditions")).contains(c)) {
                        out.add(Finding.error("CONDITION_UNMET", c, "limit condition '" + c + "' not satisfied", null));
                    }
                }
                LocalDate until = Tree.date(b.get().attrs().get("availableUntil"), null);
                LocalDate asOf = r.asOf() == null ? LocalDate.now(clock) : r.asOf();
                if (until != null && asOf.isAfter(until) && !"ADJUSTMENT".equals(r.attrs().get("purpose"))) {
                    out.add(Finding.error("PERIOD_EXPIRED", r.account().period(), "period of availability ended " + until, null));
                }
            }
        }
        if ("TRANSFER".equals(r.operation())) {
            Map<String, Object> rule = Tree.map(def.get("transfer"));
            Map<String, Object> from = Tree.map(r.attrs().get("fromAttrs"));
            Map<String, Object> to = Tree.map(r.attrs().get("toAttrs"));
            for (String a : Tree.strings(rule.get("sameAttributes"))) {
                if (!String.valueOf(from.get(a)).equals(String.valueOf(to.get(a)))) {
                    out.add(Finding.error("TRANSFER_ATTRIBUTE_DIFFERS", a, "transfer across different '" + a + "' is not allowed",
                            Tree.str(Tree.map(rule.get("ruleRefs")).get(a), null)));
                }
            }
            for (String flag : Tree.strings(rule.get("blockedFromFlags"))) {
                if (Tree.bool(from.get(flag), false)) {
                    out.add(Finding.error("TRANSFER_FROM_BLOCKED", flag, "transfer from a line flagged '" + flag + "'",
                            Tree.str(Tree.map(rule.get("ruleRefs")).get(flag), null)));
                }
            }
            for (String flag : Tree.strings(rule.get("blockedToFlags"))) {
                if (Tree.bool(to.get(flag), false)) {
                    out.add(Finding.error("TRANSFER_TO_BLOCKED", flag, "transfer to a line flagged '" + flag + "'",
                            Tree.str(Tree.map(rule.get("ruleRefs")).get(flag), null)));
                }
            }
        }
        String decisionKey = Tree.str(Tree.map(def.get("decisions")).get(r.operation()), null);
        if (decisionKey != null && decisions != null) {
            Map<String, Object> vars = new LinkedHashMap<>(r.attrs());
            vars.put("amount", r.amount());
            vars.put("operation", r.operation());
            for (Map<String, Object> row : decisions.evaluate(r.account().tenant(), decisionKey, vars)) {
                if (!Tree.bool(row.get("allowed"), true)) {
                    out.add(Finding.error(Tree.str(row.get("code"), "DECISION_REFUSED"), decisionKey,
                            Tree.str(row.get("message"), "refused by " + decisionKey), Tree.str(row.get("ruleRef"), null)));
                } else if (row.get("approval") != null) {
                    out.add(Finding.info("APPROVAL_REQUIRED", Tree.str(row.get("approval"), null),
                            Tree.str(row.get("message"), "approval required"), Tree.str(row.get("ruleRef"), null)));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ accounting
    private List<LedgerPorts.AccountingEntry> accounting(LedgerDefinition def, LedgerRequest r) {
        List<LedgerPorts.AccountingEntry> out = new ArrayList<>();
        List<String> bases = Tree.strings(def.get("bases")); // accounting bases in force for this ledger (PFM-054)
        for (Map<String, Object> m : Tree.maps(def.get("accounting"))) {
            if (!r.operation().equals(Tree.str(m.get("event"), ""))) {
                continue;
            }
            if (!bases.isEmpty() && !bases.contains(Tree.str(m.get("basis"), "CASH"))) {
                continue;
            }
            String debit = expand(Tree.str(m.get("debit"), ""), r);
            String credit = expand(Tree.str(m.get("credit"), ""), r);
            BigDecimal amt = r.amount() == null ? BigDecimal.ZERO : r.amount();
            out.add(new LedgerPorts.AccountingEntry(r.account().tenant(), r.reference(), r.operation(),
                    Tree.str(m.get("basis"), "CASH"), debit, credit, amt, r.currency() == null ? def.currency() : r.currency(),
                    convertFunctional(def, r, amt)));
        }
        return out;
    }

    private BigDecimal convertFunctional(LedgerDefinition def, LedgerRequest r, BigDecimal amt) {
        if (r.currency() == null || def.currency() == null || r.currency().equals(def.currency())) {
            return amt;
        }
        return rates.rate(r.currency(), def.currency(), r.rateType() == null ? "OFFICIAL" : r.rateType(),
                r.asOf() == null ? LocalDate.now(clock) : r.asOf()).map(amt::multiply).orElse(null);
    }

    private static String expand(String t, LedgerRequest r) {
        return t.replace("{line}", r.account().line()).replace("{holder}", r.account().holder())
                .replace("{period}", r.account().period()).replace("{sub}", r.account().sub());
    }

    // ------------------------------------------------------------------------------------------------ multi-year, ageing, cash
    /**
     * Multi-year commitment (PFM-021, PFM-036): one atomic posting commits each period's instalment against that
     * period's account, so an outer-year ceiling (the account's total in a later period, e.g. an MTEF ceiling stage)
     * can never be exceeded. {@code schedule} maps period label to amount.
     */
    public Result commitMultiYear(AccountKey firstYear, Map<String, BigDecimal> schedule, String reference, String actor,
                                  Map<String, Object> attrs) {
        LedgerDefinition def = definition(firstYear.tenant(), firstYear.ledger());
        LedgerRequest r = new LedgerRequest("COMMIT", firstYear, null,
                schedule.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add), null, null, reference, null, actor, null,
                false, false, false, attrs);
        List<Finding> findings = new ArrayList<>(policies(def, r));
        if (Finding.anyError(findings)) {
            return new Result(false, false, false, findings.get(0).code(), findings, List.of(), List.of());
        }
        List<Movement> legs = new ArrayList<>();
        BigDecimal guard = guard(def, firstYear.stage());
        schedule.forEach((period, amount) -> legs.add(mv(firstYear.withPeriod(period), def.currency(),
                Map.of(Bucket.COMMITTED, amount), guard, true)));
        return post(def, r, legs, findings);
    }

    /**
     * Payables ageing (PFM-038): open encumbrances (verified, unpaid) grouped by days past their {@code dueDate}
     * attribute into the configured buckets, e.g. {@code [30, 60, 90]} gives 0-30, 31-60, 61-90 and over 90.
     */
    public Map<String, BigDecimal> payablesAgeing(String tenant, String ledger, LocalDate asOf, List<Integer> buckets) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        int prev = 0;
        for (int b : buckets) {
            out.put(prev + "-" + b, BigDecimal.ZERO);
            prev = b + 1;
        }
        out.put(">" + (buckets.isEmpty() ? 0 : buckets.get(buckets.size() - 1)), BigDecimal.ZERO);
        out.put("notDue", BigDecimal.ZERO);
        for (Balance b : store.find(tenant, ledger, null, null, null, null)) {
            if (b.encumbered().signum() <= 0) {
                continue;
            }
            LocalDate due = Tree.date(b.attrs().get("dueDate"), null);
            if (due == null || !due.isBefore(asOf)) {
                out.merge("notDue", b.encumbered(), BigDecimal::add);
                continue;
            }
            long days = java.time.temporal.ChronoUnit.DAYS.between(due, asOf);
            String key = null;
            int lower = 0;
            for (int x : buckets) {
                if (days <= x) {
                    key = lower + "-" + x;
                    break;
                }
                lower = x + 1;
            }
            out.merge(key == null ? ">" + buckets.get(buckets.size() - 1) : key, b.encumbered(), BigDecimal::add);
        }
        return out;
    }

    /**
     * Cash forecast input (PFM-047): the cash still to go out per period from open commitments and encumbrances
     * (an encumbrance's attribute {@code expectedPayment} moves it to that period). Feeds the cash plan.
     */
    public Map<String, BigDecimal> cashForecast(String tenant, String ledger) {
        Map<String, BigDecimal> out = new java.util.TreeMap<>();
        for (Balance b : store.find(tenant, ledger, null, null, null, null)) {
            BigDecimal open = b.committed().add(b.encumbered());
            if (open.signum() > 0) {
                out.merge(Tree.str(b.attrs().get("expectedPayment"), b.key().period()), open, BigDecimal::add);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ freeze, conditions
    public void freeze(FreezeOrder order) {
        control.freeze(order);
    }

    public boolean releaseFreeze(String tenant, String orderId) {
        return control.releaseFreeze(tenant, orderId, clock.instant());
    }

    public List<FreezeOrder> freezes(String tenant) {
        return control.freezes(tenant);
    }

    public LedgerStore store() {
        return store;
    }

    /** Attaches conditions / attributes (availableUntil, dueDate, expiry, flags, source agreement ...) to an account. */
    public Balance setAttributes(AccountKey key, Map<String, Object> attrs) {
        LedgerDefinition def = definition(key.tenant(), key.ledger());
        return store.setState(key, def.currency(), null, attrs);
    }

    // ------------------------------------------------------------------------------------------------ quantity
    /**
     * QUANTITY: issues {@code qty} of an item at a holder (location) across lots ({@code sub}) in the configured
     * order: FIFO (receipt date attribute {@code receivedOn}), FEFO (attribute {@code expiry}) or LIFO. Expired lots
     * are skipped. Each lot leg is guarded, so concurrent issues never take more than is on hand.
     */
    public Result issue(AccountKey itemAtLocation, BigDecimal qty, String reference, LocalDate asOf) {
        LedgerDefinition def = definition(itemAtLocation.tenant(), itemAtLocation.ledger());
        String order = Tree.str(def.get("issueOrder"), "FIFO").toUpperCase();
        List<Balance> lots = new ArrayList<>(store.find(itemAtLocation.tenant(), itemAtLocation.ledger(),
                itemAtLocation.period(), itemAtLocation.line(), itemAtLocation.holder(), itemAtLocation.stage()));
        lots.removeIf(b -> !b.key().line().equals(itemAtLocation.line()) || !b.key().holder().equals(itemAtLocation.holder())
                || b.available().signum() <= 0);
        lots.removeIf(b -> {
            LocalDate exp = Tree.date(b.attrs().get("expiry"), null);
            return exp != null && exp.isBefore(asOf);
        });
        Comparator<Balance> cmp = switch (order) {
            case "FEFO" -> Comparator.comparing(b -> Tree.date(b.attrs().get("expiry"), LocalDate.MAX));
            case "LIFO" -> Comparator.comparing((Balance b) -> Tree.date(b.attrs().get("receivedOn"), LocalDate.MIN)).reversed();
            default -> Comparator.comparing(b -> Tree.date(b.attrs().get("receivedOn"), LocalDate.MIN));
        };
        lots.sort(cmp);
        BigDecimal left = qty;
        List<Movement> legs = new ArrayList<>();
        for (Balance b : lots) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal take = b.available().min(left);
            legs.add(new Movement(b.key(), b.currency(), Map.of(Bucket.CONSUMED, take), BigDecimal.ZERO, true));
            left = left.subtract(take);
        }
        if (left.signum() > 0) {
            return new Result(false, false, false, "INSUFFICIENT_STOCK", List.of(Finding.error("INSUFFICIENT_STOCK",
                    itemAtLocation.toString(), "short by " + left, null)), lots, List.of());
        }
        LedgerRequest r = new LedgerRequest("CONSUME", itemAtLocation, null, qty, null, null, reference, null, null, asOf,
                false, false, false, Map.of("issueOrder", order));
        return post(def, r, legs, new ArrayList<>());
    }

    // ------------------------------------------------------------------------------------------------ period close
    /**
     * Closes a period of availability: for every account of the ledger and period, ANNUAL funds lapse (the unused
     * available is removed and the account becomes EXPIRED), MULTI_YEAR funds lapse only when their
     * {@code availableUntil} has passed, NO_YEAR funds stay open. Open commitments are carried forward to
     * {@code nextPeriod} when the definition says so (otherwise they lapse with the account). Returns the results.
     */
    public List<Result> closePeriod(String tenant, String ledger, String period, String nextPeriod, LocalDate asOf,
                                    String actor) {
        LedgerDefinition def = definition(tenant, ledger);
        List<Result> out = new ArrayList<>();
        if ("NO_YEAR".equals(def.availabilityType())) {
            return out;
        }
        for (Balance b : store.find(tenant, ledger, period, null, null, null)) {
            LocalDate until = Tree.date(b.attrs().get("availableUntil"), null);
            if ("MULTI_YEAR".equals(def.availabilityType()) && until != null && !asOf.isAfter(until)) {
                continue;
            }
            if (Tree.bool(b.attrs().get("nonLapsable"), false)) {
                continue;
            }
            BigDecimal avail = b.available();
            if (avail.signum() > 0) {
                out.add(post(def, new LedgerRequest("LAPSE", b.key(), null, avail, null, null,
                        "lapse:" + period + ":" + b.key().id(), null, actor, asOf, false, false, false, Map.of()),
                        List.of(mv(b.key(), b.currency(), Map.of(Bucket.TOTAL, avail.negate()), null, false)), new ArrayList<>()));
            }
            BigDecimal open = b.committed().add(b.encumbered());
            if (def.carryForwardCommitments() && nextPeriod != null && open.signum() > 0) {
                AccountKey nk = b.key().withPeriod(nextPeriod);
                out.add(post(def, new LedgerRequest("CARRY_FORWARD", b.key(), nk, open, null, null,
                        "carry:" + period + ":" + b.key().id(), null, actor, asOf, false, false, false, Map.of()),
                        List.of(mv(b.key(), b.currency(), Map.of(Bucket.COMMITTED, b.committed().negate(),
                                        Bucket.ENCUMBERED, b.encumbered().negate(), Bucket.TOTAL, open.negate()), null, false),
                                mv(nk, b.currency(), Map.of(Bucket.TOTAL, open, Bucket.COMMITTED, b.committed(),
                                        Bucket.ENCUMBERED, b.encumbered()), null, false)), new ArrayList<>()));
            }
            store.setState(b.key(), b.currency(), Balance.EXPIRED, Map.of("expiredOn", asOf.toString()));
        }
        return out;
    }

    /** Cancels EXPIRED accounts older than the definition's {@code cancelAfterYears} (expired → cancelled lifecycle). */
    public int cancelExpired(String tenant, String ledger, String period, LocalDate asOf) {
        LedgerDefinition def = definition(tenant, ledger);
        int years = Tree.integer(def.availability().get("cancelAfterYears"), 0);
        int n = 0;
        if (years <= 0) {
            return 0;
        }
        for (Balance b : store.find(tenant, ledger, period, null, null, null)) {
            LocalDate exp = Tree.date(b.attrs().get("expiredOn"), null);
            if (Balance.EXPIRED.equals(b.state()) && exp != null && !exp.plusYears(years).isAfter(asOf)) {
                store.setState(b.key(), b.currency(), Balance.CANCELLED, Map.of("cancelledOn", asOf.toString()));
                n++;
            }
        }
        return n;
    }

    /**
     * Formula-derived interim authority (vote on account, continuing resolution): allocates {@code percent} of a
     * basis account's total (e.g. last year's appropriation) to the target account.
     */
    public Result allocateFormula(AccountKey target, AccountKey basis, BigDecimal percent, String reference, String actor) {
        BigDecimal base = store.get(basis).map(Balance::total).orElse(BigDecimal.ZERO);
        BigDecimal amt = base.multiply(percent).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        return apply(new LedgerRequest("ALLOCATE", target, null, amt, null, null, reference, null, actor, null, false, false,
                false, Map.of("formula", "percentOf:" + basis + ":" + percent)));
    }

    /**
     * Revaluation of open commitments held in a transaction currency: posts the functional-currency difference
     * between the booked rate (account attribute {@code bookedRate}) and the current rate, as accounting entries
     * ({@code event: REVALUE} in the mapping), and updates the booked rate.
     */
    public List<LedgerPorts.AccountingEntry> revalue(String tenant, String ledger, String rateType, LocalDate asOf) {
        LedgerDefinition def = definition(tenant, ledger);
        List<LedgerPorts.AccountingEntry> out = new ArrayList<>();
        for (Balance b : store.find(tenant, ledger, null, null, null, null)) {
            if (b.currency() == null || def.currency() == null || b.currency().equals(def.currency())) {
                continue;
            }
            BigDecimal open = b.committed().add(b.encumbered());
            Optional<BigDecimal> now = rates.rate(b.currency(), def.currency(), rateType, asOf);
            BigDecimal booked = Tree.decimal(b.attrs().get("bookedRate"), null);
            if (open.signum() == 0 || now.isEmpty()) {
                continue;
            }
            if (booked != null) {
                BigDecimal diff = open.multiply(now.get().subtract(booked)).setScale(2, RoundingMode.HALF_UP);
                if (diff.signum() != 0) {
                    String gainLoss = Tree.str(def.get("exchangeDifferenceAccount"), "EXCHANGE_DIFFERENCE");
                    out.add(new LedgerPorts.AccountingEntry(tenant, "reval:" + asOf + ":" + b.key().id(), "REVALUE", "ACCRUAL",
                            diff.signum() > 0 ? gainLoss : b.key().line(), diff.signum() > 0 ? b.key().line() : gainLoss,
                            diff.abs(), def.currency(), diff.abs()));
                }
            }
            store.setState(b.key(), b.currency(), null, Map.of("bookedRate", now.get().toPlainString()));
        }
        if (!out.isEmpty()) {
            accountingSink.accept(out);
        }
        return out;
    }

    /** SHADOW mode reconciliation: mirror vs external available per account; returns the differing accounts. */
    public Map<AccountKey, BigDecimal[]> reconcile(String tenant, String ledger, String period) {
        Map<AccountKey, BigDecimal[]> out = new LinkedHashMap<>();
        if (external == null) {
            return out;
        }
        for (Map.Entry<AccountKey, BigDecimal> e : external.balances(tenant, ledger, period).entrySet()) {
            BigDecimal mine = store.get(e.getKey()).map(Balance::available).orElse(BigDecimal.ZERO);
            if (mine.compareTo(e.getValue()) != 0) {
                out.put(e.getKey(), new BigDecimal[]{mine, e.getValue()});
            }
        }
        return out;
    }
}
