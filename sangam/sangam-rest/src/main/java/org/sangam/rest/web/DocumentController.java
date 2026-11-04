// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.io.IOException;
import java.util.List;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.Download;
import org.sangam.rest.port.LinkPort;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Documents/content (FC-40, R74) and entity links (FC-31, FC-32). */
@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private final DocumentPort documents;
    private final LinkPort links;
    private final PermissionPort permissions;

    public DocumentController(DocumentPort documents, LinkPort links, PermissionPort permissions) {
        this.documents = documents;
        this.links = links;
        this.permissions = permissions;
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentPort.Document> upload(RequestContext ctx, @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String name, @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String caseInstanceId) throws IOException {
        permissions.require(ctx, Permissions.DOCUMENT_WRITE);
        DocumentPort.Document d = documents.upload(ctx, new DocumentPort.UploadRequest(
                name != null ? name : file.getOriginalFilename(), file.getContentType(), file.getBytes(), taskId,
                processInstanceId, caseInstanceId, null));
        return Lists.created("/api/v1/documents/" + d.id(), d.id(), d);
    }

    @GetMapping("/documents")
    public ResponseEntity<List<DocumentPort.Document>> list(RequestContext ctx,
            @RequestParam(required = false) String taskId, @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String caseInstanceId, @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.DOCUMENT_READ);
        return Lists.ok(documents.list(ctx, new DocumentPort.DocumentFilter(taskId, processInstanceId, caseInstanceId,
                name), Lists.page(page, size)));
    }

    @GetMapping("/documents/{id}")
    public DocumentPort.Document get(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.DOCUMENT_READ);
        return documents.get(ctx, id);
    }

    @GetMapping("/documents/{id}/content")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> content(RequestContext ctx, @PathVariable String id,
                                          @RequestParam(required = false) Integer version) {
        permissions.require(ctx, Permissions.DOCUMENT_READ);
        return download(documents.content(ctx, id, version));
    }

    @PostMapping(value = "/documents/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentPort.Document> newVersion(RequestContext ctx, @PathVariable String id,
            @RequestPart("file") MultipartFile file, @RequestParam(required = false) String name) throws IOException {
        permissions.require(ctx, Permissions.DOCUMENT_WRITE);
        DocumentPort.Document d = documents.newVersion(ctx, id, new DocumentPort.UploadRequest(
                name != null ? name : file.getOriginalFilename(), file.getContentType(), file.getBytes(), null, null,
                null, null));
        return Lists.created("/api/v1/documents/" + d.id(), d.id(), d);
    }

    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> delete(RequestContext ctx, @PathVariable String id,
                                       @RequestParam(required = false) String reason) {
        permissions.require(ctx, Permissions.DOCUMENT_WRITE);
        ProcessController.requireReason(reason);
        documents.delete(ctx, id, reason);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/links")
    public ResponseEntity<List<LinkPort.EntityLink>> links(RequestContext ctx,
            @RequestParam String scopeType, @RequestParam String scopeId,
            @RequestParam(required = false) String linkType) {
        permissions.require(ctx, Permissions.PROCESS_READ);
        return Lists.ok(links.links(ctx, scopeType, scopeId, linkType));
    }

    @PostMapping("/links")
    public ResponseEntity<LinkPort.EntityLink> createLink(RequestContext ctx, @RequestBody LinkPort.CreateLinkRequest body) {
        permissions.require(ctx, Permissions.PROCESS_WRITE);
        if (body.sourceId() == null || body.targetId() == null || body.linkType() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "linkType, sourceId and targetId are required");
        }
        LinkPort.EntityLink l = links.create(ctx, body);
        return Lists.created("/api/v1/links/" + l.id(), l.id(), l);
    }

    @DeleteMapping("/links/{id}")
    public ResponseEntity<Void> deleteLink(RequestContext ctx, @PathVariable String id,
                                           @RequestParam(required = false) String reason) {
        permissions.require(ctx, Permissions.PROCESS_WRITE);
        links.delete(ctx, id, reason);
        return ResponseEntity.noContent().build();
    }

    /** Streams a download to the response (no full buffering); Content-Length when the size is known. */
    static ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> download(Download d) {
        var builder = ResponseEntity.ok().contentType(MediaType.parseMediaType(d.contentType()))
                .header("Content-Disposition", ContentDisposition.attachment().filename(d.filename()).build().toString());
        if (d.size() >= 0) {
            builder = builder.contentLength(d.size());
        }
        return builder.body(out -> {
            try (java.io.InputStream in = d.body().open()) {
                in.transferTo(out);
            }
        });
    }
}
