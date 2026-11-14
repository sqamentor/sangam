// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.sangam.core.money.Money;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The one payment-confirmation rule of billing (RM-02, RM-03, owner decisions 136 and 141). Plain Java: every host binds the
 * same rule, and the host's payment port only carries connector results to it.
 *
 * <ul>
 *   <li><b>A rail result confirms only when it says so.</b> {@link #confirmedByRail}: the rail must report a success
 *       status <i>and</i> a parseable amount in the collection currency. No status, a non-success status, no amount
 *       or an amount that does not parse means "not confirmed" (null), never "the reported amount".</li>
 *   <li><b>No trusted channel by default.</b> A channel is accepted unverified only when the server configuration lists
 *       it ({@code sangam.billing.trusted-channels}, default empty). A request names a channel, but whether that channel
 *       is trusted is decided here from the configuration alone, so a request-supplied channel can never widen trust.</li>
 *   <li><b>Production needs an explicit acknowledgement</b> of a non-empty trusted list
 *       ({@link #fromConfiguration}).</li>
 *   <li><b>A trusted channel never confirms a collection</b> ({@link BillingService#collect} refuses it): its payments
 *       are recorded only by {@link UnverifiedPayments}, by a server-authenticated caller holding the scoped
 *       ACCEPT_UNVERIFIED action, pending until verified (decision 141). The trusted list is the outer bound of the
 *       channels {@link UnverifiedPaymentPolicy} can enable.</li>
 * </ul>
 */
public final class PaymentConfirmationPolicy {

    /** The server configuration key of the trusted channels (comma list, default empty). */
    public static final String TRUSTED_CHANNELS = "sangam.billing.trusted-channels";

    /** The server configuration key that acknowledges a non-empty trusted list in production. */
    public static final String TRUSTED_CHANNELS_ACKNOWLEDGED = "sangam.billing.trusted-channels-acknowledged";

    private final Set<String> trustedChannels;

    private PaymentConfirmationPolicy(Set<String> trustedChannels) {
        this.trustedChannels = Set.copyOf(trustedChannels);
    }

    /** No trusted channel: every collection is verified by its rail. */
    public static PaymentConfirmationPolicy verifiedOnly() {
        return new PaymentConfirmationPolicy(Set.of());
    }

    /**
     * The policy from the server configuration.
     *
     * @param trustedChannelsCsv the value of {@value #TRUSTED_CHANNELS} (null or blank: none)
     * @param acknowledged       the value of {@value #TRUSTED_CHANNELS_ACKNOWLEDGED}
     * @param production         whether the installation is in production ({@code ProductionMode})
     * @throws IllegalStateException in production, for a non-empty trusted list without the acknowledgement
     */
    public static PaymentConfirmationPolicy fromConfiguration(String trustedChannelsCsv, boolean acknowledged,
                                                              boolean production) {
        Set<String> trusted = parse(trustedChannelsCsv);
        if (production && !trusted.isEmpty() && !acknowledged) {
            throw new IllegalStateException(TRUSTED_CHANNELS + "=" + String.join(",", trusted)
                    + " accepts payments on those channels with no verification; in production set "
                    + TRUSTED_CHANNELS_ACKNOWLEDGED + "=true to acknowledge it, or leave the list empty (decision 136)");
        }
        return new PaymentConfirmationPolicy(trusted);
    }

    /** The configured trusted channels (exact names, as {@code sangam.billing.channels.<channel>} uses them). */
    public Set<String> trustedChannels() {
        return trustedChannels;
    }

    /** Whether the server configuration trusts {@code channel} (exact name). */
    public boolean trusts(String channel) {
        return channel != null && trustedChannels.contains(channel);
    }

    /**
     * The amount a rail confirms, or null when it does not confirm (RM-02).
     *
     * @param successStatus whether the rail reported a success status (the host maps the rail's status word with the
     *                      platform's one payment-status vocabulary); false also when the rail reported no status
     * @param railAmount    the rail's {@code amount} output (a decimal string, R33), may be null
     * @param reported      the collection as reported (its currency is the collection currency)
     * @return the rail amount in the collection currency, or null (not confirmed)
     */
    public static Money confirmedByRail(boolean successStatus, Object railAmount, Money reported) {
        Objects.requireNonNull(reported, "reported");
        if (!successStatus || railAmount == null || railAmount.toString().isBlank()) {
            return null;
        }
        Money confirmed;
        try {
            confirmed = Money.parse(railAmount.toString().trim(), reported.currencyCode());
        } catch (RuntimeException notAnAmount) {
            return null;    // a sub-unit or malformed figure confirms nothing
        }
        return confirmed.signum() > 0 ? confirmed : null;
    }

    private static Set<String> parse(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(out::add);
        return out;
    }
}
