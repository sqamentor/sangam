// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events;

import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Outbox;
import org.sangam.ports.events.model.EventDefinition;
import org.sangam.ports.support.Json;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Send-event path (FLOWABLE-INTO-OPERATON FC-02 send-event task / throw event, R4): an outbound event is one {@link Outbox} row with one
 * delivery per channel ({@code channel:<key>}), written in the engine transaction. The relay (WS-INTEGRATION) hands
 * each delivery to {@link ChannelPublisher}; the event leaves the system only if the engine transaction committed.
 */
public class OutboundEventService {

    public static final String DESTINATION_PREFIX = "channel:";
    public static final String EVENT_TYPE_PREFIX = "sangam.event.";
    private static final UUID NS = UUID.fromString("6f1c3c1e-6c55-4b1e-9b7e-7e0d6a2b5c01");

    private final EventRegistry registry;
    private final Outbox outbox;
    private final EventLog log;

    public OutboundEventService(EventRegistry registry, Outbox outbox, EventLog log) {
        this.registry = registry;
        this.outbox = outbox;
        this.log = log;
    }

    /**
     * Appends the event. {@code values} supplies payload fields by name (process variables for the BPMN path); only
     * fields declared in the event definition are sent. {@code operationScope} makes the message id deterministic
     * (UUIDv5 over tenant, root, event key and scope), so a retried command produces the same message id (R36).
     */
    public Outbox.Appended send(Connection c, String tenant, String rootId, String eventKey, List<String> channelKeys,
                                Map<String, Object> values, String operationScope) {
        EventDefinition def = registry.event(tenant, eventKey).map(EventRegistry.DeployedEvent::definition)
                .orElseThrow(() -> new IllegalArgumentException("event " + eventKey + " not deployed in tenant " + tenant));
        if (channelKeys == null || channelKeys.isEmpty()) {
            throw new IllegalArgumentException("send event " + eventKey + ": no outbound channel");
        }
        List<Outbox.Destination> dests = new ArrayList<>();
        for (String ck : channelKeys) {
            EventRegistry.DeployedChannel ch = registry.channel(tenant, ck)
                    .orElseThrow(() -> new IllegalArgumentException("unknown channel " + ck));
            if (ch.definition().direction() != org.sangam.ports.events.model.ChannelDefinition.Direction.OUTBOUND) {
                throw new IllegalArgumentException("channel " + ck + " is not outbound");
            }
            dests.add(Outbox.Destination.ordered(DESTINATION_PREFIX + ck));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        for (EventDefinition.Field f : def.payload()) {
            if (values.containsKey(f.name())) {
                payload.put(f.name(), values.get(f.name()));
            } else if (f.isRequired()) {
                throw new IllegalArgumentException("send event " + eventKey + ": required payload field '" + f.name() + "' missing");
            }
        }
        String messageId = Hashes.uuidV5(NS, tenant + "|" + rootId + "|" + eventKey + "|" + operationScope).toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventKey", eventKey);
        body.put("messageId", messageId);
        body.put("payload", payload);
        String json = Json.write(body);
        Outbox.Appended a = outbox.append(c, tenant, rootId, EVENT_TYPE_PREFIX + eventKey, json, dests);
        log.log(c, tenant, EventLog.Direction.OUTBOUND, String.join(",", channelKeys), eventKey, messageId, rootId, "QUEUED", null, json);
        return a;
    }
}
