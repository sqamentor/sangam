// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Duration;
import java.util.List;

import javax.sql.DataSource;

import org.sangam.core.layer.Transactions;
import org.sangam.core.ports.IdentityExtrasPort;
import org.sangam.rest.adapter.memory.GroupPermissionAdapter;
import org.sangam.rest.adapter.jdbc.JdbcAdminAdapter;
import org.sangam.rest.adapter.jdbc.JdbcIdentityAdminAdapter;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.IdentityAdminPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.assignment.SangamSecurityEnginePlugin;
import org.sangam.security.audit.LoginEventBatcher;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.oidc.SecurityProperties;
import org.sangam.security.oidc.TokenAuthenticationFilters;
import org.sangam.server.bridge.SecurityAdminBridge;
import org.sangam.server.bridge.SecurityBootstrap;
import org.sangam.server.bridge.SecurityChainContributions;
import org.sangam.server.bridge.SecurityIdentityAdminBridge;
import org.sangam.server.bridge.SecurityIdentityExtras;
import org.sangam.server.bridge.SecurityIdentityFilter;
import org.sangam.server.bridge.SecurityPermissionBridge;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * sangam-security wiring (sangam-security README "Integration notes" 3–6, 9, 11).
 *
 * <ul>
 *   <li><b>One filter chain.</b> The server's {@code SecurityConfig} stays the only {@code SecurityFilterChain} (deploy
 *       contract: configurable OIDC claims, dev token, {@code SEAT_NOT_HELD}, problem+json). sangam-security's
 *       {@code SangamResourceServerConfiguration} is not imported; its API-token filter and an identity-resolution
 *       filter ({@link SecurityIdentityFilter}) are added to the server chain through {@link SecurityChainContributions}.</li>
 *   <li><b>Beans:</b> {@link SangamSecurity}, the product {@link Transactions} (independent JDBC transactions, also the
 *       denial-audit transaction so {@code AUTH_DENIED}/{@code SOD_VIOLATION_BLOCKED} survive a rollback),
 *       {@link SecurityProperties} bound to {@code sangam.security} (no field clash with the server's
 *       {@code SangamSecurityProperties}), {@link SangamSecurityEnginePlugin} (seat assignment, tenant id provider,
 *       Operaton authorization off), and {@link IdentityExtrasPort} for sangam-flowable-ports.</li>
 *   <li><b>REST ports replaced:</b> {@code PermissionPort}, {@code AdminPort}, {@code IdentityAdminPort} (bridges in
 *       {@code org.sangam.server.bridge}).</li>
 * </ul>
 * Disable with {@code sangam.modules.security.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.security.enabled", havingValue = "true", matchIfMissing = true)
public class SecurityWiring {

    @Bean
    @ConfigurationProperties("sangam.security")
    public SecurityProperties sangamSecurityModuleProperties() {
        return new SecurityProperties();
    }

    /** Product-layer transactions on the pool: own, short transactions (not the engine/command transaction). */
    @Bean
    public Transactions sangamProductTransactions(DataSource dataSource) {
        return Transactions.of(dataSource);
    }

    @Bean
    public SangamSecurity sangamSecurity(DataSource dataSource, Transactions sangamProductTransactions,
                                         RestProperties rest, Environment env) {
        SecurityDialect dialect;
        try (var c = dataSource.getConnection()) {
            dialect = SecurityDialect.detect(c);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("cannot detect the database dialect for sangam-security", e);
        }
        Duration staleness = env.getProperty("sangam.security.max-staleness", Duration.class, Duration.ofSeconds(5));
        SangamSecurity.Options options = new SangamSecurity.Options(staleness, null, rest.getBatchFourEyesThreshold(),
                sangamProductTransactions, null);
        return new SangamSecurity(dialect, options);
    }

    @Bean
    public SangamSecurityEnginePlugin sangamSecurityEnginePlugin(SangamSecurity security) {
        return new SangamSecurityEnginePlugin(security);
    }

    @Bean(destroyMethod = "close")
    public LoginEventBatcher sangamLoginEventBatcher(Transactions sangamProductTransactions, SangamSecurity security,
                                                     SecurityProperties props) {
        return new LoginEventBatcher(sangamProductTransactions, security.audit(), props.getLoginBatchMillis());
    }

    @Bean
    public SecurityChainContributions sangamSecurityChainContributions(Transactions sangamProductTransactions,
            SangamSecurity security, SecurityProperties props, LoginEventBatcher logins) {
        return new SecurityChainContributions(
                new TokenAuthenticationFilters.ApiTokenFilter(sangamProductTransactions, security.tokens()),
                new SecurityIdentityFilter(sangamProductTransactions, security, props, logins));
    }

    // ---------------------------------------------------------------- sangam-rest ports on sangam-security

    @Bean
    public PermissionPort sangamSecurityPermissionPort(SangamSecurity security, TxSupport tx) {
        return new SecurityPermissionBridge(security, tx, new GroupPermissionAdapter());
    }

    @Bean
    public AdminPort sangamSecurityAdminPort(SangamSecurity security, TxSupport tx, AuditPort audit,
                                             RestProperties props, org.sangam.rest.support.DocumentStore store) {
        // Parts the security bridge does not serve (person delegations) persist in REST_DOCUMENT.
        return new SecurityAdminBridge(security, tx, new JdbcAdminAdapter(store, tx, audit, props), props);
    }

    @Bean
    public IdentityAdminPort sangamSecurityIdentityAdminPort(SangamSecurity security, TxSupport tx, AdminPort admin,
            TaskPort tasks, PermissionPort permissions, AuditPort audit, RestProperties props, Environment env,
            org.sangam.rest.support.DocumentStore store) {
        IdentityAdminPort fallback = new JdbcIdentityAdminAdapter(store, admin, tasks, permissions, audit, props,
                env.getProperty("sangam.outbound.enabled", Boolean.class, false));
        return new SecurityIdentityAdminBridge(security, tx, fallback, props);
    }

    /** For sangam-flowable-ports' {@code IdmFacade} (FC-42). */
    @Bean
    public IdentityExtrasPort sangamIdentityExtrasPort(SangamSecurity security) {
        return new SecurityIdentityExtras(security.privileges(), security.tokens());
    }

    /** Dev only: tenant + administrator grants on an empty database (see {@link SecurityBootstrap}). */
    @Bean
    @ConditionalOnProperty(name = "sangam.security.bootstrap.enabled", havingValue = "true")
    public InitializingBean sangamSecurityBootstrap(SangamSecurity security, Transactions sangamProductTransactions,
                                                    SecurityProperties props, Environment env,
                                                    org.springframework.beans.factory.BeanFactory beans) {
        return () -> {
            for (String name : List.of("sangamLiquibase", "sangamLiquibaseTask", "sangamDevSchema")) {
                if (beans.containsBean(name)) {
                    beans.getBean(name); // the product schema must exist first (SchemaBootstrap)
                }
            }
            List<String> tenants = List.of(env.getProperty("sangam.security.bootstrap.tenants", String[].class,
                    new String[]{env.getProperty("sangam.security.dev-token.tenant", "dev")}));
            List<String> groups = List.of(env.getProperty("sangam.security.bootstrap.admin-groups", String[].class,
                    new String[]{"sangam-admin"}));
            SecurityBootstrap.run(security, sangamProductTransactions, tenants, groups, props.isProduction());
        };
    }
}
