// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.SangamSecurity;
import org.sangam.security.admin.RoleAdminService;
import org.sangam.security.admin.TenantAdminService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * First administrators come from install-time configuration bound to identity-provider subjects, never from an
 * unauthenticated API. With {@code sangam.security.first-admins.tenant} and
 * {@code sangam.security.first-admins.subjects} (two different {@code person:subject:Display name} entries), start-up
 * creates the tenant if it is missing and appoints them. It acts only when the tenant has no administrator role yet, so
 * later runs change nothing and the settings can be removed after the install.
 * <p>
 * The tenant is created in sangam-security directly, not through {@code AdminPort}, so the hooks on
 * {@code AdminPort.createTenant} do not run by themselves. The module defaults (role templates and self-service
 * grants, {@code SecurityKeysWiring#sangamModuleDefaultsOnboarding}) are installed here for a tenant this bootstrap
 * creates, with the same call (register E-33 f); a tenant that existed before is left as it is, and its
 * administrators install them with {@code POST /api/v1/admin/security/module-defaults}.
 * <p>
 * It deploys no workflow pack (register E-25): which packs a tenant gets is decided by onboarding or by
 * {@code sangam.packs.deploy-on-start} with {@code sangam.packs.tenants}; that start-up deployment runs after this
 * bootstrap ({@link #ORDER}). A start that leaves this tenant without a pack says so at WARN
 * ({@code PacksWiring#sangamFirstAdminsTenantPacksCheck}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.security.first-admins.tenant")
public class FirstAdminBootstrap {

    /**
     * The order of {@link #sangamFirstAdmins} among the start-up runners (register E-33 d): before every runner that
     * declares no order. Spring Boot keeps no order among those, so without this the start-up pack deployment
     * ({@code PacksWiring#sangamPacksDeployOnStart}) could run, and audit {@code PACKS_DEPLOYED}, before the tenant
     * of the install existed. Runners that work per tenant find the tenant created.
     */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    /** A start-up runner with the declared {@link #ORDER}. */
    private interface OrderedRunner extends ApplicationRunner, Ordered {
        @Override
        default int getOrder() {
            return ORDER;
        }
    }

    @Bean
    public ApplicationRunner sangamFirstAdmins(SangamSecurity security, DataSource ds, Environment env) {
        return (OrderedRunner) args -> {
            String tenant = env.getRequiredProperty("sangam.security.first-admins.tenant");
            List<RoleAdminService.AdminSeed> seeds = new ArrayList<>();
            for (String s : env.getRequiredProperty("sangam.security.first-admins.subjects").split(",")) {
                String[] p = s.trim().split(":", 3);
                if (p.length != 3) {
                    throw new IllegalStateException("sangam.security.first-admins.subjects: expected person:subject:name");
                }
                seeds.add(new RoleAdminService.AdminSeed(p[0], p[1], p[2]));
            }
            SecurityIdentity platform = SecurityIdentity.system(tenant, "install-bootstrap")
                    .withGroupsAndRoles(Set.of(), Set.of("system", TenantAdminService.PLATFORM_ADMIN_ROLE));
            boolean[] created = {false};
            Transactions.of(ds).inTransaction(c -> {
                if (security.tenants().find(c, tenant).isEmpty()) {
                    security.tenants().createTenant(c, platform, tenant, tenant, null, null, null, Map.of());
                    created[0] = true;
                }
                try (var ps = c.prepareStatement("SELECT 1 FROM SEC_ROLE WHERE tenant_id=? AND role_id=?")) {
                    ps.setString(1, tenant);
                    ps.setString(2, TenantAdminService.TENANT_ADMIN_ROLE);
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return null;
                        }
                    }
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException("cannot check tenant administrators", e);
                }
                security.roles().bootstrapTenantAdmins(c, platform, tenant, seeds);
                return null;
            });
            if (created[0]) {
                // register E-33 (f): the hook on tenant creation that this bootstrap does not pass through
                SecurityKeysWiring.installDefaults(security, ds, tenant);
            }
        };
    }
}
