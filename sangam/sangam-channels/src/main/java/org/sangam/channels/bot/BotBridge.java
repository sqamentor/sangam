// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.bot;

import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chatbots driven by process and case models, with one channel-neutral message contract. The dialogue lives in the model: a
 * message catch event receives input, and a send task (connector {@code bot} below) sends a prompt. Inbound messages go
 * through {@link #onInbound}: they correlate to the running conversation instance or start one. See
 * {@code resources/bots/sample-bot.bpmn} for the pattern.
 */
public final class BotBridge {

    /** The channel-neutral message. {@code externalId} is the sender's id on {@code channel}. */
    public record InboundMessage(String tenantId, String channel, String externalId, String principalId, String messageId, String text, Map<String, Object> attributes) {
    }

    /** Engine access through the REST or engine adapter. {@code correlate} returns false when no instance waits. */
    public interface ProcessGateway {
        boolean correlate(String tenantId, String messageName, String businessKey, Map<String, Object> variables);

        void start(String tenantId, String processKey, String businessKey, Map<String, Object> variables);
    }

    public static final String REPLY_MESSAGE = "bot.reply";

    private final ProcessGateway engine;
    private final Map<String, String> botProcessByChannel;

    /** {@code botProcessByChannel}: channel to process key; {@code *} is the fallback. */
    public BotBridge(ProcessGateway engine, Map<String, String> botProcessByChannel) {
        this.engine = engine;
        this.botProcessByChannel = Map.copyOf(botProcessByChannel);
    }

    /** One instance per (channel, external id). Duplicate deliveries are removed before this call by the adapter's inbox. */
    public void onInbound(InboundMessage m) {
        String businessKey = "bot:" + m.channel() + ":" + m.externalId();
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("botChannel", m.channel());
        vars.put("botExternalId", m.externalId());
        vars.put("botPrincipal", m.principalId());
        vars.put("botInput", m.text());
        if (engine.correlate(m.tenantId(), REPLY_MESSAGE, businessKey, vars)) {
            return;
        }
        String process = botProcessByChannel.getOrDefault(m.channel(), botProcessByChannel.get("*"));
        if (process == null) {
            throw new IllegalStateException("no bot process for channel " + m.channel());
        }
        engine.start(m.tenantId(), process, businessKey, vars);
    }

    /** Routes a prompt from a model to the channel's connector, so one model serves many channels. */
    public interface ChannelSender {
        ConnectorResult send(String channel, ConnectorRequest request);
    }

    public static ConnectorResult prompt(ChannelSender sender, String tenantId, UUID operationId, String rootId, String businessKey, String channel, String externalId, String text,
                                         List<String> quickReplies) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("to", externalId);
        in.put("text", text);
        in.put("quickReplies", quickReplies == null ? List.of() : quickReplies);
        return sender.send(channel, new ConnectorRequest(tenantId, operationId, null, 1, rootId, businessKey, "send", in, null));
    }
}
