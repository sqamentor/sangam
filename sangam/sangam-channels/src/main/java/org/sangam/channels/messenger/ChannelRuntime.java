// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.messenger;

import org.sangam.channels.messenger.ProviderTokens.TokenSource;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.SangamConnector;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;

import java.net.URI;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds the running connector for an R81 {@code conversation-channels} registry entry, so a stored channel definition has a runtime.
 * Fields: {@code key}, {@code channel}, and per channel:
 * <ul>
 *   <li>TEAMS: {@code tokenRef}, or {@code clientId} + {@code clientSecretRef} (optional {@code tokenUrl}, default the Bot
 *       Framework token endpoint) to fetch and refresh the token here.</li>
 *   <li>WHATSAPP: {@code apiBase}, {@code phoneNumberId}, {@code tokenRef}; optional {@code templates}.</li>
 *   <li>WECHAT: {@code apiBase}, and {@code tokenRef}, or {@code appId} + {@code appSecretRef} to fetch and refresh the stable
 *       access token here; optional {@code templates}.</li>
 *   <li>MATRIX: {@code apiBase} (the homeserver), {@code tokenRef} (the bridge user's access token).</li>
 *   <li>SLACK: {@code tokenRef} (the bot token), optional {@code apiBase} (default {@link MessengerConnectors#SLACK_API}).</li>
 * </ul>
 * {@code templates} is the list of provider-approved templates ({@link TemplateRules}). Any other channel is refused here
 * rather than accepted and silently not delivered.
 */
public final class ChannelRuntime {

    /** Shared by the overload without a {@link ProviderTokens}, so cached tokens outlive a runtime refresh. */
    private static final ProviderTokens SHARED = new ProviderTokens(Clock.systemUTC());

    private ChannelRuntime() {
    }

    /** Uses a process-wide token cache; a host that owns its own {@link ProviderTokens} calls the other overload. */
    public static SangamConnector connector(Map<String, Object> doc, ConnectorProfile profile, HttpTransport transport, SecretResolver secrets, Function<ConnectorRequest, Boolean> optedIn) {
        return connector(doc, profile, transport, secrets, SHARED, optedIn);
    }

    public static SangamConnector connector(Map<String, Object> doc, ConnectorProfile profile, HttpTransport transport, SecretResolver secrets, ProviderTokens tokens,
                                            Function<ConnectorRequest, Boolean> optedIn) {
        String id = String.valueOf(doc.get("key"));
        String channel = channel(doc);
        return switch (channel) {
            case "TEAMS" -> MessengerConnectors.teams(id, profile, transport, tokenSource(channel, doc, transport, secrets, tokens), optedIn);
            case "WHATSAPP" -> MessengerConnectors.whatsApp(id, profile, URI.create(required(doc, "apiBase")), required(doc, "phoneNumberId"),
                    tokenSource(channel, doc, transport, secrets, tokens), TemplateRules.fromEntry(doc), transport, optedIn);
            case "WECHAT" -> MessengerConnectors.weChat(id, profile, URI.create(required(doc, "apiBase")), tokenSource(channel, doc, transport, secrets, tokens),
                    TemplateRules.fromEntry(doc), transport, optedIn);
            case "MATRIX" -> MessengerConnectors.matrix(id, profile, URI.create(required(doc, "apiBase")), tokenSource(channel, doc, transport, secrets, tokens),
                    transport, optedIn);
            case "SLACK" -> MessengerConnectors.slack(id, profile, slackApi(doc), tokenSource(channel, doc, transport, secrets, tokens), transport, optedIn);
            default -> throw new IllegalArgumentException("CHANNEL_ADAPTER_NOT_AVAILABLE: " + channel);
        };
    }

    /**
     * Downloads the media an inbound message refers to ({@code mediaRef} from {@link InboundMessageParsers}) with the same
     * entry fields, token cache and transport as the channel's connector. Teams attachments are not downloaded here.
     *
     * @throws MediaDownloads.MediaUnavailableException when the provider cannot give it (see {@code retryable()})
     */
    public static MediaDownloads.Media media(String tenantId, Map<String, Object> doc, HttpTransport transport, SecretResolver secrets,
                                             ProviderTokens tokens, String mediaRef) {
        String channel = channel(doc);
        MediaDownloads d = new MediaDownloads(transport);
        return switch (channel) {
            case "WHATSAPP" -> d.whatsApp(tenantId, URI.create(required(doc, "apiBase")), tokenSource(channel, doc, transport, secrets, tokens), mediaRef);
            case "WECHAT" -> d.weChat(tenantId, URI.create(required(doc, "apiBase")), tokenSource(channel, doc, transport, secrets, tokens), mediaRef);
            case "MATRIX" -> d.matrix(tenantId, URI.create(required(doc, "apiBase")), tokenSource(channel, doc, transport, secrets, tokens), mediaRef);
            case "SLACK" -> d.slack(tenantId, slackApi(doc), tokenSource(channel, doc, transport, secrets, tokens), mediaRef);
            default -> throw new MediaDownloads.MediaUnavailableException("no media download for channel " + channel, false);
        };
    }

    private static String channel(Map<String, Object> doc) {
        return String.valueOf(doc.get("channel")).toUpperCase(Locale.ROOT);
    }

    private static URI slackApi(Map<String, Object> doc) {
        return URI.create(optional(doc, "apiBase", MessengerConnectors.SLACK_API.toString()));
    }

    /** The provider token of an entry: fetched and refreshed here when the entry names client credentials, else a secret. */
    private static TokenSource tokenSource(String channel, Map<String, Object> doc, HttpTransport transport, SecretResolver secrets, ProviderTokens tokens) {
        if ("TEAMS".equals(channel) && has(doc, "clientSecretRef")) {
            return tokens.oauth2(URI.create(optional(doc, "tokenUrl", ProviderTokens.BOT_FRAMEWORK_TOKEN_URI.toString())), required(doc, "clientId"),
                    required(doc, "clientSecretRef"), ProviderTokens.BOT_FRAMEWORK_SCOPE, secrets, transport);
        }
        if ("WECHAT".equals(channel) && has(doc, "appSecretRef")) {
            return tokens.weChat(URI.create(required(doc, "apiBase")), required(doc, "appId"), required(doc, "appSecretRef"), secrets, transport);
        }
        return TokenSource.ofSecret(secrets, required(doc, "tokenRef"));
    }

    private static boolean has(Map<String, Object> doc, String field) {
        Object v = doc.get(field);
        return v != null && !v.toString().isBlank();
    }

    private static String optional(Map<String, Object> doc, String field, String fallback) {
        return has(doc, field) ? doc.get(field).toString() : fallback;
    }

    private static String required(Map<String, Object> doc, String field) {
        if (!has(doc, field)) {
            throw new IllegalArgumentException("channel entry needs " + field);
        }
        return doc.get(field).toString();
    }
}
