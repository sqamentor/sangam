// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.spring;

import org.sangam.security.oidc.JtiReplayGuard;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies a JWT that a registered <b>client</b> signs with its own key and sends as a bearer token (a client
 * assertion; for example the CDS Hooks 2.0 client JWT, US-HLT-074, M-006). This is not the platform OIDC login: each
 * client has its own key set, registered here by issuer.
 *
 * <p>Checks, all fail closed:
 * <ul>
 *   <li>{@code iss} is a registered client (allow-list); unknown issuers are refused before any key is fetched;</li>
 *   <li>the signature verifies against that client's JWKS (a pinned {@code jwks-uri}, or an inline JWKS for air-gapped
 *       deployments), with an algorithm the client is allowed to use; a {@code jku} header must name the client's
 *       pinned {@code jwks-uri} (keys are never fetched from a URL the token chose);</li>
 *   <li>{@code aud} contains the endpoint being called (the caller passes the public URL it serves);</li>
 *   <li>{@code exp} and {@code iat} are present, {@code exp} is not past and {@code iat} not in the future (clock skew),
 *       and the token lives at most {@code maxLifetime};</li>
 *   <li>{@code jti} is present and not seen before for that issuer while the token is valid ({@link JtiReplayGuard}).</li>
 * </ul>
 * Signature and key handling are Spring Security's {@link NimbusJwtDecoder} (Nimbus JOSE+JWT, on the host's classpath
 * through {@code spring-security-oauth2-jose}); nothing here implements cryptography.
 *
 * <p>M-141a (decisions 72-75): this class uses Spring Security, so it lives in the Spring host (sangam-server), outside the
 * plain-Java sangam-security jar; the replay store contract it calls ({@link JtiReplayGuard}) stays there.
 */
public final class ClientJwtVerifier {

    /** Signature algorithms a client may use when it declares none (CDS Hooks 2.0 names ES384 and RS384). */
    public static final Set<String> DEFAULT_ALGORITHMS = Set.of("RS384", "ES384");

    /**
     * One registered client.
     *
     * @param issuer     the {@code iss} the client signs with
     * @param jwksUri    the client's JWKS URL (pinned), or null when {@code jwks} is given
     * @param jwks       an inline JWKS (public keys, JSON), or null when {@code jwksUri} is given
     * @param algorithms allowed JWS algorithms (null or empty = {@link #DEFAULT_ALGORITHMS})
     */
    public record Client(String issuer, String jwksUri, String jwks, Set<String> algorithms) {
        public Client {
            if (issuer == null || issuer.isBlank()) {
                throw new IllegalArgumentException("a client needs its issuer");
            }
            boolean uri = jwksUri != null && !jwksUri.isBlank();
            boolean inline = jwks != null && !jwks.isBlank();
            if (uri == inline) {
                throw new IllegalArgumentException("client " + issuer + ": set exactly one of jwks-uri and jwks");
            }
            jwksUri = uri ? jwksUri.trim() : null;
            jwks = inline ? jwks : null;
            algorithms = algorithms == null || algorithms.isEmpty() ? DEFAULT_ALGORITHMS : Set.copyOf(algorithms);
            for (String a : algorithms) {
                if (SignatureAlgorithm.from(a) == null) {
                    throw new IllegalArgumentException("client " + issuer + ": unknown JWS algorithm " + a);
                }
            }
        }
    }

    /** A verified token: the client ({@code iss}), its subject, token id and expiry, and all claims. */
    public record Verified(String issuer, String subject, String jti, Instant expiresAt, Map<String, Object> claims) {
    }

    /** Why a token was refused (stable codes for problem responses and audit). */
    public enum Reason {
        MALFORMED, UNKNOWN_CLIENT, JKU_NOT_ALLOWED, BAD_SIGNATURE, WRONG_AUDIENCE, MISSING_CLAIM, EXPIRED, NOT_YET_VALID,
        LIFETIME_TOO_LONG, REPLAYED
    }

    /** A refused token. The message never contains the token. */
    public static final class Rejected extends RuntimeException {
        private final Reason reason;

        public Rejected(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /**
     * Node-local {@link JtiReplayGuard}: remembers each (issuer, jti) until its {@code exp} (bounded by the maximum token
     * lifetime). It does not see uses on other nodes; a cluster-wide guard needs a shared store.
     */
    public static final class InMemoryReplayGuard implements JtiReplayGuard {
        private final Map<String, Instant> seen = new ConcurrentHashMap<>();
        private final Clock clock;

        public InMemoryReplayGuard(Clock clock) {
            this.clock = clock == null ? Clock.systemUTC() : clock;
        }

        @Override
        public boolean firstUse(String issuer, String jti, Instant expiresAt) {
            Instant now = clock.instant();
            for (Iterator<Map.Entry<String, Instant>> it = seen.entrySet().iterator(); it.hasNext(); ) {
                if (!it.next().getValue().isAfter(now)) {
                    it.remove();
                }
            }
            return seen.putIfAbsent(issuer + "\u0000" + jti, expiresAt) == null;
        }

        int size() {
            return seen.size();
        }
    }

    private final Map<String, Client> clients;
    private final Map<String, NimbusJwtDecoder> decoders = new LinkedHashMap<>();
    private final Duration clockSkew;
    private final Duration maxLifetime;
    private final Clock clock;
    private final JtiReplayGuard replay;

    /**
     * @param clients     the registered clients (their issuers are the allow-list)
     * @param clockSkew   tolerance for {@code exp} and {@code iat}
     * @param maxLifetime the longest accepted {@code exp - iat}
     * @param replay      the {@code jti} guard (null = node-local {@link InMemoryReplayGuard})
     */
    public ClientJwtVerifier(List<Client> clients, Duration clockSkew, Duration maxLifetime, Clock clock,
                             JtiReplayGuard replay) {
        Map<String, Client> byIssuer = new LinkedHashMap<>();
        for (Client c : clients == null ? List.<Client>of() : clients) {
            if (byIssuer.put(c.issuer(), c) != null) {
                throw new IllegalArgumentException("client issuer " + c.issuer() + " is registered twice");
            }
        }
        this.clients = Map.copyOf(byIssuer);
        // built now, so a bad key set or algorithm fails at start-up, not at the first call (a jwks-uri is fetched lazily)
        this.clients.values().forEach(c -> decoders.put(c.issuer(), build(c)));
        this.clockSkew = clockSkew == null ? Duration.ofSeconds(60) : clockSkew;
        this.maxLifetime = maxLifetime == null ? Duration.ofMinutes(5) : maxLifetime;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.replay = replay == null ? new InMemoryReplayGuard(this.clock) : replay;
    }

    public Set<String> issuers() {
        return clients.keySet();
    }

    /**
     * Verifies {@code token} for a call to {@code audience} (the public URL of the endpoint being called).
     *
     * @throws Rejected when any check fails
     */
    public Verified verify(String token, String audience) {
        Objects.requireNonNull(audience, "audience");
        if (token == null || token.isBlank()) {
            throw new Rejected(Reason.MALFORMED, "no token");
        }
        JWT parsed;
        String issuer;
        boolean issuedAtPresent;
        try {
            parsed = JWTParser.parse(token);
            issuer = parsed.getJWTClaimsSet().getIssuer();
            issuedAtPresent = parsed.getJWTClaimsSet().getIssueTime() != null;
        } catch (ParseException e) {
            throw new Rejected(Reason.MALFORMED, "the token is not a JWT");
        }
        if (!(parsed instanceof SignedJWT signed)) {
            throw new Rejected(Reason.MALFORMED, "the token is not a signed JWT");
        }
        Client client = issuer == null ? null : clients.get(issuer);
        if (client == null) {
            throw new Rejected(Reason.UNKNOWN_CLIENT, "issuer is not a registered client");
        }
        if (signed.getHeader().getJWKURL() != null
                && !signed.getHeader().getJWKURL().toString().equals(client.jwksUri())) {
            throw new Rejected(Reason.JKU_NOT_ALLOWED, "jku is not the registered key set of client " + issuer);
        }
        Jwt jwt;
        try {
            jwt = decoders.get(issuer).decode(token);
        } catch (JwtException e) {
            throw new Rejected(Reason.BAD_SIGNATURE, "token of client " + issuer + " is not valid: " + e.getMessage());
        }
        Instant now = clock.instant();
        Instant exp = jwt.getExpiresAt();
        Instant iat = jwt.getIssuedAt();
        String jti = jwt.getId();
        // iat is asked of the token itself: the decoder's claim converter fills a missing iat with exp - 1 s
        if (exp == null || iat == null || !issuedAtPresent || jti == null || jti.isBlank()) {
            throw new Rejected(Reason.MISSING_CLAIM, "exp, iat and jti are required");
        }
        if (!now.minus(clockSkew).isBefore(exp)) {
            throw new Rejected(Reason.EXPIRED, "token expired");
        }
        if (iat.isAfter(now.plus(clockSkew)) || jwt.getNotBefore() != null && jwt.getNotBefore().isAfter(now.plus(clockSkew))) {
            throw new Rejected(Reason.NOT_YET_VALID, "token is not valid yet");
        }
        if (Duration.between(iat, exp).compareTo(maxLifetime) > 0) {
            throw new Rejected(Reason.LIFETIME_TOO_LONG, "token lifetime exceeds " + maxLifetime);
        }
        List<String> aud = jwt.getAudience();
        if (aud == null || !aud.contains(audience)) {
            throw new Rejected(Reason.WRONG_AUDIENCE, "aud does not name this endpoint");
        }
        // last, so a token refused for another reason does not use up its id
        if (!replay.firstUse(issuer, jti, exp.plus(clockSkew))) {
            throw new Rejected(Reason.REPLAYED, "jti already used");
        }
        return new Verified(issuer, jwt.getSubject(), jti, exp, jwt.getClaims());
    }

    private static NimbusJwtDecoder build(Client client) {
        Set<SignatureAlgorithm> algs = new LinkedHashSet<>();
        for (String a : client.algorithms()) {
            algs.add(SignatureAlgorithm.from(a));
        }
        NimbusJwtDecoder decoder;
        if (client.jwksUri() != null) {
            decoder = NimbusJwtDecoder.withJwkSetUri(client.jwksUri())
                    .jwsAlgorithms(s -> { s.clear(); s.addAll(algs); }).build();
        } else {
            JWKSet set;
            try {
                set = JWKSet.parse(client.jwks());
            } catch (ParseException e) {
                throw new IllegalArgumentException("client " + client.issuer() + ": the inline jwks is not a JWK set", e);
            }
            decoder = NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<SecurityContext>(set.toPublicJWKSet()))
                    .jwsAlgorithms(s -> { s.clear(); s.addAll(algs); }).build();
        }
        // signature + issuer here; time, audience and jti are checked by verify() with this verifier's clock
        decoder.setJwtValidator(new JwtIssuerValidator(client.issuer()));
        return decoder;
    }
}
