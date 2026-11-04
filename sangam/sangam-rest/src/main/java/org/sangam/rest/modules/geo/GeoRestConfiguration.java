// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.geo;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sangam.geo.rest.GeoCaller;
import org.sangam.geo.web.GeoCallerResolver;
import org.sangam.geo.web.GeoController;
import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.Problems;
import org.sangam.rest.web.RequestContexts;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * REST exposure of sangam-geo ({@code /api/v1/geo/**}, geo README §2): the module's {@link GeoController} over the
 * server's {@code GeoRestPort}, with the caller from the authenticated {@link RequestContext} (tenant from the token,
 * VI.4 rule 6). Endpoint permissions: GET {@code geo.read}, other methods {@code geo.write}; layer administration is
 * further limited by the module to its admin group.
 */
@AutoConfiguration(after = SangamRestAutoConfiguration.class)
@ConditionalOnClass(name = "org.sangam.geo.rest.GeoRestPort")
@ConditionalOnBean(type = "org.sangam.geo.rest.GeoRestPort")
@Import({GeoController.class, GeoRestConfiguration.GeoApiProblemAdvice.class})
public class GeoRestConfiguration {

    public static final String READ = "geo.read";
    public static final String WRITE = "geo.write";

    @Bean
    public GeoCallerResolver sangamGeoCallerResolver() {
        return request -> {
            RequestContext ctx = RequestContexts.from(request);
            return new GeoCaller(ctx.tenantId(), ctx.principalId(), ctx.groups());
        };
    }

    @Bean
    public WebMvcConfigurer sangamGeoPermissions(PermissionPort permissions) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        permissions.require(RequestContexts.from(request), "GET".equals(request.getMethod()) ? READ : WRITE);
                        return true;
                    }
                }).addPathPatterns("/api/v1/geo/**");
            }
        };
    }

    /** RFC 9457 mapping of {@link ApiProblem} for the geo controller (the module maps its own GeoException). */
    @RestControllerAdvice(assignableTypes = GeoController.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class GeoApiProblemAdvice {
        @ExceptionHandler(ApiProblem.class)
        public ResponseEntity<Map<String, Object>> problem(ApiProblem p, HttpServletRequest request) {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.parseMediaType(Problems.MEDIA_TYPE));
            return new ResponseEntity<>(Problems.body(p, request.getRequestURI()), h, HttpStatus.valueOf(p.status()));
        }
    }
}
