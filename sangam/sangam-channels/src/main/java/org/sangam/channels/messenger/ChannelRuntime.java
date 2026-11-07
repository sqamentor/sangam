// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.messenger;

import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.SangamConnector;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds the running connector for an R81 {@code conversation-channels} registry entry, so a stored channel definition has a runtime.
 * Fields: {@code key}, {@code channel}, {@code tokenRef}, and per channel {@code apiBase} and {@code phoneNumberId}. Entries for channels
 * with no adapter (MATRIX, SLACK) are refused here rather than accepted and silently not delivered.
 */
public final class ChannelRuntime {

    private ChannelRuntime() {
    }

    public static SangamConnector connector(Map<String, Object> doc, ConnectorProfile profile, HttpTransport transport, SecretResolver secrets, Function<ConnectorRequest, Boolean> optedIn) {
        String id = String.valueOf(doc.get("key"));
        String channel = String.valueOf(doc.get("channel")).toUpperCase(Locale.ROOT);
        String tokenRef = required(doc, "tokenRef");
        return switch (channel) {
            case "TEAMS" -> MessengerConnectors.teams(id, profile, transport, secrets, tokenRef, optedIn);
            case "WHATSAPP" -> MessengerConnectors.whatsApp(id, profile, URI.create(required(doc, "apiBase")), required(doc, "phoneNumberId"), tokenRef, transport, secrets, optedIn);
            case "WECHAT" -> MessengerConnectors.weChat(id, profile, URI.create(required(doc, "apiBase")), tokenRef, transport, secrets, optedIn);
            default -> throw new IllegalArgumentException("CHANNEL_ADAPTER_NOT_AVAILABLE: " + channel);
        };
    }

    private static String required(Map<String, Object> doc, String field) {
        Object v = doc.get(field);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("channel entry needs " + field);
        }
        return v.toString();
    }
}
