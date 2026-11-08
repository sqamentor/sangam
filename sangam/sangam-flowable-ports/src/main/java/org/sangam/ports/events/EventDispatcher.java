// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.runtime.CaseInstance;
import org.operaton.bpm.engine.runtime.MessageCorrelationBuilder;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.core.layer.Inbox;
import org.sangam.core.ports.UniqueStartPort;
import org.sangam.ports.events.InboundEventProcessor.StoredEvent;
import org.sangam.ports.events.model.EventDefinition;
import org.sangam.ports.links.InstanceLinkService;
import org.sangam.ports.support.Json;
import org.sangam.ports.support.PortsProblem;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Delivers buffered inbound events to the engine (FLOWABLE-INTO-OPERATON FC-02 event-to-process/case start and correlation, FLOWABLE-INTO-OPERATON FC-04 unique
 * start (on WS-INTEGRATION's {@code UNIQUE_START}, business key = unique reference value), FLOWABLE-INTO-OPERATON D-33 early-message buffering, FLOWABLE-INTO-OPERATON D-35 branch addressing). It is an {@link Inbox.Handler} for sources with the
 * prefix {@code evt:}; the inbox loop must run its transaction as an engine command
 * ({@code EngineTx.transactions(engine)}), so the engine effect, the {@code PROCESSED} mark and the event-log row
 * commit together.
 *
 * <p>Rules, per event:
 * <ol>
 *   <li><b>Correlation.</b> Every waiting execution subscribed to the BPMN message named after the event key and
 *       matching all correlation parameters (process variables, or local variables for {@code scope=local}) and the
 *       business key receives the event ({@code correlateAll}); the payload becomes process variables.</li>
 *   <li><b>Start.</b> If a process definition has a message start event named after the event key, or the event
 *       definition names {@code startProcessKey} / {@code startCaseKey}, one instance is started. With
 *       {@code startUniqueReference} the reference value is inserted into WS-INTEGRATION's {@code UNIQUE_START} first; a second
 *       event with the same value is logged as {@code DUPLICATE_START} and starts nothing.</li>
 *   <li><b>No match.</b> The handler returns false; the inbox keeps the event {@code BUFFERED} until its TTL.</li>
 * </ol>
 */
public class EventDispatcher implements Inbox.Handler {

    public record Delivery(int correlated, String startedProcessInstanceId, String startedCaseInstanceId, boolean duplicateStart) {
        public boolean matched() {
            return correlated > 0 || startedProcessInstanceId != null || startedCaseInstanceId != null || duplicateStart;
        }
    }

    private final ProcessEngine engine;
    private final EventRegistry registry;
    private final EventLog log;
    private final InstanceLinkService links;
    private final UniqueStartPort uniqueStart;

    /**
     * @param uniqueStartOrNull WS-INTEGRATION's {@code UNIQUE_START} guard; required when an event definition uses
     *                          {@code startUniqueReference}
     */
    public EventDispatcher(ProcessEngine engine, EventRegistry registry, EventLog log, InstanceLinkService linksOrNull,
                           UniqueStartPort uniqueStartOrNull) {
        this.engine = engine;
        this.registry = registry;
        this.log = log;
        this.links = linksOrNull;
        this.uniqueStart = uniqueStartOrNull;
    }

    /** True for inbox rows this handler owns. */
    public static boolean owns(Inbox.Message m) {
        return m.source() != null && m.source().startsWith(InboundEventProcessor.SOURCE_PREFIX);
    }

    @Override
    public boolean handle(Connection c, Inbox.Message m) {
        StoredEvent ev = Json.read(m.payload(), StoredEvent.class);
        EventDefinition def = registry.event(ev.tenantId(), ev.eventKey()).map(EventRegistry.DeployedEvent::definition)
                .orElse(null);
        if (def == null) {
            log.log(c, ev.tenantId(), EventLog.Direction.DISPATCH, ev.channelKey(), ev.eventKey(), ev.messageId(), null,
                    "BUFFERED", "event definition not deployed", null);
            return false;
        }
        Delivery d = deliver(c, ev, def);
        log.log(c, ev.tenantId(), EventLog.Direction.DISPATCH, ev.channelKey(), ev.eventKey(), ev.messageId(), ev.businessKey(),
                d.matched() ? (d.duplicateStart() ? "DUPLICATE_START" : "DELIVERED") : "BUFFERED",
                "correlated=" + d.correlated() + " process=" + d.startedProcessInstanceId() + " case=" + d.startedCaseInstanceId(),
                null);
        return d.matched();
    }

    /** Delivers one event inside the caller's engine command. */
    public Delivery deliver(Connection c, StoredEvent ev, EventDefinition def) {
        RuntimeService rs = engine.getRuntimeService();
        String tenantId = engineTenant(ev.tenantId());
        Map<String, Object> vars = new LinkedHashMap<>();
        if (ev.payload() != null) {
            ev.payload().forEach((k, v) -> {
                if (!PortsProblem.isReservedVariable(k)) {      // FW-30
                    vars.put(k, retype(v, field(def.payload(), k)));
                }
            });
        }

        // 1. correlation to waiting executions
        Map<String, Object> corr = new LinkedHashMap<>();
        if (ev.correlation() != null) {
            ev.correlation().forEach((k, v) -> corr.put(k, retype(v, field(def.correlationParameters(), k))));
        }
        int matchers = ev.businessKey() != null ? 1 : 0;
        for (EventDefinition.Field f : def.correlationParameters()) {
            if (corr.get(f.name()) != null) {
                matchers++;
            }
        }
        List<String> correlatedInstances = new ArrayList<>();
        // FW-31: Flowable correlates an event without any correlation value to every subscription. Here an event of a
        // definition that declares correlation parameters, but carries none of them and no business key, is not
        // broadcast: it stays buffered (and expires) instead of advancing every waiting instance of the tenant.
        boolean broadcastGuard = matchers == 0 && !def.correlationParameters().isEmpty();
        if (!broadcastGuard) {
            // Waiting executions only. Operaton's correlateAll also fires matching message start events; the start is
            // step 2's decision (unique-start claim first, one instance), so correlateAll here started a second
            // instance for every message-start event and bypassed UNIQUE_START.
            var q = rs.createExecutionQuery().messageEventSubscriptionName(ev.eventKey());
            if (tenantId == null) {
                q.withoutTenantId();
            } else {
                q.tenantIdIn(tenantId);
            }
            if (ev.businessKey() != null) {
                // FW-03: the process-instance business key, for executions at any depth (Flowable's one-argument form
                // compared the child execution's own key and missed parallel branches)
                q.processInstanceBusinessKey(ev.businessKey());
            }
            for (EventDefinition.Field f : def.correlationParameters()) {
                Object v = corr.get(f.name());
                if (v == null) {
                    continue;
                }
                if (f.local()) {
                    q.variableValueEquals(f.name(), v);
                } else {
                    q.processVariableValueEquals(f.name(), v);
                }
            }
            for (var execution : q.list()) {
                rs.messageEventReceived(ev.eventKey(), execution.getId(), vars);
                correlatedInstances.add(execution.getProcessInstanceId());
            }
        }
        int correlated = correlatedInstances.size();

        // 2. start
        String startedPi = null;
        String startedCase = null;
        boolean duplicate = false;
        String startDefinitionKey = startProcessDefinitionKey(ev);
        boolean wantsStart = startDefinitionKey != null || def.startProcessKey() != null || def.startCaseKey() != null;
        if (wantsStart) {
            String uniqueKey = startDefinitionKey != null ? startDefinitionKey
                    : def.startProcessKey() != null ? def.startProcessKey() : "case:" + def.startCaseKey();
            Object refValue = def.startUniqueReference() == null ? null : corr.get(def.startUniqueReference());
            if (def.startUniqueReference() != null && (refValue == null || String.valueOf(refValue).isBlank())) {
                // FW-32: never claim the literal "null" (every later event without a value would be a silent duplicate)
                throw new PortsProblem("INVALID_INPUT", 422, "event " + ev.eventKey() + " needs a value for its unique start reference '"
                        + def.startUniqueReference() + "'");
            }
            String ref = refValue == null ? null : String.valueOf(refValue);
            if (ref != null && uniqueStart == null) {
                throw new IllegalStateException("event " + ev.eventKey() + " needs unique start but no UniqueStartPort is bound");
            }
            if (ref != null && !uniqueStart.claim(c, tenantId, uniqueKey, ref, InboundEventProcessor.SOURCE_PREFIX + ev.channelKey(),
                    ev.messageId())) {
                duplicate = true;
            } else {
                Map<String, Object> startVars = new LinkedHashMap<>(vars);
                corr.forEach(startVars::putIfAbsent);
                if (startDefinitionKey != null) {
                    MessageCorrelationBuilder sb = rs.createMessageCorrelation(ev.eventKey()).setVariables(startVars);
                    tenant(sb, tenantId);
                    if (ev.businessKey() != null) {
                        sb.processInstanceBusinessKey(ev.businessKey());
                    }
                    ProcessInstance pi = sb.correlateStartMessage();
                    startedPi = pi.getId();
                } else if (def.startProcessKey() != null) {
                    var pb = rs.createProcessInstanceByKey(def.startProcessKey()).businessKey(ev.businessKey()).setVariables(startVars);
                    if (tenantId != null) {
                        pb.processDefinitionTenantId(tenantId);
                    } else {
                        pb.processDefinitionWithoutTenantId();
                    }
                    startedPi = pb.execute().getId();
                } else {
                    var cb = engine.getCaseService().withCaseDefinitionByKey(def.startCaseKey()).businessKey(ev.businessKey())
                            .setVariables(startVars);
                    if (tenantId != null) {
                        cb.caseDefinitionTenantId(tenantId);
                    } else {
                        cb.caseDefinitionWithoutTenantId();
                    }
                    CaseInstance ci = cb.create();
                    startedCase = ci.getId();
                }
                if (ref != null) {
                    uniqueStart.bind(c, tenantId, uniqueKey, ref, startedPi != null ? startedPi : startedCase);
                }
            }
        }
        if (links != null) {
            for (String t : correlatedInstances) {
                links.link(c, ev.tenantId(), "event-delivery", null, "event", ev.messageId(), "process", t, null);
            }
        }
        return new Delivery(correlated, startedPi, startedCase, duplicate);
    }

    private static EventDefinition.Field field(List<EventDefinition.Field> fields, String name) {
        for (EventDefinition.Field f : fields) {
            if (name.equals(f.name())) {
                return f;
            }
        }
        return null;
    }

    /**
     * Restores the declared type of a payload/correlation value. The pipeline coerces values at intake, but the event
     * then travels through the inbox as JSON, which loses Java types: a {@code long} below 2^31 came back as
     * {@code Integer} and a {@code date} as epoch millis. The process variable type then depended on the magnitude,
     * and variable-equality matching compares typed values, so a {@code long} correlation parameter never matched a
     * {@code Long} process variable. Undeclared fields keep their JSON type.
     */
    static Object retype(Object v, EventDefinition.Field f) {
        if (v == null || f == null || f.type() == null) {
            return v;
        }
        if ("json".equalsIgnoreCase(f.type()) && v instanceof String) {
            return v;                                        // already JSON text; re-writing it would double-encode
        }
        if ("date".equalsIgnoreCase(f.type())) {
            if (v instanceof java.util.Date) {
                return v;
            }
            if (v instanceof Number n) {
                return new java.util.Date(n.longValue());
            }
        }
        return org.sangam.ports.events.pipeline.InboundEventPipeline.coerce(Json.MAPPER.valueToTree(v), f.type(), f.name());
    }

    /** Process definition key whose latest version has a message start event named after the event key. */
    private String startProcessDefinitionKey(StoredEvent ev) {
        var q = engine.getRepositoryService().createProcessDefinitionQuery().messageEventSubscriptionName(ev.eventKey()).latestVersion();
        String tenantId = engineTenant(ev.tenantId());
        if (tenantId != null) {
            q.tenantIdIn(tenantId);
        } else {
            q.withoutTenantId();
        }
        List<ProcessDefinition> defs = q.list();
        return defs.isEmpty() ? null : defs.get(0).getKey();
    }

    /**
     * FW-29: the registry stores the default tenant as {@code ""}, the engine as {@code null}. Without this mapping an
     * event of the default tenant would never match ({@code tenantId("")} selects nothing).
     */
    static String engineTenant(String tenant) {
        return tenant == null || tenant.isBlank() ? null : tenant;
    }

    private static void tenant(MessageCorrelationBuilder b, String tenant) {
        if (tenant == null) {
            b.withoutTenantId();
        } else {
            b.tenantId(tenant);
        }
    }
}
