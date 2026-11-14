// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.sangam.billing.UnverifiedPayment.Resolution;
import org.sangam.billing.UnverifiedPayment.State;
import org.sangam.billing.UnverifiedPaymentAuthority.ActorType;
import org.sangam.billing.UnverifiedPaymentAuthority.Caller;
import org.sangam.billing.UnverifiedPaymentException.Code;
import org.sangam.core.money.Money;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The application service of unverified payments (RM-03, owner decisions 141 and 193-201), plain Java, the one
 * boundary every host path uses (REST, internal calls, jobs). It is the only way a payment on a trusted channel is
 * recorded: {@link BillingService#collect} refuses those channels, so a pack step or connector call can never accept one.
 *
 * <ul>
 *   <li><b>Who (decision 201).</b> Every acceptance and resolution needs a {@link Caller} that a host adapter built from
 *       a server-authenticated identity, of the same tenant, signed in as staff ({@link ActorType#STAFF}: API keys,
 *       service and system identities are refused), holding the explicit scoped action
 *       ({@link UnverifiedPaymentAuthority}) on (tenant, office, channel). Only the rail reconciliation job
 *       ({@link #reconcile}) resolves without a caller, as {@value #RECONCILIATION}.</li>
 *   <li><b>Which channels.</b> Only trusted channels whose operating policy is fully resolved
 *       ({@link UnverifiedPaymentPolicy}); otherwise disabled.</li>
 *   <li><b>What is required.</b> Reason, evidence reference, payment details (paid-at time at least), currency of the
 *       policy, amount within the policy limit; an audit event {@value #PAYMENT_ACCEPTED_UNVERIFIED} with the record.</li>
 *   <li><b>Pending means nothing moves.</b> {@link State#PAYMENT_ACCEPTED_UNVERIFIED} posts nothing to the ledger: the
 *       demand stays open, nothing is released, cleared or disbursed. Only VERIFIED allocates the payment, through
 *       the same receipt path as a rail-confirmed collection.</li>
 *   <li><b>Accrual (decision 195).</b> While pending, penalty and interest on the part of the arrears the payment covers
 *       stop from its acceptance ({@link BillingService#levyPenalties}, {@link AccrualPauses}). VERIFIED forgives the
 *       paused accrual; REJECTED and REVERSED levy it after all, audited as {@value #PAYMENT_UNVERIFIED_ACCRUAL_RESUMED}
 *       (an interrupted resumption is completed by {@link #reconcile}).</li>
 *   <li><b>Resolution (decisions 194 and 196).</b> Rail reconciliation ({@link #reconcile}) is the normal path. A manual
 *       {@link #resolve} is maker-checker always: the resolver is never the acceptor. A manual VERIFIED needs the
 *       channel's verification reference and an evidence reference and has its own audit event
 *       {@value #PAYMENT_UNVERIFIED_MANUALLY_VERIFIED}; REJECTED and REVERSED write
 *       {@value #PAYMENT_UNVERIFIED_RESOLVED}. State and authorisation are rechecked on the locked row inside the
 *       transition, so a second or concurrent resolution is refused.</li>
 *   <li><b>Ageing.</b> {@link #ageing}: pending payments older than their channel's alert age.</li>
 * </ul>
 */
public final class UnverifiedPayments {

    /** The pending state and the audit event of an acceptance (SANGAM.md X.4.0.3). */
    public static final String PAYMENT_ACCEPTED_UNVERIFIED = "PAYMENT_ACCEPTED_UNVERIFIED";

    /** The audit event of a rail VERIFIED and of a REJECTED or REVERSED resolution on the record's chain. */
    public static final String PAYMENT_UNVERIFIED_RESOLVED = "PAYMENT_UNVERIFIED_RESOLVED";

    /** The audit event of a manual VERIFIED (decision 196): its evidence and checker on the record's chain. */
    public static final String PAYMENT_UNVERIFIED_MANUALLY_VERIFIED = "PAYMENT_UNVERIFIED_MANUALLY_VERIFIED";

    /** The audit event of the levy of the accrual a REJECTED or REVERSED payment had paused (decision 195). */
    public static final String PAYMENT_UNVERIFIED_ACCRUAL_RESUMED = "PAYMENT_UNVERIFIED_ACCRUAL_RESUMED";

    /** The resolving principal of a rail reconciliation (the rail is the verification, no person resolves it). */
    public static final String RECONCILIATION = "system:reconciliation";

    /** An acceptance request. The tenant and the actor are not in it: both come from the {@link Caller}. */
    public record AcceptRequest(String ledgerId, String consumer, String office, String channel, String paymentRef,
                                Money amount, Instant paidAt, String payerName, String instrument,
                                Map<String, String> details, String reason, String evidenceRef) {
    }

    /** The outcome of reconciling one record. */
    public record Reconciled(String id, String outcome, String detail) {
    }

    private final BillingService billing;
    private final UnverifiedPaymentPolicy policy;
    private final UnverifiedPaymentAuthority authority;
    private final UnverifiedPaymentStore store;
    private final BillingService.PaymentPort rails;
    private final Clock clock;

    /**
     * @param billing   allocates a VERIFIED payment (its receipt path) and levies resumed accrual; its
     *                  {@link AccrualPauses} must be {@code store}, so the levy and the resolutions see the same pauses
     * @param policy    the per-channel operating policy (only trusted channels can be enabled)
     * @param authority the scoped authorisation (null: {@link UnverifiedPaymentAuthority#NONE}, all refused)
     * @param store     records, accrual pauses and their audit events
     * @param rails     the channels' rails for {@link #reconcile} (null: nothing is verified by reconciliation)
     * @throws IllegalStateException when {@code billing} pauses accrual elsewhere than in {@code store}
     */
    public UnverifiedPayments(BillingService billing, UnverifiedPaymentPolicy policy,
                              UnverifiedPaymentAuthority authority, UnverifiedPaymentStore store,
                              BillingService.PaymentPort rails, Clock clock) {
        this.billing = Objects.requireNonNull(billing, "billing");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.authority = authority == null ? UnverifiedPaymentAuthority.NONE : authority;
        this.store = Objects.requireNonNull(store, "store");
        this.rails = rails;
        this.clock = Objects.requireNonNull(clock, "clock");
        if (billing.accrualPauses() != store) {
            throw new IllegalStateException("the billing service must pause accrual in the unverified payment store"
                    + " (owner decision 195): build it with the same store");
        }
    }

    /** Accepts a payment unverified: state {@value #PAYMENT_ACCEPTED_UNVERIFIED}, nothing posted. */
    public UnverifiedPayment accept(Caller caller, String tenant, AcceptRequest r) {
        requireCaller(caller, tenant);
        Objects.requireNonNull(r, "request");
        UnverifiedPaymentPolicy.ChannelPolicy p = policy.channel(r.channel()).orElseThrow(() ->
                new UnverifiedPaymentException(Code.CHANNEL_NOT_ENABLED, "channel " + r.channel()
                        + " is not enabled for unverified acceptance (not a trusted channel, or its operating policy"
                        + " is unresolved)"));
        for (String[] f : new String[][]{{"ledgerId", r.ledgerId()}, {"consumer", r.consumer()},
                {"office", r.office()}, {"paymentRef", r.paymentRef()}, {"reason", r.reason()},
                {"evidenceRef", r.evidenceRef()}}) {
            if (f[1] == null || f[1].isBlank()) {
                throw new UnverifiedPaymentException(Code.INVALID_REQUEST, f[0] + " is required");
            }
        }
        if (r.paidAt() == null) {
            throw new UnverifiedPaymentException(Code.INVALID_REQUEST, "paidAt (payment details) is required");
        }
        if (r.amount() == null || r.amount().signum() <= 0) {
            throw new UnverifiedPaymentException(Code.INVALID_REQUEST, "amount must be greater than zero");
        }
        if (!p.currency().equals(r.amount().currencyCode())) {
            throw new UnverifiedPaymentException(Code.INVALID_REQUEST, "channel " + r.channel() + " accepts "
                    + p.currency() + ", not " + r.amount().currencyCode());
        }
        if (p.maxAmount() != null && r.amount().compareTo(p.maxAmount()) > 0) {
            throw new UnverifiedPaymentException(Code.LIMIT_EXCEEDED, "amount " + r.amount().toPlainString()
                    + " exceeds the limit " + p.maxAmount().toPlainString() + " of channel " + r.channel()
                    + " (policy " + p.version() + ")");
        }
        requirePermit(caller, UnverifiedPaymentAuthority.ACCEPT_UNVERIFIED, tenant, r.office(), r.channel());
        Instant now = clock.instant();
        UnverifiedPayment rec = new UnverifiedPayment(tenant, UUID.randomUUID().toString(), r.ledgerId(),
                r.consumer(), r.office(), r.channel(), r.paymentRef(), r.amount(), r.paidAt(), r.payerName(),
                r.instrument(), r.details(), r.reason(), r.evidenceRef(), p.version(), p.downstreamPermitted(),
                caller.principalId(), now, State.PAYMENT_ACCEPTED_UNVERIFIED, 1, null, null);
        if (!store.insert(rec, PAYMENT_ACCEPTED_UNVERIFIED, acceptedAudit(rec, caller))) {
            String existing = store.findByRef(tenant, r.channel(), r.paymentRef()).map(UnverifiedPayment::id)
                    .orElse("?");
            throw new UnverifiedPaymentException(Code.DUPLICATE, "payment " + r.paymentRef() + " on channel "
                    + r.channel() + " is already accepted as " + existing);
        }
        return rec;
    }

    /**
     * Resolves a pending payment by hand. The state, the caller's authorisation and maker-checker (the resolver is not
     * the acceptor, decision 194) are rechecked on the locked row. VERIFIED (decision 196) needs the channel's
     * {@code verificationRef} and an {@code evidenceRef}, writes {@value #PAYMENT_UNVERIFIED_MANUALLY_VERIFIED},
     * forgives the paused accrual and allocates the payment through the receipt path. REJECTED and REVERSED write
     * {@value #PAYMENT_UNVERIFIED_RESOLVED} and then levy the paused accrual ({@value #PAYMENT_UNVERIFIED_ACCRUAL_RESUMED}).
     *
     * @param evidenceRef the evidence of a manual VERIFIED (required for it; optional, kept, for the others)
     */
    public UnverifiedPayment resolve(Caller caller, String tenant, String id, State outcome, String reason,
                                     String verificationRef, String evidenceRef) {
        requireCaller(caller, tenant);
        if (outcome == null || outcome.pending()) {
            throw new UnverifiedPaymentException(Code.INVALID_REQUEST, "outcome must be VERIFIED, REJECTED or REVERSED");
        }
        if (reason == null || reason.isBlank()) {
            throw new UnverifiedPaymentException(Code.INVALID_REQUEST, "reason is required");
        }
        String evidence = evidenceRef == null || evidenceRef.isBlank() ? null : evidenceRef;
        if (outcome == State.VERIFIED) {
            if (verificationRef == null || verificationRef.isBlank()) {
                throw new UnverifiedPaymentException(Code.INVALID_REQUEST,
                        "VERIFIED needs the channel's verificationRef");
            }
            if (evidence == null) {
                throw new UnverifiedPaymentException(Code.INVALID_REQUEST, "a manual VERIFIED needs an evidenceRef"
                        + " (owner decision 196: rail reconciliation is the normal path)");
            }
        }
        UnverifiedPayment done = store.transition(tenant, id, locked -> {
            requirePending(locked);
            requirePermit(caller, UnverifiedPaymentAuthority.RESOLVE_UNVERIFIED, tenant, locked.office(),
                    locked.channel());
            if (caller.principalId().equals(locked.acceptedBy())) {
                throw new UnverifiedPaymentException(Code.NOT_PERMITTED, "maker-checker: " + locked.acceptedBy()
                        + " accepted payment " + id + " and cannot also resolve it");
            }
            Resolution r = new Resolution(outcome, reason, verificationRef, evidence, caller.principalId(),
                    clock.instant());
            UnverifiedPaymentStore.Transition t = resolution(locked, r);
            if (outcome != State.VERIFIED) {
                return t;
            }
            Map<String, Object> a = new LinkedHashMap<>(t.audit());
            put(a, "seat_id", caller.seatId());
            return new UnverifiedPaymentStore.Transition(t.next(), PAYMENT_UNVERIFIED_MANUALLY_VERIFIED, a);
        });
        if (outcome == State.VERIFIED) {
            return allocate(done);
        }
        resumeAccrual(done, caller.principalId());
        return done;
    }

    /**
     * Reconciles the tenant's pending payments against their channels' rails: a rail confirmation of the exact amount
     * resolves VERIFIED (resolver {@value #RECONCILIATION}, the rail's evidence) and allocates it; anything else stays
     * pending and is reported. VERIFIED payments whose allocation was interrupted are allocated again (idempotent per
     * payment reference), and REJECTED or REVERSED ones whose paused accrual was not yet levied are levied (idempotent
     * per pause). One record's failure does not stop the others; each outcome is returned.
     */
    public List<Reconciled> reconcile(String tenant, int limit) {
        List<Reconciled> out = new ArrayList<>();
        for (UnverifiedPayment v : store.verifiedNotAllocated(tenant, limit)) {
            out.add(attempt(v, () -> allocate(v).receiptNo() == null ? "NOT_ALLOCATED" : "ALLOCATED"));
        }
        for (UnverifiedPayment r : store.resumptionDue(tenant, limit)) {
            out.add(attempt(r, () -> resumeAccrual(r, r.resolution().resolvedBy()) ? "ACCRUAL_RESUMED"
                    : "NOTHING_TO_RESUME"));
        }
        for (UnverifiedPayment p : store.list(tenant, State.PAYMENT_ACCEPTED_UNVERIFIED, limit)) {
            out.add(attempt(p, () -> {
                if (rails == null) {
                    return "NO_RAIL";
                }
                Money confirmed = rails.confirm(tenant, p.channel(), p.paymentRef(), p.amount());
                if (confirmed == null) {
                    return "NOT_CONFIRMED";
                }
                if (!confirmed.equals(p.amount())) {
                    return "AMOUNT_MISMATCH rail " + confirmed.toPlainString() + " " + confirmed.currencyCode();
                }
                UnverifiedPayment done = store.transition(tenant, p.id(), locked -> {
                    requirePending(locked);
                    if (locked.version() != p.version()) {
                        throw new UnverifiedPaymentException(Code.INVALID_TRANSITION, "payment " + p.id()
                                + " changed while its rail was asked");
                    }
                    return resolution(locked, new Resolution(State.VERIFIED, "confirmed by the channel's rail",
                            "rail:" + p.channel() + ":" + p.paymentRef(), null, RECONCILIATION, clock.instant()));
                });
                allocate(done);
                return "VERIFIED";
            }));
        }
        return out;
    }

    /** Pending payments older than their channel's alert age (a channel whose policy became unresolved: always). */
    public List<UnverifiedPayment> ageing(String tenant, int limit) {
        Instant now = clock.instant();
        List<UnverifiedPayment> out = new ArrayList<>();
        for (UnverifiedPayment p : store.list(tenant, State.PAYMENT_ACCEPTED_UNVERIFIED, limit)) {
            Optional<UnverifiedPaymentPolicy.ChannelPolicy> cp = policy.channel(p.channel());
            if (cp.isEmpty() || p.acceptedAt().plus(cp.get().ageingAlertAfter()).isBefore(now)) {
                out.add(p);
            }
        }
        return out;
    }

    /** One record of the caller's tenant. */
    public Optional<UnverifiedPayment> find(String tenant, String id) {
        return store.find(tenant, id);
    }

    /** A filtered page of the tenant's records, every filter applied before paging, with the total match count. */
    public UnverifiedPaymentStore.Slice list(String tenant, UnverifiedPaymentStore.Query query) {
        return store.query(tenant, Objects.requireNonNull(query, "query"));
    }

    /** Every accrual pause of a payment of the tenant (forgiven, levied or open). */
    public List<AccrualPauses.Pause> pauses(String tenant, String id) {
        return store.pausesOf(tenant, id);
    }

    /**
     * Tenants with reconciliation work (pending, VERIFIED and not yet allocated, or paused accrual not yet levied), for
     * the host's periodic job.
     */
    public List<String> tenantsWithWork(int limit) {
        return store.tenantsWithWork(limit);
    }

    /** The time source of ages and resolutions. */
    public Instant now() {
        return clock.instant();
    }

    private UnverifiedPayment allocate(UnverifiedPayment verified) {
        BillingService.Receipt receipt = billing.allocateVerified(verified.tenant(), verified.ledgerId(),
                verified.consumer(), verified.channel(), verified.paymentRef(), verified.amount(),
                verified.resolution().resolvedBy());
        store.receipted(verified.tenant(), verified.id(), receipt.number());
        return new UnverifiedPayment(verified.tenant(), verified.id(), verified.ledgerId(), verified.consumer(),
                verified.office(), verified.channel(), verified.paymentRef(), verified.amount(), verified.paidAt(),
                verified.payerName(), verified.instrument(), verified.details(), verified.reason(),
                verified.evidenceRef(), verified.policyVersion(), verified.downstreamPermitted(),
                verified.acceptedBy(), verified.acceptedAt(), verified.state(), verified.version(),
                verified.resolution(), receipt.number());
    }

    /**
     * Levies what a REJECTED or REVERSED payment had paused (the ledger postings are idempotent per pause), then marks
     * the pauses levied with {@value #PAYMENT_UNVERIFIED_ACCRUAL_RESUMED}. Pauses are written only while the payment is
     * pending (under its row lock), so after the resolution committed none can be added.
     *
     * @return whether anything was left to levy
     */
    private boolean resumeAccrual(UnverifiedPayment resolved, String actor) {
        List<AccrualPauses.Pause> todo = store.pausesToResume(resolved.tenant(), resolved.id());
        if (todo.isEmpty()) {
            return false;
        }
        billing.resumeAccrual(resolved.tenant(), resolved.id(), todo, actor);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("payment_id", resolved.id());
        a.put("outcome", resolved.state().name());
        a.put("ledger", resolved.ledgerId());
        a.put("consumer", resolved.consumer());
        a.put("resumed", pauseView(todo));
        a.put("actor", actor);
        store.markResumed(resolved.tenant(), resolved.id(), clock.instant(), PAYMENT_UNVERIFIED_ACCRUAL_RESUMED, a);
        return true;
    }

    /** The audit view of accrual pauses (also the {@code accrual_forgiven} of a VERIFIED, written by the store). */
    static List<Map<String, Object>> pauseView(List<AccrualPauses.Pause> pauses) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AccrualPauses.Pause p : pauses) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("account", p.baseAccountId());
            m.put("levy_date", p.asOf().toString());
            m.put("paused_from", p.pausedFrom().toString());
            m.put("penalty_account", p.penaltyAccount().id());
            m.put("amount", p.suppressed().toPlainString());
            m.put("currency", p.suppressed().currencyCode());
            out.add(m);
        }
        return out;
    }

    private Reconciled attempt(UnverifiedPayment p, java.util.function.Supplier<String> work) {
        try {
            return new Reconciled(p.id(), work.get(), null);
        } catch (RuntimeException e) {
            return new Reconciled(p.id(), "FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static UnverifiedPaymentStore.Transition resolution(UnverifiedPayment locked, Resolution r) {
        UnverifiedPayment next = locked.resolved(r);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("payment_id", locked.id());
        a.put("channel", locked.channel());
        a.put("payment_ref", locked.paymentRef());
        a.put("office", locked.office());
        a.put("amount", locked.amount().toPlainString());
        a.put("currency", locked.amount().currencyCode());
        a.put("outcome", r.outcome().name());
        a.put("reason", r.reason());
        put(a, "verification_ref", r.verificationRef());
        put(a, "evidence_ref", r.evidenceRef());
        a.put("actor", r.resolvedBy());
        a.put("accepted_by", locked.acceptedBy());
        return new UnverifiedPaymentStore.Transition(next, PAYMENT_UNVERIFIED_RESOLVED, a);
    }

    private static Map<String, Object> acceptedAudit(UnverifiedPayment p, Caller caller) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("payment_id", p.id());
        a.put("ledger", p.ledgerId());
        a.put("consumer", p.consumer());
        a.put("channel", p.channel());
        a.put("office", p.office());
        a.put("payment_ref", p.paymentRef());
        a.put("amount", p.amount().toPlainString());
        a.put("currency", p.amount().currencyCode());
        a.put("paid_at", p.paidAt().toString());
        put(a, "payer_name", p.payerName());
        put(a, "instrument", p.instrument());
        a.put("details", p.details());
        a.put("reason", p.reason());
        a.put("evidence_ref", p.evidenceRef());
        a.put("policy_version", p.policyVersion());
        a.put("actor", caller.principalId());
        put(a, "seat_id", caller.seatId());
        return a;
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    private static void requireCaller(Caller caller, String tenant) {
        if (caller == null) {
            throw new UnverifiedPaymentException(Code.NOT_PERMITTED,
                    "unverified payments need a server-authenticated caller (no pack, connector or job may act)");
        }
        if (caller.actorType() != ActorType.STAFF) {
            throw new UnverifiedPaymentException(Code.NOT_PERMITTED, "only signed-in staff can accept or resolve an"
                    + " unverified payment (owner decision 201); " + caller.principalId() + " is "
                    + caller.actorType());
        }
        if (tenant == null || !tenant.equals(caller.tenant())) {
            throw new UnverifiedPaymentException(Code.NOT_PERMITTED, "caller " + caller.principalId() + " of tenant "
                    + caller.tenant() + " cannot act in tenant " + tenant);
        }
    }

    private static void requirePending(UnverifiedPayment locked) {
        if (!locked.state().pending()) {
            throw new UnverifiedPaymentException(Code.INVALID_TRANSITION, "payment " + locked.id() + " is already "
                    + locked.state());
        }
    }

    private void requirePermit(Caller caller, String action, String tenant, String office, String channel) {
        boolean ok;
        try {
            ok = authority.permits(caller, action, tenant, office, channel);
        } catch (RuntimeException failed) {
            throw new UnverifiedPaymentException(Code.NOT_PERMITTED, action + " could not be evaluated for "
                    + caller.principalId() + ": refused", failed);
        }
        if (!ok) {
            throw new UnverifiedPaymentException(Code.NOT_PERMITTED, caller.principalId() + " does not hold " + action
                    + " on channel " + channel + ", office " + office + ", tenant " + tenant);
        }
    }
}
