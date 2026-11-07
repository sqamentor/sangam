// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.pipeline;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.impl.context.Context;
import org.sangam.core.engine.OperationIdentity;
import org.sangam.integration.connector.Json;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * A pipeline as a step of a process (N11). The delegate only queues the run, in the engine transaction, so no data work
 * holds engine locks (VI.1 rule 5). It sets {@code pipelineRunId}; the model then waits on message
 * {@value PipelineMessages#MESSAGE}, correlated on that variable, which carries {@code pipelineState},
 * {@code pipelineRecordsOut}, {@code pipelineRejected} and {@code pipelineError}.
 *
 * <p>The run id is the T35 identity of the step, so a job retry cannot start a second run. Fields: {@code pipeline},
 * {@code parameters} (Map or JSON). Call {@link #install} at start-up.
 */
public final class PipelineDelegate implements JavaDelegate {

    private static volatile PipelineStore store;
    private static volatile BiFunction<String, String, PipelineDefinition> definitions;

    private Expression pipeline;
    private Expression parameters;

    public static void install(PipelineStore s, BiFunction<String, String, PipelineDefinition> d) {
        store = s;
        definitions = d;
    }

    public void setPipeline(Expression pipeline) {
        this.pipeline = pipeline;
    }

    public void setParameters(Expression parameters) {
        this.parameters = parameters;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(DelegateExecution ex) {
        if (store == null) {
            throw new IllegalStateException("PipelineDelegate is not installed (sangam-server wiring)");
        }
        String tenant = ex.getTenantId() == null ? "default" : ex.getTenantId();
        String id = String.valueOf(pipeline.getValue(ex));
        Object p = parameters == null ? null : parameters.getValue(ex);
        Map<String, Object> params = p instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m)
                : p instanceof String s ? Json.readMap(s) : new LinkedHashMap<>();
        long visit = ex.getProcessEngineServices().getHistoryService().createHistoricActivityInstanceQuery()
                .processInstanceId(ex.getProcessInstanceId()).activityId(ex.getCurrentActivityId()).finished().count();
        Object loop = ex.getVariable("loopCounter");
        String bk = ex.getProcessBusinessKey() == null ? ex.getProcessInstanceId() : ex.getProcessBusinessKey();
        String runId = OperationIdentity.of(tenant, bk, ex.getCurrentActivityId(),
                OperationIdentity.scope(visit, loop instanceof Number n ? n.intValue() : null, null)).toString();
        Connection c = Context.getCommandContext().getDbSqlSession().getSqlSession().getConnection();
        store.enqueue(c, runId, tenant, definitions.apply(tenant, id), params, "BPMN");
        ex.setVariable("pipelineRunId", runId);
    }
}
