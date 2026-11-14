import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
def rep(old, new):
    global s
    assert s.count(old) == 1, old[:80]
    s = s.replace(old, new)
rep(""" * <p><b>Payment confirmation fails closed (RM-02, RM-03, owner decision 136).</b> A collection is recorded only for an
 * amount its rail confirmed through the {@link PaymentPort} ({@link PaymentConfirmationPolicy#confirmedByRail}), or,
 * on a channel the server configuration trusts, through the policy's unverified-acceptance gate (channel permission
 * and {@code PAYMENT_ACCEPTED_UNVERIFIED}). Without a bound payment port every collection is refused.
 */""", """ * <p><b>Payment confirmation fails closed (RM-02, RM-03, owner decisions 136 and 141).</b> {@link #collect} records
 * only an amount its rail confirmed through the {@link PaymentPort} ({@link PaymentConfirmationPolicy#confirmedByRail});
 * without a bound payment port every collection is refused. A channel the server configuration trusts is refused here
 * on every path (connector, pack step, job): its payments are recorded only by {@link UnverifiedPayments}, pending
 * ({@code PAYMENT_ACCEPTED_UNVERIFIED}) with nothing posted, and allocated through {@link #allocateVerified} once
 * VERIFIED.
 */""")
rep("""    public Receipt collect(String tenant, String ledgerId, String consumer, String channel, String paymentRef,
                           Money amount, String actor) {
        Money confirmed = confirm(tenant, channel, paymentRef, amount, actor);
        if (confirmed != null && !confirmed.currencyCode().equals(amount.currencyCode())) {
            throw new IllegalStateException("payment " + paymentRef + " confirmed in " + confirmed.currencyCode());
        }
        if (confirmed == null || confirmed.signum() <= 0) {
            throw new IllegalStateException("payment not confirmed: " + paymentRef);
        }
        String receiptKey""", """    public Receipt collect(String tenant, String ledgerId, String consumer, String channel, String paymentRef,
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
        String receiptKey""")
rep("""    /**
     * The confirmed amount of a reported collection (RM-02, RM-03): no payment port refuses; a trusted channel goes
     * through the policy's gate; every other channel needs its rail's confirmation (null: not confirmed).
     */
    private Money confirm(String tenant, String channel, String paymentRef, Money amount, String actor) {
        java.util.Objects.requireNonNull(amount, "amount");
        if (payments == null) {
            throw new IllegalStateException("no payment port is bound: payment " + paymentRef
                    + " cannot be confirmed and is not recorded");
        }
        if (confirmation.trusts(channel)) {
            return confirmation.acceptUnverified(tenant, channel, paymentRef, amount, actor);
        }
        return payments.confirm(tenant, channel, paymentRef, amount);
    }""", """    /**
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
    }""")
rep("""     * @param confirmation the trusted channels and their gate (decision 136)""",
    """     * @param confirmation the trusted channels (decisions 136 and 141: refused by {@link #collect})""")
rep("""    /** With no trusted channel ({@link PaymentConfirmationPolicy#verifiedOnly}): every collection is rail-verified. */""",
    """    /** With no trusted channel ({@link PaymentConfirmationPolicy#verifiedOnly}): every collection is rail-verified. */""")
open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ok")
