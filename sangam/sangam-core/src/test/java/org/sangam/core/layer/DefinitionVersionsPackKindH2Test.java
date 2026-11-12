// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SL-116: the {@code pack} kind of {@link DefinitionVersions} (EVT_DEFINITION_SEQ, H2 in PostgreSQL mode as the
 * development database runs it). One pack in one tenant scope is serialised; another pack, another tenant and the
 * event registry kinds are separate rows; the shared scope is stored as {@link TenantKey#SHARED}.
 */
class DefinitionVersionsPackKindH2Test {

    private static final String URL = "jdbc:h2:mem:defver-sl116;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    private Connection holder;
    private Connection other;

    @BeforeEach
    void open() throws SQLException {
        holder = DriverManager.getConnection(URL, "sa", "");
        other = DriverManager.getConnection(URL, "sa", "");
        try (Statement s = holder.createStatement()) {
            // the baseline DDL (sangam-persistence changeset 0030-03, h2)
            s.execute("CREATE TABLE IF NOT EXISTS EVT_DEFINITION_SEQ (tenant_id VARCHAR(64) NOT NULL, "
                    + "def_kind VARCHAR(64) NOT NULL, def_key VARCHAR(255) NOT NULL, last_version INTEGER NOT NULL, "
                    + "CONSTRAINT PK_EVT_DEFINITION_SEQ PRIMARY KEY (tenant_id, def_kind, def_key))");
            s.execute("DELETE FROM EVT_DEFINITION_SEQ");
        }
        holder.setAutoCommit(false);
        other.setAutoCommit(false);
        try (Statement s = other.createStatement()) {
            s.execute("SET LOCK_TIMEOUT 300"); // a bounded wait for the blocked side
        }
    }

    @AfterEach
    void close() throws SQLException {
        holder.rollback();
        other.rollback();
        holder.close();
        other.close();
    }

    private static boolean isLockTimeout(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause() == x ? null : x.getCause()) {
            if (x instanceof SQLException s && ("HYT00".equals(s.getSQLState()) || s.getErrorCode() == 50200)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void onePackInOneTenantIsSerialisedOthersAreNot() throws SQLException {
        DefinitionVersions.lockPack(holder, Dialect.POSTGRESQL, "t1", "gov.tender");
        holder.commit(); // the mark row exists
        DefinitionVersions.lockPack(holder, Dialect.POSTGRESQL, "t1", "gov.tender"); // held until holder ends

        RuntimeException blocked = assertThrows(RuntimeException.class,
                () -> DefinitionVersions.lockPack(other, Dialect.POSTGRESQL, "t1", "gov.tender"));
        assertTrue(isLockTimeout(blocked), () -> "a lock-wait timeout, not " + blocked);
        other.rollback();

        // another pack, another tenant, the shared scope and an event key are other rows: not blocked
        DefinitionVersions.lockPack(other, Dialect.POSTGRESQL, "t1", "gov.works");
        DefinitionVersions.lockPack(other, Dialect.POSTGRESQL, "t2", "gov.tender");
        DefinitionVersions.lockPack(other, Dialect.POSTGRESQL, null, "gov.tender");
        DefinitionVersions.lock(other, Dialect.POSTGRESQL, "EVT_EVENT_DEF", "t1", "gov.tender");
        other.commit();

        holder.commit(); // released: the second caller now gets the lock
        DefinitionVersions.lockPack(other, Dialect.POSTGRESQL, "t1", "gov.tender");
        other.commit();

        List<String> rows = Jdbc.query(holder, "SELECT tenant_id || '|' || def_kind || '|' || def_key || '|' || last_version "
                + "FROM EVT_DEFINITION_SEQ ORDER BY 1", rs -> rs.getString(1));
        assertEquals(List.of(TenantKey.SHARED + "|pack|gov.tender|0", "t1|event|gov.tender|0", "t1|pack|gov.tender|0",
                "t1|pack|gov.works|0", "t2|pack|gov.tender|0"), rows);
    }

    @Test
    void anUnknownKindIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> DefinitionVersions.lockKind(holder, Dialect.POSTGRESQL, "case", "t1", "x"));
        assertThrows(IllegalArgumentException.class, () -> DefinitionVersions.kindOf("ACT_RE_DEPLOYMENT"));
        assertEquals(DefinitionVersions.PACK, "pack");
    }
}
