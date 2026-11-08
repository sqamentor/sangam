// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.DeploymentPort;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Raw deployments and governed executable packages (R43 / X.2.4, FLOWABLE-INTO-OPERATON FC-41 app bundles). */
@RestController
@RequestMapping("/api/v1")
public class DeploymentController {

    private final DeploymentPort deployments;
    private final PackagePort packages;
    private final PermissionPort permissions;

    public DeploymentController(DeploymentPort deployments, PackagePort packages, PermissionPort permissions) {
        this.deployments = deployments;
        this.packages = packages;
        this.permissions = permissions;
    }

    @PostMapping(value = "/deployments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DeploymentPort.Deployment> deploy(RequestContext ctx,
            @RequestParam(required = false) String name, @RequestParam(required = false) String source,
            @RequestParam(required = false, defaultValue = "false") boolean duplicateFiltering,
            @RequestPart("files") List<MultipartFile> files) throws IOException {
        permissions.require(ctx, Permissions.DEPLOYMENT_WRITE);
        if (files == null || files.isEmpty()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "at least one file is required");
        }
        Map<String, byte[]> resources = new LinkedHashMap<>();
        for (MultipartFile f : files) {
            String n = f.getOriginalFilename() != null ? f.getOriginalFilename() : f.getName();
            resources.put(n, f.getBytes());
        }
        DeploymentPort.Deployment d = deployments.deploy(ctx, new DeploymentPort.DeployRequest(
                name != null ? name : "deployment", source != null ? source : "sangam-rest", duplicateFiltering, resources));
        return Lists.created("/api/v1/deployments/" + d.id(), d.id(), d);
    }

    @GetMapping("/deployments")
    public ResponseEntity<List<DeploymentPort.Deployment>> list(RequestContext ctx,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.DEPLOYMENT_READ);
        return Lists.ok(deployments.list(ctx, name, Lists.page(page, size)));
    }

    @GetMapping("/deployments/{id}")
    public DeploymentPort.Deployment get(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.DEPLOYMENT_READ);
        return deployments.get(ctx, id);
    }

    @GetMapping("/deployments/{id}/resources/{resourceName}")
    public ResponseEntity<byte[]> resource(RequestContext ctx, @PathVariable String id,
                                           @PathVariable String resourceName) {
        permissions.require(ctx, Permissions.DEPLOYMENT_READ);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", ContentDisposition.attachment().filename(resourceName).build().toString())
                .body(deployments.resource(ctx, id, resourceName));
    }

    @DeleteMapping("/deployments/{id}")
    public ResponseEntity<Void> delete(RequestContext ctx, @PathVariable String id,
            @RequestParam(required = false, defaultValue = "false") boolean cascade,
            @RequestParam(required = false) String reason) {
        permissions.require(ctx, Permissions.DEPLOYMENT_WRITE);
        ProcessController.requireReason(reason);
        deployments.delete(ctx, id, cascade, reason);
        return ResponseEntity.noContent().header(Lists.RESOURCE_REF, id).build();
    }

    @PostMapping("/packages")
    public ResponseEntity<PackagePort.Package> createPackage(RequestContext ctx,
                                                             @RequestBody PackagePort.CreatePackageRequest body) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        PackagePort.Package p = packages.create(ctx, body);
        return ResponseEntity.created(java.net.URI.create("/api/v1/packages/" + p.id()))
                .header(Lists.RESOURCE_REF, p.id()).eTag("\"" + p.revision() + "\"").body(p);
    }

    @GetMapping("/packages")
    public ResponseEntity<List<PackagePort.Package>> packages(RequestContext ctx,
            @RequestParam(required = false) String key, @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return Lists.ok(packages.list(ctx, key, state, Lists.page(page, size)));
    }

    @GetMapping("/packages/{id}")
    public ResponseEntity<PackagePort.Package> getPackage(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        PackagePort.Package p = packages.get(ctx, id);
        return Lists.withRevision(p.revision(), p);
    }

    @PutMapping("/packages/{id}")
    public ResponseEntity<PackagePort.Package> updatePackage(RequestContext ctx, @PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody PackagePort.UpdatePackageRequest body) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        PackagePort.Package p = packages.update(ctx, id, Lists.ifMatchRevision(ifMatch), body);
        return ResponseEntity.ok().header(Lists.RESOURCE_REF, id).eTag("\"" + p.revision() + "\"").body(p);
    }

    @PostMapping("/packages/{id}/transitions")
    public ResponseEntity<PackagePort.Package> transition(RequestContext ctx, @PathVariable String id,
                                                          @RequestBody PackagePort.TransitionRequest body) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        if (body.action() == null || body.action().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "action is required");
        }
        // The adapter checks the permission per transition (author / reviewer / approver / deployer / operator).
        return Lists.okRef(id, packages.transition(ctx, id, body));
    }

    @GetMapping("/packages/{id}/diff")
    public ResponseEntity<List<PackagePort.DiffEntry>> diff(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return Lists.ok(packages.diff(ctx, id));
    }
}
