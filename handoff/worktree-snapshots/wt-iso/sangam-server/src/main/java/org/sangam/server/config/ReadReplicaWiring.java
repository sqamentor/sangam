// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sangam.server.bridge.CellReadReplicaRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * IR-6 read-replica routing (sangam-cell README). {@code sangam.datasource.replica.url} (+ {@code username},
 * {@code password}, {@code maximum-pool-size}) configures a read-only replica pool; the GET routes of analytics and
 * dashboards ({@code /api/v1/analytics/**}), the audit timeline, search and exports ({@code /api/v1/audit/**}) and
 * history ({@code /api/v1/history/**}) carry the read-replica hint for their request thread. Task lists, every
 * command and read-then-write stay on the primary. See {@link CellReadReplicaRouter} for why the routing source is
 * offered to read-only query code instead of replacing the primary DataSource.
 */
@Configuration(proxyBeanMethods = false)
public class ReadReplicaWiring {

    private static final Logger LOG = LoggerFactory.getLogger(ReadReplicaWiring.class);
    static final String[] REPLICA_ROUTES = {"/api/v1/analytics/**", "/api/v1/audit/**", "/api/v1/history/**"};

    @Bean(destroyMethod = "close")
    public CellReadReplicaRouter sangamReadReplicaRouter(DataSource dataSource, Environment env) {
        String url = env.getProperty("sangam.datasource.replica.url", "");
        if (url.isBlank()) {
            return new CellReadReplicaRouter(dataSource, null);
        }
        HikariDataSource replica = new HikariDataSource();
        replica.setPoolName("sangam-replica");
        replica.setJdbcUrl(url);
        replica.setUsername(env.getProperty("sangam.datasource.replica.username"));
        replica.setPassword(env.getProperty("sangam.datasource.replica.password"));
        replica.setMaximumPoolSize(env.getProperty("sangam.datasource.replica.maximum-pool-size", Integer.class, 10));
        replica.setReadOnly(true);
        replica.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        if (url.startsWith("jdbc:mariadb:") || url.startsWith("jdbc:mysql:")) {
            replica.setConnectionInitSql("SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED");
        }
        LOG.info("Read replica configured for analytics, audit and history GET routes");
        return new CellReadReplicaRouter(dataSource, replica);
    }

    @Bean
    public WebMvcConfigurer sangamReadReplicaInterceptor(CellReadReplicaRouter router) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                if (!router.replicaConfigured()) {
                    return;
                }
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        CellReadReplicaRouter.hint("GET".equals(request.getMethod()));
                        return true;
                    }

                    @Override
                    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                                Exception ex) {
                        CellReadReplicaRouter.hint(false);
                    }
                }).addPathPatterns(REPLICA_ROUTES);
            }
        };
    }
}
