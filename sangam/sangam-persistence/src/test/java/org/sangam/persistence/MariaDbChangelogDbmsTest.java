// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Proxy;
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
import liquibase.database.DatabaseConnection;
import liquibase.database.DatabaseFactory;
import liquibase.database.core.MariaDBDatabase;
import liquibase.database.core.MySQLDatabase;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.DirectoryResourceAccessor;
import liquibase.resource.ResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SL-112 (MariaDB, Tier 2), the case decision 379 found for CockroachDB: the shipped MariaDB Connector/J 3.5.11
 * reports the product {@code MariaDB} for a MariaDB server ({@code DatabaseMetaData.getDatabaseProductName}, unless
 * {@code useMysqlMetadata}, which no Sangam URL sets). Liquibase 4.33.0 then takes its own {@code MariaDBDatabase}
 * (short name {@code mariadb}); {@code MySQLDatabase} accepts only the product {@code MySQL} without "mariadb" in the
 * version, and {@code dbms:} is matched by short name only. A changeset tagged {@code dbms:mysql} alone is therefore
 * skipped on MariaDB and the product schema would stay empty. Every MySQL product changeset and both engine patches
 * also name {@code mariadb}; the dbms value is not part of the checksum.
 */
class MariaDbChangelogDbmsTest {

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

    /** A Liquibase connection answering what MariaDB Connector/J answers for a MariaDB 11.4 server. */
    private static DatabaseConnection mariaDbServer() {
        return (DatabaseConnection) Proxy.newProxyInstance(MariaDbChangelogDbmsTest.class.getClassLoader(),
                new Class<?>[] {DatabaseConnection.class}, (p, m, a) -> switch (m.getName()) {
                    case "getDatabaseProductName" -> "MariaDB";
                    case "getDatabaseProductVersion" -> "11.4.5-MariaDB-ubu2404";
                    case "getDatabaseMajorVersion" -> 11;
                    case "getDatabaseMinorVersion" -> 4;
                    case "getURL" -> "jdbc:mariadb://db.example:3306/sangam";
                    case "getConnectionUserName" -> "sangam";
                    default -> throw new UnsupportedOperationException("DatabaseConnection." + m.getName());
                });
    }

    @Test
    void liquibaseTakesAMariaDbServerForMariadbNotMysql() throws Exception {
        DatabaseConnection conn = mariaDbServer();
        assertTrue(new MariaDBDatabase().isCorrectDatabaseImplementation(conn));
        assertFalse(new MySQLDatabase().isCorrectDatabaseImplementation(conn));
        Set<String> claimants = new TreeSet<>();
        for (Database d : DatabaseFactory.getInstance().getImplementedDatabases()) {
            if (d.isCorrectDatabaseImplementation(conn)) {
                claimants.add(d.getShortName());
            }
        }
        assertEquals(Set.of("mariadb"), claimants);
    }

    @Test
    void everyMysqlChangesetAlsoRunsOnMariaDb() throws Exception {
        ResourceAccessor ra = new ClassLoaderResourceAccessor(SangamSchema.class.getClassLoader());
        for (String changelog : new String[] {SangamSchema.MASTER_CHANGELOG, SangamSchema.ENGINE_PATCHES_CHANGELOG}) {
            DatabaseChangeLog log = parse(changelog, ra);
            Set<String> mysql = accepted(log, new MySQLDatabase());
            Set<String> maria = accepted(log, new MariaDBDatabase());
            assertFalse(mysql.isEmpty(), changelog);
            assertEquals(mysql, maria, changelog + ": the MySQL changesets must also run on MariaDB (SL-112)");
        }
    }

    /** Adding {@code mariadb} to the dbms value keeps every checksum, formatted SQL and XML alike. */
    @Test
    void addingMariadbToTheDbmsAttributeKeepsEveryChecksum(@TempDir Path tmp) throws Exception {
        String[][] files = {
                {"mysql/sangam-0001-baseline.sql", "dbms:mysql,mariadb", "dbms:mysql"},
                {"sangam-0002-onboarding.sql", "dbms:mysql,mariadb", "dbms:mysql"},
                {"sangam-engine-patches.xml", "dbms=\"mysql,mariadb\"", "dbms=\"mysql\""}};
        for (String[] f : files) {
            String shipped;
            try (InputStream in = SangamSchema.class.getClassLoader().getResourceAsStream("db/changelog/" + f[0])) {
                shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String before = shipped.replace(f[1], f[2]);
            String after = before.replace(f[2], f[1]);
            String name = "c" + f[0].substring(f[0].lastIndexOf('.'));
            Path a = Files.createDirectories(tmp.resolve(f[0].replace('/', '_') + "-before"));
            Path b = Files.createDirectories(tmp.resolve(f[0].replace('/', '_') + "-after"));
            Files.writeString(a.resolve(name), before);
            Files.writeString(b.resolve(name), after);
            assertFalse(before.equals(after), f[0] + ": the two forms differ");
            Map<String, String> sumsBefore = checksums(parse(name, new DirectoryResourceAccessor(a)));
            Map<String, String> sumsAfter = checksums(parse(name, new DirectoryResourceAccessor(b)));
            assertFalse(sumsBefore.isEmpty(), f[0]);
            assertEquals(sumsBefore, sumsAfter, f[0]);
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
