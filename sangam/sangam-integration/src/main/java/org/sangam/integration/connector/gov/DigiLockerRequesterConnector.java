// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.AuditedConnector;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.sim.SimulatorProfile;
import org.sangam.integration.sim.digilocker.DigiLockerRequesterSimulator;

/**
 * DigiLocker requester connector, pull with consent (M-072, R59 GOV-9 "requester adapter for applicant documents",
 * ADR-D-13: built from the public description of DigiLocker's OAuth 2.0 requester API, with
 * {@link DigiLockerRequesterSimulator}; the paths are configurable, {@link Paths#DEFAULT} is that approximation).
 *
 * <p><b>Consent</b>: the person is sent to {@link #authorization} (authorization code with PKCE S256 and a state); the
 * DigiLocker consent screen returns a one-time {@code code} to the requester's redirect URI. Nothing is pulled without
 * that code, and a code works once.
 *
 * <p><b>Operations</b> (input {@code code}, {@code codeVerifier}, optional {@code redirectUri}):
 * <ul>
 *   <li>{@code verify-adult}: the token answer (or the user endpoint) gives the date of birth; the answer is
 *       {@code identifiable}, {@code adult} (age of majority, default 18, on the business date in the configured zone)
 *       and {@code tokenRef}, a pseudonym ({@code dl:} + SHA-256 of tenant and DigiLocker id). The date of birth and
 *       the DigiLocker id are not returned (data minimisation).</li>
 *   <li>{@code pull}: the list of issued documents (metadata) and, for each {@code uris} entry, the file (content type,
 *       size, SHA-256, base64 content up to {@code maxFileBytes}).</li>
 * </ul>
 * Both are reads: no downstream effect, so the class is (a) and a lost answer is a transient failure (a retry needs a
 * new consent when the code was used). A refused code is the business failure {@code CONSENT_INVALID}. Every call goes
 * through the connector's {@link HttpTransport} (egress allow-list, R27); the client secret is a reference.
 */
public final class DigiLockerRequesterConnector implements SangamConnector, AuditedConnector {

    public static final String TYPE = "digilocker-requester";
    public static final String OP_VERIFY_ADULT = "verify-adult";
    public static final String OP_PULL = "pull";
    private static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("ddMMuuuu");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Endpoint paths relative to the base URI. */
    public record Paths(String authorize, String token, String user, String issued, String file) {
        public static final Paths DEFAULT = new Paths("/public/oauth2/1/authorize", "/public/oauth2/1/token",
                "/public/oauth2/1/user", "/public/oauth2/2/files/issued", "/public/oauth2/1/file/");
    }

    /**
     * @param ageOfMajority years; default 18
     * @param zone          zone of the business date used for the age; default Asia/Kolkata (the provider's zone)
     * @param maxFileBytes  largest file returned by {@code pull}; default 2 MiB
     */
    public record Config(String id, URI baseUri, Paths paths, String clientId, String clientSecretRef, URI redirectUri,
                         SecretResolver secrets, HttpTransport transport, ConnectorProfile profile, Duration timeout,
                         int ageOfMajority, ZoneId zone, int maxFileBytes, Clock clock) {
        public Config {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(baseUri, "baseUri");
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(redirectUri, "redirectUri");
            Objects.requireNonNull(transport, "transport");
            paths = paths == null ? Paths.DEFAULT : paths;
            secrets = secrets == null ? SecretResolver.fromEnvironment() : secrets;
            profile = profile == null ? ConnectorProfile.LIVE : profile;
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
            ageOfMajority = ageOfMajority <= 0 ? 18 : ageOfMajority;
            zone = zone == null ? ZoneId.of("Asia/Kolkata") : zone;
            maxFileBytes = maxFileBytes <= 0 ? 2 * 1024 * 1024 : maxFileBytes;
            clock = clock == null ? Clock.systemUTC() : clock;
        }
    }

    /** Where to send the person, and the PKCE verifier to keep (server side) until the code comes back. */
    public record AuthorizationRequest(URI url, String state, String codeVerifier) {
    }

    /** Result of {@link #verifyAdult}; {@code detail} is a short code, never personal data. */
    public record AdultCheck(boolean identifiable, boolean adult, String issuer, String tokenRef, String detail) {
    }

    /** The consent could not be used (a refused, used or expired code). */
    public static final class ConsentException extends RuntimeException {
        public ConsentException(String message) {
            super(message);
        }
    }

    /** DigiLocker could not be reached or answered unreadably; nothing was learnt. */
    public static final class UnavailableException extends RuntimeException {
        public UnavailableException(String message) {
            super(message);
        }
    }

    private final Config config;
    private final ConnectorDescriptor descriptor;

    public DigiLockerRequesterConnector(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.descriptor = new ConnectorDescriptor(config.id(), TYPE, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                List.of(OP_VERIFY_ADULT, OP_PULL), config.profile());
    }

    /** Wired to the requester simulator of {@code sims}. */
    public static DigiLockerRequesterConnector simulator(SimulatorProfile sims) {
        DigiLockerRequesterSimulator sim = sims.digiLockerRequester();
        return new DigiLockerRequesterConnector(new Config("digilocker-requester-sim", sim.baseUri(), Paths.DEFAULT,
                DigiLockerRequesterSimulator.CLIENT_ID, DigiLockerRequesterSimulator.CLIENT_SECRET,
                URI.create(DigiLockerRequesterSimulator.REDIRECT_URI), SecretResolver.of(Map.of()),
                HttpTransport.forSimulator(), ConnectorProfile.SIMULATOR, Duration.ofSeconds(10), 0, null, 0, null));
    }

    public Config config() {
        return config;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    /** The consent URL with a fresh PKCE verifier (S256). */
    public AuthorizationRequest authorization(String state) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state is required (it binds the answer to the person's session)");
        }
        byte[] v = new byte[32];
        RANDOM.nextBytes(v);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(v);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
        URI url = URI.create(join(config.paths().authorize()) + "?response_type=code&client_id=" + enc(config.clientId())
                + "&redirect_uri=" + enc(config.redirectUri().toString()) + "&state=" + enc(state)
                + "&code_challenge=" + challenge + "&code_challenge_method=S256");
        return new AuthorizationRequest(url, state, verifier);
    }

    /**
     * Verifies that the person who consented is an identifiable adult.
     *
     * @throws ConsentException     the code was refused
     * @throws UnavailableException DigiLocker could not answer
     */
    public AdultCheck verifyAdult(String tenantId, Map<String, String> evidence) {
        String code = evidence == null ? null : evidence.get("code");
        String verifier = evidence == null ? null : evidence.get("codeVerifier");
        if (code == null || code.isBlank() || verifier == null || verifier.isBlank()) {
            return new AdultCheck(false, false, "DigiLocker", null, "CONSENT_MISSING");
        }
        JsonNode token = token(tenantId, code, verifier, evidence.get("redirectUri"));
        String dlId = token.path("digilockerid").asText(null);
        String dob = token.path("dob").asText(null);
        if (dob == null || dlId == null) {
            JsonNode user = get(tenantId, token.path("access_token").asText(), URI.create(join(config.paths().user())));
            dlId = dlId != null ? dlId : user.path("digilockerid").asText(null);
            dob = dob != null ? dob : user.path("dob").asText(null);
        }
        if (dlId == null || dlId.isBlank()) {
            return new AdultCheck(false, false, "DigiLocker", null, "NOT_IDENTIFIABLE");
        }
        String tokenRef = "dl:" + HexFormat.of().formatHex(sha256(tenantId + ":" + dlId));
        LocalDate birth = parseDob(dob);
        if (birth == null) {
            return new AdultCheck(true, false, "DigiLocker", tokenRef, "DOB_UNAVAILABLE");
        }
        LocalDate today = LocalDate.ofInstant(config.clock().instant(), config.zone());
        boolean adult = !birth.plusYears(config.ageOfMajority()).isAfter(today);
        return new AdultCheck(true, adult, "DigiLocker", tokenRef, adult ? "ADULT_VERIFIED" : "MINOR");
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest r) {
        try {
            return switch (r.operation() == null ? "" : r.operation()) {
                case OP_VERIFY_ADULT -> {
                    Map<String, String> ev = new LinkedHashMap<>();
                    ev.put("code", r.str("code"));
                    ev.put("codeVerifier", r.str("codeVerifier"));
                    ev.put("redirectUri", r.str("redirectUri"));
                    AdultCheck a = verifyAdult(r.tenantId(), ev);
                    if ("CONSENT_MISSING".equals(a.detail())) {
                        yield ConnectorResult.BusinessFailure.of("INVALID_INPUT", "code and codeVerifier are required");
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("identifiable", a.identifiable());
                    out.put("adult", a.adult());
                    out.put("issuer", a.issuer());
                    out.put("tokenRef", a.tokenRef());
                    out.put("detail", a.detail());
                    yield new ConnectorResult.Success(out, a.tokenRef(), false);
                }
                case OP_PULL -> pull(r);
                default -> ConnectorResult.BusinessFailure.of("UNSUPPORTED_OPERATION",
                        "DigiLocker requester supports '" + OP_VERIFY_ADULT + "' and '" + OP_PULL + "'");
            };
        } catch (ConsentException e) {
            return ConnectorResult.BusinessFailure.of("CONSENT_INVALID", e.getMessage());
        } catch (UnavailableException e) {
            // a read: nothing happened downstream, so a lost answer is transient (never UNKNOWN_OUTCOME)
            return new ConnectorResult.TransientFailure("DigiLocker: " + e.getMessage(), false, null);
        }
    }

    /**
     * Owner decision 299: one {@code DOCUMENT_FETCHED} per file a {@code pull} fetched (X.4.0.3: DigiLocker URI and
     * issuer), with the issued-list entry's issuer and doc type, and the file's SHA-256, size and content type. A file
     * that came back with an error was not fetched. {@code verify-adult} fetches no document.
     */
    @Override
    public List<AuditedConnector.Event> auditEvents(String operation, Map<String, Object> input, Map<String, Object> output) {
        if (!OP_PULL.equals(operation) || !(output.get("files") instanceof List<?> files)) {
            return List.of();
        }
        Map<String, Map<?, ?>> issued = new LinkedHashMap<>();
        if (output.get("issued") instanceof List<?> items) {
            for (Object o : items) {
                if (o instanceof Map<?, ?> m && m.get("uri") != null) {
                    issued.putIfAbsent(String.valueOf(m.get("uri")), m);
                }
            }
        }
        List<AuditedConnector.Event> events = new ArrayList<>();
        for (Object o : files) {
            if (!(o instanceof Map<?, ?> f) || f.get("uri") == null || f.get("error") != null) {
                continue;
            }
            String uri = String.valueOf(f.get("uri"));
            Map<?, ?> item = issued.getOrDefault(uri, Map.of());
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("source", "DigiLocker");
            p.put("uri", uri);
            p.put("issuer", item.get("issuer") == null ? null : String.valueOf(item.get("issuer")));
            p.put("docType", item.get("doctype") == null ? null : String.valueOf(item.get("doctype")));
            p.put("sha256", f.get("sha256"));
            p.put("size", f.get("size"));
            p.put("contentType", f.get("contentType"));
            events.add(new AuditedConnector.Event("DOCUMENT_FETCHED", p));
        }
        return events;
    }

    /** A read has no downstream effect to find: "not found" lets the invoker retry safely. */
    @Override
    public LookupResult lookup(ConnectorRequest request) {
        return new LookupResult.NotFound();
    }

    private ConnectorResult pull(ConnectorRequest r) {
        String code = r.str("code");
        String verifier = r.str("codeVerifier");
        if (code == null || verifier == null) {
            return ConnectorResult.BusinessFailure.of("INVALID_INPUT", "code and codeVerifier are required");
        }
        JsonNode token = token(r.tenantId(), code, verifier, r.str("redirectUri"));
        String access = token.path("access_token").asText();
        Map<String, Object> out = new LinkedHashMap<>();
        JsonNode issued = get(r.tenantId(), access, URI.create(join(config.paths().issued())));
        out.put("issued", Json.MAPPER.convertValue(issued.path("items"), Object.class));
        List<Map<String, Object>> files = new ArrayList<>();
        Object uris = r.input().get("uris");
        if (uris instanceof List<?> list) {
            for (Object u : list) {
                files.add(file(r.tenantId(), access, String.valueOf(u)));
            }
        }
        out.put("files", files);
        return new ConnectorResult.Success(out, null, false);
    }

    private Map<String, Object> file(String tenantId, String access, String docUri) {
        URI uri = URI.create(join(config.paths().file()) + enc(docUri));
        HttpTransport.Outcome o = config.transport().execute(tenantId, HttpTransport.Call.get(uri,
                Map.of("Authorization", "Bearer " + access)), 0);
        if (!(o instanceof HttpTransport.Outcome.Response resp)) {
            throw new UnavailableException("file: " + describe(o));
        }
        if (resp.status() == 401 || resp.status() == 403) {
            throw new ConsentException("file " + docUri + " refused (HTTP " + resp.status() + ")");
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("uri", docUri);
        if (!resp.is2xx()) {
            f.put("error", "HTTP_" + resp.status());
            return f;
        }
        f.put("contentType", resp.header("Content-Type").orElse("application/octet-stream"));
        f.put("size", resp.body().length);
        f.put("sha256", HexFormat.of().formatHex(sha256(resp.body())));
        if (resp.body().length <= config.maxFileBytes()) {
            f.put("contentBase64", Base64.getEncoder().encodeToString(resp.body()));
        } else {
            f.put("error", "FILE_TOO_LARGE");
        }
        return f;
    }

    private JsonNode token(String tenantId, String code, String verifier, String redirectUri) {
        String secret;
        try {
            secret = config.secrets().require(config.clientSecretRef());
        } catch (RuntimeException e) {
            throw new UnavailableException("client secret " + config.clientSecretRef() + " cannot be resolved");
        }
        String form = "grant_type=authorization_code&code=" + enc(code) + "&client_id=" + enc(config.clientId())
                + "&client_secret=" + enc(secret == null ? "" : secret) + "&redirect_uri="
                + enc(redirectUri == null || redirectUri.isBlank() ? config.redirectUri().toString() : redirectUri)
                + "&code_verifier=" + enc(verifier);
        Map<String, String> h = Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json");
        HttpTransport.Outcome o = config.transport().execute(tenantId, new HttpTransport.Call("POST",
                URI.create(join(config.paths().token())), h, form.getBytes(StandardCharsets.UTF_8), config.timeout(),
                false), 0);
        if (!(o instanceof HttpTransport.Outcome.Response resp)) {
            throw new UnavailableException("token: " + describe(o));
        }
        if (resp.status() == 400 || resp.status() == 401) {
            throw new ConsentException("the consent code was refused (HTTP " + resp.status() + ")");
        }
        JsonNode tree = Json.tree(resp.body());
        if (!resp.is2xx() || tree == null || tree.path("access_token").asText("").isBlank()) {
            throw new UnavailableException("token: HTTP " + resp.status());
        }
        return tree;
    }

    private JsonNode get(String tenantId, String access, URI uri) {
        HttpTransport.Outcome o = config.transport().execute(tenantId, HttpTransport.Call.get(uri,
                Map.of("Authorization", "Bearer " + access, "Accept", "application/json")), 0);
        if (!(o instanceof HttpTransport.Outcome.Response resp)) {
            throw new UnavailableException(uri.getPath() + ": " + describe(o));
        }
        if (resp.status() == 401 || resp.status() == 403) {
            throw new ConsentException(uri.getPath() + " refused (HTTP " + resp.status() + ")");
        }
        JsonNode tree = Json.tree(resp.body());
        if (!resp.is2xx() || tree == null) {
            throw new UnavailableException(uri.getPath() + ": HTTP " + resp.status());
        }
        return tree;
    }

    /** DDMMYYYY (DigiLocker) or ISO yyyy-MM-dd; null when absent or unreadable. */
    static LocalDate parseDob(String dob) {
        if (dob == null || dob.isBlank()) {
            return null;
        }
        try {
            return dob.contains("-") ? LocalDate.parse(dob) : LocalDate.parse(dob, DDMMYYYY);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String join(String path) {
        String b = config.baseUri().toString();
        return (b.endsWith("/") ? b.substring(0, b.length() - 1) : b) + path;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static byte[] sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String describe(HttpTransport.Outcome o) {
        return switch (o) {
            case HttpTransport.Outcome.Response r -> "HTTP " + r.status();
            case HttpTransport.Outcome.NoEffect ne -> ne.reason();
            case HttpTransport.Outcome.Unknown u -> u.reason();
        };
    }
}
