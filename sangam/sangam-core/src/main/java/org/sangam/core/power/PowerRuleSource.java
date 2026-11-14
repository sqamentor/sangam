// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.power;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Supplies every rule that applies to a {@link PowerRequest} (owner decision 142). Independent of any decision engine:
 * a DMN-backed source maps its outputs with {@link LegacyPowerContracts} or produces {@link PowerDecision}s itself.
 * A source that throws is an evaluation failure and the evaluator denies.
 */
@FunctionalInterface
public interface PowerRuleSource {

    /**
     * The rules of {@code request}, read from the limit table the trusted {@code metadata} names
     * ({@link OperationMetadata.Classification#limitTableKey}, decision 193); never from a table named by the request,
     * a process variable or a pack input.
     */
    ApplicableRules rules(PowerRequest request, OperationMetadata.Classification metadata);

    /** One delegated limit and the rule that produced it. */
    record DelegatedLimit(String ruleId, String ruleVersion, PowerDecision decision) {
        public DelegatedLimit {
            requireText(ruleId, "ruleId");
            requireText(ruleVersion, "ruleVersion");
            Objects.requireNonNull(decision, "decision");
        }
    }

    /**
     * A mandatory ceiling outside the delegated power (posting cap of a seat holder, budget control). An
     * {@link PowerDecision.Unlimited} delegated limit never removes it.
     */
    record MandatoryCap(String capId, String capVersion, BigDecimal maxAmount, String currency) {
        public MandatoryCap {
            requireText(capId, "capId");
            requireText(capVersion, "capVersion");
            Objects.requireNonNull(maxAmount, "maxAmount");
            if (maxAmount.signum() < 0) {
                throw new IllegalArgumentException("cap must not be negative, was " + maxAmount.toPlainString());
            }
            PowerDecision.requireCurrency(currency);
        }
    }

    /** Everything that applies; every delegated limit and every cap must pass. */
    record ApplicableRules(List<DelegatedLimit> limits, List<MandatoryCap> caps) {
        public ApplicableRules {
            limits = limits == null ? null : List.copyOf(limits);
            caps = caps == null ? List.of() : List.copyOf(caps);
        }
    }

    private static void requireText(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
