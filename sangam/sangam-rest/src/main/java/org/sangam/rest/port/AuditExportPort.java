// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Asynchronous audit exports and evidence packs (R42, U02/U03, X.2.3): bounded, logged ({@code AUDIT_EXPORTED}),
 * tenant-scoped. The evidence pack is a ZIP of the class-B timeline, the chain verification, the model and form
 * versions used and the document list, with a SHA-256 manifest.
 */
public interface AuditExportPort {

    /** status: QUEUED | RUNNING | READY | FAILED. format: CSV | JSON | PDFA. */
    record ExportJob(String id, String status, Long rowCount, Instant requestedAt, String format,
                     Map<String, Object> scope, String error) {
    }

    /** scope: businessKey, chainId, type, actor, from, to. */
    record ExportRequest(String format, Map<String, Object> scope) {
    }

    ExportJob create(RequestContext ctx, ExportRequest request);

    List<ExportJob> list(RequestContext ctx);

    Download content(RequestContext ctx, String exportId);

    Download evidencePack(RequestContext ctx, String businessKey);
}
