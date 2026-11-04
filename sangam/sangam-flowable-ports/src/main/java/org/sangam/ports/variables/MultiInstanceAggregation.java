// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.variables;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.pvm.process.ScopeImpl;
import org.operaton.bpm.engine.impl.util.xml.Element;
import org.operaton.bpm.engine.variable.Variables;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.ports.bpmn.SangamBpmn;
import org.sangam.ports.support.EngineTx;
import org.sangam.ports.support.Json;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-instance variable aggregation (FC-21, Flowable {@code VariableAggregationDefinition}). On a multi-instance
 * activity element:
 * <pre>{@code <userTask id="review" sangam:aggregateTo="reviews" sangam:aggregateVariables="decision,comment">
 *   <multiInstanceLoopCharacteristics .../></userTask>}</pre>
 * When each item ends, the named variables (local first) are written with the {@code loopCounter} to
 * {@code MI_AGGREGATE} in the same transaction; no shared parent variable is touched per item, so parallel items do
 * not collide on one variable row (no OLE storm, D-23). When the multi-instance body ends, the rows are assembled in
 * loop order into a JSON array {@code [{"loopCounter":0,"decision":..},..]}, stored as variable {@code aggregateTo}
 * (a string holding JSON; large values follow the FC-29 rule of WS-ENGINE) and the rows are deleted.
 */
public class MultiInstanceAggregation implements BpmnParseListener {

    private static final String MI_BODY_SUFFIX = "#multiInstanceBody";

    private final Dialect dialect;

    public MultiInstanceAggregation(Dialect dialect) {
        this.dialect = dialect;
    }

    @Override
    public void parseUserTask(Element e, ScopeImpl scope, ActivityImpl a) {
        attach(e, a);
    }

    @Override
    public void parseServiceTask(Element e, ScopeImpl scope, ActivityImpl a) {
        attach(e, a);
    }

    @Override
    public void parseSubProcess(Element e, ScopeImpl scope, ActivityImpl a) {
        attach(e, a);
    }

    @Override
    public void parseCallActivity(Element e, ScopeImpl scope, ActivityImpl a) {
        attach(e, a);
    }

    @Override
    public void parseTask(Element e, ScopeImpl scope, ActivityImpl a) {
        attach(e, a);
    }

    @Override
    public void parseBusinessRuleTask(Element e, ScopeImpl scope, ActivityImpl a) {
        attach(e, a);
    }

    @Override
    public void parseReceiveTask(Element e, ScopeImpl scope, ActivityImpl a) {
        attach(e, a);
    }

    @Override
    public void parseSendTask(Element e, ScopeImpl scope, ActivityImpl a) {
        attach(e, a);
    }

    private void attach(Element e, ActivityImpl inner) {
        String target = SangamBpmn.attr(e, SangamBpmn.AGGREGATE_TO);
        if (target == null) {
            return;
        }
        String vars = SangamBpmn.attr(e, SangamBpmn.AGGREGATE_VARIABLES);
        if (vars == null) {
            throw new IllegalArgumentException("activity " + inner.getId() + ": sangam:aggregateTo needs sangam:aggregateVariables");
        }
        if (!(inner.getFlowScope() instanceof ActivityImpl body) || !body.getId().endsWith(MI_BODY_SUFFIX)) {
            throw new IllegalArgumentException("activity " + inner.getId() + ": sangam:aggregateTo is only valid on a multi-instance activity");
        }
        List<String> names = Arrays.stream(vars.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        inner.addBuiltInListener(ExecutionListener.EVENTNAME_END, (ExecutionListener) ex -> collect(ex, names));
        body.addBuiltInListener(ExecutionListener.EVENTNAME_END, (ExecutionListener) ex -> assemble(ex, target));
    }

    void collect(DelegateExecution ex, List<String> names) {
        Object lc = ex.getVariable("loopCounter");
        if (!(lc instanceof Integer loop)) {
            return;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("loopCounter", loop);
        for (String n : names) {
            Object v = ex.hasVariableLocal(n) ? ex.getVariableLocal(n) : ex.getVariable(n);
            item.put(n, v instanceof java.util.Date d ? d.toInstant().toString() : v);
        }
        String json = Json.write(item);
        String body = ex.getParentActivityInstanceId();
        Connection c = EngineTx.connection();
        int n = Jdbc.update(c, "UPDATE MI_AGGREGATE SET payload = ? WHERE process_instance_id = ? AND mi_body_instance_id = ? AND loop_counter = ?",
                json, ex.getProcessInstanceId(), body, loop);
        if (n == 0) {
            Jdbc.update(c, "INSERT INTO MI_AGGREGATE(tenant_id, process_instance_id, mi_body_instance_id, loop_counter, payload, created_at)"
                    + " VALUES (?,?,?,?,?," + dialect.now() + ")", ex.getTenantId() == null ? "" : ex.getTenantId(),
                    ex.getProcessInstanceId(), body, loop, json);
        }
    }

    void assemble(DelegateExecution ex, String target) {
        Connection c = EngineTx.connection();
        String body = ex.getActivityInstanceId();
        List<Object> items = new ArrayList<>(Jdbc.query(c, "SELECT payload FROM MI_AGGREGATE WHERE process_instance_id = ? AND mi_body_instance_id = ?"
                + " ORDER BY loop_counter", rs -> Json.tree(rs.getString(1)), ex.getProcessInstanceId(), body));
        Jdbc.update(c, "DELETE FROM MI_AGGREGATE WHERE process_instance_id = ? AND mi_body_instance_id = ?", ex.getProcessInstanceId(), body);
        ex.setVariable(target, Variables.stringValue(Json.write(items)));
    }
}
