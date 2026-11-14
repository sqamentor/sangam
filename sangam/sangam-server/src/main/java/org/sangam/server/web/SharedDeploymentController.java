// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import java.util.List;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.web.Lists;
import org.sangam.server.bridge.SecurityPermissionBridge;
import org.sangam.server.bridge.SharedDeploymentBridge;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Decisions 144 and 197 (RM-49), reading per owner decision 250: the shared-deployment registry (contract
 * {@code routes/shared_deployments.py}). Reading needs {@value SecurityPermissionBridge#SHARED_DEPLOYMENT_READ}
 * (platform deployer, administrator or operator of the platform), registering and revoking
 * {@value SecurityPermissionBridge#SHARED_DEPLOYMENT_MANAGE} (platform deployer); both are platform-only. The R41
 * commands {@code shared-deployment.register} and {@code shared-deployment.revoke} and their idempotency handling come
 * from the contract; a replay re-checks the manage permission (SecurityPermissionBridge). The registry is platform-wide:
 * no tenant parameter and no tenant filter. Without the engine registry (not a Sangam engine) every route answers 501.
 */
@RestController
@RequestMapping("/api/v1/admin/shared-deployments")
public class SharedDeploymentController {

    public record SharedDeploymentRegistration(String deploymentId, String reason) {
    }

    public record SharedDeploymentRevocation(String reason) {
    }

    private final ObjectProvider<SharedDeploymentBridge> registry;
    private final PermissionPort permissions;
    private final RestProperties props;

    public SharedDeploymentController(ObjectProvider<SharedDeploymentBridge> registry, PermissionPort permissions,
                                      RestProperties props) {
        this.registry = registry;
        this.permissions = permissions;
        this.props = props;
    }

    @GetMapping
    public ResponseEntity<List<SharedDeploymentBridge.SharedDeployment>> list(RequestContext ctx,
            @RequestParam(required = false) Boolean includeRevoked, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        permissions.require(ctx, SecurityPermissionBridge.SHARED_DEPLOYMENT_READ);
        Page.PageRequest p = Lists.page(page, size);
        List<SharedDeploymentBridge.SharedDeployment> all = bridge().list(ctx, Boolean.TRUE.equals(includeRevoked));
        return Lists.ok(Page.slice(all, p, props.getCountCap()));
    }

    @PostMapping
    public ResponseEntity<SharedDeploymentBridge.SharedDeployment> register(RequestContext ctx,
            @RequestBody(required = false) SharedDeploymentRegistration body) {
        permissions.require(ctx, SecurityPermissionBridge.SHARED_DEPLOYMENT_MANAGE);
        if (body == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "deploymentId and reason are required");
        }
        SharedDeploymentBridge.SharedDeployment d = bridge().register(ctx, body.deploymentId(), body.reason());
        return Lists.created("/api/v1/admin/shared-deployments/" + d.deploymentId(), d.deploymentId(), d);
    }

    @DeleteMapping("/{deploymentId}")
    public ResponseEntity<SharedDeploymentBridge.SharedDeployment> revoke(RequestContext ctx,
            @PathVariable String deploymentId, @RequestBody(required = false) SharedDeploymentRevocation body) {
        permissions.require(ctx, SecurityPermissionBridge.SHARED_DEPLOYMENT_MANAGE);
        if (body == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "reason is required");
        }
        SharedDeploymentBridge.SharedDeployment d = bridge().revoke(ctx, deploymentId, body.reason());
        return Lists.okRef(d.deploymentId(), d);
    }

    private SharedDeploymentBridge bridge() {
        SharedDeploymentBridge b = registry.getIfAvailable();
        if (b == null) {
            throw ApiProblem.notInstalled("shared-deployment registry", "sangam-engine (SangamProcessEnginePlugin)");
        }
        return b;
    }
}
