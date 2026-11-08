// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.booking;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.ResourcesContext;
import org.sangam.resources.catalogue.Resource;
import org.sangam.resources.catalogue.ResourceType;
import org.sangam.resources.rules.RuleEvaluator;
import org.sangam.resources.support.Sql;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The DMN-backed rules of N01, each with a profile-data fallback when no table is configured:
 * <ul>
 *   <li><b>entitlement</b> ({@code entitlementDecision}): who may book which class, for how long, how far ahead, at
 *       which tariff, at which priority and whether approval is needed. Outputs: {@code allowed}, {@code entitlementClass},
 *       {@code priorityLevel}, {@code maxDurationMinutes}, {@code maxAdvanceDays}, {@code tariffCode},
 *       {@code requiresApproval}, {@code reason};</li>
 *   <li><b>priority</b> ({@code priorityDecision}): waitlist order. Outputs {@code priorityLevel} (lower first) and
 *       optional {@code priorityScore}; fallback {@code extra.priorityByCategory};</li>
 *   <li><b>tariff</b> ({@code tariffDecision}): {@code tariffCode} plus extra pricing inputs handed to the billing port;</li>
 *   <li><b>cancellation</b> ({@code cancellationDecision}): {@code refundPercent} and optional {@code fee} by notice
 *       hours, state, class and who cancelled; fallback {@code refundTiers}.</li>
 * </ul>
 * Inputs common to all tables: {@code typeId}, {@code kind}, {@code resourceId}, {@code resourceCode}, {@code orgUnitId},
 * {@code locationId}, {@code capacity}, every resource attribute as {@code res_<name>}, {@code requesterId},
 * {@code roles}/{@code groups}/{@code seats} (comma-separated), {@code entitlementClass}, {@code purpose},
 * {@code quantity}, {@code durationMinutes}, {@code advanceDays} and every request attribute as {@code req_<name>}.
 */
public final class BookingRules {

    public static final int DEFAULT_PRIORITY = 100;

    private final ResourcesContext ctx;

    public BookingRules(ResourcesContext ctx) {
        this.ctx = ctx;
    }

    public Map<String, Object> inputs(SecurityIdentity actor, ResourceType type, Resource r, String requesterId,
                                      String entitlementClass, String purpose, int quantity, Instant from, Instant to,
                                      Instant now, Map<String, Object> attributes) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("typeId", type.typeId());
        in.put("kind", type.kind());
        if (r != null) {
            in.put("resourceId", r.resourceId());
            in.put("resourceCode", r.code());
            in.put("orgUnitId", r.orgUnitId());
            in.put("locationId", r.locationId());
            in.put("capacity", r.capacity());
            r.attributes().forEach((k, v) -> in.put("res_" + k, v));
        }
        in.put("requesterId", requesterId);
        in.put("roles", String.join(",", actor.roles()));
        in.put("groups", String.join(",", actor.groups()));
        in.put("seats", String.join(",", actor.seats()));
        in.put("entitlementClass", entitlementClass);
        in.put("purpose", purpose);
        in.put("quantity", quantity);
        if (from != null && to != null) {
            in.put("durationMinutes", Duration.between(from, to).toMinutes());
        }
        if (from != null && now != null) {
            in.put("advanceDays", Duration.between(now, from).toDays());
        }
        if (attributes != null) {
            attributes.forEach((k, v) -> in.put("req_" + k, v));
        }
        return in;
    }

    public BookingModel.Entitlement entitlement(String tenantId, ResourceType type, Map<String, Object> inputs,
                                                Integer requestedPriority) {
        Optional<RuleEvaluator.Outcome> o = ctx.rules().evaluate(tenantId, type.entitlementDecision(), inputs);
        int defaultPriority = requestedPriority == null ? DEFAULT_PRIORITY : requestedPriority;
        String cls = Sql.str(inputs.get("entitlementClass"));
        if (o.isEmpty()) {
            return new BookingModel.Entitlement(true, cls, defaultPriority,
                    type.maxDurationMinutes() > 0 ? type.maxDurationMinutes() : null, type.maxAdvanceDays(),
                    type.defaultTariffCode(), type.requiresApproval(), null, null);
        }
        RuleEvaluator.Outcome out = o.get();
        if (out.rows().isEmpty()) {
            return new BookingModel.Entitlement(false, cls, defaultPriority, null, null, null, false,
                    "no entitlement rule matches", out.hash());
        }
        Map<String, Object> row = out.rows().get(0);
        boolean allowed = Sql.bool(row.get("allowed"), true);
        Integer prio = Sql.intOrNull(row.get("priorityLevel"));
        // a caller may ask for a less important level than entitled, never a more important one
        int level = prio == null ? defaultPriority : (requestedPriority == null ? prio : Math.max(prio, requestedPriority));
        return new BookingModel.Entitlement(allowed,
                row.get("entitlementClass") != null ? row.get("entitlementClass").toString() : cls, level,
                Sql.intOrNull(row.get("maxDurationMinutes")), Sql.intOrNull(row.get("maxAdvanceDays")),
                row.get("tariffCode") != null ? row.get("tariffCode").toString() : type.defaultTariffCode(),
                Sql.bool(row.get("requiresApproval"), type.requiresApproval()), Sql.str(row.get("reason")), out.hash());
    }

    /** Waitlist priority: level (lower first) and score (lower first within a level). */
    public long[] priority(String tenantId, ResourceType type, Map<String, Object> inputs, String category,
                           Integer requestedLevel, long defaultScore) {
        Optional<RuleEvaluator.Outcome> o = ctx.rules().evaluate(tenantId, type.priorityDecision(), inputs);
        if (o.isPresent() && !o.get().rows().isEmpty()) {
            Map<String, Object> row = o.get().rows().get(0);
            Integer level = Sql.intOrNull(row.get("priorityLevel"));
            Object score = row.get("priorityScore");
            return new long[]{level == null ? DEFAULT_PRIORITY : level,
                    score == null ? defaultScore : new BigDecimal(score.toString()).longValue()};
        }
        Map<String, Object> byCat = Sql.asMap(type.extra().get("priorityByCategory"));
        Integer level = category == null ? null : Sql.intOrNull(byCat.get(category));
        if (level == null) {
            level = requestedLevel == null ? DEFAULT_PRIORITY : requestedLevel;
        }
        return new long[]{level, defaultScore};
    }

    /** Tariff code and the pricing inputs for the billing port. */
    public Map<String, Object> tariffInputs(String tenantId, ResourceType type, Map<String, Object> inputs, String tariffCode) {
        Map<String, Object> pricing = new LinkedHashMap<>(inputs);
        pricing.put("currency", type.currency());
        pricing.put("tariffCode", tariffCode);
        ctx.rules().evaluate(tenantId, type.tariffDecision(), inputs).flatMap(RuleEvaluator.Outcome::first).ifPresent(row -> {
            row.forEach((k, v) -> {
                if (v != null) {
                    pricing.put(k, v);
                }
            });
        });
        return pricing;
    }

    /**
     * Refund on cancellation or no-show. {@code cancelledBy} is REQUESTER, ADMIN, SYSTEM or BUMP; a bump or an
     * administrative cancellation without a table refunds in full.
     */
    public BookingModel.RefundDecision refund(String tenantId, ResourceType type, Map<String, Object> inputs,
                                              long noticeHours, String state, String cancelledBy, boolean noShow) {
        Map<String, Object> in = new LinkedHashMap<>(inputs);
        in.put("noticeHours", noticeHours);
        in.put("state", state);
        in.put("cancelledBy", cancelledBy);
        in.put("noShow", noShow);
        Optional<RuleEvaluator.Outcome> o = ctx.rules().evaluate(tenantId, type.cancellationDecision(), in);
        if (o.isPresent() && !o.get().rows().isEmpty()) {
            Map<String, Object> row = o.get().rows().get(0);
            BigDecimal pct = Sql.decimalOrNull(row.get("refundPercent"));
            return new BookingModel.RefundDecision(pct == null ? BigDecimal.ZERO : pct, Sql.decimalOrNull(row.get("fee")),
                    Sql.str(row.get("ruleRef")), o.get().hash());
        }
        if ("BUMP".equals(cancelledBy) || "ADMIN".equals(cancelledBy) || "SYSTEM_CLOSURE".equals(cancelledBy)) {
            return new BookingModel.RefundDecision(new BigDecimal("100"), null, "full refund: " + cancelledBy, null);
        }
        if (noShow) {
            Object pct = type.extra().get("noShowRefundPercent");
            return new BookingModel.RefundDecision(pct == null ? BigDecimal.ZERO : new BigDecimal(pct.toString()), null,
                    "no-show", null);
        }
        for (var tier : type.refundTiers()) {
            if (noticeHours >= tier.minNoticeHours()) {
                return new BookingModel.RefundDecision(tier.refundPercent(), null, "tier>=" + tier.minNoticeHours() + "h", null);
            }
        }
        return new BookingModel.RefundDecision(type.refundTiers().isEmpty() ? new BigDecimal("100") : BigDecimal.ZERO,
                null, type.refundTiers().isEmpty() ? "no policy: full refund" : "below lowest tier", null);
    }
}
