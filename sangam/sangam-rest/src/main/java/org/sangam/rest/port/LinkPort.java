// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;

/**
 * Entity links (FC-31 sibling/reference links, FC-32 parent/child/root hierarchy across BPMN, CMMN and tasks, FC-59
 * reactivation links). Implemented by sangam-flowable-ports ({@code INSTANCE_LINK} table); the default adapter derives
 * the hierarchy from Operaton super/sub instances and keeps explicit links in memory.
 */
public interface LinkPort {

    /** linkType: PARENT | CHILD | ROOT | REFERENCE | SIBLING | REACTIVATED_FROM. scopeType: bpmn | cmmn | task. */
    record EntityLink(String id, String tenantId, String linkType, String sourceScopeType, String sourceId,
                      String targetScopeType, String targetId, String referenceType, Instant createdAt,
                      String createdBy) {
    }

    record CreateLinkRequest(String linkType, String sourceScopeType, String sourceId, String targetScopeType,
                             String targetId, String referenceType) {
    }

    List<EntityLink> links(RequestContext ctx, String scopeType, String scopeId, String linkType);

    EntityLink create(RequestContext ctx, CreateLinkRequest request);

    void delete(RequestContext ctx, String linkId, String reason);
}
