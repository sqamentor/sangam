// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.sangam.server.acceptance.AbstractAcceptanceIT;
import org.sangam.server.acceptance.AcceptanceDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * On the lane's real database, after the product changelog was applied (the same Liquibase update a migrate-only run
 * does), {@code SANGAM_LIQUIBASE_MODE=validate} accepts the schema. On stock PostgreSQL (no pgvector) the optional
 * changeset 0044-08-vector-optional (decision 170) stays unrun; validate must not refuse it.
 */
class ValidateAfterMigrateIT extends AbstractAcceptanceIT {

    @Autowired
    DataSource dataSource;

    @Autowired
    Environment env;

    @Test
    void validateAcceptsTheMigratedProductSchema() throws Exception {
        String changelog = env.getProperty(SchemaBootstrap.CHANGELOG_PROPERTY, SchemaBootstrap.DEFAULT_CHANGELOG);
        String path = changelog.startsWith("classpath:") ? changelog.substring("classpath:".length()) : changelog;
        Contexts contexts = new Contexts(env.getProperty("sangam.persistence.contexts", ""));
        try (Connection c = dataSource.getConnection()) {
            Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            Liquibase lb = new Liquibase(path, new ClassLoaderResourceAccessor(getClass().getClassLoader()), db);
            List<String> unrun = lb.listUnrunChangeSets(contexts, new LabelExpression()).stream()
                    .map(ChangeSet::getId).toList();
            if ("postgres".equals(AcceptanceDatabase.name()) && !pgvector(c)) {
                assertThat(unrun).as("stock PostgreSQL leaves the optional pgvector changeset unrun")
                        .containsExactly("0044-08-vector-optional");
            }
            assertThatCode(() -> SchemaBootstrap.validate(lb, db, contexts, changelog)).doesNotThrowAnyException();
            assertThat(SchemaBootstrap.pendingChangeSets(lb, db, contexts)).isEmpty();
        }
    }

    private static boolean pgvector(Connection c) throws Exception {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }
}
