// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.ports.adhoc.AdHocSubProcessService;
import org.sangam.ports.dynamic.DynamicInjectionService;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DynamicPort;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.support.TypedVariables;

/**
 * {@link DynamicPort} on sangam-flowable-ports: ad-hoc sub-process (FLOWABLE-INTO-OPERATON FC-01, {@link AdHocSubProcessService}) and dynamic
 * injection (FLOWABLE-INTO-OPERATON FC-15, {@link DynamicInjectionService}: derived model variant, deployed, single-instance migration).
 * The tenant check is the process-instance read of {@link ProcessPort#get} (404 for another tenant).
 */
public final class PortsDynamicBridge implements DynamicPort {

    private final ProcessEngine engine;
    private final AdHocSubProcessService adHoc;
    private final DynamicInjectionService injection;
    private final ProcessPort processes;
    private final TxSupport tx;
    private final AuditPort audit;

    public PortsDynamicBridge(ProcessEngine engine, AdHocSubProcessService adHoc, DynamicInjectionService injection,
                              ProcessPort processes, TxSupport tx, AuditPort audit) {
        this.engine = engine;
        this.adHoc = adHoc;
        this.injection = injection;
        this.processes = processes;
        this.tx = tx;
        this.audit = audit;
    }

    @Override
    public AdHocState adHocState(RequestContext ctx, String processInstanceId, String adHocActivityId) {
        processes.get(ctx, processInstanceId);
        return state(processInstanceId, adHocActivityId);
    }

    @Override
    public AdHocState startAdHocActivity(RequestContext ctx, String processInstanceId, String adHocActivityId,
                                         StartAdHocActivityRequest request) {
        if (request == null || request.activityId() == null || request.activityId().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "activityId is required");
        }
        processes.get(ctx, processInstanceId);
        AdHocSubProcessService.AdHocInstance instance = instance(processInstanceId, adHocActivityId);
        boolean enabled = PortsErrors.call(() -> adHoc.getEnabledActivities(processInstanceId)).stream()
                .anyMatch(a -> a.adHocActivityInstanceId().equals(instance.activityInstanceId())
                        && a.activityId().equals(request.activityId()));
        if (!enabled) {
            throw ApiProblem.conflict("ACTIVITY_NOT_ENABLED",
                    "Activity " + request.activityId() + " cannot be started in ad-hoc sub-process " + adHocActivityId + " now");
        }
        tx.runInTx(() -> {
            Map<String, Object> vars = TypedVariables.fromJson(request.variables());
            if (!vars.isEmpty()) {
                PortsErrors.run(() -> engine.getRuntimeService().setVariables(processInstanceId, vars));
            }
            PortsErrors.run(() -> adHoc.executeActivity(processInstanceId, instance.activityInstanceId(), request.activityId()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ad_hoc_activity_id", adHocActivityId);
            payload.put("activity_id", request.activityId());
            payload.put("reason", request.reason());
            audit.record(ctx, processInstanceId, "ADHOC_ACTIVITY_STARTED", payload);
        });
        return state(processInstanceId, adHocActivityId);
    }

    @Override
    public AdHocState completeAdHoc(RequestContext ctx, String processInstanceId, String adHocActivityId, String reason) {
        processes.get(ctx, processInstanceId);
        AdHocSubProcessService.AdHocInstance instance = instance(processInstanceId, adHocActivityId);
        tx.runInTx(() -> {
            PortsErrors.run(() -> adHoc.complete(processInstanceId, instance.activityInstanceId(), false));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ad_hoc_activity_id", adHocActivityId);
            payload.put("reason", reason);
            audit.record(ctx, processInstanceId, "ADHOC_SUBPROCESS_COMPLETED", payload);
        });
        boolean stillThere = PortsErrors.call(() -> adHoc.getAdHocInstances(processInstanceId)).stream()
                .anyMatch(i -> i.activityId().equals(adHocActivityId));
        if (!stillThere) {
            return new AdHocState(processInstanceId, null, adHocActivityId, List.of(), List.of(), false);
        }
        return state(processInstanceId, adHocActivityId);
    }

    @Override
    public InjectionResult inject(RequestContext ctx, String processInstanceId, InjectionRequest request) {
        if (request == null || request.kind() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "kind is required: USER_TASK | EMBEDDED_SUBPROCESS | PARALLEL_USER_TASK");
        }
        PortsErrors.requireReason(request.reason());
        processes.get(ctx, processInstanceId);
        DynamicInjectionService.Injection result = tx.inTx(() -> {
            DynamicInjectionService.Injection inj = switch (request.kind()) {
                case "USER_TASK", "PARALLEL_USER_TASK" -> {
                    if (request.name() == null || request.name().isBlank()) {
                        throw ApiProblem.badRequest("VALIDATION_FAILED", "name is required for a user task");
                    }
                    List<String> groups = request.candidateGroup() == null ? List.of() : List.of(request.candidateGroup());
                    yield PortsErrors.call(() -> injection.injectUserTask(processInstanceId,
                            new DynamicInjectionService.UserTaskSpec(request.name(), request.assignee(), groups,
                                    request.formKey(), null), ctx.principalId()));
                }
                case "EMBEDDED_SUBPROCESS" -> {
                    if (request.subProcessBpmn() == null || request.subProcessBpmn().isBlank()) {
                        throw ApiProblem.badRequest("VALIDATION_FAILED", "subProcessBpmn is required for an embedded sub-process");
                    }
                    yield PortsErrors.call(() -> injection.injectEmbeddedSubProcess(processInstanceId,
                            request.name() == null ? "injected" : request.name(),
                            request.subProcessBpmn().getBytes(StandardCharsets.UTF_8), ctx.principalId()));
                }
                default -> throw ApiProblem.badRequest("VALIDATION_FAILED", "unknown kind " + request.kind());
            };
            Map<String, Object> vars = TypedVariables.fromJson(request.variables());
            if (!vars.isEmpty()) {
                PortsErrors.run(() -> engine.getRuntimeService().setVariables(processInstanceId, vars));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kind", request.kind());
            payload.put("injection_id", inj.id());
            payload.put("derived_definition_id", inj.derivedDefinitionId());
            payload.put("injected_activity_id", inj.injectedActivityId());
            payload.put("reason", request.reason());
            audit.record(ctx, processInstanceId, "INSTANCE_MODIFIED", payload);
            return inj;
        });
        return new InjectionResult(processInstanceId, result.derivedDefinitionId(), null,
                List.of(result.injectedActivityId()), "INJECTED");
    }

    private AdHocSubProcessService.AdHocInstance instance(String processInstanceId, String adHocActivityId) {
        return PortsErrors.call(() -> adHoc.getAdHocInstances(processInstanceId)).stream()
                .filter(i -> i.activityId().equals(adHocActivityId))
                .findFirst()
                .orElseThrow(() -> ApiProblem.notFound("ad-hoc sub-process", adHocActivityId));
    }

    private AdHocState state(String processInstanceId, String adHocActivityId) {
        AdHocSubProcessService.AdHocInstance instance = instance(processInstanceId, adHocActivityId);
        List<AdHocActivity> enabled = new ArrayList<>();
        for (AdHocSubProcessService.EnabledActivity a : PortsErrors.call(() -> adHoc.getEnabledActivities(processInstanceId))) {
            if (a.adHocActivityInstanceId().equals(instance.activityInstanceId())) {
                enabled.add(new AdHocActivity(a.activityId(), a.name(), a.type(), true));
            }
        }
        return new AdHocState(processInstanceId, instance.activityInstanceId(), adHocActivityId, enabled,
                instance.activeChildActivityIds(), true);
    }
}
