// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import java.util.Map;
import java.util.function.Supplier;

import org.sangam.onboarding.api.OnboardingRun;
import org.sangam.onboarding.bundle.BundleService;
import org.sangam.onboarding.bundle.PromotionService;
import org.sangam.onboarding.flow.OnboardingException;
import org.sangam.onboarding.flow.OnboardingService;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SL-42: promotion of a tenant's configuration between environments over {@link PromotionService}, under
 * {@code /api/v1/onboarding/promotions} with the onboarding gate ({@code tenant.admin} and the platform administrator
 * group, as {@code OnboardingController}).
 * <ol>
 *   <li>{@code GET /promotions/by-tenant/bundle?tenant=}: the tenant's signed bundle labelled with THIS environment
 *       ({@code sangam.onboarding.environment}), to carry to the next environment;</li>
 *   <li>{@code POST /promotions/plan}: dry run and diff of an incoming bundle against the target tenant here, with the
 *       plan hash; changes nothing;</li>
 *   <li>{@code POST /promotions}: apply, bound to the reviewed plan hash (409 {@code PROMOTION_PLAN_CHANGED}); it starts
 *       the ordinary onboarding run, so maker-checker approval still decides before anything is applied.</li>
 * </ol>
 * Without an environment label this server cannot take part (409 {@code PROMOTION_ENVIRONMENT_NOT_SET}): a promotion
 * between two unlabelled environments could not tell them apart.
 */
@RestController
@RequestMapping("/api/v1/onboarding/promotions")
public class PromotionController {

    /** Environment label of this server, signed into exported bundles. */
    public static final String ENVIRONMENT_PROPERTY = "sangam.onboarding.environment";

    public record PromotionPlanRequest(Map<String, Object> bundle, String targetTenant) {
    }

    public record PromotionApplyRequest(Map<String, Object> bundle, String targetTenant, String planHash) {
    }

    private final ObjectProvider<OnboardingService> service;
    private final ObjectProvider<BundleService> bundles;
    private final ObjectProvider<OnboardingService.Ports> ports;
    private final PermissionPort permissions;
    private final Environment env;

    public PromotionController(ObjectProvider<OnboardingService> service, ObjectProvider<BundleService> bundles,
                               ObjectProvider<OnboardingService.Ports> ports, PermissionPort permissions, Environment env) {
        this.service = service;
        this.bundles = bundles;
        this.ports = ports;
        this.permissions = permissions;
        this.env = env;
    }

    private OnboardingService svc(RequestContext ctx) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        if (!ctx.inGroup(Permissions.GROUP_ADMIN)) {
            throw ApiProblem.notPermitted("platform onboarding");
        }
        OnboardingService s = service.getIfAvailable();
        if (s == null || bundles.getIfAvailable() == null) {
            throw ApiProblem.notInstalled("tenant promotion", "sangam-onboarding");
        }
        return s;
    }

    String environment() {
        String e = env.getProperty(ENVIRONMENT_PROPERTY);
        if (e == null || e.isBlank()) {
            throw ApiProblem.conflict("PROMOTION_ENVIRONMENT_NOT_SET", "this server has no environment label ("
                    + ENVIRONMENT_PROPERTY + "), so it cannot export or receive a promotion");
        }
        return e.trim();
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", field + " is required").with("field", field);
        }
        return value;
    }

    static <T> T call(Supplier<T> work) {
        try {
            return work.get();
        } catch (OnboardingException e) {
            throw new ApiProblem(e.status(), e.code(), e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", e.getMessage());
        }
    }

    /** The target tenant's current bundle here (null when it was never onboarded: a first promotion). */
    private Map<String, Object> current(OnboardingService s, String tenant) {
        return s.latest(tenant).map(run -> bundles.getObject().export(run.request(),
                ports.getObject().packs().installed(tenant), environment())).orElse(null);
    }

    private PromotionService promotion(OnboardingService s) {
        return new PromotionService(bundles.getObject(), s::dryRun, s::start);
    }

    @GetMapping("/by-tenant/bundle")
    public Map<String, Object> export(RequestContext ctx, @RequestParam("tenant") String tenant) {
        OnboardingService s = svc(ctx);
        String label = environment();
        OnboardingRun run = s.latest(tenant).orElseThrow(() -> ApiProblem.notFound("onboarding run of tenant", tenant));
        return call(() -> bundles.getObject().export(run.request(), ports.getObject().packs().installed(tenant), label));
    }

    @PostMapping("/plan")
    public PromotionService.Plan plan(RequestContext ctx, @RequestBody PromotionPlanRequest body) {
        OnboardingService s = svc(ctx);
        String label = environment();
        String target = requireText(body == null ? null : body.targetTenant(), "targetTenant");
        if (body.bundle() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "bundle is required").with("field", "bundle");
        }
        return call(() -> promotion(s).plan(body.bundle(), current(s, target), target, label));
    }

    @PostMapping
    public OnboardingRun apply(RequestContext ctx, @RequestBody PromotionApplyRequest body) {
        OnboardingService s = svc(ctx);
        String label = environment();
        String target = requireText(body == null ? null : body.targetTenant(), "targetTenant");
        requireText(body.planHash(), "planHash");
        if (body.bundle() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "bundle is required").with("field", "bundle");
        }
        return call(() -> promotion(s).promote(body.bundle(), current(s, target), target, label, body.planHash(),
                ctx.principalId()));
    }
}
