// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The reference DDL of {@code OPERATION_LEDGER} ({@link Schema}, used by tests and tools that create the core tables
 * without Liquibase) has the columns of the product changelog, which is the truth (sangam-persistence, changeset
 * 0002-01). It lacked {@code engine_work_id}, {@code business_key} and {@code topic}, so
 * {@link OperationLedger#findByEngineWork} and {@link OperationLedger#bindEngineWork} failed on a database created
 * from it ("Column engine_work_id not found"). Run from sangam-core, so the reactor root is the parent folder.
 */
class OperationLedgerReferenceSchemaH2Test {

    private static final Path CHANGELOG = Path.of("..", "sangam-persistence", "src", "main", "resources", "db",
            "changelog", "postgresql", "sangam-0001-baseline.sql");
    private static final Pattern TABLE = Pattern.compile("CREATE TABLE OPERATION_LEDGER \\((.*?)\\n\\)", Pattern.DOTALL);

    /** Column names of the first OPERATION_LEDGER table in {@code sql}, in order (constraint lines are not columns). */
    static List<String> columns(String sql) {
        Matcher m = TABLE.matcher(sql.replace("\r\n", "\n"));
        assertTrue(m.find(), "CREATE TABLE OPERATION_LEDGER");
        List<String> out = new ArrayList<>();
        for (String line : m.group(1).split("\n")) {
            String t = line.strip();
            if (!t.isEmpty() && !t.toUpperCase(Locale.ROOT).startsWith("CONSTRAINT")
                    && !t.toUpperCase(Locale.ROOT).startsWith("PRIMARY KEY")) {
                out.add(t.split("\\s+")[0].toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    @Test
    void theReferenceTableHasTheChangelogsColumns() throws IOException {
        String reference = String.join(";\n", Schema.ddl(Dialect.POSTGRESQL));
        List<String> changelog = columns(Files.readString(CHANGELOG, StandardCharsets.UTF_8));
        assertTrue(changelog.containsAll(List.of("engine_work_id", "business_key", "topic")), changelog.toString());
        assertEquals(changelog, columns(reference), "Schema.java OPERATION_LEDGER follows the product changelog");
    }

    @Test
    void theLedgerReadsAndBindsEngineWorkOnTheReferenceSchema() throws Exception {
        String url = "jdbc:h2:mem:ledgerref" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            Schema.create(c, Dialect.POSTGRESQL);
            OperationLedger ledger = new OperationLedger(Dialect.POSTGRESQL, new ReconciliationQueue(Dialect.POSTGRESQL, null));
            OperationLedger.OperationRef ref = new OperationLedger.OperationRef("t1", "root-1", "act-1", 0);
            assertEquals(Optional.empty(), ledger.findByEngineWork(c, "work-1"));
            OperationLedger.Intent intent = ledger.recordIntent(c, ref, null, new byte[32], "attempt-1", 1L);
            UUID op = intent.operationId();
            ledger.bindEngineWork(c, op, "work-1");
            assertEquals(op, ledger.findByEngineWork(c, "work-1").orElseThrow().operationId());
        }
    }
}
