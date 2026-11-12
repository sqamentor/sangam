// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sim.digilocker;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.sangam.integration.connector.Json;
import org.sangam.integration.sim.SimHttpServer;

/**
 * DigiLocker requester (pull with consent) simulator (M-072, SANGAM.md IX.7e GOV-9, ADR-D-13: an approximation of the
 * public OAuth 2.0 requester API). Routes ({@code Paths.DEFAULT} of the connector):
 * <ul>
 *   <li>{@code POST /public/oauth2/1/token}: authorization code + PKCE S256; one use per code; the answer carries
 *       {@code access_token}, {@code expires_in}, {@code digilockerid}, {@code name}, {@code dob} (DDMMYYYY).</li>
 *   <li>{@code GET /public/oauth2/1/user}, {@code GET /public/oauth2/2/files/issued},
 *       {@code GET /public/oauth2/1/file/<uri>} with {@code Authorization: Bearer}.</li>
 * </ul>
 * There is no consent screen: a test (or the dev profile) calls {@link #consent} for a person, which returns the code
 * the screen would have sent to the redirect URI, bound to the PKCE challenge. Ledger type {@code PULL}, key = token.
 */
public final class DigiLockerRequesterSimulator extends SimHttpServer {

    public static final String CLIENT_ID = "sim-requester";
    /** Development literal; never valid against DigiLocker. */
    public static final String CLIENT_SECRET = "sim-requester-secret";
    public static final String REDIRECT_URI = "https://sangam.invalid/digilocker/callback";
    public static final String EFFECT_PULL = "PULL";
    private static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("ddMMuuuu");

    /** A simulated DigiLocker account. */
    public record Person(String digiLockerId, String name, LocalDate dob, Map<String, byte[]> documents) {
        public Person {
            documents = documents == null ? Map.of() : Map.copyOf(documents);
        }
    }

    private record Grant(Person person, String challenge, String redirectUri) {
    }

    private final Map<String, Grant> codes = new LinkedHashMap<>();
    private final Map<String, Person> tokens = new LinkedHashMap<>();

    public DigiLockerRequesterSimulator() {
        super("digilocker-requester");
        route("POST", "/public/oauth2/1/token", this::token);
        route("GET", "/public/oauth2/1/user", this::user);
        route("GET", "/public/oauth2/2/files/issued", this::issued);
        prefixRoute("GET", "/public/oauth2/1/file/", this::file);
    }

    /**
     * The person consents: returns the one-time code for {@code codeChallenge} (the S256 challenge of the connector's
     * {@code AuthorizationRequest}, i.e. base64url(SHA-256(verifier))).
     */
    public String consent(Person person, String codeChallenge) {
        String code = UUID.randomUUID().toString();
        synchronized (codes) {
            codes.put(code, new Grant(person, codeChallenge, REDIRECT_URI));
        }
        return code;
    }

    /** The S256 challenge of a verifier (what the connector puts in the consent URL). */
    public static String challengeOf(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Response token(Request req) {
        Map<String, String> f = form(req.bodyString());
        if (!CLIENT_ID.equals(f.get("client_id")) || !CLIENT_SECRET.equals(f.get("client_secret"))) {
            return Response.json(401, "{\"error\":\"invalid_client\"}");
        }
        if (!"authorization_code".equals(f.get("grant_type"))) {
            return Response.json(400, "{\"error\":\"unsupported_grant_type\"}");
        }
        Grant g;
        synchronized (codes) {
            g = codes.remove(String.valueOf(f.get("code")));   // one use, whatever the outcome
        }
        String verifier = f.get("code_verifier");
        if (g == null || verifier == null || !challengeOf(verifier).equals(g.challenge())
                || !g.redirectUri().equals(f.get("redirect_uri"))) {
            return Response.json(400, "{\"error\":\"invalid_grant\"}");
        }
        String access = "sim-dl-" + UUID.randomUUID();
        synchronized (tokens) {
            tokens.put(access, g.person());
        }
        ledger().record(EFFECT_PULL, access, Map.of("digilockerid", g.person().digiLockerId()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("access_token", access);
        m.put("expires_in", 3600);
        m.put("token_type", "Bearer");
        m.put("digilockerid", g.person().digiLockerId());
        m.put("name", g.person().name());
        m.put("dob", g.person().dob() == null ? "" : g.person().dob().format(DDMMYYYY));
        return Response.json(200, Json.write(m));
    }

    private Response user(Request req) {
        Person p = person(req);
        if (p == null) {
            return Response.json(401, "{\"error\":\"invalid_token\"}");
        }
        return Response.json(200, Json.write(Map.of("digilockerid", p.digiLockerId(), "name", p.name(),
                "dob", p.dob() == null ? "" : p.dob().format(DDMMYYYY))));
    }

    private Response issued(Request req) {
        Person p = person(req);
        if (p == null) {
            return Response.json(401, "{\"error\":\"invalid_token\"}");
        }
        List<Map<String, Object>> items = p.documents().entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uri", e.getKey());
            m.put("name", e.getKey());
            m.put("mime", "application/pdf");
            m.put("size", e.getValue().length);
            m.put("issuer", "Simulated Issuer");
            m.put("date", Instant.EPOCH.toString());
            return m;
        }).toList();
        return Response.json(200, Json.write(Map.of("items", items)));
    }

    private Response file(Request req) {
        Person p = person(req);
        if (p == null) {
            return Response.json(401, "{\"error\":\"invalid_token\"}");
        }
        byte[] doc = p.documents().get(req.pathAfter("/public/oauth2/1/file/"));
        return doc == null ? Response.json(404, "{\"error\":\"not_found\"}")
                : new Response(200, "application/pdf", doc);
    }

    private Person person(Request req) {
        String a = req.header("Authorization");
        if (a == null || !a.startsWith("Bearer ")) {
            return null;
        }
        synchronized (tokens) {
            return tokens.get(a.substring(7));
        }
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String p : body.split("&")) {
            int eq = p.indexOf('=');
            if (eq > 0) {
                m.put(URLDecoder.decode(p.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }
}
