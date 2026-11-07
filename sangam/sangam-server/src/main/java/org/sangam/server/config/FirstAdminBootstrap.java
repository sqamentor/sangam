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
import org.springframework.core.env.Environment;

/**
 * First administrators come from install-time configuration bound to identity-provider subjects, never from an
 * unauthenticated API. With {@code sangam.security.first-admins.tenant} and
 * {@code sangam.security.first-admins.subjects} (two different {@code person:subject:Display name} entries), start-up
 * creates the tenant if it is missing and appoints them. It acts only when the tenant has no administrator role yet, so
 * later runs change nothing and the settings can be removed after the install.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.security.first-admins.tenant")
public class FirstAdminBootstrap {

    @Bean
    public ApplicationRunner sangamFirstAdmins(SangamSecurity security, DataSource ds, Environment env) {
        return args -> {
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
            Transactions.of(ds).inTransaction(c -> {
                if (security.tenants().find(c, tenant).isEmpty()) {
                    security.tenants().createTenant(c, platform, tenant, tenant, null, null, null, Map.of());
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
        };
    }
}
