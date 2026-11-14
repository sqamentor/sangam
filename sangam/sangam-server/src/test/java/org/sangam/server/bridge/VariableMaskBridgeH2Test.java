// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import org.sangam.rest.config.SpringTxSupport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.VariableMaskPort;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.Permission;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Decisions 87 and 98 server side: {@link VariableMaskBridge} on the real schema and sangam-security's
 * {@code FieldMaskService}. {@code salary} of definition {@code payroll} needs READ_SENSITIVE on the owning process
 * instance; role {@code hr} holds it on {@code pi-ok} only. One map uses {@code mask}, several {@code maskAll}; both
 * answer the same, unclassified fields and other definitions are untouched, and the internal row id never leaks.
 * Written for the test phase (not run here).
 */
class VariableMaskBridgeH2Test {

    static final String T = "t1";

    private VariableMaskBridge bridge;
    private final RequestContext hr = new RequestContext(T, "u1", Set.of("hr"), null, null, null, null, null, "OIDC");

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:vm" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        SangamSecurity security;
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
            Jdbc.update(c, "INSERT INTO SEC_FIELD_RULE(tenant_id, resource_type, scope_key, field_name, required_perm)"
                    + " VALUES (?,?,?,?,?)", T, VariableMaskPort.PROCESS_INSTANCE, "payroll", "salary", "READ_SENSITIVE");
            Jdbc.update(c, "INSERT INTO AUTH_POLICY_VERSION(tenant_id, policy_version) VALUES (?, 1)", T);
            Jdbc.update(c, "INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id, deny_flag, perms,"
                            + " condition_id, cond_key, policy_version) VALUES (?, 'role:hr', ?, 'pi-ok', 0, ?, NULL, '-', 0)",
                    T, VariableMaskPort.PROCESS_INSTANCE, Permission.READ.bit() | Permission.READ_SENSITIVE.bit());
        }
        bridge = new VariableMaskBridge(security,
                new SpringTxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds)));
    }

    static Map<String, Object> vars() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("salary", 50000);
        m.put("name", "Asha");
        return m;
    }

    @Test
    void oneMapIsMaskedUnlessTheCallerHoldsThePermissionOnThatInstance() {
        Map<String, Object> denied = bridge.mask(hr, VariableMaskPort.PROCESS_INSTANCE, "payroll", List.of("pi-1"),
                List.of(vars())).get(0);
        assertEquals("***", denied.get("salary"));
        assertEquals("Asha", denied.get("name"));
        assertEquals(50000, bridge.mask(hr, VariableMaskPort.PROCESS_INSTANCE, "payroll", List.of("pi-ok"),
                List.of(vars())).get(0).get("salary"));
    }

    @Test
    void severalMapsAreMaskedSetBasedWithTheSameAnswers() {
        List<Map<String, Object>> out = bridge.mask(hr, VariableMaskPort.PROCESS_INSTANCE, "payroll",
                List.of("pi-1", "pi-ok", "pi-1"), List.of(vars(), vars(), vars()));
        assertEquals(List.of("***", 50000, "***"), out.stream().map(m -> m.get("salary")).toList());
        out.forEach(m -> assertFalse(m.containsKey(VariableMaskBridge.ID_FIELD), "the row id never leaks"));
        assertEquals(List.of("salary", "name"), List.copyOf(out.get(0).keySet()));
    }

    @Test
    void anotherDefinitionOrResourceTypeHasNoRule() {
        assertEquals(50000, bridge.mask(hr, VariableMaskPort.PROCESS_INSTANCE, "leave", List.of("pi-1"),
                List.of(vars())).get(0).get("salary"));
        assertEquals(50000, bridge.mask(hr, VariableMaskPort.CASE_INSTANCE, "payroll", List.of("pi-1"),
                List.of(vars())).get(0).get("salary"));
    }
}
