// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.rest.adapter.operaton.OperatonOperationIds;
import org.sangam.rest.adapter.operaton.OperatonProcessAdapter;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;

/**
 * Round 3 item 4: {@code POST /messages} with a {@code processInstanceId} by a tenant identity
 * ({@code OperatonProcessAdapter.correlate}). Operaton refuses a correlation that names both a process instance and a
 * tenant ({@code MessageCorrelationBuilderImpl.ensureProcessInstanceAndTenantIdNotSet}, BadUserRequestException,
 * 2.2.0), so the adapter names the instance only and checks its tenant itself first ({@code requireRuntime}), as
 * {@code OperatonEngineAdapter.correlateMessageToBranch} does. Another tenant's instance is refused and not touched.
 */
class MessageCorrelateByInstanceH2Test {

    static RequestContext tenant(String t) {
        return new RequestContext(t, "alice", Set.of(), null, null, null, null, "127.0.0.1", "DEV_TOKEN");
    }

    @Test
    void aTenantCorrelatesToItsOwnInstanceByIdAndNeverToAnotherTenantsInstance() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:corrpi" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform");
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true"); // empty in-memory database owned by this test
        s.setJobExecutorActivate(false);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            ProcessEngine engine = a.processEngine();
            for (String t : List.of("t1", "t2")) {
                a.deploy(t, "waitMsg", Map.of("waitMsg.bpmn",
                        MessageCaptureCorrelationKeyH2Test.BPMN.getBytes(StandardCharsets.UTF_8)));
            }
            OperatonProcessAdapter rest = new OperatonProcessAdapter(engine.getRuntimeService(),
                    engine.getRepositoryService(), engine.getHistoryService(), engine.getExternalTaskService(),
                    MessageCaptureCorrelationKeyH2Test.tx(ds), MessageCaptureCorrelationKeyH2Test.audit(),
                    new RestProperties(), new OperatonOperationIds(engine.getRuntimeService(),
                            engine.getHistoryService(), engine.getExternalTaskService()));

            String own = a.startProcess("t1", "waitMsg", "bk-1", Map.of());
            String other = a.startProcess("t2", "waitMsg", "bk-2", Map.of());

            ProcessPort.MessageResult r = rest.correlate(tenant("t1"),
                    new ProcessPort.MessageRequest("go", null, own, null, null, false));
            assertEquals(List.of(own), r.processInstanceIds(), "the tenant's own instance, addressed by id");
            assertEquals(1, engine.getTaskService().createTaskQuery().processInstanceId(own).count(), "it moved on");

            assertThrows(RuntimeException.class, () -> rest.correlate(tenant("t1"),
                    new ProcessPort.MessageRequest("go", null, other, null, null, false)), "another tenant's instance");
            assertEquals(1, engine.getRuntimeService().createEventSubscriptionQuery().processInstanceId(other)
                    .eventName("go").count(), "tenant t2's instance still waits for the message");
            assertEquals(0, engine.getTaskService().createTaskQuery().processInstanceId(other).count());
        }
    }
}
