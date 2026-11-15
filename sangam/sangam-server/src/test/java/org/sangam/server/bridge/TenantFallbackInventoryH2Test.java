// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.tenant.TenantRefusal;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.server.bridge.TenantFallbackInventory.Claim;
import org.sangam.server.bridge.TenantFallbackInventory.Entry;

/**
 * Round-5 lane w2 item 4 (decisions 145 and 199): the read-only inventory lists the running instances the removed
 * {@code tenantId} fallback carried (no engine tenant, a tenant only in a variable), the ones without any tenant, and
 * the ones whose tenant variable contradicts the engine tenant; consistent instances are not listed and nothing is
 * changed. The instances "without an engine tenant" are made the way a pre-decision-145 instance looks in the engine
 * tables: started normally, then the execution's {@code TENANT_ID_} cleared.
 */
class TenantFallbackInventoryH2Test {

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <process id="grant" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="work"/>
                <userTask id="work"/><sequenceFlow id="f2" sourceRef="work" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    @Test
    void listsTheInstancesTheEngineTenantOnlyRuleRefusesAndChangesNothing() throws Exception {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setJobExecutorActivate(false);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:d145inv" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            a.deploy("t1", "inv-" + UUID.randomUUID(), Map.of("grant.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
            String consistent = a.startProcess("t1", "grant", null, Map.of("tenantId", "t1", "sangamTenantId", "t1"));
            String blankClaim = a.startProcess("t1", "grant", null, Map.of("tenantId", "  "));
            String forged = a.startProcess("t1", "grant", null, Map.of("tenantId", "t2", "amount", "10"));
            String fallback = a.startProcess("t1", "grant", null, Map.of("sangamTenantId", "t1"));
            String bare = a.startProcess("t1", "grant", null, Map.of("other", "x"));
            try (Connection c = ds.getConnection()) {
                clearEngineTenant(c, fallback);
                clearEngineTenant(c, bare);

                Map<String, Entry> found = TenantFallbackInventory.scan(c, "").stream()
                        .collect(Collectors.toMap(Entry::processInstanceId, Function.identity()));
                assertEquals(java.util.Set.of(forged, fallback, bare), found.keySet(),
                        "the consistent instance and the one with a blank claim are not listed");

                Entry f = found.get(forged);
                assertEquals(TenantRefusal.CONFLICTING_TENANT, f.finding());
                assertEquals("t1", f.engineTenant());
                assertEquals(List.of(new Claim("tenantId", "t2")), f.claims(), "only the tenant variables");
                assertFalse(f.reliedOnTheFallback());

                Entry fb = found.get(fallback);
                assertEquals(TenantRefusal.MISSING_ENGINE_TENANT, fb.finding());
                assertNull(fb.engineTenant());
                assertEquals("t1", fb.definitionTenant(), "the deployment tenant, listed as evidence");
                assertEquals("grant", fb.definitionKey());
                assertEquals(List.of(new Claim("sangamTenantId", "t1")), fb.claims());
                assertTrue(fb.reliedOnTheFallback());
                assertEquals("TENANT_CONTEXT_MISSING", fb.finding().code());

                Entry b = found.get(bare);
                assertEquals(TenantRefusal.MISSING_ENGINE_TENANT, b.finding());
                assertEquals(List.of(), b.claims());
                assertFalse(b.reliedOnTheFallback(), "no tenant anywhere: not a fallback case, still refused");

                // read-only: the engine tenant stays cleared, the variables stay as they were
                assertNull(engineTenant(c, fallback));
                assertEquals("t1", engineTenant(c, consistent));
                assertThrows(IllegalArgumentException.class, () -> TenantFallbackInventory.scan(c, "x; DROP TABLE y"));
                assertEquals(3, TenantFallbackInventory.scan(c, null).size());
            }
            assertEquals("t2", a.processEngine().getRuntimeService().getVariable(forged, "tenantId"), "unchanged");
            assertEquals("t1", a.processEngine().getRuntimeService().getVariable(fallback, "sangamTenantId"));
            assertTrue(a.processEngine().getRuntimeService().createProcessInstanceQuery()
                    .processInstanceId(blankClaim).count() == 1);
        }
    }

    @Test
    void theFindingFollowsTheSharedExecutionTenantPolicy() {
        assertEquals(TenantRefusal.MISSING_ENGINE_TENANT, TenantFallbackInventory.finding(" ", List.of()));
        assertEquals(TenantRefusal.MISSING_ENGINE_TENANT, TenantFallbackInventory.finding(null,
                List.of(new Claim("tenantId", "t1"))));
        assertEquals(TenantRefusal.CONFLICTING_TENANT, TenantFallbackInventory.finding("t1",
                List.of(new Claim("tenantId", "t1"), new Claim("sangamTenantId", "t2"))));
        assertNull(TenantFallbackInventory.finding("t1", List.of(new Claim("tenantId", "t1"),
                new Claim("sangamTenantId", null))));
    }

    private static void clearEngineTenant(Connection c, String instance) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("UPDATE ACT_RU_EXECUTION SET TENANT_ID_ = NULL"
                + " WHERE PROC_INST_ID_ = ?")) {
            ps.setString(1, instance);
            assertTrue(ps.executeUpdate() >= 1);
        }
    }

    private static String engineTenant(Connection c, String instance) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT TENANT_ID_ FROM ACT_RU_EXECUTION WHERE ID_ = ?")) {
            ps.setString(1, instance);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }
}
