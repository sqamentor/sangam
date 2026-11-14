// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.power;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What a permitting evaluation was based on (owner decision 142): the request it judged (actor, action, scope, case
 * type, amount, currency, state version), the contract version, the metadata version and every rule and cap version.
 * It is evidence, not a reusable permission: {@link PowerLimitEvaluator#recheck} always evaluates again on the
 * authoritative state re-read at application time and reports what changed.
 */
public record PowerApproval(PowerRequest request, String contractVersion, String metadataVersion,
                            List<String> ruleVersions, List<String> capVersions) {

    public PowerApproval {
        Objects.requireNonNull(request, "request");
        ruleVersions = List.copyOf(ruleVersions);
        capVersions = List.copyOf(capVersions);
    }

    /** The fields of {@code current} that differ from the judged request (amounts compared exactly, scale-insensitive). */
    public List<String> changedFields(PowerRequest current) {
        List<String> out = new ArrayList<>();
        PowerRequest a = request;
        if (!Objects.equals(a.actor(), current.actor())) {
            out.add("actor");
        }
        if (!Objects.equals(a.action(), current.action())) {
            out.add("action");
        }
        if (!Objects.equals(a.tenant(), current.tenant()) || !Objects.equals(a.office(), current.office())) {
            out.add("scope");
        }
        if (!Objects.equals(a.caseType(), current.caseType()) || a.caseTypeVersion() != current.caseTypeVersion()) {
            out.add("caseType");
        }
        if (!sameAmount(a.amount(), current.amount())) {
            out.add("amount");
        }
        if (!Objects.equals(a.currency(), current.currency())) {
            out.add("currency");
        }
        if (a.stateVersion() != current.stateVersion()) {
            out.add("stateVersion");
        }
        return out;
    }

    private static boolean sameAmount(BigDecimal x, BigDecimal y) {
        return x == null ? y == null : y != null && x.compareTo(y) == 0;
    }
}
