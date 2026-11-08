// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Event registry (FLOWABLE-INTO-OPERATON FC-02, FLOWABLE-INTO-OPERATON FC-03, FLOWABLE-INTO-OPERATON FC-04; R38 inbox, R83): channels, event definitions and publication of CloudEvents
 * 1.0. Inbound events are de-duplicated through the inbox by {@code (tenant, source, id)} and then start a process
 * ({@code uniqueStart} on the business key) or correlate a message. Implemented by sangam-flowable-ports.
 * The channel keys {@code channels} and {@code definitions} are reserved.
 */
public interface EventPort {

    /** direction: INBOUND | OUTBOUND. transport: REST | WEBHOOK | KAFKA | JMS | AMQP. */
    record Channel(String key, String tenantId, String name, String direction, String transport, String destination,
                   List<String> eventDefinitionKeys, String keyDetector, String tenantDetector,
                   Integer dedupWindowSeconds, Map<String, Object> config, boolean active, long revision) {
    }

    /**
     * startProcessKey or messageName says what an event does. correlationParameters are JSON pointers into the
     * event data; businessKeyPointer selects the business key ({@code storeAsUniqueReferenceId}).
     */
    record EventDefinition(String key, String tenantId, String name, int version, Map<String, Object> payloadSchema,
                           List<String> correlationParameters, String businessKeyPointer, String startProcessKey,
                           String messageName, boolean uniqueStart, long revision) {
    }

    /** CloudEvents 1.0 structured mode (JSON). Extension attributes go in {@code extensions}. */
    record CloudEvent(String id, String source, String type, String specversion, String subject, Instant time,
                      String datacontenttype, String dataschema, Object data, Map<String, Object> extensions) {
    }

    /** status: ACCEPTED | DUPLICATE | BUFFERED. */
    record PublishResult(String eventId, String status, String eventDefinitionKey, List<String> startedInstanceIds,
                         List<String> correlatedInstanceIds) {
    }

    Page<Channel> channels(RequestContext ctx, Page.PageRequest page);

    Channel channel(RequestContext ctx, String key);

    Channel createChannel(RequestContext ctx, Channel channel);

    Channel updateChannel(RequestContext ctx, String key, Channel channel);

    void deleteChannel(RequestContext ctx, String key, String reason);

    Page<EventDefinition> definitions(RequestContext ctx, Page.PageRequest page);

    EventDefinition definition(RequestContext ctx, String key);

    EventDefinition createDefinition(RequestContext ctx, EventDefinition definition);

    void deleteDefinition(RequestContext ctx, String key, String reason);

    PublishResult publish(RequestContext ctx, String channelKey, CloudEvent event);

    /**
     * Retires every active version of a channel: it no longer resolves and stops receiving. Refused with
     * {@code 409 EVENT_IN_FLIGHT} while events on it are buffered in the inbox or undelivered in the outbox.
     * Returns the channel as it was, with {@code active=false}.
     */
    Channel retireChannel(RequestContext ctx, String key, String reason);

    /** Re-activates the newest retired version of a channel (undo of a retire). */
    Channel restoreChannel(RequestContext ctx, String key, String reason);

    /** Retires every active version of an event definition; {@code 409 EVENT_IN_FLIGHT} as for channels. */
    EventDefinition retireDefinition(RequestContext ctx, String key, String reason);

    /** Re-activates the newest retired version of an event definition. */
    EventDefinition restoreDefinition(RequestContext ctx, String key, String reason);
}
