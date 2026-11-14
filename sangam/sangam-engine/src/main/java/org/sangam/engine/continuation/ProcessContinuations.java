// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.continuation;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.exception.NotFoundException;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.variable.VariableMap;
import org.operaton.bpm.engine.variable.Variables;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineComponents;
import org.sangam.engine.SangamProcessEnginePlugin;

/**
 * Decision 149 (M-060) continue-as-new, lifecycle contract v1 (board CHANGE 2026-11-12T19:33:01Z; REST shape
 * {@code routes/continuation.py}). One engine command, one transaction on the engine connection, retried as a whole on
 * conflicts (VI.1 rule 7):
 * <ol>
 *   <li>idempotency: a committed {@code (tenant, continuationRequestId)} returns its successor unchanged
 *       ({@link Outcome#EXISTING}); the same request id on another instance is refused;</li>
 *   <li>the instance must be an active, not suspended root process instance of the caller's tenant (decision 145:
 *       an instance of another tenant is not found); a sub-instance, an ended, suspended or already continued
 *       instance is refused with {@link ContinuationNotAllowedException} (409 {@code CONTINUATION_NOT_ALLOWED}, with the
 *       successor id when there is one);</li>
 *   <li>L2 root lock, then the state is read again; object permission {@code PROCESS_INSTANCE / MODIFY}
 *       ({@code process.continue}, W4-B ACK 20:33:06Z), and {@code PROCESS_DEFINITION / START} when the request names
 *       another version of the same definition key (never the latest by default);</li>
 *   <li>the successor starts on the predecessor's definition id with the business key and the named variables copied
 *       as stored typed values (exact decimals stay exact, decision 138) plus the request's variables;</li>
 *   <li>the predecessor ends through {@link OperatonEngineAdapter#terminateInstance} in the same transaction (open user
 *       tasks, external tasks with their leases, jobs, timers and message subscriptions end with it; stale workers get
 *       not found), delete reason {@code continued-as-new:<successorId>}, history kept;</li>
 *   <li>the chain row ({@code PROCESS_CONTINUATION}, changeset 0044-02) and the audit event
 *       {@code PROCESS_CONTINUED_AS_NEW} (through the audit sink, so the outbox) are written in the same
 *       transaction. Any failure rolls everything back. No atomicity with external systems is claimed.</li>
 * </ol>
 * Late messages correlate by business key and tenant to the successor (the predecessor no longer exists).
 */
public final class ProcessContinuations {

    public static final String AUDIT_EVENT = "PROCESS_CONTINUED_AS_NEW";
    public static final String DELETE_REASON_PREFIX = "continued-as-new:";
    static final int MAX_REQUEST_ID = 255;

    /** Whether this call created the successor or found a committed one. */
    public enum Outcome { CREATED, EXISTING }

    /** Kinds of pending work that end with the predecessor (REST ContinuationEnded.kind). */
    public enum EndedKind { USER_TASK, EXTERNAL_TASK, JOB, TIMER, MESSAGE_SUBSCRIPTION }

    public record Ended(EndedKind kind, int ended) {
    }

    /**
     * @param processDefinitionId successor definition id, null for the predecessor's own
     * @param carryVariables names of predecessor variables to copy, in order
     * @param variables further successor variables (plain or typed values)
     */
    public record Request(String continuationRequestId, String reason, String processDefinitionId,
                          List<String> carryVariables, Map<String, Object> variables) {
        public Request {
            carryVariables = carryVariables == null ? List.of() : List.copyOf(carryVariables);
            variables = variables == null ? Map.of() : new LinkedHashMap<>(variables);
        }
    }

    public record Result(String requestId, String predecessorId, String successorId, String chainId, int generation,
                         String businessKey, String processDefinitionId, Outcome outcome, String actor, String reason,
                         Instant continuedAt, List<Ended> ended) {
    }

    public record Link(String predecessorId, String successorId, int generation, String requestId, String actor,
                       String reason, Instant continuedAt) {
    }

    /** The chain an instance belongs to; {@code instanceIds} oldest first, the last one is current. */
    public record Chain(String chainId, List<String> instanceIds, List<Link> links) {
    }

    private final OperatonEngineAdapter adapter;
    private final SangamEngineComponents components;
    private final SecurityPorts.Authorization authz;
    private final RuntimeService runtime;

    /**
     * @param authz object authorization (fail closed: null refuses every continuation)
     */
    public ProcessContinuations(OperatonEngineAdapter adapter, SecurityPorts.Authorization authz) {
        this.adapter = adapter;
        this.components = componentsOf(adapter.processEngine());
        this.authz = authz;
        this.runtime = adapter.processEngine().getRuntimeService();
    }

    private static SangamEngineComponents componentsOf(ProcessEngine engine) {
        ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
        return cfg.getProcessEnginePlugins().stream().filter(SangamProcessEnginePlugin.class::isInstance)
                .map(p -> ((SangamProcessEnginePlugin) p).components()).findFirst()
                .orElseThrow(() -> new IllegalStateException("decision 149: not a Sangam engine (no SangamProcessEnginePlugin)"));
    }

    /** Continues {@code instanceId} as one new instance; see the class comment. */
    public Result continueAsNew(SecurityIdentity actor, String instanceId, Request request) {
        validate(actor, instanceId, request);
        return components.retrier().run("continueAsNew", true,
                () -> adapter.inEngineTransaction(conn -> inCommand(conn, actor, instanceId, request)));
    }

    /** The continuation chain of {@code instanceId} in the caller's tenant (one instance, no links, if never continued). */
    public Chain chain(SecurityIdentity actor, String instanceId) {
        String tenant = tenantOf(actor);
        return adapter.inEngineTransaction(conn -> {
            Optional<String> root = Jdbc.one(conn, "SELECT chain_root_id FROM PROCESS_CONTINUATION WHERE tenant_id = ? "
                    + "AND (predecessor_id = ? OR successor_id = ?)", rs -> rs.getString(1), tenant, instanceId, instanceId);
            if (root.isEmpty()) {
                return new Chain(instanceId, List.of(instanceId), List.of());
            }
            List<Link> links = Jdbc.query(conn, "SELECT predecessor_id, successor_id, generation, request_id, actor_id, "
                            + "reason, created_at FROM PROCESS_CONTINUATION WHERE tenant_id = ? AND chain_root_id = ? "
                            + "ORDER BY generation",
                    rs -> new Link(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), Jdbc.instant(rs, "created_at")), tenant, root.get());
            List<String> ids = new ArrayList<>();
            ids.add(root.get());
            links.forEach(l -> ids.add(l.successorId()));
            return new Chain(root.get(), List.copyOf(ids), List.copyOf(links));
        });
    }

    // ------------------------------------------------------------------ the command

    private Result inCommand(Connection conn, SecurityIdentity actor, String instanceId, Request req) {
        String tenant = tenantOf(actor);
        Optional<Result> existing = existing(conn, tenant, req.continuationRequestId(), instanceId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Instance inst = instance(conn, instanceId, tenant);
        components.rootLock().lock(Context.getCommandContext(), Map.of(inst.rootId(), tenant), keyOf(inst.definitionId()));
        // state after the lock: a concurrent continuation or end committed while we waited
        existing = existing(conn, tenant, req.continuationRequestId(), instanceId);
        if (existing.isPresent()) {
            return existing.get();
        }
        inst = instance(conn, instanceId, tenant);
        if (authz == null) {
            throw new org.sangam.core.security.SangamSecurityException.AccessDenied(
                    "decision 149: no authorization port bound for continue-as-new", 0);
        }
        authz.require(conn, actor, "PROCESS_INSTANCE", "MODIFY", instanceId);
        String definitionId = successorDefinition(conn, actor, tenant, inst, req.processDefinitionId());

        VariableMap vars = Variables.createVariables();
        if (!req.carryVariables().isEmpty()) {
            VariableMap stored = runtime.getVariablesLocalTyped(instanceId, req.carryVariables(), false);
            for (String name : req.carryVariables()) {
                if (!stored.containsKey(name)) {
                    throw new IllegalArgumentException("VALIDATION_FAILED: carry variable '" + name
                            + "' does not exist on process instance " + instanceId);
                }
                vars.putValueTyped(name, stored.getValueTyped(name));
            }
        }
        vars.putAll(req.variables());

        List<Ended> ended = pending(conn, inst.rootId());
        int generation;
        String chainRoot;
        Optional<Object[]> previous = Jdbc.one(conn, "SELECT chain_root_id, generation FROM PROCESS_CONTINUATION "
                + "WHERE tenant_id = ? AND successor_id = ?", rs -> new Object[] {rs.getString(1), rs.getInt(2)},
                tenant, instanceId);
        if (previous.isPresent()) {
            chainRoot = (String) previous.get()[0];
            generation = (Integer) previous.get()[1] + 1;
        } else {
            chainRoot = instanceId;
            generation = 1;
        }

        String successorId = runtime.createProcessInstanceById(definitionId).businessKey(inst.businessKey())
                .setVariables(vars).execute().getId();
        adapter.terminateInstance(instanceId, actorName(actor), DELETE_REASON_PREFIX + successorId, false);

        Instant now = components.clock().now();
        Jdbc.update(conn, "INSERT INTO PROCESS_CONTINUATION (tenant_id, request_id, predecessor_id, successor_id, "
                        + "chain_root_id, generation, definition_id, business_key, actor_id, reason, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", tenant, req.continuationRequestId(), instanceId,
                successorId, chainRoot, generation, definitionId, inst.businessKey(), actorName(actor), req.reason(), now);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("requestId", req.continuationRequestId());
        event.put("predecessorId", instanceId);
        event.put("successorId", successorId);
        event.put("chainId", chainRoot);
        event.put("generation", generation);
        event.put("processDefinitionId", definitionId);
        event.put("actorPersonId", actor.personId());
        event.put("actorSubject", actor.subject());
        event.put("reason", req.reason());
        event.put("carriedVariables", req.carryVariables()); // names only, never values
        event.put("ended", ended.stream().map(e -> Map.of("kind", e.kind().name(), "ended", e.ended())).toList());
        components.auditSink().append(conn, tenant, chainRoot, AUDIT_EVENT, event);

        return new Result(req.continuationRequestId(), instanceId, successorId, chainRoot, generation,
                inst.businessKey(), definitionId, Outcome.CREATED, actorName(actor), req.reason(), now, ended);
    }

    /** Root process instance facts read by SQL (nothing cached before the lock, SG-11). */
    private record Instance(String id, String rootId, String tenant, String definitionId, String businessKey) {
    }

    private Instance instance(Connection conn, String instanceId, String tenant) {
        record Row(String root, String superExec, String tenant, String def, String bk, int suspension) {
        }
        Optional<Row> row = Jdbc.one(conn, "SELECT ROOT_PROC_INST_ID_, SUPER_EXEC_, TENANT_ID_, PROC_DEF_ID_, "
                        + "BUSINESS_KEY_, SUSPENSION_STATE_ FROM ACT_RU_EXECUTION WHERE ID_ = ? AND ID_ = PROC_INST_ID_",
                rs -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getInt(6)), instanceId);
        if (row.isEmpty() || !tenant.equals(effectiveTenant(row.get().tenant()))) {
            Optional<String> successor = Jdbc.one(conn, "SELECT successor_id FROM PROCESS_CONTINUATION "
                    + "WHERE tenant_id = ? AND predecessor_id = ?", rs -> rs.getString(1), tenant, instanceId);
            if (successor.isPresent()) {
                throw new ContinuationNotAllowedException(ContinuationNotAllowedException.Reason.ALREADY_CONTINUED,
                        instanceId, successor.get());
            }
            boolean endedHere = Jdbc.one(conn, "SELECT 1 FROM ACT_HI_PROCINST WHERE ID_ = ? AND TENANT_ID_ = ? "
                    + "AND END_TIME_ IS NOT NULL", rs -> 1, instanceId, tenant).isPresent();
            if (endedHere) {
                throw new ContinuationNotAllowedException(ContinuationNotAllowedException.Reason.ENDED, instanceId, null);
            }
            // another tenant's instance is not found (decision 145): never confirm that it exists
            throw new NotFoundException("no running process instance " + instanceId);
        }
        Row r = row.get();
        if (r.superExec() != null || (r.root() != null && !r.root().equals(instanceId))) {
            throw new ContinuationNotAllowedException(ContinuationNotAllowedException.Reason.SUB_INSTANCE, instanceId, null);
        }
        if (r.suspension() == 2) {
            throw new ContinuationNotAllowedException(ContinuationNotAllowedException.Reason.SUSPENDED, instanceId, null);
        }
        return new Instance(instanceId, instanceId, effectiveTenant(r.tenant()), r.def(), r.bk());
    }

    private String successorDefinition(Connection conn, SecurityIdentity actor, String tenant, Instance inst,
                                       String requested) {
        if (requested == null || requested.isBlank() || requested.equals(inst.definitionId())) {
            return inst.definitionId();
        }
        Optional<String[]> def = Jdbc.one(conn, "SELECT KEY_, TENANT_ID_ FROM ACT_RE_PROCDEF WHERE ID_ = ?",
                rs -> new String[] {rs.getString(1), rs.getString(2)}, requested);
        if (def.isEmpty() || !tenant.equals(effectiveTenant(def.get()[1]))) {
            throw new NotFoundException("no process definition " + requested);
        }
        if (!def.get()[0].equals(keyOf(inst.definitionId()))) {
            throw new IllegalArgumentException("VALIDATION_FAILED: processDefinitionId " + requested
                    + " is not a version of " + keyOf(inst.definitionId()));
        }
        authz.require(conn, actor, "PROCESS_DEFINITION", "START", requested);
        return requested;
    }

    /** Pending work of the instance tree that ends with the predecessor, per kind. */
    private static List<Ended> pending(Connection conn, String rootId) {
        String tree = "(SELECT ID_ FROM ACT_RU_EXECUTION WHERE ROOT_PROC_INST_ID_ = ? AND ID_ = PROC_INST_ID_)";
        List<Ended> out = new ArrayList<>();
        out.add(new Ended(EndedKind.USER_TASK, count(conn, "SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ IN "
                + tree, rootId)));
        out.add(new Ended(EndedKind.EXTERNAL_TASK, count(conn, "SELECT COUNT(*) FROM ACT_RU_EXT_TASK WHERE "
                + "PROC_INST_ID_ IN " + tree, rootId)));
        out.add(new Ended(EndedKind.JOB, count(conn, "SELECT COUNT(*) FROM ACT_RU_JOB WHERE TYPE_ <> 'timer' AND "
                + "PROCESS_INSTANCE_ID_ IN " + tree, rootId)));
        out.add(new Ended(EndedKind.TIMER, count(conn, "SELECT COUNT(*) FROM ACT_RU_JOB WHERE TYPE_ = 'timer' AND "
                + "PROCESS_INSTANCE_ID_ IN " + tree, rootId)));
        out.add(new Ended(EndedKind.MESSAGE_SUBSCRIPTION, count(conn, "SELECT COUNT(*) FROM ACT_RU_EVENT_SUBSCR WHERE "
                + "EVENT_TYPE_ = 'message' AND PROC_INST_ID_ IN " + tree, rootId)));
        return List.copyOf(out);
    }

    private static int count(Connection conn, String sql, String rootId) {
        return Jdbc.one(conn, sql, rs -> rs.getInt(1), rootId).orElse(0);
    }

    /** A committed continuation of this request id, as an EXISTING result; refuses a reuse on another instance. */
    private static Optional<Result> existing(Connection conn, String tenant, String requestId, String instanceId) {
        record Row(String pred, String succ, String root, int gen, String def, String bk, String actor, String reason,
                   Instant at) {
        }
        Optional<Row> row = Jdbc.one(conn, "SELECT predecessor_id, successor_id, chain_root_id, generation, "
                        + "definition_id, business_key, actor_id, reason, created_at FROM PROCESS_CONTINUATION "
                        + "WHERE tenant_id = ? AND request_id = ?",
                rs -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), Jdbc.instant(rs, "created_at")),
                tenant, requestId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Row r = row.get();
        if (!r.pred().equals(instanceId)) {
            throw new ContinuationNotAllowedException(ContinuationNotAllowedException.Reason.REQUEST_ID_REUSED,
                    instanceId, null);
        }
        return Optional.of(new Result(requestId, r.pred(), r.succ(), r.root(), r.gen(), r.bk(), r.def(),
                Outcome.EXISTING, r.actor(), r.reason(), r.at(), List.of()));
    }

    private static void validate(SecurityIdentity actor, String instanceId, Request req) {
        if (actor == null || actor.tenantId() == null || actor.tenantId().isBlank()) {
            throw new IllegalArgumentException("VALIDATION_FAILED: an authenticated identity with a tenant is required");
        }
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("VALIDATION_FAILED: instance id is required");
        }
        if (req == null || req.continuationRequestId() == null || req.continuationRequestId().isBlank()
                || req.continuationRequestId().length() > MAX_REQUEST_ID) {
            throw new IllegalArgumentException("VALIDATION_FAILED: continuationRequestId is required (at most "
                    + MAX_REQUEST_ID + " characters)");
        }
        if (req.reason() == null || req.reason().isBlank()) {
            throw new IllegalArgumentException("VALIDATION_FAILED: reason is required (R42)");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String n : req.carryVariables()) {
            if (n == null || n.isBlank() || !seen.add(n)) {
                throw new IllegalArgumentException("VALIDATION_FAILED: carryVariables has a blank or repeated name");
            }
            if (req.variables().containsKey(n)) {
                throw new IllegalArgumentException("VALIDATION_FAILED: variable '" + n
                        + "' is both carried and given");
            }
        }
    }

    /** A null engine tenant is the configured default tenant, as everywhere in the adapter. */
    private String effectiveTenant(String engineTenant) {
        return engineTenant == null ? components.settings().getDefaultTenantId() : engineTenant;
    }

    private static String tenantOf(SecurityIdentity actor) {
        return actor.tenantId();
    }

    private static String actorName(SecurityIdentity actor) {
        return actor.personId() != null ? actor.personId() : actor.subject();
    }

    private static String keyOf(String processDefinitionId) {
        if (processDefinitionId == null) {
            return null;
        }
        int i = processDefinitionId.indexOf(':');
        return i < 0 ? processDefinitionId : processDefinitionId.substring(0, i);
    }
}
