// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.store;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sangam.config.master.MasterCode;
import org.sangam.core.layer.AbsentKey;
import org.sangam.core.layer.Dialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression SL-84: Oracle stores the empty string as NULL, so a root master code written with {@code parent_code = ''}
 * failed with ORA-01400 on the NOT NULL primary-key column CFG_MASTER_CODE.parent_code, and the server context did not
 * start. H2's Oracle mode has the same empty-string rule. The same assertions run in the other dialects' H2 modes so
 * the root representation ({@link AbsentKey#NONE}) behaves identically everywhere.
 */
class MasterCodeRootKeyH2Test {

    @ParameterizedTest(name = "{0}")
    // DDL type tokens: Oracle's own; PostgreSQL's for the other modes (H2 has no DATETIMEOFFSET or MySQL table suffix)
    @CsvSource({"Oracle,ORACLE", "PostgreSQL,POSTGRESQL", "MSSQLServer,POSTGRESQL", "MySQL,POSTGRESQL", "Regular,POSTGRESQL"})
    void rootCodesAreStoredFoundReplacedAndListed(String mode, Dialect dialect) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sl84-" + mode + ";MODE=" + mode + ";DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            ConfigSchema.create(c, dialect);
        }
        JdbcConfigStore store = new JdbcConfigStore(ds, dialect);
        MasterCode root = new MasterCode("IN", "HOA", "MAJOR", "2054", null, Map.of("en", "Treasury"), Map.of(), null, null, "seed");
        MasterCode child = new MasterCode("IN", "HOA", "MINOR", "095", "2054-00", Map.of("en", "Treasuries"), Map.of(),
                LocalDate.of(2020, 4, 1), null, "seed");

        store.put(root);   // ORA-01400 before the fix
        store.put(root);   // replace: the DELETE must match the stored root row (before the fix it matched nothing)
        store.put(child);

        List<MasterCode> found = store.find("IN", "HOA", "MAJOR", null, "2054");
        assertEquals(1, found.size(), mode + ": exactly one root row after a replace");
        assertNull(found.get(0).parentCode(), mode + ": a root reads back with no parent");
        assertEquals(found, store.find("IN", "HOA", "MAJOR", "", "2054"), mode + ": an empty parent is the root too");
        assertEquals(1, store.list("IN", "HOA", "MAJOR").size());
        assertEquals("2054-00", store.find("IN", "HOA", "MINOR", "2054-00", "095").get(0).parentCode());

        // children(prefix): unchanged semantics (an empty prefix lists every code, a non-empty one never a root)
        assertEquals(1, store.children("IN", "HOA", "MAJOR", "").size(), mode + ": empty prefix includes roots");
        assertEquals(List.of(), store.children("IN", "HOA", "MAJOR", "-"), mode + ": the root key never matches a prefix");
        assertEquals(1, store.children("IN", "HOA", "MINOR", "2054").size());

        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT parent_code FROM CFG_MASTER_CODE WHERE segment_id = 'MAJOR'")) {
            rs.next();
            assertEquals(AbsentKey.NONE, rs.getString(1), mode + ": the stored root key is the reserved non-empty value");
        }

        // a real parent code equal to the reserved value would alias the root: refused, never stored
        assertThrows(IllegalArgumentException.class, () -> store.put(new MasterCode("IN", "HOA", "MINOR", "1", AbsentKey.NONE,
                Map.of(), Map.of(), null, null, "seed")));
    }
}
