// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.integration.spring.SpringLiquibase;
import liquibase.resource.ClassLoaderResourceAccessor;

import org.sangam.core.api.LeaseTable;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Schema;
import org.sangam.engine.db.DatabaseGuard;
import org.sangam.core.licence.LiquibaseLicenceGuard;
import org.sangam.rest.support.TxSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Product schema at startup, driven by {@code SANGAM_LIQUIBASE_MODE} ({@link LiquibaseMode}):
 * <ul>
 *   <li>when the sangam-persistence master changelog is on the class path ({@code sangam.persistence.changelog},
 *       default {@code classpath:db/changelog/sangam-master.yaml}), Liquibase 4.33.0 (ADR-D-7) runs it in the selected
 *       mode: {@code startup}/{@code migrate-only} update, {@code validate} fails on pending changesets,
 *       {@code update-sql} writes SQL, {@code release-locks} releases the lock;</li>
 *   <li>otherwise, only when {@code sangam.persistence.dev-schema=true} (h2 profile), the core product tables
 *       ({@link Schema}) and the lease table ({@link LeaseTable}) are created if missing. Never used in production.</li>
 * </ul>
 * One-shot modes exit with status 0 once the context is ready; the Operaton schema is created/updated by the engine
 * itself during a {@code migrate-only} run ({@code operaton.bpm.database.schema-update=true} for that run only).
 */
@Configuration(proxyBeanMethods = false)
public class SchemaBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaBootstrap.class);
    static final String CHANGELOG_PROPERTY = "sangam.persistence.changelog";
    static final String DEFAULT_CHANGELOG = "classpath:db/changelog/sangam-master.yaml";

    static LiquibaseMode mode(Environment env) {
        return LiquibaseMode.parse(env.getProperty(LiquibaseMode.PROPERTY));
    }

    /** {@code startup} and {@code migrate-only}: apply pending changesets. */
    @Bean
    @Conditional(ChangelogPresent.class)
    public SpringLiquibase sangamLiquibase(DataSource dataSource, Environment env) {
        LiquibaseMode mode = mode(env);
        String changelog = env.getProperty(CHANGELOG_PROPERTY, DEFAULT_CHANGELOG);
        // H2 (development) only: the BLOB alias Operaton's H2 scripts need must exist before Liquibase's own tables,
        // because H2 2.x rejects CREATE DOMAIN BLOB once any large-object column exists.
        LiquibaseLicenceGuard.check(); // SG-26 / LIC-7
        if (isH2(dataSource)) {
            DatabaseGuard.prepareH2(dataSource);
        }
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(changelog);
        liquibase.setShouldRun(mode == LiquibaseMode.STARTUP || mode == LiquibaseMode.MIGRATE_ONLY);
        String contexts = env.getProperty("sangam.persistence.contexts");
        if (contexts != null) {
            liquibase.setContexts(contexts);
        }
        LOG.info("Product schema: Liquibase changelog {} (mode {})", changelog, mode);
        return liquibase;
    }

    /** {@code validate}, {@code update-sql}, {@code release-locks}: Liquibase API tasks without applying changes. */
    @Bean
    @Conditional(ChangelogPresent.class)
    public InitializingBean sangamLiquibaseTask(DataSource dataSource, Environment env) {
        return () -> {
            LiquibaseLicenceGuard.check(); // SG-26 / LIC-7
            LiquibaseMode mode = mode(env);
            if (mode == LiquibaseMode.STARTUP || mode == LiquibaseMode.MIGRATE_ONLY) {
                return;
            }
            String changelog = env.getProperty(CHANGELOG_PROPERTY, DEFAULT_CHANGELOG);
            String path = changelog.startsWith("classpath:") ? changelog.substring("classpath:".length()) : changelog;
            Contexts contexts = new Contexts(env.getProperty("sangam.persistence.contexts", ""));
            try (Connection c = dataSource.getConnection()) {
                Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
                Liquibase lb = new Liquibase(path, new ClassLoaderResourceAccessor(SchemaBootstrap.class.getClassLoader()), db);
                switch (mode) {
                    case VALIDATE -> {
                        List<ChangeSet> pending = lb.listUnrunChangeSets(contexts, new LabelExpression());
                        if (!pending.isEmpty()) {
                            throw new IllegalStateException("SANGAM_LIQUIBASE_MODE=validate: " + pending.size()
                                    + " pending changeset(s), first " + pending.get(0).toString(false)
                                    + "; run the migrate-only job first");
                        }
                        LOG.info("Product schema validated: no pending changesets in {}", changelog);
                    }
                    case UPDATE_SQL -> writeUpdateSql(lb, contexts, env.getProperty("SANGAM_LIQUIBASE_SQL_FILE",
                            env.getProperty("sangam.liquibase.sql-file")));
                    case RELEASE_LOCKS -> {
                        lb.forceReleaseLocks();
                        LOG.warn("Liquibase change-log lock force-released on request (SANGAM_LIQUIBASE_MODE=release-locks)");
                    }
                    default -> {
                    }
                }
            }
        };
    }

    private static boolean isH2(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            return "H2".equalsIgnoreCase(c.getMetaData().getDatabaseProductName());
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the database product name", e);
        }
    }

    private static void writeUpdateSql(Liquibase lb, Contexts contexts, String file) throws Exception {
        if (file == null || file.isBlank()) {
            PrintStream out = System.out;
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            lb.update(contexts, new LabelExpression(), w);
            w.flush();
            return;
        }
        try (OutputStream os = new FileOutputStream(file);
             Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            lb.update(contexts, new LabelExpression(), w);
        }
        LOG.info("Pending product-schema SQL written to {}", file);
    }

    @Bean
    @Conditional(ChangelogAbsent.class)
    @ConditionalOnProperty(name = "sangam.persistence.dev-schema", havingValue = "true")
    public InitializingBean sangamDevSchema(DataSource dataSource) {
        return () -> createDevSchema(dataSource);
    }

    /** Exits 0 once the context is ready in a one-shot mode (the Operaton job executor is disabled for those runs). */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> sangamLiquibaseModeExit(Environment env) {
        return event -> {
            LiquibaseMode mode = mode(env);
            if (!mode.oneShot()) {
                return;
            }
            LOG.info("SANGAM_LIQUIBASE_MODE={} finished; exiting", mode);
            int code = SpringApplication.exit(event.getApplicationContext(), () -> 0);
            System.exit(code);
        };
    }

    static void createDevSchema(DataSource dataSource) throws SQLException {
        Dialect dialect = TxSupport.detectDialect(dataSource);
        try (Connection c = dataSource.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                if (!tableExists(c, "AUDIT_EVENT")) {
                    Schema.create(c, dialect);
                    LOG.warn("DEVELOPMENT product schema created from org.sangam.core.layer.Schema ({}); "
                            + "production uses the sangam-persistence Liquibase changelog", dialect);
                }
                LeaseTable.createIfMissing(c);
                c.commit();
            } catch (RuntimeException | SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        for (String name : new String[]{table, table.toLowerCase(java.util.Locale.ROOT)}) {
            try (ResultSet rs = c.getMetaData().getTables(null, null, name, new String[]{"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when the configured changelog resource exists. */
    static final class ChangelogPresent implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String location = context.getEnvironment().getProperty(CHANGELOG_PROPERTY, DEFAULT_CHANGELOG);
            return context.getResourceLoader().getResource(location).exists();
        }
    }

    static final class ChangelogAbsent implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !new ChangelogPresent().matches(context, metadata);
        }
    }
}
