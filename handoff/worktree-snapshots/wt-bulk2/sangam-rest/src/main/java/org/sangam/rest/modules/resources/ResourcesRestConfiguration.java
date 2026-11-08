// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.resources;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.resources.support.ResourcesException;
import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.web.Problems;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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

/**
 * REST exposure of sangam-resources: booking ({@code /api/v1/resources}), queues ({@code /api/v1/queues}) and rosters
 * ({@code /api/v1/rosters}) over the module's REST ports (resources README "REST ports"). Module errors map onto
 * problem+json: 404 NOT_FOUND; 409 CONFLICT, UNAVAILABLE, STALE; 422 NOT_ENTITLED, RULE, CONSTRAINT; 400 INVALID, with
 * the module's reason as {@code code}.
 */
@AutoConfiguration(after = SangamRestAutoConfiguration.class)
@ConditionalOnClass(name = "org.sangam.resources.rest.ResourcesRestPort")
@ConditionalOnBean(type = "org.sangam.resources.rest.ResourcesRestPort")
@Import({ResourcesController.class, QueueController.class, RosterController.class,
        ResourcesRestConfiguration.ResourcesProblemAdvice.class})
public class ResourcesRestConfiguration {

    /** Default identity mapping when sangam-server binds none (token identity from the request context). */
    @Bean
    @ConditionalOnMissingBean
    public ResourcesIdentity sangamDefaultResourcesIdentity() {
        return ResourcesIdentity.FROM_CONTEXT;
    }

    @RestControllerAdvice(assignableTypes = {ResourcesController.class, QueueController.class, RosterController.class})
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class ResourcesProblemAdvice {

        @ExceptionHandler(ResourcesException.class)
        public ResponseEntity<Map<String, Object>> resources(ResourcesException e, HttpServletRequest request) {
            String code = e.reason() != null && !e.reason().isBlank() ? e.reason() : e.code().name();
            return respond(new ApiProblem(e.httpStatus(), code, null, e.getMessage(),
                    Map.of("category", e.code().name()), null), request);
        }

        @ExceptionHandler(ApiProblem.class)
        public ResponseEntity<Map<String, Object>> api(ApiProblem p, HttpServletRequest request) {
            return respond(p, request);
        }

        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e, HttpServletRequest request) {
            return respond(ApiProblem.badRequest("VALIDATION_FAILED", e.getMessage()), request);
        }

        private static ResponseEntity<Map<String, Object>> respond(ApiProblem p, HttpServletRequest request) {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.parseMediaType(Problems.MEDIA_TYPE));
            return new ResponseEntity<>(Problems.body(p, request.getRequestURI()), h, HttpStatus.valueOf(p.status()));
        }
    }
}
