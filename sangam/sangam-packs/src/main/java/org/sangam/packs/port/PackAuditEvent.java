// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import java.time.Instant;
import java.util.Map;

/**
 * One business audit event (X.4.0.3). Field names follow the catalogue.
 *
 * @param type              event name, e.g. {@code CASE_SUBMITTED}, {@code APPROVAL_RECORDED}, {@code EXT_OP_UNKNOWN}
 * @param tenantId          tenant
 * @param businessKey       case business key
 * @param processInstanceId engine instance (root id is resolved by the sink)
 * @param actorPersonId     person who acted, or {@code system}
 * @param actorSeatId       seat acted as
 * @param actedForSeatId    substituted seat when acting on additional charge / delegation
 * @param authorityBasis    posting, additional-charge order, delegation id, SoD exception id, or {@code onBehalfOf}
 * @param packageVersion    pack id and version bound to the case (R43)
 * @param payloadHash       material payload hash where one exists
 * @param operationId       external operation id where one exists
 * @param details           event-specific fields (rule key and version, reason codes, levels, ...)
 * @param at                event time (UTC, NTP-synced host clock, R51)
 */
public record PackAuditEvent(String type, String tenantId, String businessKey, String processInstanceId,
                             String actorPersonId, String actorSeatId, String actedForSeatId, String authorityBasis,
                             String packageVersion, String payloadHash, String operationId,
                             Map<String, Object> details, Instant at) {

    public PackAuditEvent {
        details = details == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details));
    }
}
