// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PackageToolsPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Modeller tooling: validation (R44, X.2.4.6, FLOWABLE-INTO-OPERATON FC-46), model tests (R80), Git (R69), review comments and the
 * migration assessment (EC-21). {@code /packages/assessment} is a literal path and wins over {@code /packages/{id}}.
 */
@RestController
@RequestMapping("/api/v1/packages")
public class PackageToolsController {

    private static final long MAX_ASSESSMENT_BYTES = 20L * 1024 * 1024;

    private final PackageToolsPort tools;
    private final PermissionPort permissions;

    public PackageToolsController(PackageToolsPort tools, PermissionPort permissions) {
        this.tools = tools;
        this.permissions = permissions;
    }

    @PostMapping("/{id}/validate")
    public ResponseEntity<List<PackageToolsPort.ValidationIssue>> validate(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return Lists.ok(tools.validate(ctx, id));
    }

    @PostMapping("/{id}/tests/run")
    public ResponseEntity<List<PackageToolsPort.TestRun>> runTests(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        return Lists.ok(tools.runTests(ctx, id));
    }

    @GetMapping("/{id}/git")
    public PackageToolsPort.GitStatus gitStatus(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return tools.gitStatus(ctx, id);
    }

    @PutMapping("/{id}/git")
    public ResponseEntity<PackageToolsPort.GitStatus> configureGit(RequestContext ctx, @PathVariable String id,
                                                                   @RequestBody PackageToolsPort.GitConfig body) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        return Lists.okRef(id, tools.configureGit(ctx, id, body));
    }

    @PostMapping("/{id}/git/pull")
    public ResponseEntity<PackageToolsPort.GitSyncResult> pull(RequestContext ctx, @PathVariable String id,
            @RequestBody(required = false) PackageToolsPort.GitSyncRequest body) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        return Lists.okRef(id, tools.gitSync(ctx, id, "pull", body));
    }

    @PostMapping("/{id}/git/push")
    public ResponseEntity<PackageToolsPort.GitSyncResult> push(RequestContext ctx, @PathVariable String id,
            @RequestBody(required = false) PackageToolsPort.GitSyncRequest body) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        return Lists.okRef(id, tools.gitSync(ctx, id, "push", body));
    }

    @GetMapping("/{id}/comments")
    public ResponseEntity<List<PackageToolsPort.PackageComment>> comments(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return Lists.ok(tools.comments(ctx, id));
    }

    @PostMapping("/{id}/comments")
    public ResponseEntity<PackageToolsPort.PackageComment> addComment(RequestContext ctx, @PathVariable String id,
            @RequestBody PackageToolsPort.AddCommentRequest body) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        PackageToolsPort.PackageComment c = tools.addComment(ctx, id, body);
        return Lists.created("/api/v1/packages/" + id + "/comments", c.id(), c);
    }

    @PostMapping(value = "/assessment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<PackageToolsPort.AssessmentRow>> assess(RequestContext ctx,
            @RequestPart("files") List<MultipartFile> files) throws IOException {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        List<PackageToolsPort.AssessmentFile> in = new ArrayList<>();
        long total = 0;
        for (MultipartFile f : files) {
            total += f.getSize();
            if (total > MAX_ASSESSMENT_BYTES) {
                throw new ApiProblem(413, "PAYLOAD_TOO_LARGE", "Assessment uploads are limited to 20 MB in total");
            }
            in.add(new PackageToolsPort.AssessmentFile(f.getOriginalFilename() != null ? f.getOriginalFilename()
                    : f.getName(), f.getBytes()));
        }
        return Lists.ok(tools.assess(ctx, in));
    }
}
