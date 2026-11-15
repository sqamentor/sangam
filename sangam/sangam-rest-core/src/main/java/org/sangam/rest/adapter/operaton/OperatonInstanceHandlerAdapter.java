// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.engine.handlers.InstanceHandlerEvaluator;
import org.sangam.engine.handlers.InstanceHandlerException;
import org.sangam.engine.handlers.InstanceHandlerRegistry;
import org.sangam.engine.handlers.InstanceHandlers;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.InstanceHandlerPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.support.TypedVariables;

/**
 * M-059 on the Operaton runtime API: the handler is looked up in the instance's own definition
 * ({@link InstanceHandlerRegistry}, same rules as at deploy) and evaluated by {@link InstanceHandlerEvaluator}.
 *
 * <ul>
 *   <li><b>Query</b>: read-only over the instance's business variables ({@code vars}, names not starting with
 *       {@code sangam}), the validated arguments ({@code args}) and the caller ({@code identity}); nothing is
 *       written.</li>
 *   <li><b>Update</b> (R49, E21): in one transaction, under the caller's permission check done by the controller: the
 *       reason is mandatory, the case version and material payload hash the caller saw must still hold
 *       ({@code 409 REVISION_CONFLICT} / {@code 409 APPROVAL_INVALIDATED}), the instance must be at one of the
 *       handler's {@code activities} when it names any ({@code 409 STATE_INVALID}), the declared assignments are applied
 *       together with case version + 1, and a class-B {@code INSTANCE_UPDATED} event records the changed fields with
 *       the material from/to values on the root's chain. Because the payload hash and case version change, every
 *       approval bound to the earlier payload (VI.4 rule 5) is invalidated at its next use: the affected obligations
 *       are re-evaluated rather than silently carried over.</li>
 * </ul>
 */
public class OperatonInstanceHandlerAdapter implements InstanceHandlerPort {

    private final RuntimeService runtime;
    private final InstanceHandlerRegistry handlers;
    private final TxSupport tx;
    private final AuditPort audit;
    private final Clock clock;

    public OperatonInstanceHandlerAdapter(RuntimeService runtime, InstanceHandlerRegistry handlers, TxSupport tx,
                                          AuditPort audit, Clock clock) {
        this.runtime = runtime;
        this.handlers = handlers;
        this.tx = tx;
        this.audit = audit;
        this.clock = clock;
    }

    /** Decisions 87 and 98: the field masks applied to the query inputs (none until set; the supplier may give null). */
    private volatile java.util.function.Supplier<org.sangam.rest.support.VariableMasking> masking =
            org.sangam.rest.support.VariableMasking::none;

    public void useVariableMasking(java.util.function.Supplier<org.sangam.rest.support.VariableMasking> masking) {
        this.masking = masking == null ? org.sangam.rest.support.VariableMasking::none : masking;
    }

    private org.sangam.rest.support.VariableMasking masking() {
        org.sangam.rest.support.VariableMasking m = masking.get();
        return m == null ? org.sangam.rest.support.VariableMasking.none() : m;
    }

    @Override
    public QueryResult query(RequestContext ctx, String id, String name, QueryRequest request) {
        ProcessInstance pi = require(ctx, id);
        InstanceHandlers.Query q = definitions(pi).queries().get(name);
        if (q == null) {
            throw ApiProblem.notFound("query handler", name);
        }
        Map<String, Object> vars = OperatonSupport.call(() -> runtime.getVariables(id));
        Object result;
        try {
            Map<String, Object> args = InstanceHandlerEvaluator.arguments(q.arguments(),
                    TypedVariables.fromJson(request == null ? null : request.arguments()));
            // decisions 87 and 98: the expression sees the caller's masked variables, so no result derives from a
            // field the caller may not read
            Map<String, Object> visible = masking().processVariables(ctx, id, vars);
            result = handlers.evaluator().query(q, business(visible), args, identity(ctx));
        } catch (InstanceHandlerException e) {
            throw problem(e);
        }
        Object hash = vars.get("sangamPackageHash");
        return new QueryResult(id, name, pi.getProcessDefinitionId(), hash == null ? null : String.valueOf(hash),
                TypedVariables.toJsonValue(result), clock.instant());
    }

    @Override
    public UpdateResult update(RequestContext ctx, String id, String name, UpdateRequest r) {
        if (r == null || r.reason() == null || r.reason().isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "A named update is a modification and needs a reason (R49)");
        }
        return tx.inTx(() -> {
            ProcessInstance pi = require(ctx, id);
            InstanceHandlers.Update u = definitions(pi).updates().get(name);
            if (u == null) {
                throw ApiProblem.notFound("update handler", name);
            }
            Map<String, Object> before = OperatonSupport.call(() -> runtime.getVariables(id));
            long current = TaskVersions.caseVersion(before);
            if (r.caseVersion() != null && r.caseVersion() != current) {
                throw ApiProblem.conflict("REVISION_CONFLICT", "This case changed since you opened it (version "
                        + r.caseVersion() + " to " + current + ")").with("currentVersion", current);
            }
            if (r.payloadHash() != null) {
                String now = PayloadHash.hash(before);
                if (!now.equalsIgnoreCase(r.payloadHash())) {
                    throw ApiProblem.conflict("APPROVAL_INVALIDATED",
                            "The material payload differs from the one you reviewed; reload before changing it")
                            .with("currentPayloadHash", now);
                }
            }
            if (!u.activities().isEmpty()) {
                List<String> active = OperatonSupport.call(() -> runtime.getActiveActivityIds(id));
                if (active.stream().noneMatch(u.activities()::contains)) {
                    throw ApiProblem.conflict("STATE_INVALID", "Update '" + name + "' is allowed only while the case is at "
                            + u.activities() + "; it is at " + active);
                }
            }
            Map<String, Object> changes;
            try {
                Map<String, Object> args = InstanceHandlerEvaluator.arguments(u.arguments(),
                        TypedVariables.fromJson(r.arguments()));
                changes = handlers.evaluator().update(u, business(before), args, identity(ctx));
            } catch (InstanceHandlerException e) {
                throw problem(e);
            }
            long next = current + 1;
            Map<String, Object> set = new HashMap<>(changes);
            set.put(OperatonTaskAdapter.CASE_VERSION, next);
            OperatonSupport.run(() -> runtime.setVariables(id, set));
            Map<String, Object> after = new HashMap<>(before);
            after.putAll(set);
            List<String> changed = PayloadHash.changedFields(before, after);
            changed.remove(OperatonTaskAdapter.CASE_VERSION);
            String hash = PayloadHash.hash(after);
            Map<String, Object> materialBefore = PayloadHash.material(before);
            Map<String, Object> materialAfter = PayloadHash.material(after);
            Map<String, Object> oldValues = new LinkedHashMap<>();
            Map<String, Object> newValues = new LinkedHashMap<>();
            for (String f : changed) {
                if (materialBefore.containsKey(f) || materialAfter.containsKey(f)) {
                    oldValues.put(f, materialBefore.get(f));
                    newValues.put(f, materialAfter.get(f));
                }
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("handler", name);
            p.put("definitionId", pi.getProcessDefinitionId());
            p.put("processInstanceId", id);
            p.put("version", next);
            p.put("previous_version", current);
            p.put("payload_hash", hash);
            p.put("changed_fields", changed);
            p.put("old_values", oldValues);
            p.put("new_values", newValues);
            p.put("reason", r.reason());
            p.put("reason_code", r.reasonCode());
            p.put("actor", ctx.principalId());
            p.values().removeIf(Objects::isNull);
            String root = pi.getRootProcessInstanceId() != null ? pi.getRootProcessInstanceId() : id;
            String eventId = audit.record(ctx, root, "INSTANCE_UPDATED", p);
            return new UpdateResult(id, name, new ArrayList<>(changed), next, hash, eventId);
        });
    }

    private ProcessInstance require(RequestContext ctx, String id) {
        ProcessInstance pi = OperatonSupport.call(() ->
                runtime.createProcessInstanceQuery().processInstanceId(id).singleResult());
        if (pi == null) {
            throw ApiProblem.notFound("process instance", id);
        }
        OperatonSupport.checkTenant(ctx, pi.getTenantId(), "process instance", id);
        return pi;
    }

    /** The definition's handlers; a declaration that no longer passes the deploy rules is a 422, not a 500. */
    private InstanceHandlers.Definitions definitions(ProcessInstance pi) {
        try {
            return OperatonSupport.call(() -> handlers.of(pi.getProcessDefinitionId()));
        } catch (InstanceHandlerException e) {
            throw problem(e);
        }
    }

    /** The business variables a handler reads ({@code vars}): every variable not managed by the platform. */
    static Map<String, Object> business(Map<String, Object> vars) {
        Map<String, Object> out = new LinkedHashMap<>();
        vars.forEach((k, v) -> {
            if (!k.startsWith("sangam")) {
                out.put(k, v);
            }
        });
        return out;
    }

    /** The caller as a handler sees it ({@code identity}). */
    static Map<String, Object> identity(RequestContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", ctx.tenantId());
        m.put("principalId", ctx.principalId());
        m.put("seatId", ctx.seatId());
        m.put("actingForSeatId", ctx.actingForSeatId());
        m.put("groups", ctx.groups() == null ? List.of() : new ArrayList<>(new java.util.TreeSet<>(ctx.groups())));
        return m;
    }

    static ApiProblem problem(InstanceHandlerException e) {
        return ApiProblem.unprocessable(e.code(), e.detail());
    }
}
