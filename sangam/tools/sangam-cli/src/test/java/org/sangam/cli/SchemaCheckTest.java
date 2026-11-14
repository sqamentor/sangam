// SPDX-License-Identifier: Apache-2.0
package org.sangam.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** M-092 schema part: applied changesets of the server against the target release's bundled changelog. */
class SchemaCheckTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode status(boolean locked, String... ids) throws Exception {
        return JSON.readTree("{\"product\":{\"applied\":" + ids.length + ",\"ids\":" + JSON.writeValueAsString(List.of(ids))
                + "},\"engine\":{\"schemaVersion\":\"1.0.0\"},\"liquibaseLocked\":" + locked
                + (locked ? ",\"lockedBy\":\"job-7\",\"lockGrantedAt\":\"2026-11-12T09:00:00Z\"" : "") + "}");
    }

    @Test
    void changesetLinesAreReadPerDatabase() throws Exception {
        String sql = """
                --liquibase formatted sql
                --changeset sangam:0001-01-lease dbms:postgresql
                CREATE TABLE A (X INT);
                --changeset sangam:onb-run-oracle dbms:oracle
                CREATE TABLE B (X INT);
                --changeset sangam:everywhere
                CREATE TABLE C (X INT);
                --changeset sangam:not-mysql dbms:!mysql
                CREATE TABLE D (X INT);
                """;
        assertEquals(List.of("0001-01-lease", "everywhere", "not-mysql"), SchemaCheck.changesetIds(new StringReader(sql), "postgresql"));
        assertEquals(List.of("onb-run-oracle", "everywhere", "not-mysql"), SchemaCheck.changesetIds(new StringReader(sql), "oracle"));
        assertEquals(List.of("everywhere"), SchemaCheck.changesetIds(new StringReader(sql), "mysql"));
    }

    @Test
    void theBundledChangelogIsTheProductMasterOfThisBuild() throws Exception {
        List<String> pg = SchemaCheck.bundledIds("postgresql");
        List<String> h2 = SchemaCheck.bundledIds("h2");
        assertTrue(pg.contains("0001-01-sangam_lease"), "the baseline is bundled");
        assertTrue(pg.contains("onb-run-postgresql"));
        assertFalse(pg.contains("onb-run-h2"), "dialect-only changesets count for their database only");
        assertTrue(h2.contains("onb-run-h2"));
        assertEquals(pg.size(), h2.size(), "every dialect has the same number of product changesets");
        assertFalse(SchemaCheck.includes().stream().anyMatch(f -> f.contains("engine-patches") || f.contains("history-partitioning")),
                "engine patches and partitioning are not product changesets");
    }

    @Test
    void unknownAppliedChangesetsBlockAndPendingOnesInform() throws Exception {
        List<UpgradeCheck.Finding> f = new ArrayList<>();
        SchemaCheck.compare(List.of("a", "b", "c"), status(false, "a", "b", "z-from-a-newer-release"), f);
        assertEquals(List.of("BLOCKING", "INFO"), f.stream().map(UpgradeCheck.Finding::severity).toList());
        assertTrue(f.get(0).message().contains("z-from-a-newer-release"));
        assertTrue(f.get(0).message().contains("downgrades are not supported"));
        assertTrue(f.get(1).message().startsWith("1 changeset(s) will be applied"));
    }

    @Test
    void aHeldLiquibaseLockBlocks() throws Exception {
        List<UpgradeCheck.Finding> f = new ArrayList<>();
        SchemaCheck.compare(List.of("a"), status(true, "a"), f);
        assertEquals(1, f.size());
        assertEquals("BLOCKING", f.get(0).severity());
        assertTrue(f.get(0).message().contains("job-7"));
    }

    @Test
    void anUpToDateDatabaseHasNoSchemaFinding() throws Exception {
        List<UpgradeCheck.Finding> f = new ArrayList<>();
        SchemaCheck.compare(List.of("a", "b"), status(false, "a", "b"), f);
        assertTrue(f.isEmpty());
    }

    @Test
    void theReportCarriesTheSchemaSectionAndNoLongerListsItAsUnchecked() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        UpgradeCheck check = new UpgradeCheck(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()),
                Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC));
        List<String> all = SchemaCheck.bundledIds("postgresql");
        int exit = check.run(List.of(), List.of(), null, null, null, "postgresql", status(false, all.toArray(String[]::new)));
        assertEquals(Main.OK, exit);
        JsonNode r = JSON.readTree(out.toString(StandardCharsets.UTF_8));
        assertEquals("postgresql", r.path("schema").path("database").asText());
        assertEquals(all.size(), r.path("schema").path("targetChangesets").asInt());
        assertEquals(0, r.path("notChecked").size());
    }

    @Test
    void anUnknownDatabaseNameIsAUsageError() {
        assertThrows(IllegalArgumentException.class, () -> SchemaCheck.database("db2"));
        assertEquals("mssql", SchemaCheck.database(" MSSQL "));
    }
}
