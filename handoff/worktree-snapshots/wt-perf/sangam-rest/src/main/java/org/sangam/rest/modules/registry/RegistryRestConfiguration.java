// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.registry;

import java.util.function.Function;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sangam.core.layer.Transactions;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.rest.AssetController;
import org.sangam.registry.rest.CustodyController;
import org.sangam.registry.rest.InstrumentController;
import org.sangam.registry.rest.PartyController;
import org.sangam.registry.rest.RegisterController;
import org.sangam.registry.rest.RegistryController;
import org.sangam.registry.rest.RegistryExceptionHandler;
import org.sangam.registry.rest.RegistryRestSupport;
import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.web.RequestContexts;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * REST exposure of sangam-registry ({@code /api/v1/registry/**}, registry README §2): the module's own Spring
 * controllers and its problem+json handler, with a {@link RegistryRestSupport} whose caller is the authenticated
 * {@link RequestContext} (tenant from the token, VI.4 rule 6; {@code Idempotency-Key} as operationId) and whose
 * transactions join the Spring transaction the engine and sangam-rest's Idempotency-Key filter use (P-1).
 *
 * <p>Endpoint permissions ({@link RegistryPermissions}): GET needs {@code registry.read}, every other method
 * {@code registry.write}; the public verification paths need none (sangam-server permits and rate-limits them).
 * Object-level visibility is applied inside the module (VisibilityPort → VI.4).
 */
@AutoConfiguration(after = SangamRestAutoConfiguration.class)
@ConditionalOnClass(name = "org.sangam.registry.RegistryModule")
@ConditionalOnBean(type = "org.sangam.registry.RegistryModule")
@Import({RegistryController.class, PartyController.class, InstrumentController.class, RegisterController.class,
        AssetController.class, CustodyController.class, RegistryExceptionHandler.class, RegistryApiProblemAdvice.class})
public class RegistryRestConfiguration {

    @Bean
    public RegistryRestSupport sangamRegistryRestSupport(TxSupport tx) {
        Transactions joined = new Transactions() {
            @Override
            public <T> T inTransaction(Function<java.sql.Connection, T> work) {
                return tx.withConnection(work);
            }
        };
        return new RegistryRestSupport(joined, RegistryRestConfiguration::currentCaller);
    }

    @Bean
    public WebMvcConfigurer sangamRegistryPermissions(PermissionPort permissions) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        RequestContext ctx = RequestContexts.from(request);
                        permissions.require(ctx, "GET".equals(request.getMethod())
                                ? RegistryPermissions.READ : RegistryPermissions.WRITE);
                        return true;
                    }
                }).addPathPatterns("/api/v1/registry/**")
                        .excludePathPatterns("/api/v1/registry/instruments/public/**",
                                "/api/v1/registry/registers/public/**");
            }
        };
    }

    /** The registry caller of the current request (authenticated identity; tenant never from the request). */
    static RegistryContext currentCaller() {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                .getRequest();
        RequestContext ctx = RequestContexts.from(request);
        return new RegistryContext(ctx.tenantId(), ctx.principalId(), ctx.actingForSeatId(),
                request.getHeader("X-Purpose"), ctx.idempotencyKey(), null);
    }
}
