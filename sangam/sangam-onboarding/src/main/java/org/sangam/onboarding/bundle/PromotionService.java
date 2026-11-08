// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.bundle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.sangam.onboarding.api.DryRunReport;
import org.sangam.onboarding.api.OnboardingRequest;
import org.sangam.onboarding.api.OnboardingRun;
import org.sangam.onboarding.api.Problem;
import org.sangam.onboarding.flow.OnboardingException;
import org.sangam.onboarding.support.OnboardingJson;

/**
 * SL-42: promotion of a tenant's configuration between environments (sandbox to test to production), reusing the
 * signed onboarding bundle. The model follows the change-set style of the reference platforms: an export from the
 * source environment, a preview against the target, and an apply that only goes ahead for exactly the previewed change.
 * <ol>
 *   <li>{@link #plan}: the incoming bundle's signature is verified against the target's trusted keys
 *       ({@link BundleService#verified}); a bundle from the target's own environment is refused; the incoming
 *       configuration is compared with the target's current bundle ({@link TreeDiff}, tenant ids normalised); it is
 *       turned into an onboarding request for the target tenant, keeping the target's own name and parent; the
 *       onboarding dry run evaluates it without a write. The plan carries a hash over all of that.</li>
 *   <li>{@link #promote}: recomputes the plan and refuses with {@code PROMOTION_PLAN_CHANGED} unless the presented
 *       hash matches (the target or the bundle changed since review), refuses {@code PROMOTION_DRY_RUN_FAILED} when the
 *       dry run has an error and {@code PROMOTION_NO_CHANGE} when there is nothing to promote; otherwise it starts the
 *       ordinary onboarding run, which goes through maker-checker approval before anything is applied.</li>
 * </ol>
 */
public final class PromotionService {

    /** The reviewed change: what the apply step will do and the hash it must present. */
    public record Plan(String sourceTenant, String sourceEnvironment, String targetTenant, String targetEnvironment,
                       List<TreeDiff.Entry> changes, OnboardingRequest request, DryRunReport dryRun, String planHash) {
    }

    private final BundleService bundles;
    private final Function<OnboardingRequest, DryRunReport> dryRun;
    private final BiFunction<OnboardingRequest, String, OnboardingRun> start;

    /**
     * @param dryRun the onboarding dry run ({@code OnboardingService::dryRun})
     * @param start  starts an onboarding run as maker ({@code OnboardingService::start})
     */
    public PromotionService(BundleService bundles, Function<OnboardingRequest, DryRunReport> dryRun,
                            BiFunction<OnboardingRequest, String, OnboardingRun> start) {
        this.bundles = bundles;
        this.dryRun = dryRun;
        this.start = start;
    }

    /**
     * @param incoming          signed bundle exported from the source environment
     * @param targetCurrent     the target tenant's current bundle (its own export), or null for a first promotion
     * @param targetTenant      tenant id in the target environment
     * @param targetEnvironment label of this environment ({@code prod}, {@code uat}, ...)
     */
    @SuppressWarnings("unchecked")
    public Plan plan(Map<String, Object> incoming, Map<String, Object> targetCurrent, String targetTenant,
                     String targetEnvironment) {
        Map<String, Object> body = bundles.verified(incoming);
        String sourceEnv = body.get("environment") == null ? null : String.valueOf(body.get("environment"));
        if (sourceEnv == null) {
            throw new OnboardingException(422, "PROMOTION_ENVIRONMENT_MISSING",
                    "the bundle does not name its source environment; export it with an environment label");
        }
        if (sourceEnv.equals(targetEnvironment)) {
            throw new OnboardingException(422, "PROMOTION_SAME_ENVIRONMENT",
                    "the bundle comes from environment " + sourceEnv + ", which is this environment");
        }
        String name = null;
        Map<String, String> names = null;
        String parent = null;
        Map<String, Object> current = Map.of("sourceTenant", targetTenant, "documents", List.of());
        if (targetCurrent != null) {
            current = bundles.verified(targetCurrent);
            if (!targetTenant.equals(current.get("sourceTenant"))) {
                throw new OnboardingException(422, "PROMOTION_TARGET_MISMATCH",
                        "the current bundle belongs to " + current.get("sourceTenant") + ", not " + targetTenant);
            }
            if (current.get("request") instanceof Map<?, ?> r && r.get("tenant") instanceof Map<?, ?> t) {
                Map<String, Object> tenant = (Map<String, Object>) t;
                name = tenant.get("name") == null ? null : String.valueOf(tenant.get("name"));
                names = tenant.get("names") instanceof Map<?, ?> n ? (Map<String, String>) n : null;
                parent = tenant.get("parentId") == null ? null : String.valueOf(tenant.get("parentId"));
            }
        }
        List<TreeDiff.Entry> changes = new ArrayList<>(bundles.diff(Map.of("body", withoutLabels(current)),
                Map.of("body", withoutLabels(body))));
        OnboardingRequest request = bundles.toRequest(incoming, targetTenant, name, names, parent, Map.of(), null);
        DryRunReport report = dryRun.apply(request);
        Map<String, Object> hashed = new LinkedHashMap<>();
        hashed.put("bundle", OnboardingJson.hash(body));
        hashed.put("target", OnboardingJson.hash(current));
        hashed.put("targetTenant", targetTenant);
        hashed.put("targetEnvironment", targetEnvironment);
        hashed.put("request", report == null ? OnboardingJson.hash(request) : report.requestHash());
        return new Plan(String.valueOf(body.get("sourceTenant")), sourceEnv, targetTenant, targetEnvironment,
                List.copyOf(changes), request, report, OnboardingJson.hash(hashed));
    }

    /** Starts the onboarding run for the reviewed plan; see the class comment for the refusals. */
    public OnboardingRun promote(Map<String, Object> incoming, Map<String, Object> targetCurrent, String targetTenant,
                                 String targetEnvironment, String presentedPlanHash, String maker) {
        Plan plan = plan(incoming, targetCurrent, targetTenant, targetEnvironment);
        if (presentedPlanHash == null || !presentedPlanHash.equals(plan.planHash())) {
            throw new OnboardingException(409, "PROMOTION_PLAN_CHANGED",
                    "the target or the bundle changed since the dry run; review the new plan");
        }
        if (plan.dryRun() != null && Problem.anyError(plan.dryRun().problems())) {
            throw new OnboardingException(422, "PROMOTION_DRY_RUN_FAILED",
                    "the dry run reports errors: " + plan.dryRun().problems().stream().filter(Problem::isError)
                            .map(Problem::code).distinct().toList());
        }
        if (plan.changes().isEmpty()) {
            throw new OnboardingException(409, "PROMOTION_NO_CHANGE", "the target already has this configuration");
        }
        return start.apply(plan.request(), maker);
    }

    private static Map<String, Object> withoutLabels(Map<String, Object> body) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.remove("environment");
        return copy;
    }
}
