// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.sangam.onboarding.api.DryRunReport;
import org.sangam.onboarding.api.OnboardingRequest;
import org.sangam.onboarding.api.OnboardingRun;
import org.sangam.onboarding.bundle.BundleService;
import org.sangam.onboarding.bundle.TreeDiff;
import org.sangam.onboarding.deps.PackDependencyResolver;
import org.sangam.onboarding.flow.OnboardingException;
import org.sangam.onboarding.flow.OnboardingService;
import org.sangam.onboarding.port.OnboardingPorts;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/onboarding}: guided onboarding, dry run, pack resolution and signed bundles. Platform administrators. */
@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    public record ApproveRequest(String presentedHash) {
    }

    public record ReasonRequest(String reason) {
    }

    public record ResolveRequest(List<String> ids, Map<String, String> versions, String tenant, Boolean allowUpgrade) {
    }

    public record BundleImportRequest(Map<String, Object> bundle, String targetTenant, String name,
                                      Map<String, String> names, String parentId, Map<String, Object> profileOverrides,
                                      OnboardingRequest.GoLiveSpec goLive, Boolean dryRun) {
    }

    public record BundleDiffRequest(Map<String, Object> left, Map<String, Object> right) {
    }

    private final ObjectProvider<OnboardingService> service;
    private final ObjectProvider<BundleService> bundles;
    private final ObjectProvider<PackDependencyResolver> resolver;
    private final ObjectProvider<OnboardingService.Ports> ports;
    private final PermissionPort permissions;

    public OnboardingController(ObjectProvider<OnboardingService> service, ObjectProvider<BundleService> bundles,
                                ObjectProvider<PackDependencyResolver> resolver, ObjectProvider<OnboardingService.Ports> ports,
                                PermissionPort permissions) {
        this.service = service;
        this.bundles = bundles;
        this.resolver = resolver;
        this.ports = ports;
        this.permissions = permissions;
    }

    private OnboardingService svc(RequestContext ctx) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        if (!ctx.inGroup(Permissions.GROUP_ADMIN)) {
            throw ApiProblem.notPermitted("platform onboarding");
        }
        OnboardingService s = service.getIfAvailable();
        if (s == null) {
            throw ApiProblem.notInstalled("tenant onboarding", "sangam-onboarding");
        }
        return s;
    }

    private static <T> T call(Supplier<T> work) {
        try {
            return work.get();
        } catch (OnboardingException e) {
            throw new ApiProblem(e.status(), e.code(), e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", e.getMessage());
        }
    }

    @PostMapping("/dry-run")
    public DryRunReport dryRun(RequestContext ctx, @RequestBody OnboardingRequest body) {
        OnboardingService s = svc(ctx);
        return call(() -> s.dryRun(body));
    }

    @PostMapping("/runs")
    public OnboardingRun start(RequestContext ctx, @RequestBody OnboardingRequest body) {
        OnboardingService s = svc(ctx);
        return call(() -> s.start(body, ctx.principalId()));
    }

    @GetMapping("/runs")
    public List<OnboardingRun> list(RequestContext ctx, @RequestParam(required = false) String tenant) {
        OnboardingService s = svc(ctx);
        return s.list(tenant);
    }

    @GetMapping("/runs/{id}")
    public OnboardingRun get(RequestContext ctx, @PathVariable String id) {
        OnboardingService s = svc(ctx);
        return call(() -> s.get(id));
    }

    @PutMapping("/runs/{id}/request")
    public OnboardingRun amend(RequestContext ctx, @PathVariable String id, @RequestBody OnboardingRequest body) {
        OnboardingService s = svc(ctx);
        return call(() -> s.amend(id, body, ctx.principalId()));
    }

    @PostMapping("/runs/{id}/recheck")
    public OnboardingRun recheck(RequestContext ctx, @PathVariable String id) {
        OnboardingService s = svc(ctx);
        return call(() -> s.recheck(id, ctx.principalId()));
    }

    @PostMapping("/runs/{id}/approve")
    public OnboardingRun approve(RequestContext ctx, @PathVariable String id, @RequestBody ApproveRequest body) {
        OnboardingService s = svc(ctx);
        return call(() -> s.approve(id, ctx.principalId(), body.presentedHash()));
    }

    @PostMapping("/runs/{id}/reject")
    public OnboardingRun reject(RequestContext ctx, @PathVariable String id, @RequestBody ReasonRequest body) {
        OnboardingService s = svc(ctx);
        return call(() -> s.reject(id, ctx.principalId(), body.reason()));
    }

    @PostMapping("/runs/{id}/resume")
    public OnboardingRun resume(RequestContext ctx, @PathVariable String id) {
        OnboardingService s = svc(ctx);
        return call(() -> s.resume(id, ctx.principalId()));
    }

    @PostMapping("/runs/{id}/cancel")
    public OnboardingRun cancel(RequestContext ctx, @PathVariable String id, @RequestBody(required = false) ReasonRequest body) {
        OnboardingService s = svc(ctx);
        return call(() -> s.cancel(id, ctx.principalId(), body == null ? null : body.reason()));
    }

    @PostMapping("/packs/resolve")
    public PackDependencyResolver.Resolution resolve(RequestContext ctx, @RequestBody ResolveRequest body) {
        svc(ctx);
        OnboardingPorts.PackInstallPort packs = ports.getObject().packs();
        return resolver.getObject().resolve(body.ids() == null ? List.of() : body.ids(), body.versions(),
                body.tenant() == null ? Map.of() : packs.installed(body.tenant()), Boolean.TRUE.equals(body.allowUpgrade()));
    }

    @GetMapping("/tenants/{tenant}/bundle")
    public Map<String, Object> export(RequestContext ctx, @PathVariable String tenant) {
        OnboardingService s = svc(ctx);
        OnboardingRun run = s.latest(tenant).orElseThrow(() -> ApiProblem.notFound("onboarding run of tenant", tenant));
        return call(() -> bundles.getObject().export(run.request(), ports.getObject().packs().installed(tenant)));
    }

    /** Verifies the signature, then dry-runs or starts the onboarding of the target tenant. */
    @PostMapping("/bundles/import")
    public Map<String, Object> importBundle(RequestContext ctx, @RequestBody BundleImportRequest body) {
        OnboardingService s = svc(ctx);
        return call(() -> {
            OnboardingRequest req = bundles.getObject().toRequest(body.bundle(), body.targetTenant(), body.name(), body.names(),
                    body.parentId(), body.profileOverrides(), body.goLive());
            Map<String, Object> out = new LinkedHashMap<>();
            if (Boolean.TRUE.equals(body.dryRun())) {
                out.put("dryRun", s.dryRun(req));
            } else {
                out.put("run", s.start(req, ctx.principalId()));
            }
            return out;
        });
    }

    @PostMapping("/bundles/diff")
    public List<TreeDiff.Entry> diff(RequestContext ctx, @RequestBody BundleDiffRequest body) {
        svc(ctx);
        return call(() -> bundles.getObject().diff(body.left(), body.right()));
    }
}
