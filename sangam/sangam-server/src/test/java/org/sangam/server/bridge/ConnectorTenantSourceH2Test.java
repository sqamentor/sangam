// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.runtime.ProcessInstanceWithVariables;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.tenant.TenantResolutionException;
import org.sangam.integration.connector.ConnectorRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * DEC-145 (owner decision 145), connector selection on the real server (h2 profile): the tenant of
 * {@code SangamConnectorDelegate} and {@code SendEventDelegate} work is the execution's engine tenant, and nothing
 * else. The tasks run synchronously in the start command, so no job guard stands in front of the delegates.
 * <ol>
 *   <li>the engine tenant's connector binding is used (the control);</li>
 *   <li>a {@code sangamTenantId} or {@code tenantId} variable naming another tenant is refused
 *       ({@code TENANT_CONTEXT_CONFLICT}) before any connector call, and before any outbox write; before the fix the
 *       {@code sangamTenantId} variable selected the other tenant's binding and outbox stream;</li>
 *   <li>work without an engine tenant fails closed ({@code TENANT_CONTEXT_MISSING}) instead of running under the
 *       configured default tenant.</li>
 * </ol>
 */
@SpringBootTest(properties = {"sangam.engine.allow-multiple-engines-per-jvm=true",
        "operaton.bpm.job-execution.enabled=false"})
@ActiveProfiles("h2")
@DirtiesContext
class ConnectorTenantSourceH2Test {

    static final String TENANT = "dev";
    static final String OTHER = "dec145-other";
    static final String CONNECTOR = "dec145-echo";

    static String connectorBpmn(String key) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="dec145">
                  <process id="%s" isExecutable="true" operaton:historyTimeToLive="30">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="call"/>
                    <serviceTask id="call" operaton:class="org.sangam.integration.bridge.operaton.SangamConnectorDelegate">
                      <extensionElements>
                        <operaton:field name="connector" stringValue="%s"/>
                        <operaton:field name="operation" stringValue="send"/>
                        <operaton:field name="resultVariable" stringValue="echoResult"/>
                      </extensionElements>
                    </serviceTask>
                    <sequenceFlow id="f2" sourceRef="call" targetRef="wait"/>
                    <userTask id="wait"/>
                    <sequenceFlow id="f3" sourceRef="wait" targetRef="e"/>
                    <endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(key, CONNECTOR);
    }

    static String eventBpmn(String key, String eventType) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="dec145">
                  <process id="%s" isExecutable="true" operaton:historyTimeToLive="30">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="send"/>
                    <serviceTask id="send" operaton:class="org.sangam.integration.bridge.operaton.SendEventDelegate">
                      <extensionElements>
                        <operaton:field name="eventType" stringValue="%s"/>
                        <!-- a fixed channel: OutboxWriter writes nothing for an event type without a subscriber -->
                        <operaton:field name="channel" stringValue="dec145-channel"/>
                      </extensionElements>
                    </serviceTask>
                    <sequenceFlow id="f2" sourceRef="send" targetRef="wait"/>
                    <userTask id="wait"/>
                    <sequenceFlow id="f3" sourceRef="wait" targetRef="e"/>
                    <endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(key, eventType);
    }

    /** A connector that records the tenant of every request it gets. */
    static final class Recording implements SangamConnector {
        final String name;
        final List<String> tenants = new CopyOnWriteArrayList<>();

        Recording(String name) {
            this.name = name;
        }

        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor(CONNECTOR, "test", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, List.of("send"),
                    ConnectorProfile.LIVE);
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest request) {
            tenants.add(request.tenantId());
            return ConnectorResult.Success.of(Map.of("by", name), name + "-" + request.operationId());
        }
    }

    @Autowired
    ProcessEngine engine;
    @Autowired
    DataSource dataSource;
    @Autowired
    ConnectorRegistry registry;

    final Recording platform = new Recording("platform");
    final Recording dev = new Recording("dev");
    final Recording other = new Recording("other");

    @BeforeEach
    void bindings() {
        registry.register(platform);
        registry.register(TENANT, dev);
        registry.register(OTHER, other);
    }

    static String key(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    String deploy(String tenant, String key, String bpmn) {
        var d = engine.getRepositoryService().createDeployment().name(key).addString(key + ".bpmn", bpmn);
        if (tenant != null) {
            d.tenantId(tenant);
        }
        d.deploy();
        return key;
    }

    ProcessInstanceWithVariables start(String tenant, String key, Map<String, Object> vars) {
        var b = engine.getRuntimeService().createProcessInstanceByKey(key).setVariables(vars);
        if (tenant != null) {
            b.processDefinitionTenantId(tenant);
        }
        return b.executeWithVariablesInReturn();
    }

    /** The tenant refusal anywhere in the cause chain of what {@code run} throws. */
    static TenantResolutionException refusal(Runnable run) {
        RuntimeException e = assertThrows(RuntimeException.class, run::run);
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TenantResolutionException r) {
                return r;
            }
        }
        fail("not a tenant refusal: " + e);
        return null;
    }

    long outbox(String eventType) {
        return Transactions.of(dataSource).inTransaction((Connection c) -> Jdbc.one(c,
                "SELECT COUNT(*) FROM OUTBOX WHERE event_type=?", rs -> rs.getLong(1), eventType).orElse(0L));
    }

    long outbox(String eventType, String tenant) {
        return Transactions.of(dataSource).inTransaction((Connection c) -> Jdbc.one(c,
                "SELECT COUNT(*) FROM OUTBOX WHERE event_type=? AND tenant_id=?", rs -> rs.getLong(1), eventType,
                tenant).orElse(0L));
    }

    String deployConnector(String tenant, String prefix) {
        String key = key(prefix);
        return deploy(tenant, key, connectorBpmn(key));
    }

    @Test
    void theEngineTenantSelectsItsOwnBinding() {
        String key = deployConnector(TENANT, "dec145a");
        ProcessInstanceWithVariables pi = start(TENANT, key, Map.of(
                "sangamTenantId", TENANT, "tenantId", TENANT));   // agreeing claims are accepted
        assertNotNull(pi.getVariables().get("echoResult"), "the connector ran");
        assertEquals(List.of(TENANT), dev.tenants, "the engine tenant binding was called once, with its tenant");
        assertEquals(List.of(), other.tenants);
        assertEquals(List.of(), platform.tenants);
    }

    @Test
    void aForgedSangamTenantIdVariableIsRefusedBeforeTheConnectorCall() {
        String key = deployConnector(TENANT, "dec145b");
        TenantResolutionException r = refusal(() -> start(TENANT, key, Map.of("sangamTenantId", OTHER)));
        assertEquals("TENANT_CONTEXT_CONFLICT", r.code(), r.getMessage());
        assertEquals(List.of(), other.tenants, "the other tenant binding is never called");
        assertEquals(List.of(), dev.tenants, "nothing is called at all");
        assertEquals(List.of(), platform.tenants);
    }

    @Test
    void aForgedTenantIdVariableIsRefusedBeforeTheConnectorCall() {
        String key = deployConnector(TENANT, "dec145c");
        TenantResolutionException r = refusal(() -> start(TENANT, key, Map.of("tenantId", OTHER)));
        assertEquals("TENANT_CONTEXT_CONFLICT", r.code(), r.getMessage());
        assertEquals(List.of(), dev.tenants, "nothing is called at all");
        assertEquals(List.of(), other.tenants);
        assertEquals(List.of(), platform.tenants);
    }

    @Test
    void connectorWorkWithoutAnEngineTenantFailsClosed() {
        String key = deployConnector(null, "dec145d");
        TenantResolutionException r = refusal(() -> start(null, key, Map.of()));
        assertEquals("TENANT_CONTEXT_MISSING", r.code(), r.getMessage());
        assertEquals(List.of(), platform.tenants, "no call under the configured default tenant");
        assertEquals(List.of(), dev.tenants);
        assertEquals(List.of(), other.tenants);
    }

    @Test
    void sendEventWritesOnlyToTheEngineTenantOutboxStream() {
        String type = key("dec145.event.");
        String ok = key("dec145e");
        deploy(TENANT, ok, eventBpmn(ok, type));
        start(TENANT, ok, Map.of());
        assertEquals(1, outbox(type), "the control: one event");
        assertEquals(1L, outbox(type, TENANT), "in the engine tenant stream");
    }

    @Test
    void aForgedSendEventTenantIsRefusedBeforeTheOutboxWrite() {
        String type = key("dec145.forged.");
        String forged = key("dec145f");
        deploy(TENANT, forged, eventBpmn(forged, type));
        TenantResolutionException r = refusal(() -> start(TENANT, forged, Map.of("sangamTenantId", OTHER)));
        assertEquals("TENANT_CONTEXT_CONFLICT", r.code(), r.getMessage());
        assertEquals(0, outbox(type), "no outbox write for refused work");
    }

    @Test
    void sendEventWithoutAnEngineTenantFailsClosed() {
        String type = key("dec145.untenanted.");
        String none = key("dec145g");
        deploy(null, none, eventBpmn(none, type));
        TenantResolutionException r = refusal(() -> start(null, none, Map.of()));
        assertEquals("TENANT_CONTEXT_MISSING", r.code(), r.getMessage());
        assertEquals(0, outbox(type), "no outbox write under the configured default tenant");
    }
}
