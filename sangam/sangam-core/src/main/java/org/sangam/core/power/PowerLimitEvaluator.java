// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.power;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The one power-limit evaluator (owner decision 142, RM-08), plain Java with no framework or decision-engine type.
 * The security service and the packs task listener call it, and the shared application-service boundary enforces it
 * for manual, automatic, REST and background execution.
 *
 * <p>Rules, in order; the first that fails denies:
 * <ol>
 *   <li>The request names actor, action, tenant and case type.</li>
 *   <li>Trusted {@link OperationMetadata} (the deployed pack manifest, decision 193) classifies the operation; no answer
 *       or a failure denies. Monetary-ness, the currency and the limit table come only from there.</li>
 *   <li>A monetary operation has an amount and the ISO 4217 currency the manifest declares (another currency denies).
 *       Basis: gross amount of this operation; a negative amount is compared by its magnitude. Zero is a valid
 *       amount.</li>
 *   <li>The {@link PowerRuleSource}, given the classification (its limit table), answers without failing and names
 *       at least one delegated limit; none means no power.</li>
 *   <li>Every delegated limit passes: any {@link PowerDecision.Deny} denies; every {@link PowerDecision.Limited}
 *       must be in the request currency and at least the magnitude (inclusive, exact). Conflicting rules are never
 *       resolved by taking the permissive one: all of them must pass, so the lowest ceiling binds and an
 *       {@link PowerDecision.Unlimited} next to a ceiling leaves the ceiling in force.</li>
 *   <li>Every {@link PowerRuleSource.MandatoryCap} of a monetary operation passes the same way, whatever the delegated
 *       limits say (UNLIMITED never removes a cap).</li>
 * </ol>
 * For a non-monetary operation the delegated limits still decide (any Deny denies; Limited and Unlimited both confer
 * the power); there is no amount to compare.
 */
public final class PowerLimitEvaluator {

    private final OperationMetadata metadata;
    private final PowerRuleSource rules;

    public PowerLimitEvaluator(OperationMetadata metadata, PowerRuleSource rules) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    public PowerOutcome evaluate(PowerRequest r) {
        return evaluate(r, List.of());
    }

    /**
     * Application-time check: {@code current} carries the authoritative amount, currency and state version re-read by
     * the caller inside the transaction that applies the action (holding the row lock or using a guarded update on
     * {@code stateVersion}). The earlier approval is never reused: the request is always evaluated again against the
     * rules in force, and the outcome lists what changed since {@code approval}. A null approval denies.
     */
    public PowerOutcome recheck(PowerApproval approval, PowerRequest current) {
        if (approval == null) {
            return deny(current, null, null, List.of(), "no earlier approval to recheck");
        }
        return evaluate(current, approval.changedFields(current));
    }

    private PowerOutcome evaluate(PowerRequest r, List<String> changed) {
        if (r == null) {
            return deny(null, null, null, changed, "no request");
        }
        if (blank(r.actor()) || blank(r.action()) || blank(r.tenant()) || blank(r.caseType())) {
            return deny(r, null, null, changed, "request without actor, action, tenant or case type");
        }
        OperationMetadata.Classification cls;
        try {
            Optional<OperationMetadata.Classification> c = metadata.classify(r.tenant(), r.caseType(),
                    r.caseTypeVersion(), r.action());
            cls = c == null ? null : c.orElse(null);
        } catch (RuntimeException e) {
            return deny(r, null, null, changed, "case-type metadata failed: " + message(e));
        }
        if (cls == null) {
            return deny(r, null, null, changed, "no trusted metadata for case type " + r.caseType()
                    + " version " + r.caseTypeVersion() + " action " + r.action());
        }
        BigDecimal magnitude = null;
        if (cls.monetary()) {
            if (r.amount() == null) {
                return deny(r, cls, null, changed, "monetary operation without an amount");
            }
            if (r.currency() == null || !PowerDecision.CURRENCY.matcher(r.currency()).matches()) {
                return deny(r, cls, null, changed, "monetary operation without a valid currency");
            }
            if (!r.currency().equals(cls.currency())) {
                return deny(r, cls, null, changed, "the amount is in " + r.currency() + ", the case type declares "
                        + cls.currency() + " (" + cls.metadataVersion() + ")");
            }
            magnitude = r.amount().abs();
        }
        PowerRuleSource.ApplicableRules applicable;
        try {
            applicable = rules.rules(r, cls);
        } catch (RuntimeException e) {
            return deny(r, cls, null, changed, "power rule evaluation failed: " + message(e));
        }
        if (applicable == null || applicable.limits() == null || applicable.limits().isEmpty()) {
            return deny(r, cls, applicable, changed, "no delegated power applies");
        }
        BigDecimal ceiling = null;
        boolean anyLimited = false;
        for (PowerRuleSource.DelegatedLimit l : applicable.limits()) {
            if (l == null) {
                return deny(r, cls, applicable, changed, "malformed delegated limit");
            }
            switch (l.decision()) {
                case PowerDecision.Deny d -> {
                    return deny(r, cls, applicable, changed, "rule " + l.ruleId() + "@" + l.ruleVersion()
                            + " denies: " + d.reason());
                }
                case PowerDecision.Limited lim -> {
                    anyLimited = true;
                    if (magnitude != null) {
                        if (!lim.currency().equals(r.currency())) {
                            return deny(r, cls, applicable, changed, "rule " + l.ruleId() + "@"
                                    + l.ruleVersion() + " is in " + lim.currency() + ", the amount in " + r.currency());
                        }
                        if (magnitude.compareTo(lim.maxAmount()) > 0) {
                            return deny(r, cls, applicable, changed, "amount " + magnitude.toPlainString()
                                    + " exceeds the delegated power " + lim.maxAmount().toPlainString() + " of rule "
                                    + l.ruleId() + "@" + l.ruleVersion());
                        }
                    }
                    ceiling = ceiling == null ? lim.maxAmount() : ceiling.min(lim.maxAmount());
                }
                case PowerDecision.Unlimited u -> {
                    // explicit: no delegated ceiling from this rule; the others and the caps still apply
                }
            }
        }
        if (magnitude != null) {
            for (PowerRuleSource.MandatoryCap cap : applicable.caps()) {
                if (cap == null) {
                    return deny(r, cls, applicable, changed, "malformed mandatory cap");
                }
                if (!cap.currency().equals(r.currency())) {
                    return deny(r, cls, applicable, changed, "cap " + cap.capId() + "@" + cap.capVersion()
                            + " is in " + cap.currency() + ", the amount in " + r.currency());
                }
                if (magnitude.compareTo(cap.maxAmount()) > 0) {
                    return deny(r, cls, applicable, changed, "amount " + magnitude.toPlainString()
                            + " exceeds the mandatory cap " + cap.maxAmount().toPlainString() + " of " + cap.capId()
                            + "@" + cap.capVersion());
                }
            }
        }
        PowerDecision effective = anyLimited ? new PowerDecision.Limited(ceiling, limitCurrency(applicable, r))
                : new PowerDecision.Unlimited();
        List<String> ruleVersions = new ArrayList<>();
        for (PowerRuleSource.DelegatedLimit l : applicable.limits()) {
            ruleVersions.add(l.ruleId() + "@" + l.ruleVersion());
        }
        List<String> capVersions = new ArrayList<>();
        for (PowerRuleSource.MandatoryCap c : applicable.caps()) {
            capVersions.add(c.capId() + "@" + c.capVersion());
        }
        PowerApproval approval = new PowerApproval(r, PowerDecision.CONTRACT_VERSION, cls.metadataVersion(),
                ruleVersions, capVersions);
        String reason = cls.monetary() ? "within every delegated power and mandatory cap" : "non-monetary operation"
                + " within the delegated power";
        return new PowerOutcome(true, effective, reason, approval, changed,
                audit(r, cls, applicable, effective, true, reason, changed));
    }

    /** The currency of the binding ceiling: the request currency when monetary, else the first Limited one. */
    private static String limitCurrency(PowerRuleSource.ApplicableRules a, PowerRequest r) {
        if (r.currency() != null && PowerDecision.CURRENCY.matcher(r.currency()).matches()) {
            for (PowerRuleSource.DelegatedLimit l : a.limits()) {
                if (l.decision() instanceof PowerDecision.Limited lim && lim.currency().equals(r.currency())) {
                    return r.currency();
                }
            }
        }
        for (PowerRuleSource.DelegatedLimit l : a.limits()) {
            if (l.decision() instanceof PowerDecision.Limited lim) {
                return lim.currency();
            }
        }
        throw new IllegalStateException("no limited decision");
    }

    private static PowerOutcome deny(PowerRequest r, OperationMetadata.Classification cls,
                                     PowerRuleSource.ApplicableRules applicable, List<String> changed,
                                     String reason) {
        PowerDecision.Deny d = new PowerDecision.Deny(reason);
        return new PowerOutcome(false, d, reason, null, changed, audit(r, cls, applicable, d, false, reason, changed));
    }

    /** The audit payload; decimals are plain strings (canonical JSON takes no fractional numbers). */
    static Map<String, Object> audit(PowerRequest r, OperationMetadata.Classification cls,
                                     PowerRuleSource.ApplicableRules applicable, PowerDecision effective,
                                     boolean permitted, String reason, List<String> changed) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("contract_version", PowerDecision.CONTRACT_VERSION);
        if (r != null) {
            p.put("actor", r.actor());
            p.put("action", r.action());
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("tenant", r.tenant());
            scope.put("office", r.office());
            scope.values().removeIf(Objects::isNull);
            p.put("scope", scope);
            p.put("case_type", r.caseType());
            p.put("case_type_version", r.caseTypeVersion());
            p.put("amount", r.amount() == null ? null : r.amount().toPlainString());
            p.put("currency", r.currency());
            p.put("state_version", r.stateVersion());
        }
        p.put("monetary", cls == null ? null : cls.monetary());
        p.put("metadata_version", cls == null ? null : cls.metadataVersion());
        p.put("limit_table", cls == null ? null : cls.limitTableKey());
        p.put("declared_currency", cls == null ? null : cls.currency());
        p.put("rule_case_type", cls == null ? null : cls.ruleCaseType());    // decision 222: from the manifest only
        List<Map<String, Object>> limits = new ArrayList<>();
        List<Map<String, Object>> caps = new ArrayList<>();
        if (applicable != null) {
            if (applicable.limits() != null) {
                for (PowerRuleSource.DelegatedLimit l : applicable.limits()) {
                    if (l != null) {
                        limits.add(describe(l.ruleId(), l.ruleVersion(), l.decision()));
                    }
                }
            }
            for (PowerRuleSource.MandatoryCap c : applicable.caps()) {
                if (c != null) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("cap", c.capId() + "@" + c.capVersion());
                    m.put("max_amount", c.maxAmount().toPlainString());
                    m.put("currency", c.currency());
                    caps.add(m);
                }
            }
        }
        p.put("applicable_limits", limits);
        p.put("mandatory_caps", caps);
        p.put("decision", permitted ? "PERMIT" : "DENY");
        p.put("effective", describe(null, null, effective));
        p.put("reason", reason);
        if (!changed.isEmpty()) {
            p.put("changed_since_approval", List.copyOf(changed));
        }
        p.values().removeIf(Objects::isNull);
        return p;
    }

    private static Map<String, Object> describe(String ruleId, String ruleVersion, PowerDecision d) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (ruleId != null) {
            m.put("rule", ruleId + "@" + ruleVersion);
        }
        m.put("kind", d.kind());
        if (d instanceof PowerDecision.Limited lim) {
            m.put("max_amount", lim.maxAmount().toPlainString());
            m.put("currency", lim.currency());
        } else if (d instanceof PowerDecision.Deny deny) {
            m.put("reason", deny.reason());
        }
        return m;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
