// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.secrets;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.RetryPolicy;
import org.sangam.integration.connector.SecretResolver;

/**
 * What the HTTP secret providers share (M-075): one {@link HttpTransport} per provider whose {@link EgressPolicy} allows
 * exactly the provider's own origins (the same egress guard every connector has, R27; a private address only with
 * {@code allowPrivate}, for an in-cluster Vault), the platform tenant used for the egress check, and the reading of the
 * provider's own bootstrap credentials from the bootstrap resolver (file and environment only, never a provider).
 */
final class ProviderCalls {

    /** Egress tenant of secret-provider calls; the provider rules are platform rules (tenant {@code *}). */
    static final String PLATFORM = "platform";
    static final long MAX_RESPONSE_BYTES = 1024L * 1024;

    private ProviderCalls() {
    }

    /** A transport allowed to reach only {@code origins} (scheme://host[:port]); production needs https. */
    static HttpTransport transport(List<URI> origins, boolean allowPrivate, boolean production, Duration timeout) {
        List<EgressPolicy.Rule> rules = new ArrayList<>();
        for (URI u : origins) {
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (u.getHost() == null || !(scheme.equals("https") || scheme.equals("http"))) {
                throw new IllegalStateException("secret provider url must be an absolute http(s) URL: " + u);
            }
            if (production && !scheme.equals("https")) {
                throw new IllegalStateException("secret provider url must be https on a production profile: " + u);
            }
            if (u.getUserInfo() != null) {
                throw new IllegalStateException("secret provider url must not carry credentials");
            }
            rules.add(EgressPolicy.Rule.parse("*", u.getScheme() + "://" + u.getHost()
                    + (u.getPort() == -1 ? "" : ":" + u.getPort()), allowPrivate));
        }
        Duration t = timeout == null ? Duration.ofSeconds(10) : timeout;
        return new HttpTransport(EgressPolicy.of(rules, 0, MAX_RESPONSE_BYTES), null, t, t,
                new RetryPolicy(2, Duration.ofMillis(200), Duration.ofSeconds(2), 0.5));
    }

    /** A bootstrap credential: must resolve to a non-empty value without control characters (never trimmed). */
    static String bootstrap(SecretResolver bootstrap, String what, String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalStateException(what + " is required");
        }
        String v;
        try {
            v = bootstrap.require(ref);
        } catch (RuntimeException e) {
            throw new IllegalStateException(what + " cannot be resolved (" + e.getMessage() + ")");
        }
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException(what + " resolves to an empty value");
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalStateException(what + " resolves to a value with a control character such as a line"
                        + " break; secrets are never trimmed (the value is not shown)");
            }
        }
        return v;
    }

    /** The failure of a provider call, without any value: the caller throws it (the chain stops, nothing is guessed). */
    static IllegalStateException failure(String provider, HttpTransport.Outcome o) {
        String why = switch (o) {
            case HttpTransport.Outcome.Response r -> "HTTP " + r.status();
            case HttpTransport.Outcome.NoEffect ne -> ne.reason();
            case HttpTransport.Outcome.Unknown u -> u.reason();
        };
        return new IllegalStateException(provider + " secret read failed: " + why);
    }

    static URI origin(URI u) {
        return URI.create(u.getScheme() + "://" + u.getHost() + (u.getPort() == -1 ? "" : ":" + u.getPort()));
    }
}
