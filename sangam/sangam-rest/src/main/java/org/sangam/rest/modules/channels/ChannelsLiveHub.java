// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.channels;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.sangam.channels.conversation.ConversationService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-sent events behind {@link ConversationService.LiveDelivery}. Streams are held per tenant and principal, so an
 * event can reach only a recipient of the same tenant. Streams live on this node: a client on another node catches up
 * from the history endpoint. A failed send drops the stream and never affects the stored message.
 */
public final class ChannelsLiveHub implements ConversationService.LiveDelivery {

    private record Stream(String conversationId, SseEmitter emitter) {
    }

    private final Map<String, List<Stream>> streams = new ConcurrentHashMap<>();

    /** The caller has already been checked as a participant of the conversation. */
    public SseEmitter subscribe(String tenantId, String principal, String conversationId) {
        SseEmitter emitter = new SseEmitter(0L);
        Stream stream = new Stream(conversationId, emitter);
        String key = key(tenantId, principal);
        streams.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(stream);
        Runnable remove = () -> streams.computeIfPresent(key, (k, list) -> {
            list.remove(stream);
            return list.isEmpty() ? null : list;
        });
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    @Override
    public void deliver(String tenantId, Set<String> recipients, ConversationService.Message message) {
        for (String recipient : recipients) {
            List<Stream> list = streams.get(key(tenantId, recipient));
            if (list == null) {
                continue;
            }
            for (Stream s : list) {
                if (s.conversationId().equals(message.conversationId())) {
                    try {
                        s.emitter().send(SseEmitter.event().name("message").id(message.id()).data(message));
                    } catch (IOException | IllegalStateException e) {
                        s.emitter().completeWithError(e);
                    }
                }
            }
        }
    }

    private static String key(String tenantId, String principal) {
        return tenantId + '\n' + principal;
    }
}
