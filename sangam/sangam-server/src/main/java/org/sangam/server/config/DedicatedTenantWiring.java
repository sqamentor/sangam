// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.rest.port.AdminPort;
import org.sangam.server.bridge.DedicatedTenant;
import org.sangam.server.bridge.DedicatedTenantFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * M-016 (owner decision 51, Q-02 option A): an instance dedicated to one tenant's database. Active whenever
 * {@value DedicatedTenant#PROPERTY} is set (an empty value too, which {@link DedicatedTenant#of} then refuses); without it
 * {@link SharedInstanceIsolationWiring} applies instead (exactly one of the two, {@link #dedicated}). The outbox, relay,
 * inbox, idempotency store and Liquibase run are the instance's own already (they live in its one DataSource), so this
 * wiring adds only the binding and its fail-closed checks:
 * <ul>
 *   <li><b>Cell:</b> the instance must be a cell ({@value DedicatedTenant#CELL_PROPERTY}); {@link CellWiring} then
 *       registers it with the control plane as dedicated (placement exclusivity) and parks broker messages of other
 *       tenants.</li>
 *   <li><b>Start-up:</b> the attribution tenant ({@link AttributionTenant}) must be the bound tenant, and the tenant
 *       directory of the database ({@link ScheduleTenants#directory(AdminPort, org.sangam.rest.port.RequestContext,
 *       boolean)}, suspended tenants included) may hold no other tenant, and the bound tenant, once onboarded, must be
 *       recorded with isolation {@value DedicatedTenant#DATABASE}. The directory needs the security module's
 *       {@link AdminPort}; without it the start-up is refused rather than the check skipped.</li>
 *   <li><b>Requests:</b> {@link DedicatedTenantFilter} refuses every request naming another tenant (421).</li>
 * </ul>
 * Placement and routing of the instance are the control plane's (ADR-D-21 cells).
 */
@Configuration(proxyBeanMethods = false)
@Conditional(DedicatedTenantWiring.Dedicated.class)
public class DedicatedTenantWiring {

    private static final Logger LOG = LoggerFactory.getLogger(DedicatedTenantWiring.class);

    /** After Spring Security (-100), just before the IR-4 move-state filter ({@link CellWiring#STATE_FILTER_ORDER}). */
    public static final int FILTER_ORDER = CellWiring.STATE_FILTER_ORDER - 1;

    /** The system principal the directory is read as. */
    static final String PRINCIPAL = "system:dedicated-tenant";

    @Bean
    public DedicatedTenant sangamDedicatedTenant(Environment env) {
        DedicatedTenant binding = DedicatedTenant.of(env.getProperty(DedicatedTenant.PROPERTY));
        binding.requireCell(env.getProperty(DedicatedTenant.CELL_PROPERTY));
        binding.requireAttribution(AttributionTenant.resolve(env));
        return binding;
    }

    @Bean
    public FilterRegistrationBean<DedicatedTenantFilter> sangamDedicatedTenantFilter(DedicatedTenant binding) {
        FilterRegistrationBean<DedicatedTenantFilter> reg = new FilterRegistrationBean<>(new DedicatedTenantFilter(binding));
        reg.addUrlPatterns("/api/*", "/engine-rest/*");
        reg.setOrder(FILTER_ORDER);
        reg.setName("sangamDedicatedTenantFilter");
        return reg;
    }

    /** Runs once the context is up (the directory needs the schema and the security module). */
    @Bean
    public ApplicationRunner sangamDedicatedTenantDirectoryCheck(DedicatedTenant binding, Environment env,
                                                                 ObjectProvider<AdminPort> admin) {
        return args -> checkDirectory(binding, env, admin.getIfAvailable());
    }

    static void checkDirectory(DedicatedTenant binding, Environment env, AdminPort admin) {
        if (admin == null) {
            throw new IllegalStateException(DedicatedTenant.PROPERTY + " needs the tenant directory (AdminPort of the"
                    + " security module, sangam.modules.security.enabled=true) to prove this database holds no other"
                    + " tenant; refusing to start");
        }
        org.sangam.rest.port.RequestContext sys = ScheduleTenants.systemContext(env, PRINCIPAL);
        java.util.List<String> tenants = ScheduleTenants.directory(admin, sys, true);
        binding.requireDirectory(tenants);
        if (tenants.contains(binding.tenant())) {
            binding.requireIsolation(admin.tenant(sys, binding.tenant()).isolation());
        }
        LOG.info("M-016: instance dedicated to tenant {} (its own database, outbox, relay, inbox and idempotency store)",
                binding.tenant());
    }
    /** True when {@value DedicatedTenant#PROPERTY} is present, whatever its value; the shared wiring is the complement. */
    static boolean dedicated(Environment env) {
        return env.containsProperty(DedicatedTenant.PROPERTY);
    }

    static final class Dedicated implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return dedicated(context.getEnvironment());
        }
    }
}