// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.sangam.engine.tx.CommandState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;

/**
 * Regression for the Q1 rollback guard under Operaton's Spring integration: ROLLINGBACK runs from Spring's
 * beforeCompletion, after the command context closed its MyBatis session. The guard used to ask that closed session
 * for its connection ("Executor was closed") and logged a failed rollback on every failing command. It must roll back
 * through the connection the command was handed, and the write must not survive.
 */
@SpringBootTest
@ActiveProfiles("h2")
@ExtendWith(OutputCaptureExtension.class)
class RollbackGuardSpringTxTest {

    @Autowired
    ProcessEngine engine;

    @Autowired
    DataSource dataSource;

    @Test
    void failingCommandIsRolledBackThroughTheHandedOutConnection(CapturedOutput output) throws Exception {
        String key = "rbg-" + UUID.randomUUID();
        ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
        assertThatThrownBy(() -> cfg.getCommandExecutorTxRequired().execute(cc -> {
            Connection c = CommandState.connection(cc);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO INTEGRATION_LEADER(name, holder, epoch, expires_at) VALUES (?, 'x', 1, CURRENT_TIMESTAMP)")) {
                ps.setString(1, key.substring(0, 40));
                ps.executeUpdate();
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            throw new IllegalStateException("fail after write");
        })).hasMessageContaining("fail after write");

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM INTEGRATION_LEADER WHERE name=?")) {
            ps.setString(1, key.substring(0, 40));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).as("write of the failed command").isZero();
            }
        }
        assertThat(output.getAll()).doesNotContain("Q1 rollback guard: explicit rollback of the engine connection failed");
    }
}
