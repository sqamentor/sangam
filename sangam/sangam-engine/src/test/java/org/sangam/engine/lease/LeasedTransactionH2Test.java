// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.api.StaleLeaseException;
import org.sangam.core.engine.SangamEngineAdapter;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * W3-I REQUEST for M-025: {@code inLeasedTransaction} runs the caller's database work only behind a valid lease, on
 * the engine's connection in the same transaction; a stale version or another worker is refused and nothing runs.
 */
class LeasedTransactionH2Test {

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="fence" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="x"/>
                <serviceTask id="x" operaton:type="external" operaton:topic="fence"/>
                <sequenceFlow id="f2" sourceRef="x" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    @Test
    void workRunsOnlyBehindTheCurrentLease() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:fence" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            a.deploy("t1", "fence-" + UUID.randomUUID(), Map.of("fence.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
            a.startProcess("t1", "fence", "F-" + UUID.randomUUID(), Map.of());
            List<SangamEngineAdapter.LeasedWork> w = a.fetchAndLockWork("w1", "fence", 1, Duration.ofMinutes(1));
            assertEquals(1, w.size());
            String id = w.get(0).work().id();
            long v = w.get(0).work().lockVersion();

            Integer one = a.inLeasedTransaction(id, "w1", v, conn -> {
                try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT 1")) {
                    rs.next();
                    return rs.getInt(1);
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertEquals(1, one, "the work ran on the engine connection behind the valid lease");

            AtomicBoolean ran = new AtomicBoolean();
            StaleLeaseException stale = assertThrows(StaleLeaseException.class,
                    () -> a.inLeasedTransaction(id, "w1", v + 1, conn -> ran.getAndSet(true)));
            assertEquals(StaleLeaseException.Reason.STALE_VERSION, stale.reason());
            assertThrows(StaleLeaseException.class, () -> a.inLeasedTransaction(id, "w2", v, conn -> ran.getAndSet(true)));
            assertThrows(StaleLeaseException.class,
                    () -> a.inLeasedTransaction("no-such-work", "w1", v, conn -> ran.getAndSet(true)));
            assertTrue(!ran.get(), "nothing runs without the lease");
        }
    }
}
