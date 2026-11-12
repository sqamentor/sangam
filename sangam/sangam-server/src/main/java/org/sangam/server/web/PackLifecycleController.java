// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.sangam.packs.spi.PackDeployer;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SL-41: {@code /api/v1/packs}: the packs installed for a tenant, uninstall and rollback, over {@link PackDeployer}
 * ({@code installed / uninstall / rollback}, {@code PackLifecycleTest}). Packs are installed by onboarding, a platform
 * operation, so these endpoints have the same gate as {@code OnboardingController} and {@code PluginAdminController}:
 * {@code tenant.admin} and the platform administrator group. Uninstall and rollback need a reason and are audited on
 * the tenant's security chain. Refusals: 404 {@code PACK_NOT_INSTALLED}; 409 {@code PACK_HAS_DEPENDANTS},
 * {@code PACK_HAS_LIVE_INSTANCES} (with {@code blockers}), {@code PACK_NO_PREVIOUS_VERSION}; 422 {@code PACK_REJECTED}
 * (with {@code violations}). The tenant is always named: the shared scope is not changed over REST.
 */
@RestController
@RequestMapping("/api/v1/packs")
public class PackLifecycleController {

    /** {@code tenant} the tenant whose packs change; {@code reason} mandatory (audited). */
    public record PackLifecycleRequest(String tenant, String reason) {
    }

    private final ObjectProvider<PackDeployer> deployer;
    private final PermissionPort permissions;
    private final AuditPort audit;

    public PackLifecycleController(ObjectProvider<PackDeployer> deployer, PermissionPort permissions, AuditPort audit) {
        this.deployer = deployer;
        this.permissions = permissions;
        this.audit = audit;
    }

    private PackDeployer admin(RequestContext ctx) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        if (!ctx.inGroup(Permissions.GROUP_ADMIN)) {
            throw ApiProblem.notPermitted("platform pack administration");
        }
        PackDeployer d = deployer.getIfAvailable();
        if (d == null) {
            throw ApiProblem.notInstalled("pack lifecycle", "sangam-packs");
        }
        return d;
    }

    static String requireTenant(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "tenant is required").with("field", "tenant");
        }
        return tenant;
    }

    static PackLifecycleRequest requireBody(PackLifecycleRequest body) {
        if (body == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "tenant is required").with("field", "tenant");
        }
        requireTenant(body.tenant());
        if (body.reason() == null || body.reason().isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "A reason is required").with("field", "reason");
        }
        return body;
    }

    /** Maps the deployer's refusals to problems (see the class comment). */
    static <T> T call(Supplier<T> work) {
        try {
            return work.get();
        } catch (PackDeployer.PackLifecycleException e) {
            ApiProblem p = "PACK_NOT_INSTALLED".equals(e.code())
                    ? new ApiProblem(404, e.code(), e.getMessage())
                    : ApiProblem.conflict(e.code(), e.getMessage());
            return throwWith(p, "blockers", e.blockers());
        } catch (PackDeployer.PackRejectedException e) {
            return throwWith(ApiProblem.unprocessable("PACK_REJECTED", e.getMessage()), "violations", e.violations());
        }
    }

    private static <T> T throwWith(ApiProblem p, String key, List<String> items) {
        throw items == null || items.isEmpty() ? p : p.with(key, items);
    }

    @GetMapping("/installed")
    public List<PackDeployer.Installed> installed(RequestContext ctx, @RequestParam(required = false) String tenant) {
        PackDeployer d = admin(ctx);
        return d.installed(requireTenant(tenant));
    }

    @PostMapping("/{id}/uninstall")
    public PackDeployer.Uninstalled uninstall(RequestContext ctx, @PathVariable String id,
                                              @RequestBody(required = false) PackLifecycleRequest body) {
        PackDeployer d = admin(ctx);
        PackLifecycleRequest r = requireBody(body);
        PackDeployer.Uninstalled u = call(() -> d.uninstall(id, r.tenant()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("packId", id);
        payload.put("tenant", r.tenant());
        payload.put("deployments", u.deploymentIds());
        payload.put("reason", r.reason());
        payload.put("actor", ctx.actor());
        audit.record(ctx, "sec:" + r.tenant(), "PACK_UNINSTALLED", payload);
        return u;
    }

    @PostMapping("/{id}/rollback")
    public PackDeployer.Result rollback(RequestContext ctx, @PathVariable String id,
                                        @RequestBody(required = false) PackLifecycleRequest body) {
        PackDeployer d = admin(ctx);
        PackLifecycleRequest r = requireBody(body);
        PackDeployer.Result res = call(() -> d.rollback(id, r.tenant()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("packId", id);
        payload.put("tenant", r.tenant());
        payload.put("version", res.version());
        payload.put("deploymentId", res.deploymentId());
        payload.put("reason", r.reason());
        payload.put("actor", ctx.actor());
        audit.record(ctx, "sec:" + r.tenant(), "PACK_ROLLED_BACK", payload);
        return res;
    }
}
