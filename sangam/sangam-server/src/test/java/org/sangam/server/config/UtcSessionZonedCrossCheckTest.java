// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.sangam.engine.db.EngineTimestampInventory;
import org.sangam.ops.utc.UtcInventory;

/**
 * Decisions 150 and 240 (W4-E ACK 03:29:48Z item 2): the MySQL/MariaDB session-zoned engine columns are listed twice,
 * by the engine (EngineTimestampInventory) and by the UTC conversion tool (UtcInventory, sangam-ops, which does not
 * compile against sangam-engine). sangam-server is the only module with both on its class path, so the two sets are
 * compared here: a column added to one list only would be converted, or left alone, by mistake.
 */
class UtcSessionZonedCrossCheckTest {

    @Test
    void theEngineAndTheConversionToolListTheSameSessionZonedColumns() {
        assertFalse(EngineTimestampInventory.MYSQL_SESSION_ZONED.isEmpty());
        assertEquals(EngineTimestampInventory.MYSQL_SESSION_ZONED, UtcInventory.MYSQL_SESSION_ZONED);
    }
}
