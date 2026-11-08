// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

import org.sangam.rest.port.SangamPrincipal;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Log correlation (ARCHITECTURE section 1.8, R13): puts {@code traceId}, {@code tenant} and {@code principal} into the
 * SLF4J MDC for the JSON logs. Spans and metrics come from the Spring Boot OpenTelemetry starter; OTLP export is off
 * unless {@code management.otlp.tracing.endpoint} is configured (air-gapped by default, section 1.9). Span attributes
 * {@code sangam.tenant}, {@code sangam.business_key} and {@code sangam.operation_id} are set by sangam-rest.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    /** Runs late (after the security chain) so the principal is known; the MDC is cleared after every request. */
    @Bean
    public FilterRegistrationBean<Filter> sangamMdcFilter() {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>(new MdcFilter());
        reg.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        reg.addUrlPatterns("/*");
        return reg;
    }

    static final class MdcFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            try {
                SpanContext span = Span.current().getSpanContext();
                if (span.isValid()) {
                    MDC.put("traceId", span.getTraceId());
                    MDC.put("spanId", span.getSpanId());
                }
                if (request.getAttribute(SangamPrincipal.ATTRIBUTE) instanceof SangamPrincipal p) {
                    MDC.put("tenant", p.tenantId());
                    MDC.put("principal", p.principalId());
                }
                chain.doFilter(request, response);
            } finally {
                MDC.remove("traceId");
                MDC.remove("spanId");
                MDC.remove("tenant");
                MDC.remove("principal");
            }
        }
    }
}
