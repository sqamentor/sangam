// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.pipeline;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.integration.connector.Json;
import org.sangam.integration.inbox.ChannelBinding;
import org.sangam.integration.inbox.InboundMessage;
import org.sangam.integration.inbox.InboxHandler;
import org.sangam.integration.inbox.InboxService;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Delivers a finished BPMN-started run to its waiting instance through the inbox (buffered if the instance is not
 * waiting yet, D-33), and offers the DMN port on the engine.
 */
public final class PipelineMessages implements InboxHandler {

    public static final String MESSAGE = "sangam.pipelineRunCompleted";
    private static final String VARIABLE = "pipelineRunId";

    private final ProcessEngine engine;

    public PipelineMessages(ProcessEngine engine) {
        this.engine = engine;
    }

    public static void wire(ProcessEngine engine, InboxService inbox, PipelineRunner runner) {
        inbox.registerHandler(MESSAGE, new PipelineMessages(engine));
        runner.onCompletion(run -> {
            if (!"BPMN".equals(run.trigger())) {
                return;
            }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("pipelineState", run.state());
            v.put("pipelineRecordsOut", run.recordsOut());
            v.put("pipelineRejected", run.rejected());
            v.put("pipelineError", run.error());
            // the epoch suffix lets a restarted run deliver its later result
            String id = MESSAGE + ":" + run.runId() + ":" + run.epoch();
            inbox.receive(ChannelBinding.of("sangam:pipeline", run.tenantId(), "system:integration"),
                    new InboundMessage(id, MESSAGE, run.runId(), run.tenantId(), Json.write(v)));
        });
    }

    /** DMN through the engine; tenant {@code default} means a decision deployed without a tenant. */
    public static PipelineResources.DecisionPort decisions(ProcessEngine engine) {
        return (tenant, key, vars) -> {
            var b = engine.getDecisionService().evaluateDecisionByKey(key).variables(vars);
            b = tenant == null || "default".equals(tenant) ? b.decisionDefinitionWithoutTenantId() : b.decisionDefinitionTenantId(tenant);
            return b.evaluate().getFirstResult();
        };
    }

    @Override
    public Disposition handle(Connection c, Envelope m) {
        var q = engine.getRuntimeService().createExecutionQuery().messageEventSubscriptionName(MESSAGE)
                .processVariableValueEquals(VARIABLE, m.correlationKey());
        boolean noTenant = "default".equals(m.tenantId());
        q = noTenant ? q.withoutTenantId() : q.tenantIdIn(m.tenantId());
        if (q.count() == 0) {
            return Disposition.NO_SUBSCRIPTION;
        }
        var b = engine.getRuntimeService().createMessageCorrelation(MESSAGE).processInstanceVariableEquals(VARIABLE, m.correlationKey())
                .setVariables(Json.readMap(m.dataJson()));
        b = noTenant ? b.withoutTenantId() : b.tenantId(m.tenantId());
        return b.correlateAllWithResult().isEmpty() ? Disposition.NO_SUBSCRIPTION : Disposition.PROCESSED;
    }
}
