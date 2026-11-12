// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.web.Problems;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * SL-59 / SCALE-ARCHITECTURE 1.6: when no database connection can be had within the pool's acquire wait (2 s,
 * {@code spring.datasource.hikari.connection-timeout}), the request answers {@code 503 DATABASE_UNAVAILABLE} with
 * {@code Retry-After} ({@code sangam.db.unavailable-retry-after}, default 2 s) instead of a 500 or a hidden queue.
 *
 * <p>The signal is a {@link SQLTransientConnectionException} anywhere in the cause chain: HikariCP throws it when the
 * acquire wait expires ("Connection is not available, request timed out after ...") or the pool cannot open a
 * connection; Spring ({@code CannotGetJdbcConnectionException}), MyBatis/Operaton ({@code PersistenceException},
 * {@code ProcessEngineException}) and the product layer ({@code LayerSqlException}) wrap it. Nothing ran on the
 * connection that was never handed out, so retrying is safe; a connection lost mid-statement is not matched.
 *
 * <p>Generic, not per controller: a {@link HandlerExceptionResolver} ordered before Spring MVC's
 * {@code @ExceptionHandler} resolution (so no module advice with an {@code Exception} catch-all turns it into a 500),
 * and a servlet filter around the whole chain for the servlet filters that use the database themselves (tenant
 * quotas, idempotency, cell state) and never reach the dispatcher.
 */
@Configuration(proxyBeanMethods = false)
public class PoolExhaustionProblems {

    private static final Logger LOG = LoggerFactory.getLogger(PoolExhaustionProblems.class);
    static final String RETRY_AFTER = "sangam.db.unavailable-retry-after";
    static final String CODE = "DATABASE_UNAVAILABLE";
    private static final int MAX_DEPTH = 64;
    private static final long LOG_EVERY_MS = 10_000;
    private static final AtomicLong LAST_LOG = new AtomicLong();

    /** Before Spring MVC's composite resolver (order 0); after Boot's {@code DefaultErrorAttributes} (highest). */
    @Bean
    public HandlerExceptionResolver sangamPoolExhaustionResolver(Environment env) {
        return new Resolver(retryAfterSeconds(env));
    }

    /** Around every other filter, so a filter's database call that cannot get a connection answers 503 as well. */
    @Bean
    public FilterRegistrationBean<UnavailableFilter> sangamPoolExhaustionFilter(Environment env) {
        FilterRegistrationBean<UnavailableFilter> reg = new FilterRegistrationBean<>(
                new UnavailableFilter(retryAfterSeconds(env)));
        reg.setName("sangamPoolExhaustionFilter");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        reg.addUrlPatterns("/*");
        return reg;
    }

    /** {@code sangam.db.unavailable-retry-after} in whole seconds, at least 1. */
    static int retryAfterSeconds(Environment env) {
        Duration d = env.getProperty(RETRY_AFTER, Duration.class, Duration.ofSeconds(2));
        long s = d == null ? 2 : (d.toMillis() + 999) / 1000;
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, s));
    }

    /**
     * Whether {@code e} means "no connection could be had": a {@link SQLTransientConnectionException} in its cause
     * chain (or a {@link SQLException#getNextException() next exception}). Cycle-safe and depth-bounded.
     */
    public static boolean connectionUnavailable(Throwable e) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable t = e; t != null && depth < MAX_DEPTH && seen.add(t); t = t.getCause(), depth++) {
            if (t instanceof SQLTransientConnectionException) {
                return true;
            }
            if (t instanceof SQLException s) {
                for (SQLException n = s.getNextException(); n != null && depth < MAX_DEPTH && seen.add(n);
                     n = n.getNextException(), depth++) {
                    if (n instanceof SQLTransientConnectionException) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The 503 problem written for an unavailable connection. */
    static ApiProblem problem(int retryAfterSeconds) {
        return new ApiProblem(503, CODE, null,
                "No database connection is available right now; retry after the indicated delay", null,
                retryAfterSeconds);
    }

    /** Writes the 503 unless the response is already committed; returns whether it did. */
    static boolean write(HttpServletRequest req, HttpServletResponse res, Throwable cause, int retryAfterSeconds)
            throws IOException {
        if (res.isCommitted()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long last = LAST_LOG.get();
        if (now - last >= LOG_EVERY_MS && LAST_LOG.compareAndSet(last, now)) {
            LOG.warn("503 {} on {} {}: {} (logged at most every {} s)", CODE, req.getMethod(), req.getRequestURI(),
                    cause, LOG_EVERY_MS / 1000);
        }
        res.resetBuffer();
        Problems.write(req, res, problem(retryAfterSeconds));
        return true;
    }

    /** Controller exceptions: runs ahead of every {@code @ExceptionHandler} advice. */
    static final class Resolver implements HandlerExceptionResolver, Ordered {
        private final int retryAfterSeconds;

        Resolver(int retryAfterSeconds) {
            this.retryAfterSeconds = retryAfterSeconds;
        }

        @Override
        public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler,
                                             Exception ex) {
            if (!connectionUnavailable(ex)) {
                return null;
            }
            try {
                return write(request, response, ex, retryAfterSeconds) ? new ModelAndView() : null;
            } catch (IOException io) {
                ex.addSuppressed(io);
                return null;
            }
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 1;
        }
    }

    /** Exceptions thrown by servlet filters (they never reach the dispatcher's resolvers). */
    static final class UnavailableFilter extends OncePerRequestFilter {
        private final int retryAfterSeconds;

        UnavailableFilter(int retryAfterSeconds) {
            this.retryAfterSeconds = retryAfterSeconds;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            try {
                chain.doFilter(request, response);
            } catch (ServletException | IOException | RuntimeException e) {
                if (connectionUnavailable(e) && write(request, response, e, retryAfterSeconds)) {
                    return;
                }
                throw e;
            }
        }
    }
}
