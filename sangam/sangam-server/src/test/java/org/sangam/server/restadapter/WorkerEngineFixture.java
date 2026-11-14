// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import org.sangam.rest.config.SpringTxSupport;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.sangam.core.layer.Dialect;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.rest.adapter.jdbc.JdbcAuditAdapter;
import org.sangam.rest.adapter.operaton.EngineAdapterWorkAdapter;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The real Sangam engine adapter on H2 (PostgreSQL mode) with the REST worker adapter over it, and one process with an
 * external task on the topic {@value #TOPIC} (and one on {@value #OTHER_TOPIC}) deployed for two tenants.
 */
final class WorkerEngineFixture implements AutoCloseable {

    static final String TOPIC = "shared";
    static final String OTHER_TOPIC = "other";
    static final String KEY = "sl157";
    static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="sl157">
              <process id="sl157" isExecutable="true" operaton:historyTimeToLive="1">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="split"/>
                <parallelGateway id="split"/>
                <sequenceFlow id="f2" sourceRef="split" targetRef="work"/>
                <sequenceFlow id="f3" sourceRef="split" targetRef="side"/>
                <serviceTask id="work" operaton:type="external" operaton:topic="shared"/>
                <serviceTask id="side" operaton:type="external" operaton:topic="other"/>
                <sequenceFlow id="f4" sourceRef="work" targetRef="join"/>
                <sequenceFlow id="f5" sourceRef="side" targetRef="join"/>
                <parallelGateway id="join"/>
                <sequenceFlow id="f6" sourceRef="join" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;

    final OperatonEngineAdapter engine;
    final TxSupport tx;
    final EngineAdapterWorkAdapter work;

    WorkerEngineFixture(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required by the engine, the server's default
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        engine = SangamEngineFactory.build(ds, s, "h2");
        tx = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), Dialect.POSTGRESQL);
        RestProperties props = new RestProperties();
        work = new EngineAdapterWorkAdapter(engine, tx, new JdbcAuditAdapter(tx, null, null, props), props);
        for (String tenant : new String[]{"tA", "tB"}) {
            engine.deploy(tenant, KEY + "-" + tenant, Map.of("sl157.bpmn", BPMN.getBytes(StandardCharsets.UTF_8)));
        }
    }

    String start(String tenant, String businessKey) {
        return engine.startProcess(tenant, KEY, businessKey, Map.of());
    }

    static RequestContext worker(String tenant, String principal) {
        return new RequestContext(tenant, principal, Set.of("sangam-worker"), null, null, null, null, null, "API_TOKEN");
    }

    @Override
    public void close() {
        engine.close();
    }
}
