// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.power;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One delegated-power decision of the versioned power-limit contract (owner decision 142, RM-08). The three outcomes
 * stay distinct: {@link Deny}, {@link Limited} (a ceiling in one currency) and {@link Unlimited}, which is only ever an
 * explicit statement of a rule and never the result of a missing, malformed or failed value.
 *
 * <p>{@link Unlimited} removes only the delegated ceiling of that rule. Mandatory constraints (posting caps, budget
 * controls, role and scope checks, maker-checker) are separate and still apply ({@link PowerRuleSource.MandatoryCap}).
 */
public sealed interface PowerDecision permits PowerDecision.Deny, PowerDecision.Limited, PowerDecision.Unlimited {

    /** Contract identifier and version carried in every approval and audit payload. */
    String CONTRACT_VERSION = "sangam.power-decision/1";

    /** ISO 4217 alphabetic code. */
    Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    /** The contract kind name used in audit payloads. */
    String kind();

    static String requireCurrency(String currency) {
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code, was " + currency);
        }
        return currency;
    }

    /** No power for this request. */
    record Deny(String reason) implements PowerDecision {
        public Deny {
            reason = reason == null || reason.isBlank() ? "denied" : reason;
        }

        @Override
        public String kind() {
            return "DENY";
        }
    }

    /** Power up to {@code maxAmount} (inclusive, exact decimal comparison) in {@code currency}. */
    record Limited(BigDecimal maxAmount, String currency) implements PowerDecision {
        public Limited {
            Objects.requireNonNull(maxAmount, "maxAmount");
            if (maxAmount.signum() < 0) {
                throw new IllegalArgumentException("maxAmount must not be negative, was " + maxAmount.toPlainString());
            }
            requireCurrency(currency);
        }

        @Override
        public String kind() {
            return "LIMITED";
        }
    }

    /** Explicitly authorised power without a delegated ceiling. */
    record Unlimited() implements PowerDecision {
        @Override
        public String kind() {
            return "UNLIMITED";
        }
    }
}
