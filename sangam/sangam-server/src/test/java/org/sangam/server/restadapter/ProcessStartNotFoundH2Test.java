// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.rest.adapter.operaton.OperatonOperationIds;
import org.sangam.rest.adapter.operaton.OperatonProcessAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * Register E-33 (g): {@code POST /process-instances} by key for a tenant that has no definition of the key answered
 * 404 with the engine's words for the shared scope, "no processes deployed with key 'k' and tenant-id 'null'", so the
 * officer of GOI.FIN read that the tenant was null. The start looks in the caller's tenant first and in the shared
 * scope second; when neither has the key the answer names the key and the caller's tenant.
 */
class ProcessStartNotFoundH2Test {

    private static ProcessPort.StartProcessRequest byKey(String key) {
        return new ProcessPort.StartProcessRequest(key, "E-33/g/" + UUID.randomUUID(), Map.of(), null, null, null, null);
    }

    /** The transaction seam of the adapter without Spring: the work runs at once, on one connection of the test. */
    private static TxSupport tx(JdbcDataSource ds, Connection connection) {
        return new TxSupport(ds, TxSupport.detectDialect(ds)) {
            @Override
            public <T> T inTxWithStatus(Function<TxStatus, T> work) {
                return work.apply(new TxStatus() {
                    @Override
                    public void setRollbackOnly() {
                    }

                    @Override
                    public boolean isRollbackOnly() {
                        return false;
                    }
                });
            }

            @Override
            public <T> T inNewTx(Supplier<T> work) {
                return work.get();
            }

            @Override
            public <T> T withReadConnection(org.sangam.core.api.ReadWork<T> work) {
                return work.read(connection);
            }

            @Override
            public <T> T inReadTx(org.sangam.core.api.ReadSupplier<T> work) {
                return work.read();
            }

            @Override
            public <T> T inNewReadTx(org.sangam.core.api.ReadWork<T> work) {
                return work.read(connection);
            }

            @Override
            public Connection connection() {
                return connection;
            }

            @Override
            public void afterCommit(Runnable work) {
                work.run();
            }
        };
    }

    @Test
    void aStartOfAKeyTheTenantDoesNotHaveNamesTheKeyAndTheCallersTenant() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:startnotfound" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform");
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true"); // empty in-memory database owned by this test
        s.setJobExecutorActivate(false);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2"); Connection connection = ds.getConnection()) {
            ProcessEngine engine = a.processEngine();
            a.deploy("GOI.OTHER", "waitMsg", Map.of("waitMsg.bpmn",
                    MessageCaptureCorrelationKeyH2Test.BPMN.getBytes(StandardCharsets.UTF_8)));
            OperatonProcessAdapter rest = new OperatonProcessAdapter(engine.getRuntimeService(),
                    engine.getRepositoryService(), engine.getHistoryService(), engine.getExternalTaskService(),
                    tx(ds, connection), MessageCaptureCorrelationKeyH2Test.audit(),
                    new RestProperties(), new OperatonOperationIds(engine.getRuntimeService(),
                            engine.getHistoryService(), engine.getExternalTaskService()));

            // typical: nothing is deployed for the caller's tenant (the demo tenant before its packs)
            ApiProblem none = assertThrows(ApiProblem.class,
                    () -> rest.start(ProcessDefinitionCountsH2Test.tenant("GOI.FIN"), byKey("sangam-ifms-bill")));
            assertEquals(404, none.status());
            assertEquals("NOT_FOUND", none.code());
            assertFalse(none.detail().contains("null"), "the tenant is not reported as null: " + none.detail());
            assertTrue(none.detail().contains("'sangam-ifms-bill'"), "the key is named: " + none.detail());
            assertTrue(none.detail().contains("'GOI.FIN'"), "the caller's tenant is named: " + none.detail());

            // difficult: only another tenant has the key; the answer is the same and does not name that tenant
            ApiProblem other = assertThrows(ApiProblem.class,
                    () -> rest.start(ProcessDefinitionCountsH2Test.tenant("GOI.FIN"), byKey("waitMsg")));
            assertEquals(404, other.status());
            assertEquals("NOT_FOUND", other.code());
            assertFalse(other.detail().contains("null"), other.detail());
            assertTrue(other.detail().contains("'waitMsg'") && other.detail().contains("'GOI.FIN'"), other.detail());
            assertFalse(other.detail().contains("GOI.OTHER"), "no word of the tenant that has it: " + other.detail());

            // unchanged: the tenant that has the key starts it
            ProcessPort.ProcessInstance started = rest.start(ProcessDefinitionCountsH2Test.tenant("GOI.OTHER"), byKey("waitMsg"));
            assertNotNull(started.id());
            assertEquals(1, engine.getRuntimeService().createProcessInstanceQuery().tenantIdIn("GOI.OTHER").count());
            assertEquals(0, engine.getRuntimeService().createProcessInstanceQuery().tenantIdIn("GOI.FIN").count());
        }
    }
}
