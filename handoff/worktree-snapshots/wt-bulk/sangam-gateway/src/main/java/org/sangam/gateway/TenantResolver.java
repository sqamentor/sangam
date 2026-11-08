// SPDX-License-Identifier: Apache-2.0
package org.sangam.gateway;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.sangam.cell.rollup.MiniJson;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves the tenant of a request, in precedence order: (1) JWT claim, (2) host name {@code <tenant><hostSuffix>},
 * (3) header. The gateway does <b>not</b> verify the JWT signature: routing is not authorization. The cell verifies the
 * token and takes the tenant only from it (R19); a request whose header names another tenant than its token is
 * rejected by the cell, so header spoofing can at most mis-route to a cell that then refuses the call.
 */
@Component
public class TenantResolver {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,63}");

    private final GatewayProperties props;

    public TenantResolver(GatewayProperties props) {
        this.props = props;
    }

    public Optional<String> resolve(ServerHttpRequest req) {
        String auth = req.getHeaders().getFirst("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            Optional<String> fromToken = claim(auth.substring(7).trim());
            if (fromToken.isPresent()) {
                return fromToken;
            }
        }
        String host = req.getHeaders().getFirst("Host");
        if (host != null && props.hostSuffix() != null && !props.hostSuffix().isBlank()) {
            String h = host.toLowerCase(Locale.ROOT);
            int colon = h.indexOf(':');
            if (colon > 0) {
                h = h.substring(0, colon);
            }
            String suffix = props.hostSuffix().toLowerCase(Locale.ROOT);
            if (h.endsWith(suffix) && h.length() > suffix.length()) {
                Optional<String> t = valid(h.substring(0, h.length() - suffix.length()));
                if (t.isPresent()) {
                    return t;
                }
            }
        }
        return valid(req.getHeaders().getFirst(props.tenantHeader()));
    }

    Optional<String> claim(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2 || parts[1].length() > 16_384) {
            return Optional.empty();
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Map<String, Object> m = MiniJson.readObject(json);
            Object v = m.get(props.tenantClaim());
            return v == null ? Optional.empty() : valid(v.toString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    static Optional<String> valid(String s) {
        if (s == null) {
            return Optional.empty();
        }
        String c = s.trim().toUpperCase(Locale.ROOT);
        return CODE.matcher(c).matches() ? Optional.of(c) : Optional.empty();
    }
}
