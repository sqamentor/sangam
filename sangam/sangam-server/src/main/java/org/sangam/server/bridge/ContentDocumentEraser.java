// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.List;
import java.util.Objects;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.lifecycle.LifecyclePorts;
import org.sangam.ports.content.ContentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The document step of data erasure (VI.5, R74) on the server's document store ({@link ContentService}, the store
 * behind {@link PortsDocumentBridge}): every document of the erased process instances (scope {@value #SCOPE}, the root
 * and its sub-instances) is erased physically, all versions and the stored objects, including documents that were
 * only deleted logically before. Before this class the erasure service ran with no document eraser and reported
 * success while every document stayed in the store.
 *
 * <p>A document under its own legal hold (R61, {@code LEGAL_HOLD} with {@code root_id} = document id, as
 * {@link PortsDocumentBridge} records it) is kept: the hold wins, the document is not counted and the kept documents
 * are logged with their number, never their names. The erasure of an instance that is itself on hold is refused
 * earlier by the erasure service.
 */
public final class ContentDocumentEraser implements LifecyclePorts.DocumentEraser {

    private static final Logger LOG = LoggerFactory.getLogger(ContentDocumentEraser.class);

    /** The content scope of a process instance's documents ({@code PortsDocumentBridge.scope}). */
    public static final String SCOPE = "bpmn";

    private final ContentService content;
    private final Transactions tx;

    /**
     * @param content the document store
     * @param tx      short product transactions for the document list and the hold check
     */
    public ContentDocumentEraser(ContentService content, Transactions tx) {
        this.content = Objects.requireNonNull(content, "content");
        this.tx = Objects.requireNonNull(tx, "tx");
    }

    @Override
    public int erase(String tenantId, List<String> instanceIds, String reason) {
        int erased = 0;
        int held = 0;
        for (String instanceId : instanceIds == null ? List.<String>of() : instanceIds) {
            // every item of the scope, the logically deleted ones too (ContentService.byScope leaves those out)
            List<String> items = tx.inTransaction(c -> Jdbc.query(c, "SELECT id FROM CONTENT_ITEM WHERE tenant_id=?"
                    + " AND scope_type=? AND scope_id=? ORDER BY id", rs -> rs.getString(1), tenantId, SCOPE, instanceId));
            for (String item : items) {
                if (onHold(tenantId, item)) {
                    held++;
                    continue;
                }
                content.erase(item);
                erased++;
            }
        }
        if (held > 0) {
            LOG.warn("data erasure of tenant {}: {} document(s) under legal hold were kept ({} erased)", tenantId, held,
                    erased);
        }
        return erased;
    }

    private boolean onHold(String tenantId, String documentId) {
        return tx.inTransaction(c -> !Jdbc.query(c, "SELECT hold_id FROM LEGAL_HOLD WHERE tenant_id=? AND root_id=?"
                + " AND released_at IS NULL", rs -> rs.getString(1), tenantId, documentId).isEmpty());
    }
}
