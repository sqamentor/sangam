// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.replay.CaptureListener;
import org.sangam.engine.replay.CapturedInput;
import org.sangam.rest.adapter.operaton.OperatonOperationIds;
import org.sangam.rest.adapter.operaton.OperatonProcessAdapter;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * Decision 332 on the REST adapter ({@code OperatonProcessAdapter.correlate}, {@code POST /messages}) over a real
 * engine with replay capture (H2): the business key the adapter was called with is the {@code correlationKey} of the
 * captured {@code MESSAGE_DELIVERED}; a call without a business key (correlation by variables), and a correlation
 * that does not go through the adapter, are captured without one.
 */
class MessageCaptureCorrelationKeyH2Test {

    static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <message id="goMsg" name="go"/>
              <process id="waitMsg" isExecutable="true">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="m"/>
                <intermediateCatchEvent id="m"><messageEventDefinition messageRef="goMsg"/></intermediateCatchEvent>
                <sequenceFlow id="f2" sourceRef="m" targetRef="w"/>
                <userTask id="w"/>
              </process>
            </definitions>
            """.strip();

    static final RequestContext CTX = new RequestContext("t1", "alice", Set.of(), null, null, null, null, "127.0.0.1",
            "DEV_TOKEN");

    final List<CapturedInput> seen = new CopyOnWriteArrayList<>();

    static AuditPort audit() {
        return (AuditPort) Proxy.newProxyInstance(AuditPort.class.getClassLoader(), new Class<?>[]{AuditPort.class},
                (p, m, a) -> {
                    if (!"record".equals(m.getName())) {
                        throw new AssertionError("not used: " + m.getName());
                    }
                    return "evt";
                });
    }

    static TxSupport tx(JdbcDataSource ds) {
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
            public <T> T inReadTx(org.sangam.core.api.ReadSupplier<T> work) {
                return work.read();
            }

            @Override
            public <T> T withReadConnection(org.sangam.core.api.ReadWork<T> work) {
                return work.read(connection());
            }

            @Override
            public <T> T inNewTx(Supplier<T> work) {
                return work.get();
            }

            @Override
            public <T> T inNewReadTx(org.sangam.core.api.ReadWork<T> work) {
                return work.read(connection());
            }

            @Override
            public Connection connection() {
                throw new AssertionError("not used");
            }

            @Override
            public void afterCommit(Runnable work) {
                work.run();
            }
        };
    }

    List<String> keys() {
        List<String> out = new ArrayList<>();
        seen.stream().filter(i -> CapturedInput.MESSAGE_DELIVERED.equals(i.type()))
                .forEach(i -> out.add(i.payload().get("messageName") + "=" + i.payload().get("correlationKey")));
        return out;
    }

    @Test
    void theRestAdaptersBusinessKeyIsTheCapturedCorrelationKey() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:capkey" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform");
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true"); // empty in-memory database owned by this test
        s.setJobExecutorActivate(false);
        s.enableReplayCapture((CaptureListener) seen::add, false);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            ProcessEngine engine = a.processEngine();
            a.deploy("t1", "waitMsg", Map.of("waitMsg.bpmn", BPMN.getBytes(StandardCharsets.UTF_8)));
            OperatonProcessAdapter rest = new OperatonProcessAdapter(engine.getRuntimeService(),
                    engine.getRepositoryService(), engine.getHistoryService(), engine.getExternalTaskService(), tx(ds),
                    audit(), new RestProperties(), new OperatonOperationIds(engine.getRuntimeService(),
                            engine.getHistoryService(), engine.getExternalTaskService()));

            String byKey = a.startProcess("t1", "waitMsg", "bk-rest", Map.of());
            ProcessPort.MessageResult r = rest.correlate(CTX, new ProcessPort.MessageRequest("go", "bk-rest", null, null,
                    Map.of("approved", true), false));
            assertEquals(List.of(byKey), r.processInstanceIds());
            assertEquals(List.of("go=bk-rest"), keys(), "the key the REST adapter was called with");
            seen.clear();

            a.startProcess("t1", "waitMsg", "bk-all", Map.of());
            rest.correlate(CTX, new ProcessPort.MessageRequest("go", "bk-all", null, null, null, true));
            assertEquals(List.of("go=bk-all"), keys(), "correlate-all hands it over too");
            seen.clear();

            a.startProcess("t1", "waitMsg", "bk-unused", Map.of("colour", "red"));
            rest.correlate(CTX, new ProcessPort.MessageRequest("go", null, null, Map.of("colour", "red"), null, false));
            assertEquals(List.of("go=null"), keys(), "called without a business key: nothing to hand over");
            seen.clear();

            a.startProcess("t1", "waitMsg", "bk-direct", Map.of());
            engine.getRuntimeService().createMessageCorrelation("go").tenantId("t1")
                    .processInstanceBusinessKey("bk-direct").correlate();
            assertEquals(List.of("go=null"), keys(), "not through an adapter: the key stays null");
        }
    }
}