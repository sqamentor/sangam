// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.adapter.jdbc.JdbcTenantCustomFieldStore;
import org.sangam.rest.extension.TenantCustomFieldRegistry;
import org.sangam.rest.extension.TenantCustomFieldRegistry.ConcurrentChangeException;
import org.sangam.rest.extension.TenantCustomFieldRegistry.FieldDefinition;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.Download;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * SL-40: tenant custom fields persisted in {@code TENANT_CUSTOM_FIELD} on the generated H2 changelog (changeset
 * 0034-01, applied by {@link SangamSchema#apply}), through the server's wiring: definitions survive a new registry
 * (another node, a restart), effective dating and retirement are stored, the audit goes to the tenant's security chain
 * in the change's transaction, and concurrent changes are refused.
 */
class TenantCustomFieldsH2Test {

    private static final String FORM = "form:grant-application";
    private static final LocalDate APR = LocalDate.of(2026, 4, 1);
    private static final LocalDate JAN27 = LocalDate.of(2027, 1, 1);

    /** Records class-B audit calls: context principal, auth method, chain, type, key. */
    static final class RecordingAudit implements AuditPort {
        final List<String> events = new ArrayList<>();
        RuntimeException failure;

        @Override
        public String record(RequestContext ctx, String chainId, String eventType, Map<String, ?> payload) {
            if (failure != null) {
                throw failure;
            }
            events.add(ctx.tenantId() + "|" + ctx.principalId() + "|" + ctx.authMethod() + "|" + chainId + "|" + eventType
                    + "|" + payload.get("key") + "|v" + payload.get("version"));
            return "e" + events.size();
        }

        @Override
        public Page<AuditEntry> timeline(RequestContext ctx, String businessKey, boolean includeHistory, Page.PageRequest page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<AuditEntry> search(RequestContext ctx, AuditFilter filter, Page.PageRequest page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VerifyResult verify(RequestContext ctx, String chainId, String businessKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Download export(RequestContext ctx, String businessKey, String format) {
            throw new UnsupportedOperationException();
        }
    }

    private TxSupport tx;
    private final RecordingAudit audit = new RecordingAudit();
    private TenantCustomFieldRegistry reg;

    @BeforeEach
    void schema() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:tcf" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(h2);
        tx = new TxSupport(h2, new DataSourceTransactionManager(h2), Dialect.POSTGRESQL);
        reg = TenantCustomFieldsWiring.registry(tx, audit);
    }

    private int rows(String tenant) {
        return tx.withConnection(c -> Jdbc.one(c, "SELECT COUNT(*) FROM TENANT_CUSTOM_FIELD WHERE tenant_id=?",
                rs -> rs.getInt(1), tenant).orElseThrow());
    }

    @Test
    void definitionsArePersistedAndReadByAFreshRegistry() {
        Map<String, Object> decimal = Map.of("type", "string", "format", "decimal", "x-sangam-scale", 2);
        Map<String, Object> list = Map.of("type", "array", "maxItems", 3,
                "items", Map.of("type", "string", "enum", List.of("A", "B")));
        reg.define("t1", "admin", FORM, "x_grant_amount", "Grant amount", true, false, decimal, APR, null);
        reg.define("t1", "admin", FORM, "x_tags", null, false, true, list, APR, null);

        TenantCustomFieldRegistry other = TenantCustomFieldsWiring.registry(tx, new RecordingAudit());
        assertFalse(other.isEmpty(), "a shared table is never known empty");
        List<FieldDefinition> now = other.inForce("t1", FORM, LocalDate.of(2026, 11, 12));
        assertEquals(List.of("x_grant_amount", "x_tags"), now.stream().map(FieldDefinition::key).toList());
        FieldDefinition amount = now.get(0);
        assertEquals("Grant amount", amount.label());
        assertTrue(amount.required());
        assertFalse(amount.readOnly());
        assertEquals(decimal, amount.schema(), "the schema fragment round-trips");
        assertEquals(APR, amount.effectiveFrom());
        assertNull(amount.effectiveTo());
        assertEquals(list, now.get(1).schema());
        assertTrue(now.get(1).readOnly());
        assertTrue(other.inForce("t1", FORM, APR.minusDays(1)).isEmpty(), "not in force before effectiveFrom");
        assertTrue(other.inForce("t2", FORM, APR).isEmpty(), "another tenant sees nothing");
        assertTrue(other.inForce("t1", "form:other", APR).isEmpty(), "another form sees nothing");
    }

    @Test
    void versionsAndRetirementAreStoredWithoutRewritingHistory() {
        reg.define("t1", "admin", FORM, "x_code", null, true, false, Map.of("type", "string", "pattern", "^[0-9]{3}$"), APR, null);
        reg.define("t1", "officer", FORM, "x_code", null, true, false, Map.of("type", "string", "pattern", "^[0-9]{4}$"), JAN27, null);
        reg.retire("t1", "auditor", FORM, "x_code", LocalDate.of(2028, 1, 1));

        TenantCustomFieldRegistry other = TenantCustomFieldsWiring.registry(tx, new RecordingAudit());
        assertEquals("^[0-9]{3}$", other.inForce("t1", FORM, LocalDate.of(2026, 12, 31)).get(0).schema().get("pattern"));
        assertEquals("^[0-9]{4}$", other.inForce("t1", FORM, JAN27).get(0).schema().get("pattern"));
        assertTrue(other.inForce("t1", FORM, LocalDate.of(2028, 1, 1)).isEmpty(), "retired from its end date");
        List<FieldDefinition> history = other.history("t1");
        assertEquals(2, history.size(), "nothing is deleted");
        assertEquals(JAN27, history.get(0).effectiveTo(), "v1 closed on v2's start");
        assertEquals(LocalDate.of(2028, 1, 1), history.get(1).effectiveTo());
        List<String> closers = tx.withConnection(c -> Jdbc.query(c, "SELECT retired_by, retired_at, created_by FROM"
                + " TENANT_CUSTOM_FIELD WHERE tenant_id='t1' ORDER BY version", rs -> {
                    assertNotNull(rs.getTimestamp("retired_at"));
                    return rs.getString("created_by") + ">" + rs.getString("retired_by");
                }));
        assertEquals(List.of("admin>officer", "officer>auditor"), closers);
        assertThrows(IllegalArgumentException.class, () -> other.define("t1", "admin", FORM, "x_code", null, false, false,
                Map.of("type", "string"), APR.plusDays(1), null), "a back-dated version is refused");
    }

    @Test
    void everyChangeIsAuditedOnTheTenantSecurityChain() {
        reg.define("t1", "admin", FORM, "x_code", null, false, false, Map.of("type", "string"), APR, null);
        reg.retire("t1", "officer", FORM, "x_code", JAN27);
        assertEquals(List.of(
                "t1|system|SYSTEM:onBehalfOf=admin|sec:t1|CUSTOM_FIELD_DEFINED|x_code|v1",
                "t1|system|SYSTEM:onBehalfOf=officer|sec:t1|CUSTOM_FIELD_RETIRED|x_code|v1"), audit.events);
    }

    @Test
    void aFailedAuditRollsTheChangeBack() {
        audit.failure = new IllegalStateException("audit chain unavailable");
        assertThrows(IllegalStateException.class, () -> reg.define("t1", "admin", FORM, "x_code", null, false, false,
                Map.of("type", "string"), APR, null));
        assertEquals(0, rows("t1"), "the definition and its audit commit together or not at all");
    }

    @Test
    void concurrentChangesAreRefused() {
        reg.define("t1", "admin", FORM, "x_code", null, false, false, Map.of("type", "string"), APR, null);
        JdbcTenantCustomFieldStore store = new JdbcTenantCustomFieldStore(tx);
        FieldDefinition v1 = store.versions("t1", FORM, "x_code").get(0);
        assertThrows(ConcurrentChangeException.class, () -> tx.inTx(() -> {
            store.insert("t1", "node-b", v1);
            return null;
        }), "the same version defined twice collides on the primary key");
        reg.retire("t1", "node-a", FORM, "x_code", JAN27);
        assertThrows(ConcurrentChangeException.class, () -> store.close("t1", "node-b", v1, LocalDate.of(2026, 9, 1)),
                "a close based on a stale read does not overwrite the stored end");
        assertEquals(JAN27, store.versions("t1", FORM, "x_code").get(0).effectiveTo());
        assertEquals(1, rows("t1"));
    }
}
