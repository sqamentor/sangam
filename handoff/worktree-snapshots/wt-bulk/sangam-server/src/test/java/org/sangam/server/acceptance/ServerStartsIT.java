// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.springframework.beans.factory.annotation.Autowired;

/** AC-00: the server starts on the Tier-1 database; product (Liquibase) and engine (ACT_*) schemas both exist. */
class ServerStartsIT extends AbstractAcceptanceIT {

    @Autowired
    ProcessEngine engine;

    @Autowired
    DataSource dataSource;

    @Test
    void serverStartsWithBothSchemas() throws Exception {
        assertThat(engine.getRepositoryService().createDeploymentQuery().count()).isGreaterThanOrEqualTo(0);
        try (var c = dataSource.getConnection(); var s = c.createStatement();
             var rs = s.executeQuery("SELECT COUNT(*) FROM DATABASECHANGELOG")) {
            rs.next();
            assertThat(rs.getLong(1)).as("applied product changesets").isGreaterThan(0);
        }
    }
}
