// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.operaton.bpm.engine.CaseService;
import org.operaton.bpm.engine.ExternalTaskService;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.externaltask.ExternalTask;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.operaton.bpm.engine.history.HistoricCaseInstance;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.db.DbEntity;
import org.operaton.bpm.engine.impl.db.entitymanager.OptimisticLockingListener;
import org.operaton.bpm.engine.impl.db.entitymanager.OptimisticLockingResult;
import org.operaton.bpm.engine.impl.db.entitymanager.operation.DbOperation;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ExternalTaskEntity;
import org.operaton.bpm.engine.repository.DeploymentBuilder;
import org.operaton.bpm.engine.runtime.CaseExecution;
import org.operaton.bpm.engine.runtime.CaseInstance;
import org.operaton.bpm.engine.runtime.CaseInstanceBuilder;
import org.operaton.bpm.engine.runtime.Execution;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.runtime.MessageCorrelationBuilder;
import org.operaton.bpm.engine.runtime.MessageCorrelationResult;
import org.operaton.bpm.engine.runtime.MessageCorrelationResultType;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstantiationBuilder;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.api.ExpiryPolicy;
import org.sangam.core.engine.ConflictRetryExhaustedException;
import org.sangam.core.engine.OperationAwareExpiryPolicy;
import org.sangam.core.engine.OperationIdentity;
import org.sangam.core.engine.SangamClock;
import org.sangam.core.engine.SangamEngineAdapter;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Tombstones;
import org.sangam.engine.clock.OperatonClock;
import org.sangam.engine.db.EngineDialect;
import org.sangam.engine.lease.ExclusiveLeaseTable;
import org.sangam.engine.lease.SangamLeaseTable;
import org.sangam.engine.tx.CommandRetrier;
import org.sangam.engine.tx.CommandState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hardened {@link SangamEngineAdapter} on Operaton 2.2.0, from {@code spike/adapter-operaton} (W-1…W-12) plus the
 * Stage C fixes:
 * <ul>
 *   <li><b>L2 first (A-11, VI.1)</b>: every state-changing call resolves its root read-only, locks it
 *       ({@code ROOT_SEQ FOR UPDATE}) and only then touches engine state; whole commands are retried on retryable
 *       conflicts with full-jitter back-off and end in {@link ConflictRetryExhaustedException} (VI.1 rule 7).</li>
 *   <li><b>Fencing (VI.3, R20, FLOWABLE-INTO-OPERATON D-17, FLOWABLE-INTO-OPERATON D-18)</b>: {@code SANGAM_LEASE} checked under a row lock in the same command as
 *       the engine call; grants are idempotent per (work_id, lock_version) and serialized with the engine's fetch by the
 *       per-topic fetch lock (XI.10.4, W-9, FLOWABLE-INTO-OPERATON D-19).</li>
 *   <li><b>A-10 expiry hook (FLOWABLE-INTO-OPERATON D-21)</b> before any re-offer, in the transaction that ends the lease; an unacknowledged
 *       cancel always holds (VI.3.1 CANCEL_REQUESTED → UNKNOWN_OUTCOME).</li>
 *   <li><b>T35 operationId</b> on every leased item; the ledger row gets the engine work id (A-14).</li>
 *   <li><b>VI.6 exclusivity</b> ({@code sangam:exclusive} = {@code execution:<group>} | {@code resource:<variable>}).</li>
 *   <li><b>VI.5</b>: deleting an instance tombstones its leased work and records the open operation ids.</li>
 *   <li><b>Bounded job execution</b> (A-12): per call and per job definition.</li>
 *   <li><b>FLOWABLE-INTO-OPERATON D-51</b>: DOCTYPE declarations rejected at deploy.</li>
 * </ul>
 * Internal Operaton classes used are listed in {@code sangam-engine/PATCHES.md}.
 */
public final class OperatonEngineAdapter implements SangamEngineAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(OperatonEngineAdapter.class);
    private static final int SWEEP_LIMIT = 500;

    private final ProcessEngine engine;
    private final ProcessEngineConfigurationImpl configuration;
    private final SangamEngineComponents c;
    private final SangamEngineSettings settings;
    private final EngineDialect dialect;
    private final SangamLeaseTable leases;
    private final ExclusiveLeaseTable exclusive;
    private final OperatonClock clock;
    private final CommandRetrier retrier;
    private final CommandRetrier fetchRetrier;
    private final Tombstones tombstones;
    private final String jdbcUrl;
    private final Runnable onClose;
    private volatile ExpiryPolicy expiryPolicy;
    private final AtomicLong fetchGiveUps = new AtomicLong();

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final ManagementService managementService;
    private final ExternalTaskService externalTaskService;
    private final CaseService caseService;

    public OperatonEngineAdapter(ProcessEngine engine, SangamEngineComponents components, String jdbcUrl, Runnable onClose) {
        this.engine = engine;
        this.configuration = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
        this.c = components;
        this.settings = components.settings();
        this.dialect = components.dialect();
        this.leases = components.leases();
        this.exclusive = components.exclusiveLeases();
        this.clock = components.clock();
        this.retrier = components.retrier();
        this.fetchRetrier = new CommandRetrier(Math.max(1, settings.getFetchAttempts()), Duration.ofMillis(5),
                Duration.ofMillis(200), components.metrics());
        this.expiryPolicy = components.expiryPolicy();
        this.tombstones = new Tombstones(dialect.coreDialect(),
                new ReconciliationQueue(dialect.coreDialect(), new AuditChain(dialect.coreDialect())),
                new AuditChain(dialect.coreDialect()));
        this.jdbcUrl = jdbcUrl;
        this.onClose = onClose;
        this.repositoryService = engine.getRepositoryService();
        this.runtimeService = engine.getRuntimeService();
        this.taskService = engine.getTaskService();
        this.historyService = engine.getHistoryService();
        this.managementService = engine.getManagementService();
        this.externalTaskService = engine.getExternalTaskService();
        this.caseService = engine.getCaseService();
    }

    /** The underlying engine (Operaton REST, Cockpit, experiments). */
    public ProcessEngine processEngine() {
        return engine;
    }

    @Override
    public String engineId() {
        return "operaton-2.2.0";
    }

    @Override
    public SangamClock clock() {
        return clock;
    }

    // ================================================================== command plumbing

    /**
     * One engine command with lock timeouts; top-level calls are retried (VI.1 rule 7), nested calls (inside a
     * caller's command) run once in the caller's transaction.
     */
    private <T> T command(String name, BiFunction<CommandContext, Connection, T> work) {
        if (Context.hasActiveCommandContext()) {
            return once(work);
        }
        return retrier.run(name, true, () -> once(work));
    }

    private <T> T once(BiFunction<CommandContext, Connection, T> work) {
        Command<T> cmd = cc -> {
            Connection conn = CommandState.connection(cc);
            dialect.applyLockTimeouts(conn, settings.getLockTimeout(), settings.getStatementTimeout());
            return work.apply(cc, conn);
        };
        return configuration.getCommandExecutorTxRequired().execute(cmd);
    }

    /**
     * Q1: {@code work} runs inside one engine command and gets its connection; product rows and engine state commit
     * or roll back together (explicit rollback guard, FLOWABLE-INTO-OPERATON D-22). Not retried: the caller decides.
     *
     * <p><b>Context reuse (contract relied on by sangam-integration):</b> when an Operaton command context is already
     * active on this thread (a delegate, listener, job or an outer {@code inEngineTransaction}), {@code work} runs
     * directly in that context, on its connection and in its transaction; no new command or transaction is opened.
     */
    @Override
    public <T> T inEngineTransaction(Function<Connection, T> work) {
        if (Context.hasActiveCommandContext()) {
            return work.apply(CommandState.connection(Context.getCommandContext()));
        }
        return once((cc, conn) -> work.apply(conn));
    }

    /** A root to lock: id, tenant and the definition key for the wait metric. */
    private record RootRef(String rootId, String tenant, String definitionKey) {
    }

    private String tenant(String t) {
        return t == null ? settings.getDefaultTenantId() : t;
    }

    private static String keyOf(String processDefinitionId) {
        if (processDefinitionId == null) {
            return null;
        }
        int i = processDefinitionId.indexOf(':');
        return i < 0 ? processDefinitionId : processDefinitionId.substring(0, i);
    }

    /*
     * SG-11 (E6 hot root): the root of a command is resolved with plain SQL on the command's connection, never with an
     * engine query. An engine query inside the command puts the TaskEntity / ExecutionEntity into the command's entity
     * cache with the revision read BEFORE the L2 lock; after waiting for the lock the command then works on, and
     * flushes, that stale revision, and fails with ENGINE-03005 although L2 serialized it (reproduced:
     * InheritedWeaknessH2Test.hotRootAllCommandsCommitUnderRootLock). With SQL nothing is cached before the lock, so
     * every engine row is first read after L2 is held.
     */

    /** Root of a process or case instance id (read by SQL, nothing cached), or null if unknown. */
    private RootRef rootOfInstance(String instanceId) {
        if (instanceId == null) {
            return null;
        }
        Connection conn = CommandState.currentConnection();
        Optional<RootRef> p = Jdbc.one(conn, "SELECT COALESCE(ROOT_PROC_INST_ID_, ID_), TENANT_ID_, PROC_DEF_ID_ "
                        + "FROM ACT_RU_EXECUTION WHERE ID_ = ? AND ID_ = PROC_INST_ID_",
                rs -> new RootRef(rs.getString(1), tenant(rs.getString(2)), keyOf(rs.getString(3))), instanceId);
        if (p.isPresent()) {
            return p.get();
        }
        return Jdbc.one(conn, "SELECT ID_, TENANT_ID_, CASE_DEF_ID_ FROM ACT_RU_CASE_EXECUTION "
                        + "WHERE ID_ = ? AND ID_ = CASE_INST_ID_",
                rs -> new RootRef(rs.getString(1), tenant(rs.getString(2)), keyOf(rs.getString(3))), instanceId)
                .orElse(null);
    }

    private RootRef rootOfTask(String taskId) {
        Optional<String> inst = Jdbc.one(CommandState.currentConnection(),
                "SELECT COALESCE(PROC_INST_ID_, CASE_INST_ID_) FROM ACT_RU_TASK WHERE ID_ = ?", rs -> rs.getString(1),
                taskId);
        return inst.map(this::rootOfInstance).orElse(null);
    }

    private void lock(CommandContext cc, RootRef ref) {
        if (ref != null) {
            c.rootLock().lock(cc, Map.of(ref.rootId(), ref.tenant()), ref.definitionKey());
        }
    }

    // ================================================================== deployment, start, messages

    @Override
    public String deploy(String tenantId, String deploymentName, Map<String, byte[]> resources) {
        if (settings.isRejectDoctype()) {
            for (Map.Entry<String, byte[]> r : resources.entrySet()) {
                String head = new String(r.getValue(), 0, Math.min(r.getValue().length, 4096), StandardCharsets.UTF_8)
                        .toUpperCase(Locale.ROOT);
                if (head.contains("<!DOCTYPE") || head.contains("<!ENTITY")) {
                    throw new ProcessEngineException("FLOWABLE-INTO-OPERATON D-51/R24: resource " + r.getKey()
                            + " declares a DOCTYPE or ENTITY; XML with DTDs is rejected at deploy");
                }
            }
        }
        DeploymentBuilder builder = repositoryService.createDeployment().name(deploymentName);
        if (tenantId != null) {
            builder.tenantId(tenantId);
        }
        resources.forEach((name, bytes) -> builder.addInputStream(name, new ByteArrayInputStream(bytes)));
        return builder.deploy().getId();
    }

    /** Starts a BPMN process by key; falls back to a CMMN case of that key (spike behaviour). A new root: no L2. */
    @Override
    public String startProcess(String tenantId, String processKey, String businessKey, Map<String, Object> variables) {
        Map<String, Object> vars = variables == null ? Map.of() : variables;
        return command("startProcess", (cc, conn) -> {
            if (!hasProcessDefinition(tenantId, processKey) && hasCaseDefinition(tenantId, processKey)) {
                return startCaseInCommand(tenantId, processKey, businessKey, vars);
            }
            ProcessInstantiationBuilder pb = runtimeService.createProcessInstanceByKey(processKey)
                    .businessKey(businessKey).setVariables(vars);
            if (tenantId != null) {
                pb.processDefinitionTenantId(tenantId);
            }
            return pb.execute().getId();
        });
    }

    @Override
    public String startCase(String tenantId, String caseKey, String businessKey, Map<String, Object> variables) {
        Map<String, Object> vars = variables == null ? Map.of() : variables;
        return command("startCase", (cc, conn) -> startCaseInCommand(tenantId, caseKey, businessKey, vars));
    }

    private String startCaseInCommand(String tenantId, String caseKey, String businessKey, Map<String, Object> vars) {
        CaseInstanceBuilder cb = caseService.withCaseDefinitionByKey(caseKey).businessKey(businessKey).setVariables(vars);
        if (tenantId != null) {
            cb.caseDefinitionTenantId(tenantId);
        }
        return cb.create().getId();
    }

    private boolean hasProcessDefinition(String tenantId, String key) {
        var q = repositoryService.createProcessDefinitionQuery().processDefinitionKey(key);
        if (tenantId != null) {
            q.tenantIdIn(tenantId);
        }
        return q.count() > 0;
    }

    private boolean hasCaseDefinition(String tenantId, String key) {
        var q = repositoryService.createCaseDefinitionQuery().caseDefinitionKey(key);
        if (tenantId != null) {
            q.tenantIdIn(tenantId);
        }
        return q.count() > 0;
    }

    /**
     * VI.1 rule 2: the waiting roots are resolved read-only (executions subscribed to the message with that business
     * key), locked in ascending order, then the message is correlated. A message start event creates a new root.
     */
    @Override
    public String correlateMessage(String tenantId, String messageName, String businessKey, Map<String, Object> variables) {
        return command("correlateMessage", (cc, conn) -> {
            // SG-11: subscribed roots by SQL (an execution query would cache pre-lock revisions, see rootOfInstance)
            StringBuilder sql = new StringBuilder("SELECT DISTINCT COALESCE(p.ROOT_PROC_INST_ID_, p.ID_), p.TENANT_ID_, "
                    + "p.PROC_DEF_ID_ FROM ACT_RU_EVENT_SUBSCR s JOIN ACT_RU_EXECUTION p ON p.ID_ = s.PROC_INST_ID_ "
                    + "WHERE s.EVENT_TYPE_ = 'message' AND s.EVENT_NAME_ = ?");
            List<Object> args = new ArrayList<>(List.of(messageName));
            if (businessKey != null) {
                sql.append(" AND p.BUSINESS_KEY_ = ?");
                args.add(businessKey);
            }
            if (tenantId != null) {
                sql.append(" AND s.TENANT_ID_ = ?");
                args.add(tenantId);
            }
            Map<String, String> roots = new HashMap<>();
            String metricKey = null;
            for (RootRef r : Jdbc.query(conn, sql.toString(),
                    rs -> new RootRef(rs.getString(1), tenant(rs.getString(2)), keyOf(rs.getString(3))), args.toArray())) {
                roots.putIfAbsent(r.rootId(), r.tenant());
                metricKey = r.definitionKey();
            }
            c.rootLock().lock(cc, roots, metricKey);
            MessageCorrelationBuilder b = runtimeService.createMessageCorrelation(messageName);
            if (businessKey != null) {
                b.processInstanceBusinessKey(businessKey);
            }
            if (tenantId != null) {
                b.tenantId(tenantId);
            }
            if (variables != null) {
                b.setVariables(variables);
            }
            MessageCorrelationResult r = b.correlateWithResult();
            return r.getResultType() == MessageCorrelationResultType.ProcessDefinition
                    ? r.getProcessInstance().getId() : r.getExecution().getProcessInstanceId();
        });
    }

    @Override
    public String correlateMessageToBranch(String tenantId, String messageName, String processInstanceId,
                                           String localVariableName, Object localVariableValue,
                                           Map<String, Object> variables) {
        return command("correlateMessageToBranch", (cc, conn) -> {
            lock(cc, rootOfInstance(processInstanceId));
            // Operaton refuses processInstanceId together with tenantId on a correlation (BadUserRequestException),
            // so the tenant is checked here, against the addressed instance, for both delivery paths.
            if (tenantId != null) {
                ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                        .processInstanceId(processInstanceId).singleResult();
                if (pi == null || !tenantId.equals(pi.getTenantId())) {
                    throw new org.operaton.bpm.engine.MismatchingMessageCorrelationException(messageName,
                            "no execution of tenant " + tenantId + " waits for it");
                }
            }
            long direct = runtimeService.createExecutionQuery().processInstanceId(processInstanceId)
                    .messageEventSubscriptionName(messageName).variableValueEquals(localVariableName, localVariableValue)
                    .count();
            if (direct == 0) {
                // The branch variable (e.g. a multi-instance loopCounter) is local to the branch's scope execution,
                // while the subscription sits on a nested execution inside that scope (multi-instance sub-process,
                // event-based gateway): deliver to the one waiting execution below the matching scope.
                String target = waitingBelowScope(messageName, processInstanceId, localVariableName, localVariableValue);
                if (target != null) {
                    runtimeService.messageEventReceived(messageName, target, variables);
                    return target;
                }
            }
            MessageCorrelationBuilder b = runtimeService.createMessageCorrelation(messageName)
                    .processInstanceId(processInstanceId)
                    .localVariableEquals(localVariableName, localVariableValue);
            if (variables != null) {
                b.setVariables(variables);
            }
            MessageCorrelationResult r = b.correlateWithResult();
            return r.getExecution() != null ? r.getExecution().getId() : null;
        });
    }

    /** The single execution waiting for {@code messageName} below a scope that has the local variable, else null. */
    private String waitingBelowScope(String messageName, String processInstanceId, String name, Object value) {
        Set<String> scopes = new HashSet<>();
        for (Execution e : runtimeService.createExecutionQuery().processInstanceId(processInstanceId)
                .variableValueEquals(name, value).list()) {
            scopes.add(e.getId());
        }
        if (scopes.isEmpty()) {
            return null;
        }
        String found = null;
        for (Execution e : runtimeService.createExecutionQuery().processInstanceId(processInstanceId)
                .messageEventSubscriptionName(messageName).list()) {
            for (ExecutionEntity p = ((ExecutionEntity) e).getParent(); p != null; p = p.getParent()) {
                if (scopes.contains(p.getId())) {
                    if (found != null) {
                        throw new org.operaton.bpm.engine.MismatchingMessageCorrelationException(messageName,
                                "FLOWABLE-INTO-OPERATON D-35: more than one execution waits for it below the branch " + name + "=" + value
                                        + " of process instance " + processInstanceId);
                    }
                    found = e.getId();
                    break;
                }
            }
        }
        return found;
    }

    // ================================================================== user tasks

    @Override
    public List<TaskView> queryTasks(TaskQuery query) {
        org.operaton.bpm.engine.task.TaskQuery q = buildTaskQuery(query);
        q.orderByTaskCreateTime().asc().orderByTaskId().asc();
        List<Task> tasks = query.maxResults() > 0
                ? q.listPage(Math.max(0, query.firstResult()), query.maxResults()) : q.list();
        List<TaskView> out = new ArrayList<>(tasks.size());
        for (Task t : tasks) {
            String instanceId = t.getProcessInstanceId() != null ? t.getProcessInstanceId() : t.getCaseInstanceId();
            out.add(new TaskView(t.getId(), t.getName(), t.getTaskDefinitionKey(), instanceId, t.getAssignee(),
                    t.getTenantId(), new HashMap<>(taskService.getVariables(t.getId()))));
        }
        return out;
    }

    @Override
    public long countTasks(TaskQuery query) {
        return buildTaskQuery(query).count();
    }

    @Override
    public long countTasksCapped(TaskQuery query, int cap) {
        return buildTaskQuery(query).listPage(0, Math.max(0, cap) + 1).size();
    }

    /** FLOWABLE-INTO-OPERATON D-36 / W-4: candidate user + groups as an OR query; assigned tasks included. */
    private org.operaton.bpm.engine.task.TaskQuery buildTaskQuery(TaskQuery query) {
        org.operaton.bpm.engine.task.TaskQuery q = taskService.createTaskQuery();
        if (query.tenantId() != null) {
            q.tenantIdIn(query.tenantId());
        }
        if (query.processInstanceId() != null) {
            if (caseService.createCaseInstanceQuery().caseInstanceId(query.processInstanceId()).count() > 0) {
                q.caseInstanceId(query.processInstanceId());
            } else {
                q.processInstanceId(query.processInstanceId());
            }
        }
        if (query.taskDefinitionKey() != null) {
            q.taskDefinitionKey(query.taskDefinitionKey());
        }
        String user = query.candidateUser();
        List<String> groups = query.candidateGroups() == null ? List.of() : query.candidateGroups();
        if (user != null && !groups.isEmpty()) {
            q.or().taskCandidateUser(user).taskCandidateGroupIn(groups).includeAssignedTasks().endOr();
        } else if (user != null) {
            q.taskCandidateUser(user).includeAssignedTasks();
        } else if (!groups.isEmpty()) {
            q.taskCandidateGroupIn(groups).includeAssignedTasks();
        }
        return q;
    }

    @Override
    public void claimTask(String taskId, String userId) {
        command("claimTask", (cc, conn) -> {
            lock(cc, rootOfTask(taskId));
            taskService.claim(taskId, userId);
            return null;
        });
    }

    @Override
    public void completeTask(String taskId, Map<String, Object> variables) {
        command("completeTask", (cc, conn) -> {
            lock(cc, rootOfTask(taskId));
            taskService.complete(taskId, variables);
            return null;
        });
    }

    // ================================================================== external work (VI.3)

    @Override
    public void setExpiryPolicy(ExpiryPolicy policy) {
        this.expiryPolicy = policy == null ? c.expiryPolicy() : policy;
    }

    @Override
    public List<ExternalWork> fetchAndLock(String workerId, String topic, int maxItems, Duration lockDuration) {
        List<LeasedWork> leased = fetchAndLockWork(workerId, topic, maxItems, lockDuration);
        List<ExternalWork> out = new ArrayList<>(leased.size());
        for (LeasedWork w : leased) {
            out.add(w.work());
        }
        return out;
    }

    /**
     * Per attempt: (1) the expiry sweep with the A-10 policy, committed on its own (a HOLD is only buffered in the
     * engine cache until flush, and the engine's fetch query reads the database); (2) one fetch command: per-topic
     * fetch lock, Operaton fetchAndLock, OLE listener that fails the command instead of silently dropping a task, then
     * per task the policy for an early engine re-offer, VI.6 exclusivity, operationId and the idempotent grant.
     * Any conflict rolls the attempt back with every task still AVAILABLE; after the attempts the fetch returns
     * nothing (never a stuck task). Nested in a caller's command: one attempt, sweep and fetch together.
     */
    @Override
    public List<LeasedWork> fetchAndLockWork(String workerId, String topic, int maxItems, Duration lockDuration) {
        if (lockDuration.compareTo(settings.getMaxLease()) > 0) {
            throw new IllegalArgumentException("VI.3 rule 3: lease " + lockDuration + " exceeds the maximum "
                    + settings.getMaxLease());
        }
        if (Context.hasActiveCommandContext()) {
            return once((cc, conn) -> fetchOnce(cc, conn, sweepExpired(conn), workerId, topic, maxItems, lockDuration));
        }
        try {
            return fetchRetrier.run("fetchAndLock", true, () -> {
                Set<String> held = once((cc, conn) -> {
                    CommandState.of(cc).markAcquisition();
                    return sweepExpired(conn);
                });
                return once((cc, conn) -> {
                    CommandState.of(cc).markAcquisition();
                    return fetchOnce(cc, conn, held, workerId, topic, maxItems, lockDuration);
                });
            });
        } catch (ConflictRetryExhaustedException e) {
            fetchGiveUps.incrementAndGet();
            c.metrics().fetchRetry(topic);
            return List.of();
        }
    }

    /** Fetches that returned nothing after all conflicting attempts. */
    public long fetchGiveUps() {
        return fetchGiveUps.get();
    }

    private List<LeasedWork> fetchOnce(CommandContext cc, Connection conn, Set<String> heldBySweep, String workerId,
                                       String topic, int maxItems, Duration lockDuration) {
        leases.lockFetch(conn, topic);
        List<LockedExternalTask> locked = externalTaskService.fetchAndLock(maxItems, workerId)
                .topic(topic, lockDuration.toMillis()).includeExtensionProperties()
                .enableCustomObjectDeserialization().execute(); // JSON map/list variables arrive deserialized
        cc.getDbEntityManager().registerOptimisticLockingListener(FAIL_ON_EXTERNAL_TASK_CONFLICT);
        long now = clock.now().toEpochMilli();
        List<LeasedWork> out = new ArrayList<>(locked.size());
        for (LockedExternalTask t : locked) {
            if (heldBySweep.contains(t.getId())) {
                externalTaskService.unlock(t.getId()); // held in this same command: keep it parked
                continue;
            }
            SangamLeaseTable.Row previous = leases.lock(conn, t.getId());
            if (previous != null && previous.held()) {
                if (applyExpiry(conn, previous, workerId) == ExpiryPolicy.Decision.HOLD) {
                    continue; // the engine re-offered it at its own lock expiry; the policy parked it
                }
                previous = leases.lock(conn, t.getId());
            }
            Map<String, String> ext = t.getExtensionProperties() == null ? Map.of() : t.getExtensionProperties();
            String tenant = tenant(t.getTenantId());
            ExecutionEntity exec = cc.getExecutionManager().findExecutionById(t.getExecutionId());
            String rootId = exec != null && exec.getRootProcessInstanceId() != null
                    ? exec.getRootProcessInstanceId() : t.getProcessInstanceId();
            long expiresAt = t.getLockExpirationTime().getTime();

            // VI.6 exclusivity (L1, in the acquisition transaction)
            String scopeStored = null;
            Long fence = null;
            String exclusiveDecl = ext.get("sangam:exclusive");
            if (exclusiveDecl != null && !exclusiveDecl.isBlank() && !"state".equals(exclusiveDecl.trim())) {
                String scopeKey = exclusiveScope(exclusiveDecl.trim(), rootId, t);
                if (scopeKey == null) {
                    externalTaskService.handleFailure(t.getId(), workerId, "VI.6: exclusive scope '" + exclusiveDecl
                            + "' cannot be evaluated (missing variable)", null, 0, 0L);
                    continue;
                }
                Optional<Long> got = exclusive.acquire(conn, tenant, scopeKey, t.getId(), expiresAt, now);
                if (got.isEmpty()) {
                    externalTaskService.unlock(t.getId()); // a live lease holds the scope: skip, stays AVAILABLE
                    continue;
                }
                fence = got.get();
                scopeStored = ExclusiveLeaseTable.encode(tenant, scopeKey);
            }

            UUID operationId = operationId(tenant, t, rootId, ext);
            String capability = ext.get("sangam:capabilityClass") != null ? ext.get("sangam:capabilityClass")
                    : ext.get("sangam:effect");
            SangamLeaseTable.Lease lease = leases.grant(conn, t.getId(), previous,
                    new SangamLeaseTable.Info(tenant, t.getTopicName(), rootId, operationId, capability, scopeStored),
                    workerId, expiresAt, now);
            // A-14: the ledger row (if the operation was recorded before) carries the engine work id
            Jdbc.update(conn, "UPDATE OPERATION_LEDGER SET engine_work_id = ? WHERE operation_id = ? "
                    + "AND (engine_work_id IS NULL OR engine_work_id <> ?)", t.getId(), operationId.toString(), t.getId());

            ExternalWork work = new ExternalWork(t.getId(), t.getTopicName(), t.getProcessInstanceId(), t.getActivityId(),
                    t.getActivityInstanceId(), t.getRetries() == null ? -1 : t.getRetries(),
                    Instant.ofEpochMilli(expiresAt), lease.lockVersion(), lease.attemptId(),
                    new HashMap<>(t.getVariables()), operationId.toString());
            out.add(new LeasedWork(work, tenant, t.getBusinessKey(), rootId, operationId, lease.attemptNo(), capability,
                    fence, false));
        }
        return out;
    }

    /** {@code execution:<group>} → {@code root:group}; {@code resource:<variable>} → {@code res:<value>}. */
    private static String exclusiveScope(String decl, String rootId, LockedExternalTask t) {
        if (decl.startsWith("execution")) {
            String group = decl.contains(":") ? decl.substring(decl.indexOf(':') + 1) : t.getActivityId();
            return rootId + ":" + group;
        }
        if (decl.startsWith("resource:")) {
            Object v = t.getVariables().get(decl.substring("resource:".length()).trim());
            return v == null ? null : "res:" + v;
        }
        throw new ProcessEngineException("VI.6: unknown sangam:exclusive mode '" + decl + "'");
    }

    /**
     * T35: UUIDv5(tenant, businessKey (else root id), activityId, scope); scope = completed earlier visits of the
     * activity in the root, the MI loop counter and an optional retry-safe key ({@code sangam:retrySafeKey} names a
     * variable).
     */
    private UUID operationId(String tenant, LockedExternalTask t, String rootId, Map<String, String> ext) {
        return operationId(tenant, t.getBusinessKey(), rootId, t.getProcessInstanceId(), t.getActivityId(), ext,
                t.getVariables()::get);
    }

    /** The only place the engine gathers the T35 facts of a work item; both fetch and {@link #operationIdOf} use it. */
    private UUID operationId(String tenant, String businessKey, String rootId, String processInstanceId,
                             String activityId, Map<String, String> ext, java.util.function.Function<String, Object> var) {
        long visit = historyService.createHistoricActivityInstanceQuery().processInstanceId(processInstanceId)
                .activityId(activityId).finished().count();
        Object loop = var.apply("loopCounter");
        String keyVar = ext.get("sangam:retrySafeKey");
        Object key = keyVar == null ? null : var.apply(keyVar);
        return OperationIdentity.forWork(tenant, businessKey, rootId, activityId, visit,
                loop instanceof Number n ? n.intValue() : null, key == null ? null : String.valueOf(key));
    }

    /** Expired held leases: the policy per lease, then the lease ends. Returns the ids the policy held. */
    private Set<String> sweepExpired(Connection conn) {
        Set<String> held = new HashSet<>();
        for (SangamLeaseTable.Row row : leases.lockExpired(conn, clock.now().toEpochMilli(), SWEEP_LIMIT)) {
            if (applyExpiry(conn, row, null) == ExpiryPolicy.Decision.HOLD) {
                held.add(row.workId());
            }
        }
        return held;
    }

    /**
     * A-10 on one expired, row-locked lease: the policy decides, the lease ends (version + 1), the exclusive scope is
     * released, and on HOLD the external task is parked with retries 0 (an incident {@code LEASE_EXPIRED_HELD}, visible
     * in Cockpit and the reconciliation queue). An unacknowledged cancel always holds (VI.3.1). A lease whose
     * external task is gone is removed without a policy call.
     */
    private ExpiryPolicy.Decision applyExpiry(Connection conn, SangamLeaseTable.Row row, String engineWorker) {
        ExternalTask task = externalTaskService.createExternalTaskQuery().externalTaskId(row.workId()).singleResult();
        long now = clock.now().toEpochMilli();
        if (task == null) {
            leases.remove(conn, row.workId());
            releaseExclusive(conn, row);
            return ExpiryPolicy.Decision.REOFFER;
        }
        ExpiryPolicy.ExpiredWork work = new ExpiryPolicy.ExpiredWork(row.workId(), row.workerId(), row.lockVersion(),
                row.attemptId(), row.expiresAtMs() == null ? null : Instant.ofEpochMilli(row.expiresAtMs()),
                task.getTopicName(), task.getProcessInstanceId(), task.getActivityId(), task.getActivityInstanceId());
        ExpiryPolicy policy = expiryPolicy;
        ExpiryPolicy.Decision decision;
        if (policy instanceof OperationAwareExpiryPolicy oa) {
            decision = oa.onExpired(work, new OperationAwareExpiryPolicy.Context(row.tenantId(), task.getBusinessKey(),
                    row.rootId(), row.operationId() == null ? null : UUID.fromString(row.operationId()),
                    row.capabilityClass(), row.attemptNo()), conn);
        } else {
            decision = policy.onExpired(work, conn);
        }
        if (row.state() == SangamLeaseTable.State.CANCEL_REQUESTED) {
            decision = ExpiryPolicy.Decision.HOLD; // VI.3.1 / VI.5 rule 4: cancel never acknowledged
        }
        leases.end(conn, row.workId(), decision == ExpiryPolicy.Decision.HOLD
                ? SangamLeaseTable.State.HELD : SangamLeaseTable.State.AVAILABLE, now);
        releaseExclusive(conn, row);
        if (decision == ExpiryPolicy.Decision.HOLD) {
            String lockOwner = engineWorker != null ? engineWorker : task.getWorkerId();
            String message = "LEASE_EXPIRED_HELD: lease " + row.lockVersion() + " of " + row.workerId() + " expired at "
                    + work.expiredAt() + "; held by the expiry policy (A-10)";
            if (lockOwner != null) {
                externalTaskService.handleFailure(row.workId(), lockOwner, message, null, 0, 0L);
            } else {
                externalTaskService.setRetries(row.workId(), 0);
            }
        }
        return decision;
    }

    private void releaseExclusive(Connection conn, SangamLeaseTable.Row row) {
        if (row.exclusiveScope() != null) {
            String[] ts = ExclusiveLeaseTable.decode(row.exclusiveScope());
            exclusive.release(conn, ts[0], ts[1], row.workId());
        }
    }

    /** I-8: turns the engine's silently ignored external-task OLE in fetch into a command failure (W-9). */
    private static final OptimisticLockingListener FAIL_ON_EXTERNAL_TASK_CONFLICT = new OptimisticLockingListener() {
        @Override
        public Class<? extends DbEntity> getEntityType() {
            return ExternalTaskEntity.class;
        }

        @Override
        public OptimisticLockingResult failedOperation(DbOperation operation) {
            return OptimisticLockingResult.THROW;
        }
    };

    @Override
    public void extendLock(String workId, String workerId, long lockVersion, Duration newDuration) {
        heartbeat(workId, workerId, lockVersion, newDuration);
    }

    @Override
    public Heartbeat heartbeat(String workId, String workerId, long lockVersion, Duration newDuration) {
        if (newDuration.compareTo(settings.getMaxLease()) > 0) {
            throw new IllegalArgumentException("VI.3 rule 3: lease " + newDuration + " exceeds " + settings.getMaxLease());
        }
        return command("extendLock", (cc, conn) -> {
            long now = clock.now().toEpochMilli();
            SangamLeaseTable.Row row = leases.check(conn, workId, workerId, lockVersion, now);
            externalTaskService.extendLock(workId, workerId, newDuration.toMillis());
            ExternalTask t = externalTaskService.createExternalTaskQuery().externalTaskId(workId).singleResult();
            long expiresAt = t != null && t.getLockExpirationTime() != null
                    ? t.getLockExpirationTime().getTime() : now + newDuration.toMillis();
            leases.extend(conn, workId, expiresAt, now);
            if (row.exclusiveScope() != null) {
                String[] ts = ExclusiveLeaseTable.decode(row.exclusiveScope());
                exclusive.extend(conn, ts[0], ts[1], workId, expiresAt);
            }
            return new Heartbeat(Instant.ofEpochMilli(expiresAt), row.cancelRequested());
        });
    }

    @Override
    public void requestCancel(String workId) {
        command("requestCancel", (cc, conn) -> leases.requestCancel(conn, workId, clock.now().toEpochMilli()));
    }

    /** L2 of the work's root before the lease row (L1 → L2 → L4), from an unlocked read of the lease. */
    private void lockRootOfWork(CommandContext cc, Connection conn, String workId) {
        leases.peek(conn, workId).ifPresent(r -> {
            if (r.rootId() != null) {
                c.rootLock().lock(cc, Map.of(r.rootId(), tenant(r.tenantId())), r.topic());
            }
        });
    }

    @Override
    public void completeExternal(String workId, String workerId, long lockVersion, Map<String, Object> variables) {
        command("completeExternal", (cc, conn) -> {
            lockRootOfWork(cc, conn, workId);
            SangamLeaseTable.Row row = leases.check(conn, workId, workerId, lockVersion, clock.now().toEpochMilli());
            externalTaskService.complete(workId, workerId, variables);
            leases.remove(conn, workId);
            releaseExclusive(conn, row);
            return null;
        });
    }

    @Override
    public void failExternal(String workId, String workerId, long lockVersion, String errorMessage, int retriesLeft,
                             Duration retryBackoff) {
        command("failExternal", (cc, conn) -> {
            lockRootOfWork(cc, conn, workId);
            long now = clock.now().toEpochMilli();
            SangamLeaseTable.Row row = leases.check(conn, workId, workerId, lockVersion, now);
            externalTaskService.handleFailure(workId, workerId, errorMessage, retriesLeft,
                    retryBackoff == null ? 0L : retryBackoff.toMillis());
            leases.end(conn, workId, SangamLeaseTable.State.AVAILABLE, now);
            releaseExclusive(conn, row);
            return null;
        });
    }

    @Override
    public void bpmnErrorExternal(String workId, String workerId, long lockVersion, String errorCode,
                                  Map<String, Object> variables) {
        command("bpmnErrorExternal", (cc, conn) -> {
            lockRootOfWork(cc, conn, workId);
            SangamLeaseTable.Row row = leases.check(conn, workId, workerId, lockVersion, clock.now().toEpochMilli());
            externalTaskService.handleBpmnError(workId, workerId, errorCode, null, variables);
            leases.remove(conn, workId);
            releaseExclusive(conn, row);
            return null;
        });
    }

    /** FLOWABLE-INTO-OPERATON D-18: owner- and version-checked; Operaton's own {@code unlock(id)} checks nothing. */
    @Override
    public void unlockExternal(String workId, String workerId, long lockVersion) {
        command("unlockExternal", (cc, conn) -> {
            long now = clock.now().toEpochMilli();
            SangamLeaseTable.Row row = leases.check(conn, workId, workerId, lockVersion, now);
            externalTaskService.unlock(workId);
            leases.end(conn, workId, SangamLeaseTable.State.AVAILABLE, now);
            releaseExclusive(conn, row);
            return null;
        });
    }

    // ================================================================== instances

    /**
     * Technical delete (VI.5): L2 on the root; leased work of the instance and its sub-processes gets a
     * CANCEL_REQUESTED tombstone (lease end + retention), the instance is deleted, lease rows are removed, and an
     * {@code INSTANCE_DELETED} audit event lists the open operation ids. A late worker call then gets
     * {@code StaleLeaseException(NO_LEASE)}; REST checks the tombstone for 410. Case instances are terminated.
     */
    @Override
    public void cancelInstance(String instanceId, String reason) {
        terminateInstance(instanceId, "system", reason, true);
    }

    @Override
    public Termination terminateInstance(String instanceId, String actor, String reason, boolean technical) {
        return command("terminateInstance", (cc, conn) -> {
            RootRef root = rootOfInstance(instanceId);
            lock(cc, root);
            if (caseService.createCaseInstanceQuery().caseInstanceId(instanceId).count() > 0) {
                caseService.terminateCaseExecution(instanceId);
                return new Termination(List.of(), 0);
            }
            List<String> workIds = new ArrayList<>();
            collectExternalTaskIds(instanceId, workIds, 0);
            long now = clock.now().toEpochMilli();
            List<UUID> open = new ArrayList<>();
            int cancelRequested = 0;
            for (String workId : workIds) {
                SangamLeaseTable.Row row = leases.lock(conn, workId);
                if (row != null && row.held()) {
                    UUID op = row.operationId() == null ? null : UUID.fromString(row.operationId());
                    long remaining = row.expiresAtMs() == null ? 0 : Math.max(0, row.expiresAtMs() - now);
                    tombstones.write(conn, tenant(row.tenantId()), workId, op,
                            row.rootId() == null ? instanceId : row.rootId(), row.lockVersion(), Duration.ofMillis(remaining));
                    cancelRequested++;
                    if (op != null) {
                        open.add(op);
                    }
                }
            }
            if (technical) {
                runtimeService.deleteProcessInstance(instanceId, reason, true, true, true);
            } else {
                runtimeService.deleteProcessInstance(instanceId, reason, false, true, false);
            }
            for (String workId : workIds) {
                SangamLeaseTable.Row row = leases.lock(conn, workId);
                if (row != null) {
                    releaseExclusive(conn, row);
                    leases.remove(conn, workId);
                }
            }
            if (technical && root != null) {
                tombstones.recordDeletion(conn, root.tenant(), root.rootId(), actor, reason, open);
            }
            return new Termination(List.copyOf(open), cancelRequested);
        });
    }

    @Override
    public UUID operationIdOf(String workId) {
        return command("operationIdOf", (cc, conn) -> {
            Optional<String> stored = Jdbc.one(conn, "SELECT operation_id FROM SANGAM_LEASE WHERE work_id = ?",
                    rs -> rs.getString(1), workId);
            if (stored.isPresent() && stored.get() != null) {
                return UUID.fromString(stored.get());
            }
            ExternalTask t = externalTaskService.createExternalTaskQuery().externalTaskId(workId).singleResult();
            if (t == null) {
                return null;
            }
            ExecutionEntity exec = cc.getExecutionManager().findExecutionById(t.getExecutionId());
            String rootId = exec != null && exec.getRootProcessInstanceId() != null
                    ? exec.getRootProcessInstanceId() : t.getProcessInstanceId();
            Map<String, String> ext = t.getExtensionProperties() == null ? Map.of() : t.getExtensionProperties();
            return operationId(tenant(t.getTenantId()), t.getBusinessKey(), rootId, t.getProcessInstanceId(),
                    t.getActivityId(), ext, name -> runtimeService.getVariable(t.getExecutionId(), name));
        });
    }

    private void collectExternalTaskIds(String processInstanceId, List<String> out, int depth) {
        if (depth > 64) {
            throw new IllegalStateException("sub-process nesting deeper than 64 under " + processInstanceId);
        }
        externalTaskService.createExternalTaskQuery().processInstanceId(processInstanceId).list()
                .forEach(t -> out.add(t.getId()));
        for (ProcessInstance sub : runtimeService.createProcessInstanceQuery()
                .superProcessInstanceId(processInstanceId).list()) {
            collectExternalTaskIds(sub.getId(), out, depth + 1);
        }
    }

    private void collectInstanceTree(String processInstanceId, List<String> out, int depth) {
        if (depth > 64) {
            throw new IllegalStateException("sub-process nesting deeper than 64 under " + processInstanceId);
        }
        out.add(processInstanceId);
        for (ProcessInstance sub : runtimeService.createProcessInstanceQuery()
                .superProcessInstanceId(processInstanceId).list()) {
            collectInstanceTree(sub.getId(), out, depth + 1);
        }
    }

    /** Runtime state, or ended state from history; {@code null} for an unknown id. */
    @Override
    public InstanceView getInstance(String instanceId) {
        ProcessInstance pi = runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).singleResult();
        if (pi != null) {
            String key = repositoryService.getProcessDefinition(pi.getProcessDefinitionId()).getKey();
            return new InstanceView(pi.getId(), key, pi.getBusinessKey(), false,
                    runtimeService.getActiveActivityIds(pi.getId()), new HashMap<>(runtimeService.getVariables(pi.getId())));
        }
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(instanceId).singleResult();
        if (hpi != null) {
            return new InstanceView(hpi.getId(), hpi.getProcessDefinitionKey(), hpi.getBusinessKey(),
                    hpi.getEndTime() != null, List.of(), historicVariables(instanceId, false));
        }
        CaseInstance ci = caseService.createCaseInstanceQuery().caseInstanceId(instanceId).singleResult();
        if (ci != null) {
            String key = repositoryService.getCaseDefinition(ci.getCaseDefinitionId()).getKey();
            List<String> active = new ArrayList<>();
            for (CaseExecution ce : caseService.createCaseExecutionQuery().caseInstanceId(instanceId).active().list()) {
                if (!ce.getId().equals(instanceId)) {
                    active.add(ce.getActivityId());
                }
            }
            return new InstanceView(ci.getId(), key, ci.getBusinessKey(), ci.isCompleted() || ci.isTerminated(),
                    active, new HashMap<>(caseService.getVariables(instanceId)));
        }
        HistoricCaseInstance hci = historyService.createHistoricCaseInstanceQuery().caseInstanceId(instanceId).singleResult();
        if (hci != null) {
            String key = repositoryService.getCaseDefinition(hci.getCaseDefinitionId()).getKey();
            return new InstanceView(hci.getId(), key, hci.getBusinessKey(), hci.getCloseTime() != null,
                    List.of(), historicVariables(instanceId, true));
        }
        return null;
    }

    private Map<String, Object> historicVariables(String instanceId, boolean isCase) {
        var q = historyService.createHistoricVariableInstanceQuery();
        if (isCase) {
            q.caseInstanceId(instanceId);
        } else {
            q.processInstanceId(instanceId);
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        for (HistoricVariableInstance v : q.list()) {
            vars.put(v.getName(), v.getValue());
        }
        return vars;
    }

    // ================================================================== pause / resume (FLOWABLE-INTO-OPERATON D-32)

    @Override
    public void pauseInstance(String processInstanceId, String actor, String reason) {
        command("pauseInstance", (cc, conn) -> {
            RootRef root = rootOfInstance(processInstanceId);
            lock(cc, root);
            List<String> tree = new ArrayList<>();
            collectInstanceTree(processInstanceId, tree, 0);
            for (String id : tree) {
                runtimeService.suspendProcessInstanceById(id);
            }
            long now = clock.now().toEpochMilli();
            int n = Jdbc.update(conn, "UPDATE SANGAM_PAUSE SET paused_at_ms = ?, paused_by = ?, reason = ?, state = 'PAUSED', "
                    + "resumed_at_ms = NULL WHERE process_instance_id = ? AND state <> 'PAUSED'", now, actor, reason,
                    processInstanceId);
            if (n == 0) {
                dialect.insertIfAbsent(conn, "SANGAM_PAUSE", new String[]{"process_instance_id", "tenant_id",
                                "paused_at_ms", "paused_by", "reason", "total_paused_ms", "state"}, "state",
                        processInstanceId, root == null ? null : root.tenant(), now, actor, reason, 0L, "PAUSED");
            }
            return null;
        });
    }

    /**
     * FLOWABLE-INTO-OPERATON D-32: tx 1 moves every timer due date and task due date of the instance tree by the paused time while it is
     * still suspended (re-dating and activating in one transaction fails with ENGINE-03005); tx 2 activates it.
     */
    /**
     * <b>ADR-D-25 recovery</b> of an {@code AMBIGUOUS_ROUTE} / {@code NO_MATCHING_ROUTE} incident, in one command
     * under L2: object check {@code PROCESS_INSTANCE / MODIFY} for {@code actor} (fail closed when no authorization
     * port is bound), the corrected variables are set, every execution parked at a strict exclusive gateway of the
     * instance is re-routed by the shared policy, and a {@code ROUTE_CORRECTED} audit event records who corrected
     * what (variable names, not values) and the flow taken. If any gateway still does not route, the whole correction
     * rolls back and {@link org.sangam.engine.deploy.StrictExclusiveGatewayBehavior.RoutingException} carries the code.
     *
     * @return the ids of the re-routed gateways
     */
    public List<String> correctRoute(org.sangam.core.security.SecurityIdentity actor, String processInstanceId,
                                     Map<String, Object> variables, String reason) {
        return command("correctRoute", (cc, conn) -> {
            RootRef root = rootOfInstance(processInstanceId);
            if (root == null) {
                throw new ProcessEngineException("no running process instance " + processInstanceId);
            }
            lock(cc, root);
            var authz = settings.getRouteCorrectionAuthorization();
            if (authz == null) {
                throw new org.sangam.core.security.SangamSecurityException.AccessDenied(
                        "ADR-D-25: no authorization port bound for route corrections", 0);
            }
            authz.require(conn, actor, "PROCESS_INSTANCE", "MODIFY", processInstanceId);
            List<String> parked = Jdbc.query(conn, "SELECT EXECUTION_ID_ FROM ACT_RU_INCIDENT WHERE PROC_INST_ID_ = ? "
                    + "AND INCIDENT_TYPE_ = ?", rs -> rs.getString(1), processInstanceId,
                    org.sangam.engine.deploy.StrictExclusiveGatewayBehavior.INCIDENT_TYPE);
            if (parked.isEmpty()) {
                throw new ProcessEngineException("instance " + processInstanceId + " has no routing incident");
            }
            if (variables != null && !variables.isEmpty()) {
                runtimeService.setVariables(processInstanceId, variables);
            }
            List<String> taken = new ArrayList<>();
            for (String executionId : parked) {
                String gateway = Jdbc.one(conn, "SELECT ACT_ID_ FROM ACT_RU_EXECUTION WHERE ID_ = ?",
                        rs -> rs.getString(1), executionId).orElse(null);
                runtimeService.signal(executionId); // re-runs the shared policy; RoutingException rolls everything back
                taken.add(gateway);
            }
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("processInstanceId", processInstanceId);
            audit.put("actorPersonId", actor.personId());
            audit.put("actorSubject", actor.subject());
            audit.put("reason", reason);
            audit.put("correctedVariables", variables == null ? List.of() : new ArrayList<>(new java.util.TreeSet<>(variables.keySet())));
            audit.put("gateways", taken);
            c.auditSink().append(conn, root.tenant(), root.rootId(), "ROUTE_CORRECTED", audit);
            return taken;
        });
    }

    @Override
    public void resumeInstance(String processInstanceId, String actor) {
        List<String> tree = command("resumeInstance.redate", (cc, conn) -> {
            lock(cc, rootOfInstance(processInstanceId));
            Optional<Long> pausedAt = Jdbc.one(conn, dialect.lockingSelect("paused_at_ms", "SANGAM_PAUSE",
                            "process_instance_id = ? AND state = 'PAUSED'", settings.getLockTimeout()),
                    rs -> rs.getLong(1), processInstanceId);
            if (pausedAt.isEmpty()) {
                throw new ProcessEngineException("FLOWABLE-INTO-OPERATON D-32: process instance " + processInstanceId + " is not paused");
            }
            long now = clock.now().toEpochMilli();
            long shift = Math.max(0, now - pausedAt.get());
            List<String> ids = new ArrayList<>();
            collectInstanceTree(processInstanceId, ids, 0);
            for (String id : ids) {
                for (Job job : managementService.createJobQuery().processInstanceId(id).timers().list()) {
                    if (job.getDuedate() != null) {
                        managementService.setJobDuedate(job.getId(), new Date(job.getDuedate().getTime() + shift));
                    }
                }
                for (Task task : taskService.createTaskQuery().processInstanceId(id).list()) {
                    if (task.getDueDate() != null) {
                        task.setDueDate(new Date(task.getDueDate().getTime() + shift));
                        taskService.saveTask(task);
                    }
                }
            }
            Jdbc.update(conn, "UPDATE SANGAM_PAUSE SET state = 'RESUMED', resumed_at_ms = ?, "
                    + "total_paused_ms = total_paused_ms + ? WHERE process_instance_id = ?", now, shift, processInstanceId);
            return ids;
        });
        command("resumeInstance.activate", (cc, conn) -> {
            lock(cc, rootOfInstance(processInstanceId));
            for (String id : tree) {
                runtimeService.activateProcessInstanceById(id);
            }
            return null;
        });
    }

    // ================================================================== jobs and clock

    /**
     * Runs due jobs (timers, async continuations, retries) synchronously, after the lease sweep, <b>bounded</b>
     * (A-12): at most {@code maxJobExecutionsPerCall} executions, and at most
     * {@code maxExecutionsPerJobDefinitionPerCall} per job definition, so a refiring timer can never spin. Failing
     * jobs are not rethrown (the engine decremented their retries).
     */
    @Override
    public int executeDueJobs() {
        command("sweepLeases", (cc, conn) -> {
            CommandState.of(cc).markAcquisition();
            return sweepExpired(conn);
        });
        int executed = 0;
        Map<String, Integer> perDefinition = new HashMap<>();
        Set<String> skipped = new HashSet<>();
        while (executed < settings.getMaxJobExecutionsPerCall()) {
            List<Job> due = managementService.createJobQuery().executable().active().orderByJobId().asc().listPage(0, 100);
            boolean progressed = false;
            for (Job job : due) {
                if (executed >= settings.getMaxJobExecutionsPerCall()) {
                    break;
                }
                String def = job.getJobDefinitionId() == null ? "-" : job.getJobDefinitionId();
                int n = perDefinition.getOrDefault(def, 0);
                if (n >= settings.getMaxExecutionsPerJobDefinitionPerCall()) {
                    skipped.add(def);
                    continue;
                }
                perDefinition.put(def, n + 1);
                c.metrics().jobsAcquired(1); // sangam_jobs_acquired_total (adapter acquisition path)
                try {
                    managementService.executeJob(job.getId());
                } catch (RuntimeException e) {
                    LOG.debug("job {} failed; the engine handles its retries", job.getId(), e);
                }
                executed++;
                progressed = true;
            }
            if (!progressed) {
                break;
            }
        }
        if (executed >= settings.getMaxJobExecutionsPerCall() || !skipped.isEmpty()) {
            c.metrics().jobExecutionBounded(skipped.isEmpty() ? "per-call" : "per-job-definition");
            LOG.warn("executeDueJobs stopped at a bound: {} executions; job definitions at their limit: {}", executed, skipped);
        }
        return executed;
    }

    /** Moves the engine clock; {@code null} returns to system time. JVM-wide (FLOWABLE-INTO-OPERATON D-10). */
    @Override
    public void setClock(Instant now) {
        clock.set(now);
    }

    @Override
    public String jdbcUrl() {
        return jdbcUrl;
    }

    @Override
    public void close() {
        try {
            engine.close();
        } finally {
            clock.reset();
            org.sangam.core.engine.SangamClockHolder.reset();
            if (onClose != null) {
                onClose.run();
            }
        }
    }
}
