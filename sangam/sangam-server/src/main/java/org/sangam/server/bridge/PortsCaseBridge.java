// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.sangam.ports.cmmn.CaseLifecycleService;
import org.sangam.ports.cmmn.CmmnEventListenerService;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.CasePort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.support.TypedVariables;

/**
 * {@link CasePort} with the CMMN extensions of sangam-flowable-ports: event listeners can {@code occur} (FC-55, via
 * {@link CmmnEventListenerService}), and {@code reactivate} / {@code cutOver} create a linked new case with the
 * history read (FC-59, {@link CaseLifecycleService}). Every other call is the default Operaton case adapter.
 */
public final class PortsCaseBridge implements CasePort {

    private final CasePort base;
    private final CaseLifecycleService lifecycle;
    private final CmmnEventListenerService eventListeners;
    private final TxSupport tx;
    private final AuditPort audit;

    public PortsCaseBridge(CasePort base, CaseLifecycleService lifecycle, CmmnEventListenerService eventListeners,
                           TxSupport tx, AuditPort audit) {
        this.base = base;
        this.lifecycle = lifecycle;
        this.eventListeners = eventListeners;
        this.tx = tx;
        this.audit = audit;
    }

    @Override
    public CaseInstance start(RequestContext ctx, StartCaseRequest request) {
        return base.start(ctx, request);
    }

    @Override
    public Page<CaseInstance> list(RequestContext ctx, String key, String businessKey, Boolean active, Page.PageRequest page) {
        return base.list(ctx, key, businessKey, active, page);
    }

    @Override
    public CaseInstance get(RequestContext ctx, String caseInstanceId) {
        return base.get(ctx, caseInstanceId);
    }

    @Override
    public CaseInstance executionCommand(RequestContext ctx, String caseInstanceId, String executionId,
                                         ExecutionCommand command) {
        if (command != null && "occur".equalsIgnoreCase(command.action())) {
            base.get(ctx, caseInstanceId);
            boolean listener = PortsErrors.call(() -> eventListeners.availableEventListeners(caseInstanceId)).stream()
                    .anyMatch(e -> e.getId().equals(executionId));
            if (!listener) {
                throw ApiProblem.conflict("EVENT_LISTENER_NOT_AVAILABLE",
                        "Case execution " + executionId + " is not an available event listener");
            }
            tx.runInTx(() -> {
                PortsErrors.run(() -> eventListeners.occur(executionId));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("execution_id", executionId);
                payload.put("reason", command.reason());
                audit.record(ctx, caseInstanceId, "CASE_EVENT_OCCURRED", payload);
            });
            return base.get(ctx, caseInstanceId);
        }
        return base.executionCommand(ctx, caseInstanceId, executionId, command);
    }

    @Override
    public CaseInstance instanceCommand(RequestContext ctx, String caseInstanceId, InstanceCommand command) {
        String action = command == null ? null : command.action();
        if ("reactivate".equalsIgnoreCase(action) || "cutOver".equalsIgnoreCase(action)) {
            PortsErrors.requireReason(command.reason());
            CaseInstance old = base.get(ctx, caseInstanceId);
            String newId = tx.inTx(() -> {
                String id = "reactivate".equalsIgnoreCase(action)
                        ? PortsErrors.call(() -> lifecycle.reactivate(caseInstanceId, null, Set.of(),
                                TypedVariables.fromJson(command.variables()), ctx.principalId()))
                        : PortsErrors.call(() -> lifecycle.cutOver(caseInstanceId, null, ctx.principalId()));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("action", action);
                payload.put("from_case_instance_id", caseInstanceId);
                payload.put("to_case_instance_id", id);
                payload.put("reason", command.reason());
                audit.record(ctx, caseInstanceId, "CASE_REACTIVATED", payload);
                audit.record(ctx, id, "CASE_REACTIVATED", payload);
                return id;
            });
            CaseInstance created = base.get(ctx, newId);
            return new CaseInstance(created.id(), created.caseDefinitionId(), created.caseDefinitionKey(),
                    created.businessKey() != null ? created.businessKey() : old.businessKey(), created.tenantId(),
                    created.state(), created.created(), created.executions(), caseInstanceId);
        }
        return base.instanceCommand(ctx, caseInstanceId, command);
    }

    @Override
    public Map<String, Object> variables(RequestContext ctx, String caseInstanceId) {
        return base.variables(ctx, caseInstanceId);
    }

    @Override
    public Page<CaseDefinition> definitions(RequestContext ctx, String key, Boolean latest, Page.PageRequest page) {
        return base.definitions(ctx, key, latest, page);
    }
}
