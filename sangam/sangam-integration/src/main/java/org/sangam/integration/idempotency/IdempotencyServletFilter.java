// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.idempotency;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.sangam.integration.connector.Json;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP filter contract for R41 (X.2.2). WS-REST registers it in front of {@code /api/v1/**} mutations; Spring MVC
 * handlers then find the claim with {@link IdempotencyContext#current()} and complete it as the last write of their
 * engine transaction.
 *
 * <p>Per request: resolve the command ({@link CommandResolver}); no command → pass through. Otherwise read and cache the
 * body, build the fingerprint input, and run {@link IdempotencyService#execute}: refusals and replays are answered
 * here without calling the handler; a new claim runs the handler with a buffered response, which is then classified
 * (stored final / abandoned) and written out.
 *
 * <p>The handler's stored body must be the exact bytes it returns (the filter returns the stored bytes on replay).
 */
public final class IdempotencyServletFilter implements Filter {

    /** Command metadata for a request; empty = not an idempotent command. */
    public record CommandMeta(String commandType, String pathTemplate, Map<String, ?> pathParams,
                              Map<String, ?> semanticQueryParams) {
    }

    @FunctionalInterface
    public interface CommandResolver {
        Optional<CommandMeta> resolve(HttpServletRequest request);
    }

    /** Tenant and principal from the authenticated identity (VI.4 rule 6). */
    public interface IdentityResolver {
        String tenantId(HttpServletRequest request);

        String principalId(HttpServletRequest request);
    }

    private static final int MAX_BODY = 4 * 1024 * 1024;

    private final IdempotencyService service;
    private final CommandResolver commands;
    private final IdentityResolver identity;

    public IdempotencyServletFilter(IdempotencyService service, CommandResolver commands, IdentityResolver identity) {
        this.service = service;
        this.commands = commands;
        this.identity = identity;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http) || !(response instanceof HttpServletResponse out)) {
            chain.doFilter(request, response);
            return;
        }
        Optional<CommandMeta> meta = commands.resolve(http);
        if (meta.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = http.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            write(out, IdempotentResponse.problem(413, "PAYLOAD_TOO_LARGE", null, null));
            return;
        }
        String tenant = identity.tenantId(http);
        String principal = identity.principalId(http);
        if (tenant == null || principal == null) {
            chain.doFilter(new CachedBodyRequest(http, body), response);   // authentication answers 401 itself
            return;
        }
        Object parsed = body.length == 0 ? null : Json.MAPPER.convertValue(Json.tree(body), Object.class);
        CommandMeta m = meta.get();
        IdempotentRequest req = new IdempotentRequest(tenant, principal, m.commandType(), http.getMethod(), m.pathTemplate(),
                m.pathParams(), m.semanticQueryParams(), parsed, http.getHeader("Idempotency-Key"), http.getHeader("If-Match"),
                http.getHeader("X-Sangam-Acting-For-Seat"), IdempotentRequest.preferWait(http.getHeader("Prefer")));
        IdempotentResponse result = service.execute(req, ctx -> {
            BufferingResponse buffered = new BufferingResponse(out);
            try {
                chain.doFilter(new CachedBodyRequest(http, body), buffered);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (ServletException e) {
                throw new IllegalStateException(e);
            }
            return buffered.toResponse();
        });
        write(out, result);
    }

    private static void write(HttpServletResponse out, IdempotentResponse r) throws IOException {
        if (out.isCommitted()) {
            return;
        }
        out.setStatus(r.status());
        r.headers().forEach(out::setHeader);
        if (r.contentType() != null) {
            out.setContentType(r.contentType());
        }
        if (r.body().length > 0) {
            out.setContentLength(r.body().length);
            out.getOutputStream().write(r.body());
        }
        out.flushBuffer();
    }

    /** Request whose body can be read again by the handler. */
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
                    throw new UnsupportedOperationException("async read not supported on a cached body");
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
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
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

    /** Captures status, headers and body so the filter can classify before anything reaches the client. */
    static final class BufferingResponse extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final Map<String, String> headers = new java.util.LinkedHashMap<>();
        private int status = 200;
        private String contentType;
        private PrintWriter writer;
        private final ServletOutputStream stream = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                throw new UnsupportedOperationException("async write not supported while buffering");
            }

            @Override
            public void write(int b) {
                buf.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                buf.write(b, off, len);
            }
        };

        BufferingResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setStatus(int sc) {
            status = sc;
        }

        @Override
        public void sendError(int sc) {
            status = sc;
        }

        @Override
        public void sendError(int sc, String msg) {
            status = sc;
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public void setHeader(String name, String value) {
            headers.put(name, value);
        }

        @Override
        public void addHeader(String name, String value) {
            headers.merge(name, value, (a, b) -> a + ", " + b);
        }

        @Override
        public void setIntHeader(String name, int value) {
            headers.put(name, Integer.toString(value));
        }

        @Override
        public String getHeader(String name) {
            return headers.get(name);
        }

        @Override
        public void setContentType(String type) {
            contentType = type;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public void setContentLength(int len) {
            // computed from the buffer
        }

        @Override
        public void setContentLengthLong(long len) {
            // computed from the buffer
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return stream;
        }

        @Override
        public PrintWriter getWriter() {
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(buf, StandardCharsets.UTF_8));
            }
            return writer;
        }

        @Override
        public void flushBuffer() {
            if (writer != null) {
                writer.flush();
            }
        }

        @Override
        public boolean isCommitted() {
            return false;
        }

        IdempotentResponse toResponse() {
            flushBuffer();
            return new IdempotentResponse(status, buf.toByteArray(), contentType, headers);
        }
    }

    @Override
    public void init(jakarta.servlet.FilterConfig filterConfig) {
    }

    @Override
    public void destroy() {
    }
}
