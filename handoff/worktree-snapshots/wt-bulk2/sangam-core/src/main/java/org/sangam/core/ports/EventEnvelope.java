// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.ports;

import java.util.Map;

/**
 * Engine-neutral form of one event crossing a channel (event registry, FLOWABLE-INTO-OPERATON FC-02/FLOWABLE-INTO-OPERATON FC-03). Added by
 * WS-PORTS under {@code org.sangam.core.ports}; no existing core file is changed.
 *
 * @param tenantId       tenant of the channel binding (R38: the binding, not the payload, decides the tenant)
 * @param channelKey     channel the event arrived on or leaves through
 * @param eventKey       event definition key
 * @param messageId      broker/message identity used for inbox de-duplication
 * @param correlationKey optional correlation key (business key or correlation-parameter digest)
 * @param headers        transport headers (lower-case names)
 * @param payload        JSON payload text
 */
public record EventEnvelope(String tenantId, String channelKey, String eventKey, String messageId, String correlationKey,
                            Map<String, String> headers, String payload) {

    public EventEnvelope {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
