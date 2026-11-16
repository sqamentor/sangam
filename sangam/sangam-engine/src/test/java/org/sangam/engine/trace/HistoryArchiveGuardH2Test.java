// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.sangam.core.history.HistoryArchiveGate;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * RM-52 (decision 386(2)): the engine deletes no tree's history the archive gate refuses, on any path: the
 * {@code HistoryService} deletes, the removal-time cleanup of process and decision history, and a deployment's cascade
 * delete. A refused command deletes nothing (a mixed request is refused whole); with no gate configured every tree is
 * refused.
 */
class HistoryArchiveGuardH2Test {

    static final String MODELS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="child" isExecutable="true">
                <startEvent id="cs"/><sequenceFlow id="c1" sourceRef="cs" targetRef="fee"/>
                <businessRuleTask id="fee" operaton:decisionRef="g-fee" operaton:mapDecisionResult="singleEntry"
                                  operaton:resultVariable="fee" operaton:decisionRefBinding="deployment"/>
                <sequenceFlow id="c2" sourceRef="fee" targetRef="ce"/><endEvent id="ce"/>
              </process>
              <process id="parent" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="call"/>
                <callActivity id="call" calledElement="child" operaton:calledElementBinding="deployment">
                  <extensionElements><operaton:in variables="all"/></extensionElements>
                </callActivity>
                <sequenceFlow id="f2" sourceRef="call" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;
    static final String DMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="t">
              <decision id="g-fee" name="fee">
                <decisionTable id="t" hitPolicy="UNIQUE">
                  <input id="i"><inputExpression id="ie" typeRef="long"><text>amount</text></inputExpression></input>
                  <output id="o" name="fee" typeRef="long"/>
                  <rule id="r1"><inputEntry id="e1"><text>&lt; 1000</text></inputEntry><outputEntry id="o1"><text>10</text></outputEntry></rule>
                  <rule id="r2"><inputEntry id="e2"><text>&gt;= 1000</text></inputEntry><outputEntry id="o2"><text>25</text></outputEntry></rule>
                </decisionTable>
              </decision>
            </definitions>
            """;

    /** Roots the test gate lets through; every other tree is refused. */
    final Set<String> archived = ConcurrentHashMap.newKeySet();
    JdbcDataSource ds;

    OperatonEngineAdapter engine(boolean configured) {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform");
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setJobExecutorActivate(false);
        if (configured) {
            s.setHistoryArchiveGate((c, tenant, root) -> archived.contains(root) ? null : "HISTORY_ARCHIVE_MISSING: " + root);
        }
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:rm52g" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2");
        a.deploy("t1", "rm52g", Map.of("g.bpmn", MODELS.getBytes(StandardCharsets.UTF_8),
                "g.dmn", DMN.getBytes(StandardCharsets.UTF_8)));
        return a;
    }

    String tree(OperatonEngineAdapter a) {
        return a.startProcess("t1", "parent", "G-" + UUID.randomUUID(), Map.of("amount", 1500L));
    }

    String childOf(OperatonEngineAdapter a, String parent) {
        return a.processEngine().getHistoryService().createHistoricProcessInstanceQuery().superProcessInstanceId(parent)
                .singleResult().getId();
    }

    long rows(String table, String root) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE ROOT_PROC_INST_ID_=?")) {
            ps.setString(1, root);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    void due(String root, Instant at) throws Exception {
        try (Connection c = ds.getConnection()) {
            for (String t : List.of("ACT_HI_PROCINST", "ACT_HI_ACTINST", "ACT_HI_VARINST", "ACT_HI_DECINST",
                    "ACT_HI_DEC_IN", "ACT_HI_DEC_OUT")) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE " + t + " SET REMOVAL_TIME_=? WHERE ROOT_PROC_INST_ID_=?")) {
                    ps.setTimestamp(1, Timestamp.from(at));
                    ps.setString(2, root);
                    ps.executeUpdate();
                }
            }
        }
    }

    static <T> T inCommand(OperatonEngineAdapter a, Command<T> command) {
        return ((ProcessEngineConfigurationImpl) a.processEngine().getProcessEngineConfiguration())
                .getCommandExecutorTxRequired().execute(command);
    }

    @Test
    void theEngineUsesTheGatedHistoryManagers() {
        assertThrows(IllegalArgumentException.class, () -> new SangamEngineSettings().setHistoryArchiveGate(null),
                "there is no purge-without-archive setting");
        assertTrue(new SangamEngineSettings().getHistoryArchiveGate().refusal(null, "t", "r")
                .startsWith(HistoryArchiveGate.NOT_CONFIGURED), "the default refuses");
        try (OperatonEngineAdapter a = engine(true)) {
            assertInstanceOf(ProcessTraceHistoryRemoval.class, inCommand(a, cc -> cc.getHistoricProcessInstanceManager()));
            assertInstanceOf(HistoryArchiveGuard.Decisions.class, inCommand(a, cc -> cc.getHistoricDecisionInstanceManager()));
        }
    }

    @Test
    void withNoArchiveConfiguredNoHistoryIsDeleted() throws Exception {
        try (OperatonEngineAdapter a = engine(false)) {
            String root = tree(a);
            long acts = rows("ACT_HI_ACTINST", root);
            HistoryArchiveGate.PurgeRefusedException e = assertThrows(HistoryArchiveGate.PurgeRefusedException.class,
                    () -> a.processEngine().getHistoryService().deleteHistoricProcessInstance(root));
            assertTrue(e.getMessage().contains(HistoryArchiveGate.NOT_CONFIGURED), e.getMessage());
            assertEquals(root, e.rootId());
            assertEquals(acts, rows("ACT_HI_ACTINST", root));
        }
    }

    @Test
    void aRequestWithOneUnarchivedTreeIsRefusedWholeAndAnArchivedTreeGoes() throws Exception {
        try (OperatonEngineAdapter a = engine(true)) {
            String kept = tree(a);
            String gone = tree(a);
            archived.add(gone);
            assertThrows(HistoryArchiveGate.PurgeRefusedException.class, () -> a.processEngine().getHistoryService()
                    .deleteHistoricProcessInstancesIfExists(List.of(gone, kept)));
            assertEquals(2, rows("ACT_HI_PROCINST", gone), "rolled back whole: the archived tree is still there");
            // a child alone belongs to its root's tree: refused while the root is not archived
            String keptChild = childOf(a, kept);
            assertThrows(HistoryArchiveGate.PurgeRefusedException.class,
                    () -> a.processEngine().getHistoryService().deleteHistoricProcessInstance(keptChild));

            a.processEngine().getHistoryService().deleteHistoricProcessInstancesIfExists(
                    List.of(gone, childOf(a, gone)));
            assertEquals(0, rows("ACT_HI_PROCINST", gone));
            assertEquals(2, rows("ACT_HI_PROCINST", kept));
            assertEquals(1, rows("ACT_HI_DECINST", kept));
        }
    }

    @Test
    void theRemovalTimeCleanupIsRefusedWhileADueTreeHasNoArchive() throws Exception {
        try (OperatonEngineAdapter a = engine(true)) {
            String root = tree(a);
            String later = tree(a);
            Instant now = Instant.now();
            due(root, now.minusSeconds(3600));
            due(later, now.plusSeconds(86_400));
            assertThrows(HistoryArchiveGate.PurgeRefusedException.class, () -> inCommand(a, cc -> cc
                    .getHistoricProcessInstanceManager().deleteHistoricProcessInstancesByRemovalTime(Date.from(now), 0, 59, 500)));
            assertThrows(HistoryArchiveGate.PurgeRefusedException.class, () -> inCommand(a, cc -> cc
                    .getHistoricDecisionInstanceManager().deleteHistoricDecisionsByRemovalTime(Date.from(now), 0, 59, 500)));
            assertEquals(1, rows("ACT_HI_DECINST", root));
            assertTrue(rows("ACT_HI_ACTINST", root) > 0);

            archived.add(root); // the tree that is not due needs no archive for this run
            inCommand(a, cc -> cc.getHistoricDecisionInstanceManager().deleteHistoricDecisionsByRemovalTime(Date.from(now), 0, 59, 500));
            inCommand(a, cc -> cc.getHistoricProcessInstanceManager().deleteHistoricProcessInstancesByRemovalTime(Date.from(now), 0, 59, 500));
            assertEquals(0, rows("ACT_HI_DECINST", root));
            assertEquals(0, rows("ACT_HI_PROCINST", root));
            assertEquals(2, rows("ACT_HI_PROCINST", later));
        }
    }

    @Test
    void aCascadeDeleteOfTheDeploymentIsRefusedWhileATreeHasNoArchive() throws Exception {
        try (OperatonEngineAdapter a = engine(true)) {
            String root = tree(a);
            String deployment = a.processEngine().getRepositoryService().createDeploymentQuery().singleResult().getId();
            assertThrows(HistoryArchiveGate.PurgeRefusedException.class,
                    () -> a.processEngine().getRepositoryService().deleteDeployment(deployment, true));
            assertEquals(1, a.processEngine().getRepositoryService().createDeploymentQuery().count());
            assertEquals(2, rows("ACT_HI_PROCINST", root));
            archived.add(root);
            a.processEngine().getRepositoryService().deleteDeployment(deployment, true);
            assertEquals(0, rows("ACT_HI_PROCINST", root));
            assertEquals(0, rows("ACT_HI_DECINST", root));
        }
    }
}
