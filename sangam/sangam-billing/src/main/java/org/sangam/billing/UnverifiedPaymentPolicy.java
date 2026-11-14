// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.sangam.core.money.Money;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The operating policy of unverified acceptance, per channel (RM-03, owner decision 141). A channel is enabled only
 * when it is a trusted channel ({@link PaymentConfirmationPolicy#TRUSTED_CHANNELS}) <i>and</i> every required key of
 * its policy is set and valid; otherwise it is disabled and {@link #unresolved()} says why. Keys, under
 * {@value #PREFIX}{@code <channel>.}:
 * <ul>
 *   <li>{@code policy-version} (required): recorded on every acceptance;</li>
 *   <li>{@code currency} (required): ISO 4217; an acceptance in another currency is refused;</li>
 *   <li>{@code max-amount} (required): a decimal limit per payment, or {@code NONE} when the approved policy sets no
 *       amount limit (stated explicitly, never assumed);</li>
 *   <li>{@code maker-checker} (required, {@code true}): the resolution is made by another principal than the one who
 *       accepted the payment (owner decision 194: always at resolution); {@code false} or anything else leaves the
 *       channel disabled, so a configuration can state the policy but never switch it off;</li>
 *   <li>{@code ageing-alert-after} (required, ISO-8601 duration such as {@code P3D}): a pending payment older than this
 *       is reported by {@link UnverifiedPayments#ageing};</li>
 *   <li>{@code downstream-permitted} (optional, comma list, default none): actions the policy permits while the payment
 *       is pending. Nothing is released, cleared or disbursed while pending unless listed here.</li>
 * </ul>
 */
public final class UnverifiedPaymentPolicy {

    public static final String PREFIX = "sangam.billing.unverified.channels.";

    /** The resolved policy of one channel. {@code maxAmount} null: the approved policy states no amount limit. */
    public record ChannelPolicy(String channel, String version, String currency, Money maxAmount,
                                boolean makerChecker, Duration ageingAlertAfter, List<String> downstreamPermitted) {
        public ChannelPolicy {
            downstreamPermitted = List.copyOf(downstreamPermitted);
        }
    }

    private final Map<String, ChannelPolicy> channels;
    private final Map<String, String> unresolved;

    private UnverifiedPaymentPolicy(Map<String, ChannelPolicy> channels, Map<String, String> unresolved) {
        this.channels = Map.copyOf(channels);
        this.unresolved = Map.copyOf(unresolved);
    }

    /** No channel enabled. */
    public static UnverifiedPaymentPolicy none() {
        return new UnverifiedPaymentPolicy(Map.of(), Map.of());
    }

    /**
     * Reads the policy of each trusted channel.
     *
     * @param trustedChannels the server's trusted channels (only these can ever be enabled)
     * @param property        the configuration lookup (null or blank: not set)
     */
    public static UnverifiedPaymentPolicy from(Set<String> trustedChannels, UnaryOperator<String> property) {
        Map<String, ChannelPolicy> ok = new LinkedHashMap<>();
        Map<String, String> bad = new LinkedHashMap<>();
        for (String ch : trustedChannels) {
            List<String> problems = new ArrayList<>();
            String p = PREFIX + ch + ".";
            String version = required(property, p + "policy-version", problems);
            String currency = required(property, p + "currency", problems);
            String max = required(property, p + "max-amount", problems);
            String mc = required(property, p + "maker-checker", problems);
            String age = required(property, p + "ageing-alert-after", problems);
            Money maxAmount = null;
            Duration ageing = null;
            if (currency != null) {
                try {
                    Money.currency(currency);
                } catch (RuntimeException e) {
                    problems.add(p + "currency is not an ISO 4217 code: " + currency);
                    currency = null;
                }
            }
            if (max != null && currency != null && !"NONE".equals(max)) {
                try {
                    maxAmount = Money.parse(max, currency);
                    if (maxAmount.signum() <= 0) {
                        problems.add(p + "max-amount must be greater than zero or NONE");
                    }
                } catch (RuntimeException e) {
                    problems.add(p + "max-amount is not a decimal in " + currency + " or NONE: " + max);
                }
            }
            if ("false".equals(mc)) {
                problems.add(p + "maker-checker cannot be false: the resolver always differs from the acceptor"
                        + " (owner decision 194)");
            } else if (mc != null && !"true".equals(mc)) {
                problems.add(p + "maker-checker must be true: " + mc);
            }
            if (age != null) {
                try {
                    ageing = Duration.parse(age);
                    if (ageing.isNegative() || ageing.isZero()) {
                        problems.add(p + "ageing-alert-after must be positive");
                    }
                } catch (RuntimeException e) {
                    problems.add(p + "ageing-alert-after is not an ISO-8601 duration: " + age);
                }
            }
            if (problems.isEmpty()) {
                ok.put(ch, new ChannelPolicy(ch, version, currency, maxAmount, Boolean.parseBoolean(mc), ageing,
                        csv(property.apply(p + "downstream-permitted"))));
            } else {
                bad.put(ch, String.join("; ", problems));
            }
        }
        return new UnverifiedPaymentPolicy(ok, bad);
    }

    /** The channel's policy, or empty when the channel is not enabled for unverified acceptance. */
    public Optional<ChannelPolicy> channel(String channel) {
        return Optional.ofNullable(channel == null ? null : channels.get(channel));
    }

    /** The trusted channels whose policy is unresolved, with the reasons: these are disabled. */
    public Map<String, String> unresolved() {
        return unresolved;
    }

    private static String required(UnaryOperator<String> property, String key, List<String> problems) {
        String v = property.apply(key);
        if (v == null || v.isBlank()) {
            problems.add(key + " is not set");
            return null;
        }
        return v.trim();
    }

    private static List<String> csv(String v) {
        if (v == null || v.isBlank()) {
            return List.of();
        }
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }
}
