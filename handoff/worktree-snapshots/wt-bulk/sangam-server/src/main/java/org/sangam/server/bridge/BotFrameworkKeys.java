// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.math.BigInteger;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bot Framework signing keys per tenant, fetched from the JWKS URL through the tenant's egress-limited transport and
 * refreshed once a day, or on an unknown key id at most every five minutes. A failed refresh keeps the keys already held.
 */
public final class BotFrameworkKeys implements BiFunction<String, String, Optional<PublicKey>> {

    private static final Logger LOG = LoggerFactory.getLogger(BotFrameworkKeys.class);
    private static final Duration MAX_AGE = Duration.ofHours(24);
    private static final Duration MIN_REFETCH = Duration.ofMinutes(5);

    private record Held(Map<String, PublicKey> keys, Instant fetched) {
    }

    private final URI jwks;
    private final Function<String, HttpTransport> transports;
    private final Map<String, Held> byTenant = new ConcurrentHashMap<>();

    public BotFrameworkKeys(URI jwks, Function<String, HttpTransport> transports) {
        this.jwks = jwks;
        this.transports = transports;
    }

    @Override
    public Optional<PublicKey> apply(String tenant, String kid) {
        Held h = byTenant.get(tenant);
        Instant now = Instant.now();
        if (h == null || h.fetched().plus(MAX_AGE).isBefore(now)
                || (!h.keys().containsKey(kid) && h.fetched().plus(MIN_REFETCH).isBefore(now))) {
            h = refresh(tenant, h, now);
        }
        return Optional.ofNullable(h.keys().get(kid));
    }

    private Held refresh(String tenant, Held old, Instant now) {
        Held kept = old != null ? new Held(old.keys(), now) : new Held(Map.of(), now);
        try {
            HttpTransport.Outcome o = transports.apply(tenant).execute(tenant, HttpTransport.Call.get(jwks, Map.of()),
                    System.currentTimeMillis() + 15_000);
            if (o instanceof HttpTransport.Outcome.Response r && r.is2xx()) {
                kept = new Held(parse(r.bodyString()), now);
            } else {
                LOG.warn("Bot Framework key refresh for tenant {} got no keys: {}", tenant, o);
            }
        } catch (RuntimeException e) {
            LOG.warn("Bot Framework key refresh for tenant {} failed: {}", tenant, e.toString());
        }
        byTenant.put(tenant, kept);
        return kept;
    }

    @SuppressWarnings("unchecked")
    static Map<String, PublicKey> parse(String body) {
        Map<String, PublicKey> out = new HashMap<>();
        Object keys = Json.readMap(body).get("keys");
        if (keys instanceof List<?> list) {
            for (Object k : list) {
                Map<String, Object> m = (Map<String, Object>) k;
                if (!"RSA".equals(m.get("kty")) || m.get("kid") == null || m.get("n") == null || m.get("e") == null) {
                    continue;
                }
                try {
                    Base64.Decoder d = Base64.getUrlDecoder();
                    out.put(m.get("kid").toString(), KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                            new BigInteger(1, d.decode(m.get("n").toString())), new BigInteger(1, d.decode(m.get("e").toString())))));
                } catch (GeneralSecurityException | IllegalArgumentException e) {
                    LOG.warn("skipping an unusable Bot Framework key: {}", e.toString());
                }
            }
        }
        return out;
    }
}
