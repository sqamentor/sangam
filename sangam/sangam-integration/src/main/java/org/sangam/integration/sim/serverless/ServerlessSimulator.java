// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sim.serverless;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;

import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.http.AsyncCallback;
import org.sangam.integration.connector.http.AwsRequestSigner;
import org.sangam.integration.connector.http.GcpTokens;
import org.sangam.integration.connector.http.Jwt;
import org.sangam.integration.sim.SimHttpServer;

/**
 * Serverless provider simulator (M-067, ADR-D-13: build against public behaviour with a simulator, no vendor account).
 * One loopback server plays four roles:
 *
 * <ul>
 *   <li><b>AWS Lambda invoke API</b> {@code POST /2015-03-31/functions/<name>/invocations}: the request must carry a
 *       valid SigV4 signature (service {@code lambda}, region {@link #AWS_REGION}) by {@link #AWS_ACCESS_KEY}; it is
 *       recomputed with the same signer the connector uses ({@link AwsRequestSigner}). {@code X-Amz-Invocation-Type: RequestResponse} (default) runs the
 *       function: 200 with {@code {"function":..,"echo":<payload>}}, or, when the payload has {@code "fail": true}, 200
 *       with {@code X-Amz-Function-Error: Unhandled} and an error body (Lambda's own behaviour). {@code Event} answers
 *       202 and, when the payload carries the {@link AsyncCallback} envelope, sends the callback (below).</li>
 *   <li><b>Azure Functions</b> {@code POST /api/<name>} with {@code x-functions-key} = {@link #FUNCTION_KEY}.</li>
 *   <li><b>Cloud Run / Cloud Functions</b> {@code POST /run/<name>} with {@code Authorization: Bearer <ID token>}: an RS256
 *       token signed by this simulator's Google key whose {@code aud} is {@link #runAudience()}.</li>
 *   <li><b>Google token endpoint</b> {@code POST /token}: the JWT-bearer grant with an assertion signed by the simulated
 *       service account ({@link #serviceAccountKeyJson()}); {@code target_audience} gives an {@code id_token},
 *       {@code scope} an {@code access_token}.</li>
 * </ul>
 * <b>Callback</b>: a CloudEvent POSTed to the envelope's url ({@code ce-id} unique per invocation, {@code ce-type} =
 * eventType, {@code ce-sangamcorrelation} = correlationKey, body {@code {"status":"OK","output":{...}}}) signed with
 * {@code X-Sangam-Signature: sha256=<hex HMAC-SHA256(callbackSecret, body)>}, the inbound webhook channel scheme. The
 * callback goes to loopback only. Every invocation is recorded in the effects ledger (type {@code INVOKE}, key = the
 * request id).
 */
public final class ServerlessSimulator extends SimHttpServer {

    public static final String AWS_REGION = "ap-south-1";
    public static final String AWS_ACCESS_KEY = "AKIDSIMULATOR";
    /** Development literal; never valid against AWS. */
    public static final String AWS_SECRET_KEY = "sim-secret-access-key";
    public static final String FUNCTION_KEY = "sim-function-key";
    public static final String EFFECT_INVOKE = "INVOKE";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final Duration MAX_SKEW = Duration.ofMinutes(15);

    /** A callback the simulator sent. */
    public record Callback(URI url, String eventId, String eventType, String correlationKey, int status) {
    }

    private final KeyPair googleKey;
    private final KeyPair serviceAccountKey;
    private final String serviceAccountEmail = "sangam-sim@sim-project.iam.gserviceaccount.com";
    private final byte[] callbackSecret;
    private final AtomicLong tokens = new AtomicLong();
    private final List<Callback> callbacks = new CopyOnWriteArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ServerlessSimulator(String callbackSecret) {
        super("serverless");
        this.callbackSecret = callbackSecret == null ? null : callbackSecret.getBytes(StandardCharsets.UTF_8);
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            this.googleKey = g.generateKeyPair();
            this.serviceAccountKey = g.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        prefixRoute("POST", "/2015-03-31/functions/", this::lambda);
        prefixRoute("POST", "/api/", this::azure);
        prefixRoute("POST", "/run/", this::cloudRun);
        route("POST", "/token", this::token);
    }

    /** The audience Cloud Run expects: this simulator's origin. */
    public String runAudience() {
        return baseUri().toString();
    }

    /** A service-account key file (JSON) for the simulated project, with this simulator as its token endpoint. */
    public String serviceAccountKeyJson() {
        RSAPrivateCrtKey k = (RSAPrivateCrtKey) serviceAccountKey.getPrivate();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(k.getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "service_account");
        m.put("project_id", "sim-project");
        m.put("private_key_id", "sim-sa-key-1");
        m.put("private_key", pem);
        m.put("client_email", serviceAccountEmail);
        m.put("token_uri", uri("/token").toString());
        return Json.write(m);
    }

    public List<Callback> callbacks() {
        return List.copyOf(callbacks);
    }

    private Response lambda(Request req) {
        String name = req.pathAfter("/2015-03-31/functions/");
        if (name == null || !name.endsWith("/invocations")) {
            return Response.json(404, "{\"message\":\"unknown route\"}");
        }
        name = name.substring(0, name.length() - "/invocations".length());
        String problem = sigV4Problem(req);
        if (problem != null) {
            return Response.json(403, Json.write(Map.of("message", problem)));
        }
        JsonNode payload = Json.tree(req.body());
        String type = req.header("X-Amz-Invocation-Type") == null ? "RequestResponse" : req.header("X-Amz-Invocation-Type");
        String requestId = UUID.randomUUID().toString();
        ledger().record(EFFECT_INVOKE, requestId, Map.of("function", name, "type", type));
        if (type.equals("Event")) {
            callback(payload, Map.of("function", name, "echo", payloadWithoutEnvelope(payload)));
            return new Response(202, "application/json", new byte[0]).withHeader("X-Amzn-RequestId", requestId);
        }
        if (payload != null && payload.path("fail").asBoolean(false)) {
            return Response.json(200, "{\"errorMessage\":\"simulated failure\",\"errorType\":\"SimError\"}")
                    .withHeader("X-Amz-Function-Error", "Unhandled").withHeader("X-Amzn-RequestId", requestId);
        }
        return Response.json(200, Json.write(Map.of("function", name, "echo", payloadWithoutEnvelope(payload))))
                .withHeader("X-Amzn-RequestId", requestId);
    }

    private Response azure(Request req) {
        String key = req.header("x-functions-key");
        if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),
                FUNCTION_KEY.getBytes(StandardCharsets.UTF_8))) {
            return Response.json(401, "{\"message\":\"function key missing or wrong\"}");
        }
        return invokeHttpFunction(req, req.pathAfter("/api/"));
    }

    private Response cloudRun(Request req) {
        String auth = req.header("Authorization");
        JsonNode claims = auth == null || !auth.startsWith("Bearer ") ? null
                : Jwt.verifyRs256(auth.substring(7), googleKey.getPublic()).orElse(null);
        if (claims == null || !runAudience().equals(claims.path("aud").asText())
                || claims.path("exp").asLong(0) <= Instant.now().getEpochSecond()) {
            return Response.json(401, "{\"message\":\"ID token missing, invalid, expired or for another audience\"}");
        }
        return invokeHttpFunction(req, req.pathAfter("/run/"));
    }

    private Response invokeHttpFunction(Request req, String name) {
        JsonNode payload = Json.tree(req.body());
        String requestId = UUID.randomUUID().toString();
        ledger().record(EFFECT_INVOKE, requestId, Map.of("function", String.valueOf(name), "type", "http"));
        Object echo = payloadWithoutEnvelope(payload);
        if (payload != null && payload.has(AsyncCallback.DEFAULT_FIELD)) {
            callback(payload, Map.of("function", String.valueOf(name), "echo", echo));
            return Response.json(202, Json.write(Map.of("accepted", true, "requestId", requestId)));
        }
        return Response.json(200, Json.write(Map.of("function", String.valueOf(name), "echo", echo)));
    }

    private Response token(Request req) {
        Map<String, String> form = form(req.bodyString());
        if (!GcpTokens.GRANT_JWT_BEARER.equals(form.get("grant_type"))) {
            return Response.json(400, "{\"error\":\"unsupported_grant_type\"}");
        }
        JsonNode a = Jwt.verifyRs256(form.get("assertion"), serviceAccountKey.getPublic()).orElse(null);
        long now = Instant.now().getEpochSecond();
        if (a == null || !serviceAccountEmail.equals(a.path("iss").asText())
                || !uri("/token").toString().equals(a.path("aud").asText()) || a.path("exp").asLong(0) <= now) {
            return Response.json(400, "{\"error\":\"invalid_grant\"}");
        }
        if (a.hasNonNull("target_audience")) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("iss", "https://accounts.google.com");
            c.put("aud", a.path("target_audience").asText());
            c.put("sub", "sim-sa-subject");
            c.put("email", serviceAccountEmail);
            c.put("iat", now);
            c.put("exp", now + 3600);
            return Response.json(200, Json.write(Map.of("id_token", Jwt.signRs256("sim-google-1", c, googleKey.getPrivate()))));
        }
        return Response.json(200, Json.write(Map.of("access_token", "sim-access-" + tokens.incrementAndGet(),
                "expires_in", 3600, "token_type", "Bearer")));
    }

    /** Recomputes the SigV4 signature of the request; null when it is valid, else why not. */
    private String sigV4Problem(Request req) {
        String auth = req.header("Authorization");
        String amzDate = req.header("X-Amz-Date");
        if (auth == null || amzDate == null || !auth.startsWith("AWS4-HMAC-SHA256 ")) {
            return "missing SigV4 authorization";
        }
        Instant at;
        try {
            at = Instant.from(AMZ_DATE.parse(amzDate));
        } catch (RuntimeException e) {
            return "bad X-Amz-Date";
        }
        if (Duration.between(at, Instant.now()).abs().compareTo(MAX_SKEW) > 0) {
            return "signature expired";
        }
        int sh = auth.indexOf("SignedHeaders=");
        if (sh < 0 || !auth.contains("Credential=" + AWS_ACCESS_KEY + "/")) {
            return "unknown access key";
        }
        String signedHeaders = auth.substring(sh + "SignedHeaders=".length(), auth.indexOf(',', sh));
        Map<String, String> headers = new LinkedHashMap<>();
        for (String h : signedHeaders.split(";")) {
            if (!h.equals("host") && !h.equals("x-amz-date") && !h.equals("x-amz-security-token")) {
                String v = req.header(h);
                if (v == null) {
                    return "signed header " + h + " missing";
                }
                headers.put(h, v);
            }
        }
        String session = req.header("X-Amz-Security-Token");
        URI uri = URI.create("http://" + req.header("Host") + req.path() + (req.query() == null ? "" : "?" + req.query()));
        String expected = AwsRequestSigner.sign("POST", uri, headers, req.body(), AWS_REGION, "lambda",
                new org.sangam.ports.events.channel.AwsSigV4.Credentials(AWS_ACCESS_KEY, AWS_SECRET_KEY, session), at).get("Authorization");
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), auth.getBytes(StandardCharsets.UTF_8))
                ? null : "signature does not match";
    }

    private void callback(JsonNode payload, Map<String, Object> output) {
        JsonNode env = payload == null ? null : payload.get(AsyncCallback.DEFAULT_FIELD);
        if (env == null || !env.hasNonNull("url")) {
            return;
        }
        URI url = URI.create(env.path("url").asText());
        String eventId = UUID.randomUUID().toString();
        int status;
        try {
            if (url.getHost() == null || !InetAddress.getByName(url.getHost()).isLoopbackAddress()) {
                status = -1;   // the simulator calls back to loopback only
            } else {
                byte[] body = Json.bytes(Map.of("status", "OK", "output", output));
                HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("ce-specversion", "1.0")
                        .header("ce-id", eventId)
                        .header("ce-source", "sim://serverless")
                        .header("ce-type", env.path("eventType").asText())
                        .header("ce-sangamcorrelation", env.path("correlationKey").asText());
                if (callbackSecret != null) {
                    b.header("X-Sangam-Signature", "sha256=" + hmacHex(callbackSecret, body));
                }
                status = client.send(b.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = -2;
        } catch (Exception e) {
            status = -2;
        }
        callbacks.add(new Callback(url, eventId, env.path("eventType").asText(), env.path("correlationKey").asText(),
                status));
    }

    private static Object payloadWithoutEnvelope(JsonNode payload) {
        if (payload == null) {
            return Map.of();
        }
        Object v = Json.MAPPER.convertValue(payload, Object.class);
        if (v instanceof Map<?, ?> m && m.containsKey(AsyncCallback.DEFAULT_FIELD)) {
            Map<Object, Object> copy = new LinkedHashMap<>(m);
            copy.remove(AsyncCallback.DEFAULT_FIELD);
            return copy;
        }
        return v == null ? Map.of() : v;
    }

    static String hmacHex(byte[] key, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String p : body.split("&")) {
            int eq = p.indexOf('=');
            if (eq > 0) {
                m.put(URLDecoder.decode(p.substring(0, eq), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT),
                        URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }
}
