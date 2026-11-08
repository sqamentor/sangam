// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.security;

import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.ports.CompliancePorts.SecurityEvidence;
import org.sangam.compliance.support.Sql;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Evaluates the pack's {@code securityChecklist} against deployment facts from {@link SecurityEvidence}. An item is a
 * fact with an expected value ({@code true}, {@code set}, {@code <=N}, or a literal) or an activity with a maximum age
 * in months. IN: SPDI r.8 / ISO 27001, DPDP r.6(1), CERT-In government guidelines (CMP-054 … CMP-060, CMP-097 … CMP-107,
 * CMP-126, CMP-139).
 */
public final class SecurityPracticesChecklist {

    public enum Result { PASS, FAIL, UNKNOWN, ADVISORY }

    public record ItemResult(String id, String citation, String description, Result result, String evidence) {
        public Map<String, Object> asMap() {
            return Sql.kv("id", id, "citation", citation, "description", description, "result", result.name(), "evidence", evidence);
        }
    }

    private final ComplianceConfig.Provider config;
    private final SecurityEvidence evidence;

    public SecurityPracticesChecklist(ComplianceConfig.Provider config, SecurityEvidence evidence) {
        this.config = config;
        this.evidence = evidence == null ? SecurityEvidence.NONE : evidence;
    }

    public List<ItemResult> evaluate(String tenantId, Instant now) {
        List<ItemResult> out = new ArrayList<>();
        for (JurisdictionPack.ChecklistItem i : config.forTenant(tenantId).pack.checklist()) {
            if (!i.enforced()) {
                out.add(new ItemResult(i.id(), i.citation(), i.description(), Result.ADVISORY, "proposed / advisory: not enforced"));
                continue;
            }
            if (i.fact() != null) {
                Optional<String> v = evidence.fact(tenantId, i.fact());
                out.add(new ItemResult(i.id(), i.citation(), i.description(), v.map(x -> matches(x, i.expected())).orElse(Result.UNKNOWN),
                        v.orElse("no evidence")));
            } else if (i.activity() != null) {
                Optional<Instant> last = evidence.lastDone(tenantId, i.activity());
                Result r = last.map(t -> i.maxAgeMonths() <= 0 || t.atOffset(ZoneOffset.UTC).plusMonths(i.maxAgeMonths()).toInstant().isAfter(now)
                        ? Result.PASS : Result.FAIL).orElse(Result.UNKNOWN);
                out.add(new ItemResult(i.id(), i.citation(), i.description(), r, last.map(Instant::toString).orElse("never recorded")));
            }
        }
        return out;
    }

    static Result matches(String value, String expected) {
        if (expected == null || "set".equals(expected)) {
            return value.isBlank() ? Result.FAIL : Result.PASS;
        }
        if (expected.startsWith("<=")) {
            try {
                return Double.parseDouble(value.trim()) <= Double.parseDouble(expected.substring(2)) ? Result.PASS : Result.FAIL;
            } catch (NumberFormatException e) {
                return Result.FAIL;
            }
        }
        return expected.equalsIgnoreCase(value.trim()) ? Result.PASS : Result.FAIL;
    }
}
