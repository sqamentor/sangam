// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;

import java.util.Map;
import java.util.function.Function;

/**
 * Opens NATS connections (optional dependency {@code io.nats:jnats}, Apache-2.0). The one place where the product
 * builds a NATS client: the JetStream event channels (M-064, {@code NatsAdapters}) and the NATS wake-up bus (M-058)
 * both call it, so authentication and reconnect settings are the same everywhere.
 *
 * <p>Settings (a channel's {@code properties}, or the wake-up bus keys): {@code serverUri} ({@code nats://} or
 * {@code tls://host:4222}, comma list allowed), and either {@code username} + {@code passwordRef}, or
 * {@code tokenRef} (secret references, never values). Reconnects for ever; the callers' polls stay authoritative.
 */
public final class NatsConnections {

    private NatsConnections() {
    }

    /**
     * @param settings the {@code serverUri}, {@code username}, {@code passwordRef}, {@code tokenRef} values
     * @param secrets  resolves a secret reference (empty: not found)
     */
    public static Connection connect(Map<String, String> settings, Function<String, java.util.Optional<String>> secrets,
                                     String connectionName) throws java.io.IOException, InterruptedException {
        return Nats.connect(options(settings, secrets, connectionName));
    }

    static Options options(Map<String, String> settings, Function<String, java.util.Optional<String>> secrets, String connectionName) {
        String uri = settings.get("serverUri");
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("NATS needs serverUri");
        }
        Options.Builder b = new Options.Builder().connectionName(connectionName).maxReconnects(-1);
        for (String s : uri.split(",")) {
            if (!s.isBlank()) {
                b.server(s.trim());
            }
        }
        String user = settings.get("username");
        String passwordRef = settings.get("passwordRef");
        String tokenRef = settings.get("tokenRef");
        if (user != null && passwordRef != null) {
            b.userInfo(user, require(secrets, passwordRef));
        } else if (tokenRef != null) {
            b.token(require(secrets, tokenRef).toCharArray());
        }
        return b.build();
    }

    private static String require(Function<String, java.util.Optional<String>> secrets, String ref) {
        return secrets.apply(ref).orElseThrow(() -> new IllegalStateException("secret " + ref + " not found"));
    }
}
