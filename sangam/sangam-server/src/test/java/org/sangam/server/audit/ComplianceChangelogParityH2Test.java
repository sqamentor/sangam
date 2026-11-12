// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.compliance.ComplianceSchema;
import org.sangam.core.layer.Dialect;
import org.sangam.persistence.SangamSchema;

/**
 * SL-162 regression: the compliance tables the product changelog creates (sangam-persistence, every changeset applied)
 * equal the reference DDL sangam-compliance's code is written against ({@link ComplianceSchema}), column for column:
 * name, type, length and nullability, plus the primary key. Before the fix the changelog lacked CMP_BREACH risk_outcome,
 * risk_json, high_risk, delay_until, delay_ref; CMP_PURPOSE special_condition; CMP_RIGHTS_REQUEST extended,
 * extension_reason, access_exception, so the statements using them failed on every real database. Lives in sangam-server,
 * the module that has both.
 */
class ComplianceChangelogParityH2Test {

    record Column(String type, Long length, String nullable) {
    }

    static Map<String, Map<String, Column>> columns(Connection c, List<String> tables) throws Exception {
        Map<String, Map<String, Column>> out = new TreeMap<>();
        for (String t : tables) {
            Map<String, Column> cols = new TreeMap<>();
            try (ResultSet rs = c.getMetaData().getColumns(null, null, t, null)) {
                while (rs.next()) {
                    cols.put(rs.getString("COLUMN_NAME").toLowerCase(java.util.Locale.ROOT),
                            new Column(rs.getString("TYPE_NAME"), rs.getLong("COLUMN_SIZE"), rs.getString("IS_NULLABLE")));
                }
            }
            List<String> pk = new ArrayList<>();
            try (ResultSet rs = c.getMetaData().getPrimaryKeys(null, null, t)) {
                while (rs.next()) {
                    pk.add(rs.getShort("KEY_SEQ") + ":" + rs.getString("COLUMN_NAME").toLowerCase(java.util.Locale.ROOT));
                }
            }
            pk.sort(null);
            cols.put("<primary key>", new Column(String.join(",", pk), null, null));
            out.put(t, cols);
        }
        return out;
    }

    @Test
    void everyComplianceTableOfTheChangelogMatchesComplianceSchema() throws Exception {
        List<String> tables = ComplianceSchema.tables();
        assertTrue(tables.contains("CMP_BREACH") && tables.size() >= 28, "reference tables: " + tables);
        Map<String, Map<String, Column>> changelog;
        Map<String, Map<String, Column>> reference;
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "")) {
            SangamSchema.apply(c, SangamSchema.MASTER_CHANGELOG, null);
            changelog = columns(c, tables);
        }
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "")) {
            c.setAutoCommit(true);
            ComplianceSchema.create(c, Dialect.POSTGRESQL);
            reference = columns(c, tables);
        }
        for (String t : tables) {
            assertEquals(reference.get(t), changelog.get(t), t + ": changelog vs ComplianceSchema");
        }
        assertTrue(changelog.get("CMP_BREACH").containsKey("plan_process_instance_id"), "the second-start guard column");
    }
}
