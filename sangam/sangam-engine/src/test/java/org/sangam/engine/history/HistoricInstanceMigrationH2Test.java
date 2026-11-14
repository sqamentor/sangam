// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.engine.AuditSink;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.history.HistoricInstanceMigration.HistoryMigrationException;

/**
 * FC-18, owner decision 258: the history of an ended instance moves to a later version of the same key in the same
 * tenant, in one transaction with {@code PROCESS_HISTORY_MIGRATED}; refusals change nothing.
 *
 * <p>ACT_HI_* tables touched (every one is checked for rows of the instance left on the old definition):
 * ACT_HI_PROCINST, ACT_HI_ACTINST, ACT_HI_TASKINST, ACT_HI_VARINST, ACT_HI_DETAIL, ACT_HI_INCIDENT, ACT_HI_OP_LOG,
 * ACT_HI_EXT_TASK_LOG, ACT_HI_DECINST, ACT_HI_JOB_LOG (by instance id) and ACT_HI_IDENTITYLINK (through the instance's
 * history tasks). Rows are produced here for ACT_HI_PROCINST, ACT_HI_ACTINST, ACT_HI_TASKINST, ACT_HI_VARINST,
 * ACT_HI_DETAIL, ACT_HI_IDENTITYLINK, ACT_HI_JOB_LOG, ACT_HI_EXT_TASK_LOG and ACT_HI_OP_LOG; ACT_HI_INCIDENT and
 * ACT_HI_DECINST are checked for zero rows left only. H2 only; Oracle and SQL Server NOT_RUN. Written, NOT_RUN.
 */
class HistoricInstanceMigrationH2Test {

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="fc18" isExecutable="true" name="NAME">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="ext"/>
                <serviceTask id="ext" operaton:type="external" operaton:topic="fc18" operaton:asyncBefore="true"/>
                <sequenceFlow id="f2" sourceRef="ext" targetRef="review"/>
                <userTask id="review" operaton:candidateUsers="u1"/>
                <sequenceFlow id="f3" sourceRef="review" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    record Audited(String tenant, String root, String type, Map<String, Object> payload) {
    }

    private final List<Audited> audited = new CopyOnWriteArrayList<>();

    private OperatonEngineAdapter engine() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setAuditSink((AuditSink) (c, t, r, e, p) -> audited.add(new Audited(t, r, e, p)));
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:fc18" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return SangamEngineFactory.build(ds, s, "h2");
    }

    static SecurityIdentity person(String tenant, String... groups) {
        return new SecurityIdentity(tenant, "p-1", "p-1", Set.of(groups), Set.of(), Set.of(), Set.of(), "OIDC", null,
                null, false, Map.of());
    }

    private static byte[] model(String name) {
        return MODEL.replace("NAME", name).getBytes(StandardCharsets.UTF_8);
    }

    private static String definition(OperatonEngineAdapter a, String tenant, int version) {
        var q = a.processEngine().getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("fc18")
                .processDefinitionVersion(version);
        return (tenant == null ? q.withoutTenantId() : q.tenantIdIn(tenant)).singleResult().getId();
    }

    /** Runs one instance to its end: async job, external task, user task with a candidate user, an operation log row. */
    private static String finished(OperatonEngineAdapter a, String tenant, String business) {
        var engine = a.processEngine();
        String pid = a.startProcess(tenant, "fc18", business, Map.of("amount", 10));
        engine.getManagementService().executeJob(engine.getManagementService().createJobQuery().processInstanceId(pid)
                .singleResult().getId());
        var ext = engine.getExternalTaskService();
        var locked = ext.fetchAndLock(1, "w-1").topic("fc18", 60_000L).execute();
        ext.complete(locked.get(0).getId(), "w-1");
        engine.getIdentityService().setAuthenticatedUserId("u1");
        try {
            engine.getRuntimeService().setVariable(pid, "checked", true); // ACT_HI_OP_LOG row of the instance
            String task = engine.getTaskService().createTaskQuery().processInstanceId(pid).singleResult().getId();
            engine.getTaskService().complete(task);
        } finally {
            engine.getIdentityService().clearAuthentication();
        }
        return pid;
    }

    private static int rows(OperatonEngineAdapter a, String sql, Object... params) {
        return a.inEngineTransaction(c -> Jdbc.one(c, sql, rs -> rs.getInt(1), params).orElse(0));
    }

    /** Rows of the instance on {@code definitionId}, per touched table. */
    private static Map<String, Integer> onDefinition(OperatonEngineAdapter a, String pid, String definitionId) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (HistoricInstanceMigration.Table t : HistoricInstanceMigration.TABLES) {
            out.put(t.name(), rows(a, "SELECT COUNT(*) FROM " + t.name() + " WHERE " + t.instanceId() + " = ? AND "
                    + t.definitionId() + " = ?", pid, definitionId));
        }
        out.put(HistoricInstanceMigration.IDENTITY_LINKS, rows(a, "SELECT COUNT(*) FROM ACT_HI_IDENTITYLINK WHERE"
                + " PROC_DEF_ID_ = ? AND TASK_ID_ IN (SELECT ID_ FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = ?)",
                definitionId, pid));
        return out;
    }

    @Test
    void theTouchedTablesAreExactlyTheHistoryTablesWithADefinitionColumn() {
        Set<String> names = new LinkedHashSet<>();
        HistoricInstanceMigration.TABLES.forEach(t -> names.add(t.name()));
        names.add(HistoricInstanceMigration.IDENTITY_LINKS);
        assertEquals(Set.of("ACT_HI_PROCINST", "ACT_HI_ACTINST", "ACT_HI_TASKINST", "ACT_HI_VARINST", "ACT_HI_DETAIL",
                "ACT_HI_INCIDENT", "ACT_HI_OP_LOG", "ACT_HI_EXT_TASK_LOG", "ACT_HI_DECINST", "ACT_HI_JOB_LOG",
                "ACT_HI_IDENTITYLINK"), names);
        try (OperatonEngineAdapter a = engine()) {
            // every ACT_HI_* table with a process-definition id column is in the list (Operaton 2.2.0 H2 schema)
            Set<String> withColumn = new LinkedHashSet<>(a.inEngineTransaction(c -> Jdbc.query(c,
                    "SELECT DISTINCT TABLE_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME LIKE 'ACT\\_HI\\_%'"
                            + " AND COLUMN_NAME IN ('PROC_DEF_ID_', 'PROCESS_DEF_ID_')", rs -> rs.getString(1))));
            assertEquals(names, withColumn);
        }
    }

    @Test
    void anEndedInstanceMovesToALaterVersionInOneTransactionWithItsAuditEvent() {
        try (OperatonEngineAdapter a = engine()) {
            a.deploy("t1", "fc18", Map.of("fc18.bpmn", model("v1")));
            String pid = finished(a, "t1", "H-1");
            a.deploy("t1", "fc18", Map.of("fc18.bpmn", model("v2")));
            String v1 = definition(a, "t1", 1);
            String v2 = definition(a, "t1", 2);
            Map<String, Integer> before = onDefinition(a, pid, v1);
            for (String produced : List.of("ACT_HI_PROCINST", "ACT_HI_ACTINST", "ACT_HI_TASKINST", "ACT_HI_VARINST",
                    "ACT_HI_DETAIL", "ACT_HI_IDENTITYLINK", "ACT_HI_JOB_LOG", "ACT_HI_EXT_TASK_LOG", "ACT_HI_OP_LOG")) {
                assertTrue(before.get(produced) > 0, produced + " has rows of the instance");
            }

            HistoricInstanceMigration.Result r = new HistoricInstanceMigration(a)
                    .migrate(person("t1"), pid, v2, "model fix after GFR amendment");

            assertEquals(v1, r.fromDefinitionId());
            assertEquals(v2, r.toDefinitionId());
            assertEquals(1, r.fromVersion());
            assertEquals(2, r.toVersion());
            assertEquals(before, r.rowsByTable(), "every row of the instance on v1 moved, and only those");
            onDefinition(a, pid, v1).forEach((t, n) -> assertEquals(0, n, t + " still has rows on v1"));
            assertEquals(before, onDefinition(a, pid, v2));
            assertEquals("fc18", a.processEngine().getHistoryService().createHistoricProcessInstanceQuery()
                    .processInstanceId(pid).singleResult().getProcessDefinitionKey());
            assertEquals(v2, a.processEngine().getHistoryService().createHistoricProcessInstanceQuery()
                    .processInstanceId(pid).singleResult().getProcessDefinitionId());

            Audited e = audited.stream().filter(x -> HistoricInstanceMigration.EVENT.equals(x.type())).findFirst()
                    .orElseThrow();
            assertEquals("t1", e.tenant());
            assertEquals(pid, e.root());
            assertEquals(v1, e.payload().get("fromDefinitionId"));
            assertEquals(v2, e.payload().get("toDefinitionId"));
            assertEquals("p-1", e.payload().get("actor"));
            assertEquals("model fix after GFR amendment", e.payload().get("reason"));
            assertEquals(before, e.payload().get("rowsByTable"));
            assertEquals(r.fromVersion(), e.payload().get("fromVersion"), "the API result matches the audit record");
            assertEquals(r.toVersion(), e.payload().get("toVersion"));
        }
    }

    @Test
    void refusalsChangeNothing() {
        try (OperatonEngineAdapter a = engine()) {
            a.deploy("t1", "fc18", Map.of("fc18.bpmn", model("v1")));
            String pid = finished(a, "t1", "H-2");
            String running = a.startProcess("t1", "fc18", "H-3", Map.of());
            a.deploy("t1", "fc18", Map.of("fc18.bpmn", model("v2")));
            a.deploy("t2", "fc18", Map.of("fc18.bpmn", model("v1")));
            a.deploy("t2", "fc18", Map.of("fc18.bpmn", model("v2")));
            String v1 = definition(a, "t1", 1);
            String v2 = definition(a, "t1", 2);
            String otherTenant = definition(a, "t2", 2);
            Map<String, Integer> before = onDefinition(a, pid, v1);
            HistoricInstanceMigration m = new HistoricInstanceMigration(a);

            assertEquals(HistoricInstanceMigration.VALIDATION_FAILED, code(() -> m.migrate(person("t1"), pid, v2, " ")));
            assertEquals(HistoricInstanceMigration.VALIDATION_FAILED,
                    code(() -> m.migrate(person("t1"), pid, v2, "x".repeat(1001))));
            assertEquals(HistoricInstanceMigration.VALIDATION_FAILED, code(() -> m.migrate(null, pid, v2, "r")));
            assertEquals(HistoricInstanceMigration.NOT_FOUND, code(() -> m.migrate(person("t2"), pid, v2, "r")),
                    "another tenant's history is not visible");
            assertEquals(HistoricInstanceMigration.NOT_FOUND, code(() -> m.migrate(person("t1"), "nope", v2, "r")));
            assertEquals(HistoricInstanceMigration.NOT_ENDED, code(() -> m.migrate(person("t1"), running, v2, "r")));
            assertEquals(HistoricInstanceMigration.TARGET_INVALID, code(() -> m.migrate(person("t1"), pid, v1, "r")),
                    "the same version is not later");
            assertEquals(HistoricInstanceMigration.TARGET_INVALID,
                    code(() -> m.migrate(person("t1"), pid, otherTenant, "r")), "another tenant's definition");
            assertEquals(HistoricInstanceMigration.TARGET_INVALID, code(() -> m.migrate(person("t1"), pid, "nope", "r")));

            a.inEngineTransaction(c -> Jdbc.update(c, "INSERT INTO LEGAL_HOLD (tenant_id, root_id, hold_id, reason, held_by,"
                    + " created_at) VALUES (?, ?, ?, ?, ?, ?)", "t1", pid, "h-1", "litigation", "legal-1",
                    Timestamp.from(Instant.now())));
            assertEquals(HistoricInstanceMigration.LEGAL_HOLD_ACTIVE, code(() -> m.migrate(person("t1"), pid, v2, "r")));
            a.inEngineTransaction(c -> Jdbc.update(c, "UPDATE LEGAL_HOLD SET released_at = ?, released_by = ?"
                    + " WHERE hold_id = ?", Timestamp.from(Instant.now()), "legal-1", "h-1"));

            a.inEngineTransaction(c -> Jdbc.update(c, "UPDATE ACT_HI_PROCINST SET REMOVAL_TIME_ = ? WHERE PROC_INST_ID_ = ?",
                    Timestamp.from(Instant.now().plusSeconds(86_400)), pid));
            assertEquals(HistoricInstanceMigration.REMOVAL_TIME_SET, code(() -> m.migrate(person("t1"), pid, v2, "r")));

            assertEquals(before, onDefinition(a, pid, v1), "nothing moved");
            assertTrue(audited.stream().noneMatch(x -> HistoricInstanceMigration.EVENT.equals(x.type())));
        }
    }

    @Test
    void anUntenantedInstanceNeedsAPlatformIdentityAndAuditsToTheSystemScope() {
        try (OperatonEngineAdapter a = engine()) {
            a.deploy(null, "fc18", Map.of("fc18.bpmn", model("v1")));
            org.sangam.engine.tenant.SharedDeployments shared = new org.sangam.engine.tenant.SharedDeployments(a);
            String d1 = a.processEngine().getRepositoryService().createProcessDefinitionQuery()
                    .processDefinitionId(definition(a, null, 1)).singleResult().getDeploymentId();
            SecurityIdentity deployer = person("platform", HistoricInstanceMigration.PLATFORM_MARKER,
                    org.sangam.engine.tenant.SharedDeployments.DEPLOYER_GROUP);
            shared.register(deployer, d1, "platform process"); // decision 144: untenanted jobs run only when registered
            String pid = finished(a, null, "H-4");
            a.deploy(null, "fc18", Map.of("fc18.bpmn", model("v2")));
            String v2 = definition(a, null, 2);

            assertEquals(HistoricInstanceMigration.NOT_FOUND, code(() -> new HistoricInstanceMigration(a)
                    .migrate(person("t1"), pid, v2, "r")), "a tenant identity never sees untenanted history");
            new HistoricInstanceMigration(a).migrate(deployer, pid, v2, "platform model fix");
            Audited e = audited.stream().filter(x -> HistoricInstanceMigration.EVENT.equals(x.type())).findFirst()
                    .orElseThrow();
            assertEquals("system:platform", e.tenant());
            assertEquals(pid, e.root());
        }
    }

    private static String code(Runnable r) {
        return assertThrows(HistoryMigrationException.class, r::run).code();
    }
}
