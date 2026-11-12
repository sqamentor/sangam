// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;

import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M-074 (R43, X.2.4.5, T70, T50): package signatures, export and import of the same bytes between environments,
 * air-gapped verification and the ENV_BINDING values, behind the contract routes of {@code package_promotion.py}.
 * The permission named by the contract is checked here; the adapter checks it again with the state rules.
 * Idempotency of the three commands ({@code package.import}, {@code env-binding.set}, {@code env-binding.approve}) is
 * the {@link IdempotencyFilter}'s, from the contract.
 */
@RestController
@RequestMapping("/api/v1")
public class PackagePromotionController {

    private final PackagePort packages;
    private final PermissionPort permissions;

    public PackagePromotionController(PackagePort packages, PermissionPort permissions) {
        this.packages = packages;
        this.permissions = permissions;
    }

    @GetMapping("/packages/{id}/signatures")
    public ResponseEntity<List<PackagePort.PackageSignature>> signatures(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return Lists.ok(packages.signatures(ctx, id));
    }

    @GetMapping("/packages/{id}/export")
    public ResponseEntity<PackagePort.PackageBundle> export(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.PACKAGE_DEPLOY);
        return Lists.okRef(id, packages.export(ctx, id));
    }

    @PostMapping("/packages/import/verify")
    public PackagePort.PackageVerification verify(RequestContext ctx, @RequestBody PackagePort.PackageBundle bundle) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return packages.verify(ctx, bundle);
    }

    @PostMapping("/packages/import")
    public ResponseEntity<PackagePort.Package> importBundle(RequestContext ctx,
                                                            @RequestBody PackagePort.PackageImportRequest body) {
        permissions.require(ctx, Permissions.PACKAGE_DEPLOY);
        PackagePort.Package p = packages.importBundle(ctx, body);
        return Lists.created("/api/v1/packages/" + p.id(), p.id(), p);
    }

    @GetMapping("/env-bindings")
    public ResponseEntity<List<PackagePort.EnvBinding>> envBindings(RequestContext ctx,
            @RequestParam(required = false) String env, @RequestParam(required = false) String name,
            @RequestParam(required = false) String state) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return Lists.ok(packages.envBindings(ctx, env, name, state));
    }

    @PutMapping("/env-bindings/{name}")
    public ResponseEntity<PackagePort.EnvBinding> setEnvBinding(RequestContext ctx, @PathVariable String name,
                                                                @RequestBody PackagePort.EnvBindingRequest body) {
        permissions.require(ctx, Permissions.PACKAGE_DEPLOY);
        return Lists.okRef(name, packages.setEnvBinding(ctx, name, body));
    }

    @PostMapping("/env-bindings/{name}/approve")
    public ResponseEntity<PackagePort.EnvBinding> approveEnvBinding(RequestContext ctx, @PathVariable String name,
                                                                    @RequestBody PackagePort.EnvBindingApproval body) {
        permissions.require(ctx, Permissions.PACKAGE_APPROVE);
        return Lists.okRef(name, packages.approveEnvBinding(ctx, name, body));
    }
}
