// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.Map;

/**
 * Content and attachments (FLOWABLE-INTO-OPERATON FC-40, EC-18, R74, R47 attachments, K12): immutable versions with SHA-256 checksums,
 * a malware-scan hook ({@code scanStatus}), references passed to forms/workers instead of content.
 * Implemented by sangam-flowable-ports; the default adapter uses Operaton task/process attachments.
 */
public interface DocumentPort {

    /** scanStatus: PENDING | CLEAN | INFECTED | NOT_SCANNED. */
    record Document(String id, String tenantId, String name, String contentType, long size, String sha256,
                    int version, String taskId, String processInstanceId, String caseInstanceId, String scanStatus,
                    String createdBy, Instant createdAt, Map<String, Object> metadata) {
    }

    record DocumentFilter(String taskId, String processInstanceId, String caseInstanceId, String name) {
    }

    record UploadRequest(String name, String contentType, byte[] content, String taskId, String processInstanceId,
                         String caseInstanceId, Map<String, Object> metadata) {
    }

    Document upload(RequestContext ctx, UploadRequest request);

    /** Adds a new immutable version of an existing document. */
    Document newVersion(RequestContext ctx, String documentId, UploadRequest request);

    Page<Document> list(RequestContext ctx, DocumentFilter filter, Page.PageRequest page);

    Document get(RequestContext ctx, String documentId);

    Download content(RequestContext ctx, String documentId, Integer version);

    void delete(RequestContext ctx, String documentId, String reason);
}
