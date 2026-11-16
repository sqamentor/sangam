// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.core.layer.Dialect;
import org.sangam.ports.PortsSchema;
import org.sangam.ports.PortsSettings;
import org.sangam.ports.SangamPortsPlugin;
import org.sangam.ports.tenancy.ChangeTenantService;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TenantMigrationPort;

/**
 * Owner decision 381(3): the inventory route lists what TenantFallbackInventory finds, and a repair moves a listed
 * instance to the named tenant through ChangeTenantService.moveInstance (same activity, variables, business key, a
 * change-tenant link), writes TENANT_INSTANCE_REPAIRED with the evidence reference on both chains, and refuses an
 * instance the inventory does not list or a move into the tenant it already runs in.
 */
class TenantMigrationBridgeH2Test {

    private static final String MODEL = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <process id="grant" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="review"/>
                <userTask id="review"/><sequenceFlow id="f2" sourceRef="review" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>""";

    static final RequestContext PLATFORM = new RequestContext("platform", "ops-admin", Set.of(), null, null, null, null,
            "127.0.0.1", "OIDC");

    record Audited(String chain, String type, Map<String, ?> payload) {
    }

    private String url;
    private ProcessEngine engine;
    private TenantMigrationBridge bridge;
    private final List<Audited> audited = new ArrayList<>();
    /** When set, the audit's second record (the old instance's chain) throws it. */
    private RuntimeException failSecondRecord;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:tmig" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", ""); java.sql.Statement st = c.createStatement()) {
            st.execute("CREATE DOMAIN IF NOT EXISTS BLOB AS BINARY LARGE OBJECT");
        }
        PortsSettings s = new PortsSettings();
        s.setDialect(Dialect.POSTGRESQL);
        SangamPortsPlugin plugin = new SangamPortsPlugin(s, null);
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl(url);
        cfg.setDatabaseType("h2");
        cfg.setJobExecutorActivate(false);
        cfg.setEnforceHistoryTimeToLive(false);
        cfg.setProcessEngineName("tmig" + System.nanoTime());
        cfg.setProcessEnginePlugins(List.of(plugin));
        engine = cfg.buildProcessEngine();
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            org.sangam.core.layer.Schema.create(c, Dialect.POSTGRESQL);
            PortsSchema.create(c, Dialect.POSTGRESQL);
        }
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(url);
        ds.setUser("sa");
        AuditPort audit = (AuditPort) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {AuditPort.class}, (proxy, m, args) -> {
                    if ("record".equals(m.getName())) {
                        @SuppressWarnings("unchecked")
                        Map<String, ?> payload = (Map<String, ?>) args[3];
                        if (failSecondRecord != null && audited.size() == 1) {
                            throw failSecondRecord;
                        }
                        audited.add(new Audited((String) args[1], (String) args[2], payload));
                        return "e-" + audited.size();
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        bridge = new TenantMigrationBridge(ds, "", new ChangeTenantService(engine, plugin.links(), List.of()), audit);
        engine.getRepositoryService().createDeployment().tenantId("a").addString("grant.bpmn", MODEL).deploy();
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    private ProcessInstance start(String businessKey, Map<String, Object> vars) {
        return engine.getRuntimeService().createProcessInstanceByKey("grant").processDefinitionTenantId("a")
                .businessKey(businessKey).setVariables(vars).execute();
    }

    private void clearEngineTenant(String instance) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement("UPDATE ACT_RU_EXECUTION SET TENANT_ID_ = NULL WHERE PROC_INST_ID_ = ?")) {
            ps.setString(1, instance);
            assertTrue(ps.executeUpdate() >= 1);
        }
    }

    private static TenantMigrationPort.TenantRepairRequest to(String tenant) {
        return new TenantMigrationPort.TenantRepairRequest(tenant, "LAND-REG/2026/0042", "owner confirmed by registry");
    }

    @Test
    void aListedInstanceIsMovedToItsOwnerAndTheRepairIsAudited() throws SQLException {
        ProcessInstance legacy = start("GR-1", Map.of("tenantId", "a", "amount", "10.00"));
        clearEngineTenant(legacy.getId());

        List<TenantMigrationPort.TenantFallbackInstance> listed = bridge.inventory(PLATFORM);
        assertEquals(1, listed.size());
        TenantMigrationPort.TenantFallbackInstance i = listed.get(0);
        assertEquals(legacy.getId(), i.processInstanceId());
        assertEquals("TENANT_CONTEXT_MISSING", i.finding());
        assertTrue(i.reliedOnTheFallback());
        assertEquals(List.of(new TenantMigrationPort.TenantClaim("tenantId", "a")), i.claims());
        assertEquals("a", i.definitionTenant());

        TenantMigrationPort.TenantRepairResult r = bridge.repair(PLATFORM, legacy.getId(), to("a"));
        assertNotEquals(legacy.getId(), r.newProcessInstanceId());
        assertEquals("a", r.targetTenant());
        assertEquals(null, r.fromTenant());
        ProcessInstance moved = engine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(r.newProcessInstanceId()).singleResult();
        assertEquals("a", moved.getTenantId());
        assertEquals("GR-1", moved.getBusinessKey());
        assertEquals("10.00", engine.getRuntimeService().getVariable(moved.getId(), "amount"));
        assertEquals("review", engine.getTaskService().createTaskQuery().processInstanceId(moved.getId()).singleResult()
                .getTaskDefinitionKey());
        assertEquals(0, engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(legacy.getId()).count(),
                "the old instance is cancelled");

        assertEquals(List.of(r.newProcessInstanceId(), legacy.getId()), audited.stream().map(Audited::chain).toList());
        Audited a = audited.get(0);
        assertEquals(TenantMigrationBridge.REPAIRED, a.type());
        assertEquals("LAND-REG/2026/0042", a.payload().get("evidenceRef"));
        assertEquals("owner confirmed by registry", a.payload().get("reason"));
        assertEquals("TENANT_CONTEXT_MISSING", a.payload().get("finding"));
        assertEquals(List.of(), bridge.inventory(PLATFORM), "nothing left to repair");
    }

    /**
     * Follow-up to decision 381(3): the audit events are written inside the cut-over transaction. When the second one
     * cannot be written, the move rolls back: the old instance keeps running at its task, nothing runs in the target
     * tenant, the inventory still lists the instance, and the call fails closed with the audit's problem (or 503
     * AUDIT_UNAVAILABLE for any other failure). Before the fix the move had committed when the audit failed.
     */
    @Test
    void aRepairWhoseAuditFailsIsRolledBackAndFailsClosed() throws SQLException {
        ProcessInstance legacy = start("GR-4", Map.of("tenantId", "b"));
        failSecondRecord = new IllegalStateException("audit chain unavailable");

        ApiProblem p = assertThrows(ApiProblem.class, () -> bridge.repair(PLATFORM, legacy.getId(), to("b")));
        assertEquals(503, p.status());
        assertEquals(TenantMigrationBridge.AUDIT_UNAVAILABLE, p.code());
        assertNotMoved(legacy);

        failSecondRecord = ApiProblem.unavailable("AUDIT_CHAIN_LOCKED", "chain busy", 5);
        ApiProblem own = assertThrows(ApiProblem.class, () -> bridge.repair(PLATFORM, legacy.getId(), to("b")));
        assertEquals("AUDIT_CHAIN_LOCKED", own.code(), "the audit's own problem is passed on");
        assertNotMoved(legacy);

        failSecondRecord = null;
        audited.clear();
        TenantMigrationPort.TenantRepairResult r = bridge.repair(PLATFORM, legacy.getId(), to("b"));
        assertEquals(List.of(r.newProcessInstanceId(), legacy.getId()), audited.stream().map(Audited::chain).toList());
        assertEquals(0, engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(legacy.getId()).count());
    }

    private void assertNotMoved(ProcessInstance legacy) throws SQLException {
        assertEquals(1, engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(legacy.getId()).count(),
                "the old instance keeps running");
        assertEquals("review", engine.getTaskService().createTaskQuery().processInstanceId(legacy.getId()).singleResult()
                .getTaskDefinitionKey());
        assertEquals(0, engine.getRuntimeService().createProcessInstanceQuery().tenantIdIn("b").count(),
                "no instance remains in the target tenant");
        assertEquals(List.of(legacy.getId()), bridge.inventory(PLATFORM).stream()
                .map(TenantMigrationPort.TenantFallbackInstance::processInstanceId).toList(), "still listed for repair");
        assertEquals(1, engine.getRuntimeService().createProcessInstanceQuery().count(), "no second instance remains");
    }

    @Test
    void aConflictIsMovedOnlyToAnotherTenantAndAConsistentInstanceIsNotRepaired() {
        ProcessInstance forged = start("GR-2", Map.of("tenantId", "b"));
        ProcessInstance consistent = start("GR-3", Map.of("tenantId", "a"));

        ApiProblem same = assertThrows(ApiProblem.class, () -> bridge.repair(PLATFORM, forged.getId(), to("a")));
        assertEquals(409, same.status());
        assertEquals("INVALID_TRANSITION", same.code());
        ApiProblem notListed = assertThrows(ApiProblem.class, () -> bridge.repair(PLATFORM, consistent.getId(), to("b")));
        assertEquals(409, notListed.status());
        assertEquals("NOT_IN_INVENTORY", notListed.code());
        assertTrue(audited.isEmpty(), "a refused repair writes nothing");
        assertEquals(2, engine.getRuntimeService().createProcessInstanceQuery().tenantIdIn("a").count(), "nothing moved");

        TenantMigrationPort.TenantRepairResult r = bridge.repair(PLATFORM, forged.getId(), to("b"));
        assertEquals("a", r.fromTenant());
        assertEquals("TENANT_CONTEXT_CONFLICT", r.finding());
        assertEquals("b", engine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(r.newProcessInstanceId()).singleResult().getTenantId());
    }
}
