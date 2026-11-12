// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * M-056 / M-062: the job side tables in the changelogs match, column for column, the DDL of
 * sangam-handoff/W2-JOB-SIDE-TABLES-CONTRACT.md (W2-D builds its H2 tests from that DDL). recoveries carries the
 * optional DB default 0 the contract welcomes. No foreign key on any dialect (Operaton's bulk deletes must not fail).
 */
class JobSideTablesContractTest {

    static String table(String dbms, String name) throws IOException {
        String sql = read("db/changelog/" + dbms + "/sangam-0001-baseline.sql");
        Matcher m = Pattern.compile("CREATE TABLE " + name + " \\((.*?)\\n\\)[^;]*;", Pattern.DOTALL).matcher(sql);
        assertTrue(m.find(), name + " in the " + dbms + " changelog");
        return m.group(1);
    }

    static List<String> lines(String body) {
        return body.lines().map(String::trim).map(l -> l.endsWith(",") ? l.substring(0, l.length() - 1) : l)
                .filter(l -> !l.isEmpty()).toList();
    }

    @Test
    void h2MatchesTheContractDdl() throws IOException {
        assertEquals(List.of(
                "job_id VARCHAR(64) NOT NULL", "tenant_id VARCHAR(64) NOT NULL", "root_id VARCHAR(64) NOT NULL",
                "slot INTEGER NOT NULL", "owner_partition INTEGER", "owner_epoch BIGINT",
                "recoveries INTEGER DEFAULT 0 NOT NULL", "created_at_ms BIGINT NOT NULL",
                "CONSTRAINT PK_EXEC_JOB_SLOT PRIMARY KEY (job_id)"), lines(table("h2", "EXEC_JOB_SLOT")));
        assertEquals(List.of(
                "job_id VARCHAR(64) NOT NULL", "kind VARCHAR(16) NOT NULL", "traceparent VARCHAR(55) NOT NULL",
                "tracestate VARCHAR(512)", "created_at_ms BIGINT NOT NULL",
                "CONSTRAINT PK_JOB_TRACE_CONTEXT PRIMARY KEY (job_id)"), lines(table("h2", "JOB_TRACE_CONTEXT")));
        String h2 = read("db/changelog/h2/sangam-0001-baseline.sql");
        assertTrue(h2.contains("CREATE INDEX EXEC_JOB_SLOT_SLOT ON EXEC_JOB_SLOT (slot);"));
        assertTrue(h2.contains("CREATE INDEX EXEC_JOB_SLOT_OWNER ON EXEC_JOB_SLOT (owner_partition, owner_epoch);"));
    }

    @Test
    void everyDialectHasBothTablesWithoutForeignKeys() throws IOException {
        for (String dbms : List.of("postgresql", "oracle", "mssql", "mysql", "h2")) {
            for (String t : List.of("EXEC_JOB_SLOT", "JOB_TRACE_CONTEXT")) {
                String body = table(dbms, t);
                assertFalse(body.contains("REFERENCES") || body.contains("FOREIGN KEY"), dbms + " " + t);
                assertEquals(t.equals("EXEC_JOB_SLOT") ? 9 : 6, lines(body).size(), dbms + " " + t + " columns + key");
            }
        }
        assertTrue(SangamSchema.PRODUCT_TABLES.containsAll(List.of("EXEC_JOB_SLOT", "JOB_TRACE_CONTEXT")));
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = JobSideTablesContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
