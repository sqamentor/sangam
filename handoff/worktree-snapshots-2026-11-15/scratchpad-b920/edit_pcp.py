import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
def rep(old, new):
    global s
    assert s.count(old) == 1, old[:90]
    s = s.replace(old, new)
rep("""/**
 * The one payment-confirmation rule of billing (RM-02, RM-03, owner decision 136).""", """/**
 * The one payment-confirmation rule of billing (RM-02, RM-03, owner decisions 136 and 141).""")
rep(""" *   <li><b>Each unverified acceptance passes the host's {@link UnverifiedPaymentGate}</b>, which checks the actor's
 *       channel permission and records {@code PAYMENT_ACCEPTED_UNVERIFIED}. Without a gate it is refused (fail closed).</li>
 * </ul>""", """ *   <li><b>A trusted channel never confirms a collection</b> ({@link BillingService#collect} refuses it): its payments
 *       are recorded only by {@link UnverifiedPayments}, by a server-authenticated caller holding the scoped
 *       ACCEPT_UNVERIFIED action, pending until verified (decision 141). The trusted list is the outer bound of the
 *       channels {@link UnverifiedPaymentPolicy} can enable.</li>
 * </ul>""")
rep("""    /** The audit event of an unverified acceptance (SANGAM.md X.4.0.3, decision 136). */
    public static final String PAYMENT_ACCEPTED_UNVERIFIED = "PAYMENT_ACCEPTED_UNVERIFIED";

    /**
     * The host's check and record of one unverified acceptance: it throws when the actor does not hold the channel
     * permission, and otherwise records {@value #PAYMENT_ACCEPTED_UNVERIFIED} before the collection is allocated.
     */
    @FunctionalInterface
    public interface UnverifiedPaymentGate {
        void accept(String tenant, String channel, String paymentRef, Money amount, String actor);
    }

    private final Set<String> trustedChannels;
    private final UnverifiedPaymentGate gate;

    private PaymentConfirmationPolicy(Set<String> trustedChannels, UnverifiedPaymentGate gate) {
        this.trustedChannels = Set.copyOf(trustedChannels);
        this.gate = gate;
    }

    /** No trusted channel: every collection is verified by its rail. */
    public static PaymentConfirmationPolicy verifiedOnly() {
        return new PaymentConfirmationPolicy(Set.of(), null);
    }""", """    private final Set<String> trustedChannels;

    private PaymentConfirmationPolicy(Set<String> trustedChannels) {
        this.trustedChannels = Set.copyOf(trustedChannels);
    }

    /** No trusted channel: every collection is verified by its rail. */
    public static PaymentConfirmationPolicy verifiedOnly() {
        return new PaymentConfirmationPolicy(Set.of());
    }""")
rep("""     * @param production         whether the installation is in production ({@code ProductionMode})
     * @param gate               the host's channel-permission and audit gate (null: unverified acceptance refused)
     * @throws""", """     * @param production         whether the installation is in production ({@code ProductionMode})
     * @throws""")
rep("""                                                              boolean production, UnverifiedPaymentGate gate) {""",
    """                                                              boolean production) {""")
rep("""        return new PaymentConfirmationPolicy(trusted, gate);""", """        return new PaymentConfirmationPolicy(trusted);""")
i = s.index("    /**\n     * Accepts {@code amount} unverified")
j = s.index("    /**\n     * The amount a rail confirms")
s = s[:i] + s[j:]
open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ok")
