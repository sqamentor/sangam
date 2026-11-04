package org.sangam.spike.operaton;

import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.operaton.bpm.engine.CaseService;
import org.operaton.bpm.engine.ExternalTaskService;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.OptimisticLockingException;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.externaltask.ExternalTask;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.operaton.bpm.engine.history.HistoricCaseInstance;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.cfg.TransactionState;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.db.DbEntity;
import org.operaton.bpm.engine.impl.db.entitymanager.OptimisticLockingListener;
import org.operaton.bpm.engine.impl.db.entitymanager.OptimisticLockingResult;
import org.operaton.bpm.engine.impl.db.entitymanager.operation.DbOperation;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.ExternalTaskEntity;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.bpm.engine.repository.DeploymentBuilder;
import org.operaton.bpm.engine.runtime.CaseExecution;
import org.operaton.bpm.engine.runtime.CaseInstance;
import org.operaton.bpm.engine.runtime.CaseInstanceBuilder;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.runtime.MessageCorrelationBuilder;
import org.operaton.bpm.engine.runtime.MessageCorrelationResult;
import org.operaton.bpm.engine.runtime.MessageCorrelationResultType;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstantiationBuilder;
import org.operaton.bpm.engine.task.Task;

import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.api.ExpiryPolicy;
import org.sangam.spike.api.LeaseTable;

/**
 * {@link EngineAdapter} on an embedded Operaton 2.2.0 engine, without Spring.
 *
 * <p>Uses {@link StandaloneProcessEngineConfiguration} (JDBC/MyBatis transactions owned by the engine) over a
 * HikariCP pool. The job executor is off: timers and async jobs run only through {@link #executeDueJobs()},
 * and time is driven by {@link #setClock(Instant)} through {@link ClockUtil}.
 *
 * <p>Internal ({@code impl}) classes used here are listed in {@code spike/results/patch-ledger-operaton.md}.
 */
public final class OperatonEngineAdapter implements EngineAdapter {

    /** Upper bound on job executions per {@link #executeDueJobs()} call (guards against job loops). */
    private static final int MAX_JOB_EXECUTIONS = 10_000;

    /** Attempts per {@link #fetchAndLock} before it gives up with an empty result (W-9). */
    static final int FETCH_ATTEMPTS = 20;

    private volatile ExpiryPolicy expiryPolicy = ExpiryPolicy.ALLOW_REOFFER;
    private final AtomicLong fetchRetries = new AtomicLong();
    private final AtomicLong fetchGiveUps = new AtomicLong();

    private final String jdbcUrl;
    private final HikariDataSource ownedDataSource;
    private final ProcessEngineConfigurationImpl configuration;
    private final ProcessEngine engine;
    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final ManagementService managementService;
    private final ExternalTaskService externalTaskService;
    private final CaseService caseService;

    /** Creates an engine on a new HikariCP pool for the given JDBC URL; the schema is created or updated. */
    public OperatonEngineAdapter(String jdbcUrl, String username, String password) {
        this(newPool(jdbcUrl, username, password), jdbcUrl, true);
    }

    /** Creates an engine on a caller-owned DataSource (the caller closes it). */
    public OperatonEngineAdapter(DataSource dataSource, String jdbcUrl) {
        this(dataSource, jdbcUrl, false);
    }

    private OperatonEngineAdapter(DataSource dataSource, String jdbcUrl, boolean owned) {
        this.jdbcUrl = jdbcUrl;
        this.ownedDataSource = owned ? (HikariDataSource) dataSource : null;

        StandaloneProcessEngineConfiguration cfg = new StandaloneProcessEngineConfiguration();
        cfg.setProcessEngineName("sangam-operaton-" + UUID.randomUUID());
        cfg.setDataSource(dataSource);
        cfg.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        cfg.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
        cfg.setJobExecutorActivate(false);
        // Only relevant when the job executor acquires jobs; recorded for E6 (exists in 2.2.0).
        cfg.setJobExecutorAcquireWithSkipLocked(true);
        cfg.setDmnEnabled(true);
        cfg.setCmmnEnabled(true);
        // 2.2.0 rejects deployments without historyTimeToLive by default; the corpus is not required to set it.
        cfg.setEnforceHistoryTimeToLive(false);
        // Authorization is the product layer's job (VI.4); the engine's own checks stay off.
        cfg.setAuthorizationEnabled(false);
        // Multi-tenancy uses tenant ids on deployments; tenant checks without an authenticated user are a no-op.
        cfg.setTenantCheckEnabled(true);

        this.configuration = cfg;
        this.engine = cfg.buildProcessEngine();
        this.repositoryService = engine.getRepositoryService();
        this.runtimeService = engine.getRuntimeService();
        this.taskService = engine.getTaskService();
        this.historyService = engine.getHistoryService();
        this.managementService = engine.getManagementService();
        this.externalTaskService = engine.getExternalTaskService();
        this.caseService = engine.getCaseService();
        inEngineTransaction(c -> {
            LeaseTable.createIfMissing(c); // W-8: adapter-owned fencing table; dialect from JDBC metadata (A-15)
            return null;
        });
    }

    private static HikariDataSource newPool(String jdbcUrl, String username, String password) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbcUrl);
        hc.setUsername(username);
        hc.setPassword(password);
        hc.setMaximumPoolSize(10);
        hc.setAutoCommit(false); // MyBatis JdbcTransaction would switch it off anyway
        hc.setPoolName("operaton-" + UUID.randomUUID().toString().substring(0, 8));
        return new HikariDataSource(hc);
    }

    /** The underlying engine, for experiments that need engine-specific calls. */
    public ProcessEngine processEngine() {
        return engine;
    }

    @Override
    public String engineId() {
        return "operaton-2.2.0";
    }

    @Override
    public String deploy(String tenantId, String deploymentName, Map<String, byte[]> resources) {
        DeploymentBuilder builder = repositoryService.createDeployment().name(deploymentName);
        if (tenantId != null) {
            builder.tenantId(tenantId);
        }
        resources.forEach((name, bytes) -> builder.addInputStream(name, new ByteArrayInputStream(bytes)));
        return builder.deploy().getId();
    }

    /**
     * Starts a BPMN process by key. If no process definition has that key but a CMMN case definition does,
     * a case instance is created instead (kept for callers written before {@link #startCase} existed).
     */
    @Override
    public String startProcess(String tenantId, String processKey, String businessKey, Map<String, Object> variables) {
        Map<String, Object> vars = variables == null ? Map.of() : variables;
        if (!hasProcessDefinition(tenantId, processKey) && hasCaseDefinition(tenantId, processKey)) {
            return startCase(tenantId, processKey, businessKey, vars);
        }
        ProcessInstantiationBuilder pb = runtimeService.createProcessInstanceByKey(processKey)
                .businessKey(businessKey).setVariables(vars);
        if (tenantId != null) {
            pb.processDefinitionTenantId(tenantId);
        }
        return pb.execute().getId();
    }

    @Override
    public String startCase(String tenantId, String caseKey, String businessKey, Map<String, Object> variables) {
        CaseInstanceBuilder cb = caseService.withCaseDefinitionByKey(caseKey)
                .businessKey(businessKey).setVariables(variables == null ? Map.of() : variables);
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

    @Override
    public String correlateMessage(String tenantId, String messageName, String businessKey,
                                   Map<String, Object> variables) {
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
        // correlateWithResult() throws MismatchingMessageCorrelationException unless exactly one target matches.
        MessageCorrelationResult r = b.correlateWithResult();
        if (r.getResultType() == MessageCorrelationResultType.ProcessDefinition) {
            return r.getProcessInstance().getId();
        }
        return r.getExecution().getProcessInstanceId();
    }

    @Override
    public List<TaskView> queryTasks(TaskQuery query) {
        org.operaton.bpm.engine.task.TaskQuery q = buildTaskQuery(query);
        q.orderByTaskCreateTime().asc().orderByTaskId().asc();
        List<Task> tasks = query.maxResults() > 0
                ? q.listPage(Math.max(0, query.firstResult()), query.maxResults())
                : q.list();
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

    /**
     * Candidate semantics: with only a user, tasks where that user is a candidate (directly or through groups
     * known to the engine's identity service); with only groups, tasks whose candidate groups intersect them; with
     * both, the union (an OR query, because 2.2.0 rejects candidateUser + candidateGroupIn in one AND query).
     * Tasks already assigned are included in all cases.
     */
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
        taskService.claim(taskId, userId);
    }

    @Override
    public void completeTask(String taskId, Map<String, Object> variables) {
        taskService.complete(taskId, variables);
    }

    // ---------------------------------------------------------------- external tasks with adapter-side fencing (W-8)
    //
    // Operaton checks only workerId equality and does not check lock expiry on complete/fail/bpmnError (gap G-2),
    // and unlock() checks nothing. The adapter fences every call against SPIKE_LEASE (LeaseTable, shared with the
    // Flowable adapter) inside the same engine command as the engine call. The engine sees the plain workerId; the
    // "workerId#lockVersion" trick was not used.

    /** The lease-expiry hook (A-10, W-10). */
    @Override
    public void setExpiryPolicy(ExpiryPolicy policy) {
        this.expiryPolicy = policy == null ? ExpiryPolicy.ALLOW_REOFFER : policy;
    }

    /**
     * One command per attempt (W-9):
     * <ol>
     *   <li>the per-topic fetch lock ({@link LeaseTable#lockFetch}), so fetches of a topic are serialized and never
     *       pick the same task;</li>
     *   <li>the expiry sweep with the {@link ExpiryPolicy} (A-10), before the engine query can see the expired tasks;</li>
     *   <li>Operaton's fetchAndLock (which itself also takes tasks whose engine lock has expired);</li>
     *   <li>per task: the expiry policy again if the engine re-offered a lease the sweep had not ended yet, then the
     *       SPIKE_LEASE upsert (new lock_version and attemptId);</li>
     *   <li>an optimistic-locking listener that turns an ignored engine conflict on an external task into a command
     *       failure, so a lease row is never committed for a task the engine did not lock to this worker.</li>
     * </ol>
     * A conflict (engine optimistic lock, deadlock, serialization failure, lock timeout, duplicate key) rolls the whole
     * attempt back, leaving every task AVAILABLE, and the fetch is retried; after {@value #FETCH_ATTEMPTS} attempts it
     * returns an empty list. When called inside an outer {@link #inEngineTransaction}, there is one attempt only.
     */
    @Override
    public List<ExternalWork> fetchAndLock(String workerId, String topic, int maxItems, Duration lockDuration) {
        if (Context.hasActiveCommandContext()) {
            // Nested in a caller's command: one attempt, sweep and fetch share the caller's transaction.
            return inCommand((cc, c) -> fetchOnce(cc, c, sweepExpired(c), workerId, topic, maxItems, lockDuration));
        }
        for (int attempt = 1; ; attempt++) {
            try {
                // The sweep commits first: a HOLD (retries = 0) is only buffered in the engine's entity cache until
                // the command flushes, and the engine's fetch query reads the database, so in one command it would
                // still select the held task. A lease that expires between the two commands is caught in fetchOnce.
                Set<String> held = inEngineTransaction(this::sweepExpired);
                return inCommand((cc, c) -> fetchOnce(cc, c, held, workerId, topic, maxItems, lockDuration));
            } catch (RuntimeException e) {
                if (!isTransientConflict(e)) {
                    throw e;
                }
                fetchRetries.incrementAndGet();
                if (attempt >= FETCH_ATTEMPTS) {
                    fetchGiveUps.incrementAndGet();
                    return List.of(); // nothing changed; the work stays AVAILABLE for the next fetch
                }
                backoff(attempt);
            }
        }
    }

    private List<ExternalWork> fetchOnce(CommandContext cc, Connection c, Set<String> heldBySweep, String workerId,
                                         String topic, int maxItems, Duration lockDuration) {
        LeaseTable.lockFetch(c, topic);
        List<LockedExternalTask> locked = externalTaskService.fetchAndLock(maxItems, workerId)
                .topic(topic, lockDuration.toMillis())
                .execute();
        // Registered after the engine's own listener (which silently drops a conflicting task from its result and
        // lets the command commit): the last listener's result wins, so a conflict now fails the command.
        cc.getDbEntityManager().registerOptimisticLockingListener(FAIL_ON_EXTERNAL_TASK_CONFLICT);
        List<ExternalWork> out = new ArrayList<>(locked.size());
        for (LockedExternalTask t : locked) {
            if (heldBySweep.contains(t.getId())) {
                // Only when sweep and fetch share one command: the hold is not flushed yet. Undo the engine lock;
                // retries stay 0, so the task stays parked.
                externalTaskService.unlock(t.getId());
                continue;
            }
            LeaseTable.Row previous = LeaseTable.lock(c, t.getId());
            if (previous != null && previous.held()
                    && applyExpiry(c, previous, workerId) == ExpiryPolicy.Decision.HOLD) {
                continue; // the engine re-offered it at its own lock expiry; the policy parked it
            }
            Instant expiresAt = t.getLockExpirationTime().toInstant();
            LeaseTable.Lease lease = LeaseTable.grant(c, t.getId(), workerId, expiresAt);
            out.add(new ExternalWork(t.getId(), t.getTopicName(), t.getProcessInstanceId(), t.getActivityId(),
                    t.getActivityInstanceId(), t.getRetries() == null ? -1 : t.getRetries(), expiresAt,
                    lease.lockVersion(), lease.attemptId(), new HashMap<>(t.getVariables())));
        }
        return out;
    }

    /** Expired held leases: the expiry policy per lease, then the lease ends. Returns the ids the policy held. */
    private Set<String> sweepExpired(Connection c) {
        Set<String> held = new HashSet<>();
        for (LeaseTable.Row row : LeaseTable.lockExpired(c, now())) {
            if (applyExpiry(c, row, null) == ExpiryPolicy.Decision.HOLD) {
                held.add(row.workId());
            }
        }
        return held;
    }

    /**
     * Applies the expiry policy to one expired, row-locked lease: the policy decides, the lease ends (lock_version + 1)
     * and, on HOLD, the external task is parked with retries = 0 (an incident), so no fetch offers it again.
     * A lease whose external task no longer exists is removed without calling the policy.
     *
     * @param engineWorker the worker that holds the engine lock now, if it is not the lease owner (re-offered task)
     */
    private ExpiryPolicy.Decision applyExpiry(Connection c, LeaseTable.Row row, String engineWorker) {
        ExternalTask task = externalTaskService.createExternalTaskQuery().externalTaskId(row.workId()).singleResult();
        if (task == null) {
            LeaseTable.remove(c, row.workId());
            return ExpiryPolicy.Decision.REOFFER;
        }
        ExpiryPolicy.ExpiredWork work = new ExpiryPolicy.ExpiredWork(row.workId(), row.workerId(), row.lockVersion(),
                row.attemptId(), row.expiresAt(), task.getTopicName(), task.getProcessInstanceId(),
                task.getActivityId(), task.getActivityInstanceId());
        ExpiryPolicy.Decision decision = expiryPolicy.onExpired(work, c);
        LeaseTable.end(c, row.workId());
        if (decision == ExpiryPolicy.Decision.HOLD) {
            String lockOwner = engineWorker != null ? engineWorker : task.getWorkerId();
            String message = "LEASE_EXPIRED_HELD: lease " + row.lockVersion() + " of " + row.workerId()
                    + " expired at " + row.expiresAt() + "; held by the expiry policy";
            if (lockOwner != null) {
                // retries 0 -> incident; the task is not fetchable until setRetries(id, n > 0)
                externalTaskService.handleFailure(row.workId(), lockOwner, message, null, 0, 0L);
            } else {
                externalTaskService.setRetries(row.workId(), 0);
            }
        }
        return decision;
    }

    /** Fails a command on an engine optimistic-locking conflict on an external task (see {@link #fetchOnce}). */
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

    /** Fetch attempts that were rolled back and retried (test and experiment visibility). */
    public long fetchRetries() {
        return fetchRetries.get();
    }

    /** Fetches that returned nothing after {@value #FETCH_ATTEMPTS} conflicting attempts. */
    public long fetchGiveUps() {
        return fetchGiveUps.get();
    }

    private static void backoff(int attempt) {
        long cap = Math.min(200L, 5L << Math.min(attempt, 6));
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, cap + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while retrying fetchAndLock", e);
        }
    }

    /**
     * Engine optimistic-locking conflicts and the dialect codes of VI.1.6 for deadlock, serialization failure and lock
     * timeout (PostgreSQL 40P01/40001/55P03, Oracle ORA-00060/08177/30006, SQL Server 1205/3960/1222, H2 lock
     * timeout HYT00/40001), plus a duplicate key on first use of a fetch-lock or lease row
     * (23505, ORA-00001, SQL Server 2627/2601, H2 23505).
     */
    static boolean isTransientConflict(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof OptimisticLockingException) {
                return true;
            }
            if (t instanceof SQLException s) {
                String state = s.getSQLState() == null ? "" : s.getSQLState();
                int code = s.getErrorCode();
                if (state.equals("40001") || state.equals("40P01") || state.equals("55P03") || state.equals("23505")
                        || state.equals("HYT00")) {
                    return true;
                }
                String product = String.valueOf(s.getMessage());
                if (product.startsWith("ORA-") && (code == 60 || code == 8177 || code == 30006 || code == 1)) {
                    return true;
                }
                if (code == 1205 || code == 3960 || code == 1222 || code == 2627 || code == 2601) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void extendLock(String workId, String workerId, long lockVersion, Duration newDuration) {
        inEngineTransaction(c -> {
            LeaseTable.check(c, workId, workerId, lockVersion, now());
            externalTaskService.extendLock(workId, workerId, newDuration.toMillis());
            // The engine's new lock expiry (the cached entity of this command), so lease and engine lock agree.
            ExternalTask t = externalTaskService.createExternalTaskQuery().externalTaskId(workId).singleResult();
            Instant expiresAt = t != null && t.getLockExpirationTime() != null
                    ? t.getLockExpirationTime().toInstant() : now().plus(newDuration);
            LeaseTable.extend(c, workId, expiresAt);
            return null;
        });
    }

    @Override
    public void completeExternal(String workId, String workerId, long lockVersion, Map<String, Object> variables) {
        inEngineTransaction(c -> {
            LeaseTable.check(c, workId, workerId, lockVersion, now());
            externalTaskService.complete(workId, workerId, variables);
            LeaseTable.remove(c, workId);
            return null;
        });
    }

    @Override
    public void failExternal(String workId, String workerId, long lockVersion, String errorMessage, int retriesLeft,
                             Duration retryBackoff) {
        inEngineTransaction(c -> {
            LeaseTable.check(c, workId, workerId, lockVersion, now());
            externalTaskService.handleFailure(workId, workerId, errorMessage, retriesLeft,
                    retryBackoff == null ? 0L : retryBackoff.toMillis());
            LeaseTable.end(c, workId);
            return null;
        });
    }

    @Override
    public void bpmnErrorExternal(String workId, String workerId, long lockVersion, String errorCode,
                                  Map<String, Object> variables) {
        inEngineTransaction(c -> {
            LeaseTable.check(c, workId, workerId, lockVersion, now());
            externalTaskService.handleBpmnError(workId, workerId, errorCode, null, variables);
            LeaseTable.remove(c, workId);
            return null;
        });
    }

    /** Owner-checked and fenced by the adapter; Operaton's own {@code unlock(id)} checks nothing. */
    @Override
    public void unlockExternal(String workId, String workerId, long lockVersion) {
        inEngineTransaction(c -> {
            LeaseTable.check(c, workId, workerId, lockVersion, now());
            externalTaskService.unlock(workId);
            LeaseTable.end(c, workId);
            return null;
        });
    }

    /**
     * Ends expired SPIKE_LEASE rows (lock_version + 1) through the expiry policy (A-10). The engine needs no reset:
     * fetch takes expired tasks, unless the policy held them.
     */
    private int expireLeases() {
        return inEngineTransaction(this::sweepExpired).size();
    }

    private static Instant now() {
        return ClockUtil.getCurrentTime().toInstant();
    }

    /**
     * One command: the instance (and its sub-process instances) is deleted and the SPIKE_LEASE rows of all its
     * external tasks are removed, so a late worker call gets {@code StaleLeaseException(NO_LEASE)} (E7 finding F-7).
     */
    @Override
    public void cancelInstance(String instanceId, String reason) {
        if (caseService.createCaseInstanceQuery().caseInstanceId(instanceId).count() > 0) {
            caseService.terminateCaseExecution(instanceId);
            return;
        }
        inEngineTransaction(c -> {
            List<String> workIds = new ArrayList<>();
            collectExternalTaskIds(instanceId, workIds, 0);
            runtimeService.deleteProcessInstance(instanceId, reason);
            for (String workId : workIds) {
                LeaseTable.remove(c, workId);
            }
            return null;
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

    /** Returns runtime state, or ended state from history; {@code null} if the id is unknown. */
    @Override
    public InstanceView getInstance(String instanceId) {
        ProcessInstance pi = runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).singleResult();
        if (pi != null) {
            String key = repositoryService.getProcessDefinition(pi.getProcessDefinitionId()).getKey();
            return new InstanceView(pi.getId(), key, pi.getBusinessKey(), false,
                    runtimeService.getActiveActivityIds(pi.getId()),
                    new HashMap<>(runtimeService.getVariables(pi.getId())));
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
        HistoricCaseInstance hci = historyService.createHistoricCaseInstanceQuery()
                .caseInstanceId(instanceId).singleResult();
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

    /**
     * Runs every job that is due at the engine clock (timers, async continuations, retries) until none is left.
     * A failing job is not rethrown: its retries are decremented by the engine, as the job executor would.
     *
     * @return the number of job executions, including failed ones
     */
    @Override
    public int executeDueJobs() {
        expireLeases();
        int executed = 0;
        while (executed < MAX_JOB_EXECUTIONS) {
            List<Job> due = managementService.createJobQuery().executable().active().orderByJobId().asc()
                    .listPage(0, 100);
            if (due.isEmpty()) {
                return executed;
            }
            for (Job job : due) {
                try {
                    managementService.executeJob(job.getId());
                } catch (RuntimeException e) {
                    // retries were decremented by the engine's failed-job handling
                }
                executed++;
            }
        }
        throw new IllegalStateException("executeDueJobs: more than " + MAX_JOB_EXECUTIONS + " executions; job loop?");
    }

    /** Sets the engine clock; {@code null} resets it to system time. JVM-wide: {@link ClockUtil} is static. */
    @Override
    public void setClock(Instant now) {
        if (now == null) {
            ClockUtil.reset();
        } else {
            ClockUtil.setCurrentTime(Date.from(now));
        }
    }

    /**
     * Runs {@code work} inside one Operaton command (transaction REQUIRED), handing it the JDBC connection of the
     * command context's DbSqlSession. Engine API calls made inside {@code work} (through this adapter or the engine
     * services) reuse the same command context, so product rows and engine state commit or roll back together.
     *
     * <p>Engine changes are buffered in the DbEntityManager and flushed at command close, after {@code work}
     * returns; product SQL inside {@code work} therefore does not see engine rows written in the same call.
     */
    @Override
    public <T> T inEngineTransaction(Function<Connection, T> work) {
        return inCommand((commandContext, connection) -> work.apply(connection));
    }

    /** {@link #inEngineTransaction} with the command context (for listeners on the engine's flush). */
    private <T> T inCommand(BiFunction<CommandContext, Connection, T> work) {
        Command<T> cmd = commandContext -> {
            Connection connection = commandContext.getDbSqlSession().getSqlSession().getConnection();
            // Workaround W-2 (ledger): MyBatis skips Connection.rollback() when no MyBatis write ran in this
            // session ("dirty" flag), and JdbcTransaction.close() then calls setAutoCommit(true), which COMMITS
            // the product rows written through this connection. Engine writes are only flushed at command close,
            // so an exception thrown inside `work` would otherwise commit the product rows and drop the engine
            // changes. Rolling the connection back explicitly while the engine rolls back closes that hole.
            commandContext.getTransactionContext().addTransactionListener(TransactionState.ROLLINGBACK,
                    cc -> rollbackQuietly(connection));
            return work.apply(commandContext, connection);
        };
        return configuration.getCommandExecutorTxRequired().execute(cmd);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            throw new IllegalStateException("rollback of the engine connection failed", e);
        }
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
            ClockUtil.reset();
            if (ownedDataSource != null) {
                ownedDataSource.close();
            }
        }
    }
}
