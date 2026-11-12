// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.server.bridge.DedicatedTenantFilter;
import org.sangam.server.bridge.SharedInstanceIsolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * M-016, owner decision 97 (Q-W3H-5 option A): on a <b>shared</b> instance (no
 * {@value org.sangam.server.bridge.DedicatedTenant#PROPERTY}; the complement of {@link DedicatedTenantWiring}) a tenant
 * recorded in the tenant directory with isolation DATABASE or SCHEMA is not served: 421
 * {@value DedicatedTenantFilter#CODE}, at the dedicated filter's order. The isolation comes from the tenant directory
 * ({@link AdminPort#tenant}, read as the system principal and cached per tenant, {@link SharedInstanceIsolation}).
 *
 * <p>Without an {@link AdminPort} there is no directory and so no recorded isolation to honour; the filter is then
 * not registered, with a warning.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(SharedInstanceIsolationWiring.Shared.class)
public class SharedInstanceIsolationWiring {

    private static final Logger LOG = LoggerFactory.getLogger(SharedInstanceIsolationWiring.class);

    /** The system principal the directory is read as. */
    static final String PRINCIPAL = "system:shared-isolation";

    @Bean
    public FilterRegistrationBean<DedicatedTenantFilter> sangamSharedIsolationFilter(Environment env,
                                                                                    ObjectProvider<AdminPort> admin) {
        AdminPort port = admin.getIfAvailable();
        FilterRegistrationBean<DedicatedTenantFilter> reg = new FilterRegistrationBean<>(
                new DedicatedTenantFilter(new SharedInstanceIsolation(isolationLookup(port, env))));
        reg.addUrlPatterns("/api/*", "/engine-rest/*");
        reg.setOrder(DedicatedTenantWiring.FILTER_ORDER);
        reg.setName("sangamSharedIsolationFilter");
        if (port == null) {
            LOG.warn("No tenant directory (AdminPort): decision 97 isolation refusal is not active on this instance");
            reg.setEnabled(false);
        }
        return reg;
    }

    /** Tenant id to its recorded isolation; null when the directory has no record of it (404). */
    static java.util.function.Function<String, String> isolationLookup(AdminPort admin, Environment env) {
        if (admin == null) {
            return id -> null;
        }
        RequestContext sys = ScheduleTenants.systemContext(env, PRINCIPAL);
        return id -> {
            try {
                return admin.tenant(sys, id).isolation();
            } catch (ApiProblem p) {
                if (p.status() == 404) {
                    return null;
                }
                throw p;
            }
        };
    }

    static final class Shared implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !DedicatedTenantWiring.dedicated(context.getEnvironment());
        }
    }
}
