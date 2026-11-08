// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.IdempotencyStore;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.IdempotencyPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * R41 / X.2.2: the {@code Idempotency-Key} lifecycle for every state-changing command in {@link CommandCatalog}.
 * <ol>
 *   <li>fingerprint = SHA-256(JCS({commandType, method, pathTemplate, pathParams, semanticQueryParams, body})), with
 *       {@code If-Match} and {@code X-Sangam-Acting-For-Seat} included and transport headers left out (X.2.2.2);</li>
 *   <li>claim (own transaction); replay / conflict / in-progress answered without executing;</li>
 *   <li>execute: the controller runs inside the command transaction, and the {@code COMPLETED} write is its last write
 *       (L6, P-1); the response is buffered and only sent after commit;</li>
 *   <li>replays re-check authorization without cache (step 4) and carry {@code Idempotent-Replayed}.</li>
 * </ol>
 * Runs after authentication (it needs the principal). Without a key the command runs unprotected, except in the
 * government profile where a key is required for the listed commands ({@code 400 IDEMPOTENCY_KEY_REQUIRED}).
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final int MAX_BODY = 16 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final CommandCatalog catalog;
    private final IdempotencyPort idempotency;
    private final PermissionPort permissions;
    private final AuditPort audit;
    private final RestProperties props;

    public IdempotencyFilter(CommandCatalog catalog, IdempotencyPort idempotency, PermissionPort permissions,
                             AuditPort audit, RestProperties props) {
        this.catalog = catalog;
        this.idempotency = idempotency;
        this.permissions = permissions;
        this.audit = audit;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String m = request.getMethod();
        return !(m.equals("POST") || m.equals("PUT") || m.equals("PATCH") || m.equals("DELETE"))
                || !path(request).startsWith(CommandCatalog.BASE + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var match = catalog.match(request.getMethod(), path(request));
        if (match.isEmpty() || RequestContexts.principal(request) == null) {
            chain.doFilter(request, response);
            return;
        }
        CommandCatalog.Command command = match.get().command();
        Telemetry.commandType(command.commandType());
        String key = request.getHeader(RequestContexts.IDEMPOTENCY_HEADER);
        if (key == null || key.isBlank()) {
            if (props.isGovernmentProfile() && command.requiredInGovernmentProfile()) {
                Problems.write(request, response, ApiProblem.badRequest("IDEMPOTENCY_KEY_REQUIRED",
                        "Idempotency-Key is required for " + command.commandType() + " in the government profile"));
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        RequestContext ctx;
        try {
            ctx = RequestContexts.from(request);
        } catch (ApiProblem p) {
            Problems.write(request, response, p);
            return;
        }

        HttpServletRequest req = request;
        Object body;
        String contentType = request.getContentType() == null ? "" : request.getContentType().toLowerCase();
        if (contentType.startsWith("multipart/")) {
            body = multipartDigest(request);
        } else {
            byte[] raw = readBody(request);
            req = new CachedBodyRequest(request, raw);
            body = jsonBody(raw, contentType);
        }
        Map<String, Object> query = new TreeMap<>();
        request.getParameterMap().forEach((k, v) -> {
            if (!contentType.startsWith("application/x-www-form-urlencoded")) {
                query.put(k, List.of(v));
            }
        });
        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("query", query);
        semantic.put("ifMatch", request.getHeader("If-Match"));
        semantic.put("actingForSeat", request.getHeader(RequestContexts.ACTING_FOR_HEADER));
        byte[] fp = IdempotencyStore.fingerprint(command.commandType(), command.method(), command.pathTemplate(),
                match.get().pathParams(), semantic, body);

        IdempotencyPort.Begin begin = idempotency.begin(ctx, command.commandType(), key.trim(), fp);
        switch (begin) {
            case IdempotencyPort.Rejected r -> {
                Metrics.counter("api.idempotency.conflicts", "reason", r.code()).increment();
                if (r.status() == 409) {
                    Metrics.counter("api.idempotency.in.progress").increment();
                }
                Problems.write(request, response, new ApiProblem(r.status(), r.code(), null, describe(r.code()), null,
                        r.retryAfterSeconds() > 0 ? r.retryAfterSeconds() : null));
            }
            case IdempotencyPort.Replay r -> replay(request, response, ctx, command, key, r);
            case IdempotencyPort.Claimed claimed -> execute(req, response, chain, claimed);
        }
    }

    private void execute(HttpServletRequest req, HttpServletResponse response, FilterChain chain,
                         IdempotencyPort.Claimed claimed) throws IOException, ServletException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            idempotency.execute(claimed, () -> {
                chain.doFilter(req, wrapper);
                String ref = wrapper.getHeader(Lists.RESOURCE_REF);
                if (ref == null) {
                    ref = wrapper.getHeader("Location");
                }
                return new IdempotencyPort.Outcome(wrapper.getStatus(), wrapper.getContentAsByteArray(), ref);
            });
        } catch (IOException | ServletException | RuntimeException e) {
            if (!wrapper.isCommitted()) {
                wrapper.resetBuffer();
            }
            if (e instanceof ApiProblem p) {
                wrapper.reset();
                Problems.write(req, response, p);
                return;
            }
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
        wrapper.copyBodyToResponse();
    }

    private void replay(HttpServletRequest request, HttpServletResponse response, RequestContext ctx,
                        CommandCatalog.Command command, String key, IdempotencyPort.Replay r) throws IOException {
        // Step 4: re-check authorization without cache; on failure 403 and no stored body.
        if (!permissions.mayReplay(ctx, command.commandType(), r.resourceRef())) {
            Problems.write(request, response, ApiProblem.forbidden("ACTION_NOT_PERMITTED",
                    "You no longer hold the permission for this stored result"));
            return;
        }
        Metrics.counter("api.idempotent.replays", "command", command.commandType(),
                "kind", r.compacted() ? "compacted" : "full").increment();
        try {
            // X.2.2.7 rule 2: every replay that returns a stored response is audited (class B).
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("command_type", command.commandType());
            payload.put("idempotency_key", key);
            payload.put("replay", r.compacted() ? "compacted" : "full");
            payload.put("resource_ref", r.resourceRef());
            payload.put("status", r.status());
            audit.record(ctx, "sec:" + ctx.tenantId(), "IDEMPOTENT_REPLAY", payload);
        } catch (RuntimeException e) {
            LOG.warn("could not audit idempotent replay for {}: {}", command.commandType(), e.toString());
        }
        response.setStatus(r.status());
        response.setHeader("Idempotent-Replayed", r.compacted() ? "compacted" : "true");
        if (r.expiresAt() != null) {
            response.setHeader("Idempotency-Key-Expires", r.expiresAt().toString());
        }
        if (r.resourceRef() != null) {
            response.setHeader(Lists.RESOURCE_REF, r.resourceRef());
        }
        if (!r.compacted() && r.body() != null && r.body().length > 0) {
            boolean problem = r.status() >= 400;
            response.setContentType(problem ? Problems.MEDIA_TYPE : "application/json");
            response.getOutputStream().write(r.body());
        }
    }

    private static String describe(String code) {
        return switch (code) {
            case "IDEMPOTENCY_KEY_REUSED" -> "This Idempotency-Key was already used with a different request";
            case "IDEMPOTENCY_KEY_PRINCIPAL_MISMATCH" -> "This Idempotency-Key belongs to another principal";
            case "IDEMPOTENCY_IN_PROGRESS" -> "A request with this Idempotency-Key is still being processed";
            case "IDEMPOTENCY_KEY_INVALID" -> "Idempotency-Key must be 1-128 printable ASCII characters";
            default -> code;
        };
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctxPath = request.getContextPath();
        return ctxPath != null && !ctxPath.isEmpty() && uri.startsWith(ctxPath) ? uri.substring(ctxPath.length()) : uri;
    }

    private static byte[] readBody(HttpServletRequest request) throws IOException {
        try (InputStream in = request.getInputStream()) {
            byte[] b = in.readNBytes(MAX_BODY + 1);
            if (b.length > MAX_BODY) {
                throw new ApiProblem(413, "PAYLOAD_TOO_LARGE", "Request body above " + MAX_BODY + " bytes");
            }
            return b;
        }
    }

    private static Object jsonBody(byte[] raw, String contentType) {
        if (raw.length == 0) {
            return null;
        }
        if (contentType.contains("json")) {
            try {
                return jsonSafe(JSON.readValue(raw, Object.class));
            } catch (IOException e) {
                // Malformed JSON: fingerprint the bytes; the controller answers 400 MALFORMED_BODY (stored FAILED_FINAL).
            }
        }
        return "sha256:" + Hashes.hex(Hashes.sha256(raw));
    }

    /** Multipart: fingerprint part names, file names and content digests (parts stay readable downstream). */
    private static Object multipartDigest(HttpServletRequest request) throws IOException, ServletException {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (Part p : request.getParts()) {
            Map<String, Object> m = new TreeMap<>();
            m.put("name", p.getName());
            m.put("filename", p.getSubmittedFileName());
            try (InputStream in = p.getInputStream()) {
                m.put("sha256", Hashes.hex(Hashes.sha256(in.readAllBytes())));
            }
            parts.add(m);
        }
        parts.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
        return parts;
    }

    /** Non-integral numbers become their plain decimal string (R33), so canonical JSON never rounds. */
    @SuppressWarnings("unchecked")
    static Object jsonSafe(Object v) {
        if (v instanceof BigDecimal d) {
            try {
                return d.toBigIntegerExact();
            } catch (ArithmeticException e) {
                return d.toPlainString();
            }
        }
        if (v instanceof Double || v instanceof Float) {
            return new BigDecimal(v.toString()).toPlainString();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<Object, Object>) m).forEach((k, x) -> out.put(String.valueOf(k), jsonSafe(x)));
            return out;
        }
        if (v instanceof Collection<?> c) {
            return c.stream().map(IdempotencyFilter::jsonSafe).toList();
        }
        if (v instanceof BigInteger || v instanceof Number) {
            return v;
        }
        return v;
    }

    /** Request wrapper that replays a body already read for the fingerprint. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("synchronous body only");
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
