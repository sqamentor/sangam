// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.bpmn;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.registry.asset.WorkOrder;
import org.sangam.registry.support.Maps;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Asset and maintenance steps for BPMN service tasks (field {@code op}):
 * <ul>
 *   <li>{@code GENERATE_DUE}: creates the due preventive work orders (timer start event, e.g. daily); fields
 *       {@code limit}; output {@code workOrderIds} (list) and {@code workOrderCount};</li>
 *   <li>{@code CREATE_WORK_ORDER}: field {@code request} names a map variable (assetId, workType, priority,
 *       description, reportedBy, genKey ...); output {@code workOrderId}, {@code workOrderNumber};</li>
 *   <li>{@code COMPLETE_WORK_ORDER}: fields {@code workOrderId} and {@code request} (findings, parts, evidence, cost,
 *       meterAtDone, downtime); the work order must be IN_PROGRESS;</li>
 *   <li>{@code RECORD_READING}: fields {@code assetId}, {@code meterType}, {@code reading}, {@code source};</li>
 *   <li>{@code MOVE}: fields {@code assetId}, {@code toLocation}, {@code toCustodian}, {@code reason}.</li>
 * </ul>
 * Field values may name process variables. Registry errors become BPMN errors {@code REGISTRY_<CODE>}.
 */
public class AssetDelegate implements JavaDelegate {

    private Expression op;
    private Expression assetId;
    private Expression workOrderId;
    private Expression meterType;
    private Expression reading;
    private Expression source;
    private Expression toLocation;
    private Expression toCustodian;
    private Expression reason;
    private Expression request;
    private Expression limit;
    private Expression resultVariable;

    @Override
    public void execute(DelegateExecution ex) {
        String operation = DelegateSupport.field(op, ex, "").toUpperCase();
        switch (operation) {
            case "GENERATE_DUE" -> {
                int max = Integer.parseInt(DelegateSupport.field(limit, ex, "100"));
                List<WorkOrder> created = DelegateSupport.run(ex, (c, ctx) ->
                        DelegateSupport.module().maintenance().generateDueWorkOrders(c, ctx, null, max));
                ex.setVariable("workOrderIds", new java.util.ArrayList<>(created.stream().map(WorkOrder::id).toList()));
                ex.setVariable("workOrderCount", created.size());
            }
            case "CREATE_WORK_ORDER" -> {
                Map<String, Object> req = requestMap(ex);
                String a = str(assetId, ex);
                if (a != null) {
                    req.putIfAbsent("assetId", a);
                }
                WorkOrder wo = DelegateSupport.run(ex, (c, ctx) -> DelegateSupport.module().maintenance().createWorkOrder(c, ctx, req));
                ex.setVariable(DelegateSupport.out(resultVariable, ex, "workOrderId"), wo.id());
                ex.setVariable("workOrderNumber", wo.number());
            }
            case "COMPLETE_WORK_ORDER" -> {
                String id = required(workOrderId, ex, "workOrderId");
                Map<String, Object> req = requestMap(ex);
                WorkOrder wo = DelegateSupport.run(ex, (c, ctx) ->
                        DelegateSupport.module().maintenance().complete(c, ctx, id, null, req));
                ex.setVariable("workOrderState", wo.state());
            }
            case "RECORD_READING" -> {
                String a = required(assetId, ex, "assetId");
                String mt = required(meterType, ex, "meterType");
                BigDecimal value = new BigDecimal(required(reading, ex, "reading"));
                String src = str(source, ex);
                DelegateSupport.run(ex, (c, ctx) -> DelegateSupport.module().maintenance()
                        .recordReading(c, ctx, a, mt, value, Instant.now(), src == null ? "PROCESS" : src, null));
            }
            case "MOVE" -> {
                String a = required(assetId, ex, "assetId");
                String loc = str(toLocation, ex);
                String cust = str(toCustodian, ex);
                String why = str(reason, ex);
                DelegateSupport.run(ex, (c, ctx) -> DelegateSupport.module().assets().move(c, ctx, a, loc, cust, null, why));
            }
            default -> throw new ProcessEngineException("AssetDelegate: unknown op '" + operation + "'");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requestMap(DelegateExecution ex) {
        Object v = DelegateSupport.valueOf(request, ex);
        if (v instanceof Map<?, ?> m) {
            return new java.util.LinkedHashMap<>((Map<String, Object>) m);
        }
        if (v instanceof String s && s.trim().startsWith("{")) {
            return org.sangam.registry.support.Json.map(s);
        }
        return Maps.of();
    }

    private static String str(Expression e, DelegateExecution ex) {
        Object v = DelegateSupport.valueOf(e, ex);
        return v == null || v.toString().isBlank() ? null : v.toString();
    }

    private static String required(Expression e, DelegateExecution ex, String name) {
        String v = str(e, ex);
        if (v == null) {
            throw new ProcessEngineException("AssetDelegate: field " + name + " is required");
        }
        return v;
    }
}
