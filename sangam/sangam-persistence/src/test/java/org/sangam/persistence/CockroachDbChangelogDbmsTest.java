// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import liquibase.ChecksumVersion;
import liquibase.Scope;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.changelog.filter.DbmsChangeSetFilter;
import liquibase.database.Database;
import liquibase.database.core.CockroachDatabase;
import liquibase.database.core.PostgresDatabase;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.DirectoryResourceAccessor;
import liquibase.resource.ResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Owner decision 379 (2), decision 205: in the experimental CockroachDB mode the product changelog must apply.
 * Liquibase 4.33.0 picks its own {@code CockroachDatabase} for a CockroachDB server (priority of
 * {@code PostgresDatabase} + 5, accepted when {@code select version()} starts with {@code CockroachDB}), whose short
 * name is {@code cockroachdb}; {@code DatabaseList.definitionMatches} compares {@code dbms:} values with the short name
 * only, so a changeset tagged {@code dbms:postgresql} alone is skipped there and the product schema stays empty. Every
 * PostgreSQL product changeset therefore also names {@code cockroachdb}, except the optional pgvector changeset (its
 * precondition reads {@code pg_extension}).
 */
class CockroachDbChangelogDbmsTest {

    /** PostgreSQL-only optional changesets (generate_changelogs.py PG_EXTENSION_OPTIONAL). */
    private static final Set<String> POSTGRESQL_ONLY = Set.of("0044-08-vector-optional");

    private static DatabaseChangeLog parse(String path, ResourceAccessor ra) throws Exception {
        DatabaseChangeLog[] out = new DatabaseChangeLog[1];
        Scope.child(Scope.Attr.resourceAccessor.name(), ra, () -> {
            out[0] = ChangeLogParserFactory.getInstance().getParser(path, ra).parse(path, new ChangeLogParameters(), ra);
        });
        return out[0];
    }

    private static Set<String> accepted(DatabaseChangeLog log, Database db) {
        DbmsChangeSetFilter filter = new DbmsChangeSetFilter(db);
        Set<String> ids = new TreeSet<>();
        for (ChangeSet cs : log.getChangeSets()) {
            if (filter.accepts(cs).isAccepted()) {
                ids.add(cs.getId());
            }
        }
        return ids;
    }

    @Test
    void liquibaseNamesTheCockroachDbServerCockroachdbNotPostgresql() {
        assertEquals("cockroachdb", new CockroachDatabase().getShortName());
        assertEquals("postgresql", new PostgresDatabase().getShortName());
        assertTrue(new CockroachDatabase().getPriority() > new PostgresDatabase().getPriority(),
                "Liquibase prefers CockroachDatabase on a server both accept");
    }

    @Test
    void everyPostgresqlProductChangesetAlsoRunsOnCockroachDb() throws Exception {
        DatabaseChangeLog master = parse(SangamSchema.MASTER_CHANGELOG,
                new ClassLoaderResourceAccessor(SangamSchema.class.getClassLoader()));
        Set<String> postgresql = accepted(master, new PostgresDatabase());
        Set<String> cockroach = accepted(master, new CockroachDatabase());
        assertTrue(postgresql.size() > SangamSchema.PRODUCT_TABLES.size(), "the PostgreSQL changesets were read");
        Set<String> expected = new TreeSet<>(postgresql);
        expected.removeAll(POSTGRESQL_ONLY);
        assertEquals(expected, cockroach, "decision 379: the product changelog must apply on CockroachDB");
        assertFalse(cockroach.stream().anyMatch(POSTGRESQL_ONLY::contains), "pgvector stays PostgreSQL only");
    }

    /**
     * Adding a dbms value leaves the changeset checksums unchanged (Liquibase computes them from the changes, not
     * from the dbms attribute), so a PostgreSQL database already migrated with {@code dbms:postgresql} validates.
     */
    @Test
    void addingCockroachdbToTheDbmsAttributeKeepsEveryChecksum(@TempDir Path tmp) throws Exception {
        for (String file : new String[] {"postgresql/sangam-0001-baseline.sql", "sangam-0002-onboarding.sql"}) {
            String shipped;
            try (InputStream in = SangamSchema.class.getClassLoader()
                    .getResourceAsStream("db/changelog/" + file)) {
                shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String before = shipped.replace("dbms:postgresql,cockroachdb", "dbms:postgresql");
            String after = before.replace("dbms:postgresql", "dbms:postgresql,cockroachdb");
            Path a = Files.createDirectories(tmp.resolve("before"));
            Path b = Files.createDirectories(tmp.resolve("after"));
            Files.writeString(a.resolve("c.sql"), before);
            Files.writeString(b.resolve("c.sql"), after);
            Map<String, String> sumsBefore = checksums(parse("c.sql", new DirectoryResourceAccessor(a)));
            Map<String, String> sumsAfter = checksums(parse("c.sql", new DirectoryResourceAccessor(b)));
            assertFalse(sumsBefore.isEmpty(), file);
            assertEquals(sumsBefore, sumsAfter, file);
        }
    }

    private static Map<String, String> checksums(DatabaseChangeLog log) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ChangeSet cs : log.getChangeSets()) {
            out.put(cs.getId(), cs.generateCheckSum(ChecksumVersion.latest()).toString());
        }
        return out;
    }
}
