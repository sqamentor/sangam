// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

/**
 * {@code SANGAM_LIQUIBASE_MODE=validate} after {@code migrate-only}: a changeset whose precondition is not met and that
 * says {@code onFail:CONTINUE} (0044-08-vector-optional, decision 170, on PostgreSQL without pgvector) is skipped by
 * Liquibase and not recorded, so it is never applied by the migrate run that validate stands for. Validate must not
 * count it as pending, yet must still refuse every other unrun changeset, and must count the optional changeset once its
 * precondition is met (pgvector installed later), so the next migrate-only adds it.
 */
class ValidateOptionalChangesetTest {

    private static final String BASE = "db/changelog/validate-optional/base.sql";
    private static final String NEXT = "db/changelog/validate-optional/next.yaml";

    private static Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:vo-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static Liquibase liquibase(String changelog, Connection c) throws Exception {
        Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
        return new Liquibase(changelog, new ClassLoaderResourceAccessor(ValidateOptionalChangesetTest.class.getClassLoader()), db);
    }

    private static void migrateOnly(String changelog, Connection c) throws Exception {
        liquibase(changelog, c).update(new Contexts(), new LabelExpression());
    }

    private static void validate(String changelog, Connection c) throws Exception {
        Liquibase lb = liquibase(changelog, c);
        SchemaBootstrap.validate(lb, lb.getDatabase(), new Contexts(), changelog);
    }

    private static List<String> pending(String changelog, Connection c) throws Exception {
        Liquibase lb = liquibase(changelog, c);
        return SchemaBootstrap.pendingChangeSets(lb, lb.getDatabase(), new Contexts()).stream()
                .map(ChangeSet::getId).toList();
    }

    @Test
    void migrateOnlyThenValidateAcceptsAnOptionalChangesetWhosePreconditionIsNotMet() throws Exception {
        try (Connection c = open()) {
            migrateOnly(BASE, c);
            // Liquibase itself leaves the skipped optional changeset unrun (exactly the 0044-08 state on stock Postgres).
            assertEquals(List.of("vo-02-optional"), liquibase(BASE, c)
                    .listUnrunChangeSets(new Contexts(), new LabelExpression()).stream().map(ChangeSet::getId).toList());
            validate(BASE, c); // was refused: "1 pending changeset(s), first ...vo-02-optional"
            assertEquals(List.of(), pending(BASE, c));
        }
    }

    @Test
    void realPendingChangesetsAreStillRefused() throws Exception {
        try (Connection c = open()) {
            migrateOnly(BASE, c);
            assertEquals(List.of("vo-03-real", "vo-04-guarded-halt"), pending(NEXT, c));
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> validate(NEXT, c));
            assertTrue(e.getMessage().contains("2 pending changeset(s)"), e.getMessage());
            assertTrue(e.getMessage().contains("vo-03-real"), e.getMessage());
        }
    }

    @Test
    void anOptionalChangesetBecomesPendingOnceItsPreconditionIsMet() throws Exception {
        try (Connection c = open()) {
            migrateOnly(BASE, c);
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO VO_BASE (id) VALUES (42)"); // the prerequisite now exists (pgvector installed)
                if (!c.getAutoCommit()) {
                    c.commit();
                }
            }
            assertEquals(List.of("vo-02-optional"), pending(BASE, c));
            assertThrows(IllegalStateException.class, () -> validate(BASE, c));
            migrateOnly(BASE, c); // the next migrate-only adds it
            validate(BASE, c);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT extra FROM VO_BASE");
            }
        }
    }
}
