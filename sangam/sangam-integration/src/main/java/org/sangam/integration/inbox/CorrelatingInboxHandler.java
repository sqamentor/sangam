// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import org.sangam.integration.connector.Json;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Default inbox handler: correlate to a waiting instance, else apply the event definition's policy (VI.2.6 rule 3):
 * buffer, reject, or start a new instance guarded by {@code uniqueStart} (rule 4). Fixes Operaton D-33 (early messages
 * are lost with {@code MismatchingMessageCorrelationException}): an early message waits in the inbox as BUFFERED.
 *
 * <p>Variables passed to the engine: the payload's top-level fields, plus {@code sangamInboxSource},
 * {@code sangamInboxMessageId} and {@code sangamEventType}.
 */
public final class CorrelatingInboxHandler implements InboxHandler {

    private final MessageCorrelationPort engine;
    private final EventDefinitionPolicy.Registry policies;
    private final UniqueStartGuard uniqueStart;

    public CorrelatingInboxHandler(MessageCorrelationPort engine, EventDefinitionPolicy.Registry policies, UniqueStartGuard uniqueStart) {
        this.engine = engine;
        this.policies = policies;
        this.uniqueStart = uniqueStart;
    }

    @Override
    public Disposition handle(Connection c, Envelope m) {
        EventDefinitionPolicy p = policies.get(m.eventType());
        Map<String, Object> vars = new LinkedHashMap<>(Json.readMap(m.dataJson() == null || !m.dataJson().trim().startsWith("{")
                ? null : m.dataJson()));
        vars.put("sangamInboxSource", m.source());
        vars.put("sangamInboxMessageId", m.messageId());
        vars.put("sangamEventType", m.eventType());
        if (m.correlationKey() != null && engine.hasSubscription(c, m.tenantId(), p.messageName(), m.correlationKey())) {
            int n = engine.correlate(c, m.tenantId(), p.messageName(), m.correlationKey(), vars);
            if (n > 0) {
                return Disposition.PROCESSED;
            }
        }
        return switch (p.noSubscription()) {
            case BUFFER -> Disposition.NO_SUBSCRIPTION;
            case REJECT -> Disposition.REJECTED;
            case START_NEW -> {
                String bk = m.correlationKey();
                if (p.uniqueStart() && bk != null) {
                    if (!uniqueStart.claim(c, m.tenantId(), p.startDefinitionKey(), bk, m.source(), m.messageId())) {
                        yield Disposition.PROCESSED;   // duplicate business start: already started, drop (T35)
                    }
                    String id = engine.start(c, m.tenantId(), p.startDefinitionKey(), bk, vars);
                    uniqueStart.bind(c, m.tenantId(), p.startDefinitionKey(), bk, id);
                } else {
                    engine.start(c, m.tenantId(), p.startDefinitionKey(), bk, vars);
                }
                yield Disposition.PROCESSED;
            }
        };
    }
}
