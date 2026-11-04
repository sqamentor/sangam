package org.sangam.spike.flowable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import javax.sql.DataSource;

import org.flowable.cmmn.api.CmmnManagementService;
import org.flowable.cmmn.api.CmmnHistoryService;
import org.flowable.cmmn.api.CmmnRuntimeService;
import org.flowable.cmmn.api.CmmnTaskService;
import org.flowable.cmmn.api.history.HistoricCaseInstance;
import org.flowable.cmmn.api.runtime.CaseInstance;
import org.flowable.cmmn.api.runtime.PlanItemInstance;
import org.flowable.cmmn.engine.CmmnEngineConfiguration;
import org.flowable.cmmn.engine.configurator.CmmnEngineConfigurator;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.api.scope.ScopeTypes;
import org.flowable.common.engine.impl.cfg.TransactionContext;
import org.flowable.common.engine.impl.cfg.TransactionState;
import org.flowable.common.engine.impl.context.Context;
import org.flowable.common.engine.impl.db.DbSqlSession;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.dmn.engine.DmnEngineConfiguration;
import org.flowable.dmn.engine.configurator.DmnEngineConfigurator;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.flowable.engine.repository.DeploymentBuilder;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ExternalWorkerCompletionBuilder;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceBuilder;
import org.flowable.job.api.AcquiredExternalWorkerJob;
import org.flowable.job.api.ExternalWorkerJob;
import org.flowable.job.api.ExternalWorkerJobAcquireBuilder;
import org.flowable.job.api.ExternalWorkerJobFailureBuilder;
import org.flowable.job.api.Job;
import org.flowable.job.service.JobServiceConfiguration;
import org.flowable.job.service.impl.asyncexecutor.AbstractAsyncExecutor;
import org.flowable.job.service.impl.asyncexecutor.AsyncExecutor;
import org.flowable.job.service.impl.asyncexecutor.ResetExpiredJobsRunnable;
import org.flowable.job.service.impl.persistence.entity.ExternalWorkerJobEntity;
import org.flowable.job.service.impl.persistence.entity.ExternalWorkerJobEntityManager;
import org.flowable.task.api.Task;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.api.ExpiryPolicy;
import org.sangam.spike.api.LeaseTable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * {@link EngineAdapter} on Flowable 8.0.0: embedded process engine plus the DMN and CMMN engines
 * (through their configurators), standalone (no Spring), with the async executor disabled so that
 * jobs and timers only run through {@link #executeDueJobs()}.
 *
 * <p>Every place that needs a Flowable {@code impl} class is marked {@code LEDGER-Fn} and listed in
 * {@code spike/results/patch-ledger-flowable.md}.
 */
public class FlowableEngineAdapter implements EngineAdapter {

    private static final int MAX_JOB_ROUNDS = 100;

    /** Attempts per {@link #fetchAndLock} before it gives up with an empty result (LEDGER-F10). */
    static final int FETCH_ATTEMPTS = 20;

    /** Handler type of the job that continues the process after an external-worker completion or BPMN error. */
    static final String CONTINUATION_HANDLER = "external-worker-complete";

    /**
     * How the process continues after {@link #completeExternal} / {@link #bpmnErrorExternal} (LEDGER-F11).
     * Flowable never continues in the completion transaction: the completion moves the external-worker job to an
     * executable job ({@value #CONTINUATION_HANDLER}), and that job moves the token on in a later transaction.
     */
    public enum ContinuationMode {
        /**
         * Default. The adapter executes the continuation job on the calling thread right after the completion
         * transaction has committed (after the outermost {@link #inEngineTransaction} when nested), in its own engine
         * transaction. When {@code completeExternal} returns, the instance has moved on, as on Operaton; but it took
         * two transactions, and a failing continuation does not roll back the completion (it becomes a failed job
         * with Flowable's retries, see {@link #continuationFailures()}).
         */
        AFTER_COMMIT,
        /** Flowable as embedded by the spike before: the job waits for {@link #executeDueJobs()}. */
        DEFERRED,
        /**
         * Flowable's own async executor runs the continuation (and timers and async jobs). Its expired-job reset is
         * restricted to async and timer jobs, so expired external-worker leases still go through the
         * {@link ExpiryPolicy} (A-10).
         */
        ASYNC_EXECUTOR
    }

    private volatile ExpiryPolicy expiryPolicy = ExpiryPolicy.ALLOW_REOFFER;
    private volatile ContinuationMode continuationMode;
    private final ThreadLocal<List<String>> pendingContinuations = new ThreadLocal<>();
    private final AtomicLong fetchRetries = new AtomicLong();
    private final AtomicLong fetchGiveUps = new AtomicLong();
    private final AtomicLong continuationFailures = new AtomicLong();
    private final AtomicLong resetRowsRead = new AtomicLong();

    private final String jdbcUrl;
    private final HikariDataSource ownedDataSource;
    private final StandaloneProcessEngineConfiguration processEngineConfiguration;
    private final CmmnEngineConfiguration cmmnEngineConfiguration;
    private final DmnEngineConfiguration dmnEngineConfiguration;
    private final ProcessEngine processEngine;
    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final ManagementService managementService;
    private final CmmnManagementService cmmnManagementService;
    private final CmmnRuntimeService cmmnRuntimeService;
    private final CmmnTaskService cmmnTaskService;
    private final CmmnHistoryService cmmnHistoryService;

    /** Creates an adapter on an existing DataSource (the caller owns and closes the DataSource). */
    public FlowableEngineAdapter(DataSource dataSource, String jdbcUrl) {
        this(dataSource, jdbcUrl, null, ContinuationMode.AFTER_COMMIT);
    }

    /** As {@link #FlowableEngineAdapter(DataSource, String)} with a continuation mode. */
    public FlowableEngineAdapter(DataSource dataSource, String jdbcUrl, ContinuationMode mode) {
        this(dataSource, jdbcUrl, null, mode);
    }

    private FlowableEngineAdapter(DataSource dataSource, String jdbcUrl, HikariDataSource owned, ContinuationMode mode) {
        this.jdbcUrl = jdbcUrl;
        this.ownedDataSource = owned;

        StandaloneProcessEngineConfiguration cfg = new StandaloneProcessEngineConfiguration();
        cfg.setEngineName("sangam-flowable-" + UUID.randomUUID());
        cfg.setDataSource(dataSource);
        cfg.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        cfg.setAsyncExecutorActivate(false);
        cfg.setAsyncHistoryEnabled(false);

        CmmnEngineConfiguration cmmnCfg = new CmmnEngineConfiguration();
        cmmnCfg.setAsyncExecutorActivate(false);
        CmmnEngineConfigurator cmmnConfigurator = new CmmnEngineConfigurator();
        cmmnConfigurator.setCmmnEngineConfiguration(cmmnCfg);

        DmnEngineConfiguration dmnCfg = new DmnEngineConfiguration();
        DmnEngineConfigurator dmnConfigurator = new DmnEngineConfigurator();
        dmnConfigurator.setDmnEngineConfiguration(dmnCfg);

        cfg.addConfigurator(dmnConfigurator);
        cfg.addConfigurator(cmmnConfigurator);

        this.processEngineConfiguration = cfg;
        this.processEngine = cfg.buildProcessEngine();
        this.cmmnEngineConfiguration = cmmnConfigurator.getCmmnEngineConfiguration();
        this.dmnEngineConfiguration = dmnConfigurator.getDmnEngineConfiguration();
        this.repositoryService = processEngine.getRepositoryService();
        this.runtimeService = processEngine.getRuntimeService();
        this.taskService = processEngine.getTaskService();
        this.historyService = processEngine.getHistoryService();
        this.managementService = processEngine.getManagementService();
        this.cmmnManagementService = cmmnEngineConfiguration.getCmmnManagementService();
        this.cmmnRuntimeService = cmmnEngineConfiguration.getCmmnRuntimeService();
        this.cmmnTaskService = cmmnEngineConfiguration.getCmmnTaskService();
        this.cmmnHistoryService = cmmnEngineConfiguration.getCmmnHistoryService();
        restrictAsyncExecutorReset();
        inEngineTransaction(c -> {
            // LEDGER-F6: adapter-owned fencing table; dialect from JDBC metadata (A-15, LeaseTable)
            LeaseTable.createIfMissing(c);
            return null;
        });
        setContinuationMode(mode);
    }

    /**
     * LEDGER-F12 (A-10): Flowable's async executor resets expired jobs of three kinds, external-worker jobs included
     * ({@code DefaultAsyncJobExecutor.createResetExpiredJobsRunnable}). That reset would make expired external work
     * acquirable again without the {@link ExpiryPolicy}. The runnable is replaced (public setter, before any start) by
     * one that resets only async and timer jobs; expired external-worker jobs are reset by the adapter's sweep only.
     * This also applies when another module starts the executor itself.
     */
    private void restrictAsyncExecutorReset() {
        AsyncExecutor ex = processEngineConfiguration.getAsyncExecutor();
        if (ex instanceof AbstractAsyncExecutor a) {
            JobServiceConfiguration js = processEngineConfiguration.getJobServiceConfiguration();
            a.setResetExpiredJobsRunnable(new ResetExpiredJobsRunnable("flowable-reset-expired-jobs-no-external",
                    a, js.getJobEntityManager(), js.getTimerJobEntityManager()));
        }
    }

    /** Creates an adapter with its own HikariCP pool, closed by {@link #close()}. */
    public static FlowableEngineAdapter create(String jdbcUrl, String user, String password) {
        return create(jdbcUrl, user, password, ContinuationMode.AFTER_COMMIT);
    }

    /** As {@link #create(String, String, String)} with a continuation mode. */
    public static FlowableEngineAdapter create(String jdbcUrl, String user, String password, ContinuationMode mode) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbcUrl);
        hc.setUsername(user);
        hc.setPassword(password);
        hc.setMaximumPoolSize(10);
        hc.setAutoCommit(true);
        HikariDataSource ds = new HikariDataSource(hc);
        try {
            return new FlowableEngineAdapter(ds, jdbcUrl, ds, mode);
        } catch (RuntimeException e) {
            ds.close();
            throw e;
        }
    }

    @Override
    public String engineId() {
        return "flowable-" + ProcessEngine.VERSION;
    }

    // ---------------------------------------------------------------- deploy / start / correlate

    @Override
    public String deploy(String tenantId, String deploymentName, Map<String, byte[]> resources) {
        // One process-engine deployment: the DMN and CMMN configurators add their deployers to it,
        // so .dmn and .cmmn resources in the same map are deployed to their engines (parent deployment).
        DeploymentBuilder builder = repositoryService.createDeployment().name(deploymentName);
        if (tenantId != null) {
            builder.tenantId(tenantId);
        }
        resources.forEach(builder::addBytes);
        return builder.deploy().getId();
    }

    @Override
    public String startProcess(String tenantId, String processKey, String businessKey, Map<String, Object> variables) {
        ProcessInstanceBuilder b = runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey(processKey)
                .businessKey(businessKey);
        if (tenantId != null) {
            b.tenantId(tenantId);
        }
        if (variables != null) {
            b.variables(variables);
        }
        return b.start().getId();
    }

    @Override
    public String startCase(String tenantId, String caseKey, String businessKey, Map<String, Object> variables) {
        var b = cmmnRuntimeService.createCaseInstanceBuilder().caseDefinitionKey(caseKey).businessKey(businessKey);
        if (tenantId != null) {
            b.tenantId(tenantId);
        }
        if (variables != null) {
            b.variables(variables);
        }
        return b.start().getId();
    }

    @Override
    public String correlateMessage(String tenantId, String messageName, String businessKey, Map<String, Object> variables) {
        // Query and trigger in one engine command (one transaction).
        return managementService.executeCommand(ctx -> {
            var q = runtimeService.createExecutionQuery().messageEventSubscriptionName(messageName);
            if (businessKey != null) {
                // Fix of E1/E5-5/E6 finding F-1/B-3: the one-argument form compares the execution's OWN BUSINESS_KEY_,
                // which only the process-instance execution has, so a catch event in a child execution (parallel
                // branch, embedded sub-process) was never found. "true" matches every execution of the instance
                // with that business key (Execution.xml includeChildExecutionsWithBusinessKeyQuery).
                q.processInstanceBusinessKey(businessKey, true);
            }
            if (tenantId != null) {
                q.executionTenantId(tenantId);
            }
            List<Execution> executions = q.list();
            if (executions.size() != 1) {
                throw new IllegalStateException("Message '" + messageName + "' (businessKey=" + businessKey
                        + ") matched " + executions.size() + " waiting executions; exactly one is required");
            }
            Execution ex = executions.get(0);
            runtimeService.messageEventReceived(messageName, ex.getId(), variables == null ? Map.of() : variables);
            return ex.getProcessInstanceId();
        });
    }

    // ---------------------------------------------------------------- user tasks

    private org.flowable.task.api.TaskQuery toTaskQuery(TaskQuery query) {
        org.flowable.task.api.TaskQuery q = taskService.createTaskQuery();
        if (query.tenantId() != null) {
            q.taskTenantId(query.tenantId());
        }
        if (query.candidateUser() != null) {
            q.taskCandidateUser(query.candidateUser());
        }
        if (query.candidateGroups() != null && !query.candidateGroups().isEmpty()) {
            // With a candidate user set as well, Flowable ORs the user and the explicit groups
            // (the explicit list replaces the IDM group lookup for that user).
            q.taskCandidateGroupIn(query.candidateGroups());
        }
        if (query.processInstanceId() != null) {
            // LEDGER-F7: case tasks live in the same task table; a case instance id selects them by scope.
            if (isCaseInstance(query.processInstanceId())) {
                q.caseInstanceId(query.processInstanceId());
            } else {
                q.processInstanceId(query.processInstanceId());
            }
        }
        if (query.taskDefinitionKey() != null) {
            q.taskDefinitionKey(query.taskDefinitionKey());
        }
        return q;
    }

    private boolean isCaseInstance(String id) {
        return cmmnRuntimeService.createCaseInstanceQuery().caseInstanceId(id).count() > 0
                || cmmnHistoryService.createHistoricCaseInstanceQuery().caseInstanceId(id).count() > 0;
    }

    private static boolean isCmmnTask(Task t) {
        return t != null && ScopeTypes.CMMN.equals(t.getScopeType());
    }

    @Override
    public List<TaskView> queryTasks(TaskQuery query) {
        int max = query.maxResults() <= 0 ? Integer.MAX_VALUE : query.maxResults();
        List<Task> tasks = toTaskQuery(query).includeProcessVariables().includeCaseVariables()
                .orderByTaskCreateTime().asc().orderByTaskId().asc()
                .listPage(Math.max(0, query.firstResult()), max);
        List<TaskView> out = new ArrayList<>(tasks.size());
        for (Task t : tasks) {
            boolean cmmn = isCmmnTask(t);
            out.add(new TaskView(t.getId(), t.getName(), t.getTaskDefinitionKey(),
                    cmmn ? t.getScopeId() : t.getProcessInstanceId(),
                    t.getAssignee(), emptyToNull(t.getTenantId()), copy(cmmn ? t.getCaseVariables() : t.getProcessVariables())));
        }
        return out;
    }

    @Override
    public long countTasks(TaskQuery query) {
        return toTaskQuery(query).count();
    }

    @Override
    public void claimTask(String taskId, String userId) {
        if (isCmmnTask(taskService.createTaskQuery().taskId(taskId).singleResult())) {
            cmmnTaskService.claim(taskId, userId);
        } else {
            taskService.claim(taskId, userId);
        }
    }

    /** LEDGER-F7: case tasks are completed through the CMMN task service, so the case plan moves on. */
    @Override
    public void completeTask(String taskId, Map<String, Object> variables) {
        if (isCmmnTask(taskService.createTaskQuery().taskId(taskId).singleResult())) {
            cmmnTaskService.complete(taskId, variables);
        } else {
            taskService.complete(taskId, variables);
        }
    }

    // ---------------------------------------------------------------- external worker jobs (VI.3)
    //
    // Flowable compares only lockOwner on complete/fail/bpmnError, has no lock version, and acquires only jobs with
    // LOCK_EXP_TIME_ IS NULL (so expired work comes back only through a reset). The adapter fences every call
    // against SPIKE_LEASE (LeaseTable, the same class and SQL as the Operaton adapter) inside the same engine command
    // as the Flowable call (LEDGER-F6), and owns the reset of expired external-worker jobs (LEDGER-F3, F9, F12), so
    // the expiry policy (A-10) always runs before expired work can be acquired again.

    /** The lease-expiry hook (A-10, LEDGER-F12). */
    @Override
    public void setExpiryPolicy(ExpiryPolicy policy) {
        this.expiryPolicy = policy == null ? ExpiryPolicy.ALLOW_REOFFER : policy;
    }

    /**
     * Switches the continuation mode (LEDGER-F11). {@link ContinuationMode#ASYNC_EXECUTOR} starts Flowable's async
     * executor; switching away from it shuts the executor down.
     */
    public synchronized void setContinuationMode(ContinuationMode mode) {
        ContinuationMode next = mode == null ? ContinuationMode.AFTER_COMMIT : mode;
        AsyncExecutor ex = processEngineConfiguration.getAsyncExecutor();
        if (next == ContinuationMode.ASYNC_EXECUTOR && ex != null && !ex.isActive()) {
            restrictAsyncExecutorReset(); // shutdown() drops the runnable; a restart would create the default one
            ex.start();
        } else if (next != ContinuationMode.ASYNC_EXECUTOR && ex != null && ex.isActive()) {
            ex.shutdown();
        }
        this.continuationMode = next;
    }

    public ContinuationMode continuationMode() {
        return continuationMode;
    }

    /**
     * One attempt (LEDGER-F10):
     * <ol>
     *   <li>the expiry sweep in its own command ({@link #sweepExpired}): expired leases through the
     *       {@link ExpiryPolicy}, then the targeted reset of the topic's elapsed engine locks. It commits first,
     *       because Flowable's acquire query reads the database, not this command's unflushed entity cache;</li>
     *   <li>the fetch command: the per-topic fetch lock ({@link LeaseTable#lockFetch}), so fetches of a topic are
     *       serialized and never compete for the same job; Flowable's acquire; per job the SPIKE_LEASE upsert (new
     *       lock_version and attemptId).</li>
     * </ol>
     * A conflict (Flowable optimistic lock, deadlock, serialization failure, lock timeout, duplicate key) rolls the
     * attempt back, leaving the work AVAILABLE, and the fetch is retried; after {@value #FETCH_ATTEMPTS} attempts it
     * returns an empty list. Inside an outer {@link #inEngineTransaction} there is one attempt, in the caller's
     * transaction.
     */
    @Override
    public List<ExternalWork> fetchAndLock(String workerId, String topic, int maxItems, Duration lockDuration) {
        if (Context.getCommandContext() != null) {
            return inEngineTransaction(c -> {
                sweepExpired(c, topic);
                return fetchOnce(c, workerId, topic, maxItems, lockDuration);
            });
        }
        for (int attempt = 1; ; attempt++) {
            try {
                inEngineTransaction(c -> sweepExpired(c, topic));
                return inEngineTransaction(c -> fetchOnce(c, workerId, topic, maxItems, lockDuration));
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

    private List<ExternalWork> fetchOnce(Connection c, String workerId, String topic, int maxItems,
                                         Duration lockDuration) {
        LeaseTable.lockFetch(c, topic);
        List<AcquiredExternalWorkerJob> jobs = managementService.createExternalWorkerJobAcquireBuilder()
                .topic(topic, lockDuration)
                .onlyBpmn()
                .acquireAndLock(maxItems, workerId);
        List<ExternalWork> out = new ArrayList<>(jobs.size());
        for (AcquiredExternalWorkerJob j : jobs) {
            LeaseTable.Row previous = LeaseTable.lock(c, j.getId());
            if (previous != null && previous.held()) {
                // Only if something outside the adapter cleared the engine lock of a held lease (the async executor's
                // reset is restricted, LEDGER-F12): the lease is treated as expired and the policy decides first.
                if (applyExpiry(c, previous, workerId) == ExpiryPolicy.Decision.HOLD) {
                    continue;
                }
            }
            Instant expiresAt = toInstant(j.getLockExpirationTime());
            LeaseTable.Lease lease = LeaseTable.grant(c, j.getId(), workerId, expiresAt);
            out.add(new ExternalWork(j.getId(), j.getJobHandlerConfiguration(), j.getProcessInstanceId(), j.getElementId(),
                    activityInstanceId(j.getExecutionId(), j.getElementId()), j.getRetries(), expiresAt,
                    lease.lockVersion(), lease.attemptId(), copy(j.getVariables())));
        }
        return out;
    }

    /**
     * The expiry sweep, in the caller's command:
     * <ol>
     *   <li>every held lease with {@code expires_at <= engine now} ({@link LeaseTable#lockExpired}: row-locked, in
     *       work_id order): the {@link ExpiryPolicy} decides, the lease ends (lock_version + 1), and the engine lock is
     *       released (REOFFER) or the job is parked in the dead-letter table (HOLD);</li>
     *   <li>LEDGER-F9: the targeted reset. Only external-worker jobs of {@code topic} (all topics when null) whose
     *       engine lock has elapsed ({@code LOCK_EXP_TIME_ < now}) are read, by one SQL query on the command's
     *       connection; these are elapsed retry back-offs (no owner) and engine locks without a lease row. Jobs with a
     *       held, unexpired lease are left alone.</li>
     * </ol>
     *
     * @return the number of jobs made available again or parked
     */
    private int sweepExpired(Connection c, String topic) {
        Set<String> handled = new HashSet<>();
        for (LeaseTable.Row row : LeaseTable.lockExpired(c, now())) {
            applyExpiry(c, row, null);
            handled.add(row.workId());
        }
        int n = handled.size();
        Date now = processEngineConfiguration.getClock().getCurrentTime();
        for (String jobId : elapsedEngineLocks(c, topic, now)) {
            if (handled.contains(jobId)) {
                continue;
            }
            LeaseTable.Row lease = LeaseTable.lock(c, jobId);
            if (lease != null && lease.held()) {
                continue; // the lease (authoritative, A-1/W-8) has not expired; the engine timestamp is rounded
            }
            ExternalWorkerJobEntity job = externalWorkerJobs().findById(jobId); // LEDGER-F3
            if (job == null || job.getLockExpirationTime() == null || job.getLockExpirationTime().after(now)) {
                continue;
            }
            job.setLockOwner(null);
            job.setLockExpirationTime(null);
            n++;
        }
        return n;
    }

    /** LEDGER-F9: ids of external-worker jobs whose engine lock has elapsed, read with plain SQL. */
    private List<String> elapsedEngineLocks(Connection c, String topic, Date now) {
        if (Boolean.getBoolean("sangam.flowable.legacyResetScan")) {
            return legacyFullScan(now); // experiment switch only: the pre-fix O(backlog) scan, for the cost record
        }
        String sql = "select ID_ from " + processEngineConfiguration.getDatabaseTablePrefix() + "ACT_RU_EXTERNAL_JOB "
                + "where LOCK_EXP_TIME_ is not null and LOCK_EXP_TIME_ < ?" + (topic == null ? "" : " and HANDLER_CFG_ = ?");
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, new Timestamp(now.getTime()));
            if (topic != null) {
                ps.setString(2, topic);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read elapsed external-worker locks", e);
        }
        resetRowsRead.addAndGet(ids.size());
        return ids;
    }

    /** The reset query before the fix: every external-worker job is loaded and filtered in Java. */
    private List<String> legacyFullScan(Date now) {
        List<String> ids = new ArrayList<>();
        List<ExternalWorkerJob> all = managementService.createExternalWorkerJobQuery().list();
        resetRowsRead.addAndGet(all.size());
        for (ExternalWorkerJob j : all) {
            if (j.getLockExpirationTime() != null && j.getLockExpirationTime().before(now)) {
                ids.add(j.getId());
            }
        }
        return ids;
    }

    /**
     * Applies the expiry policy to one expired, row-locked lease (A-10): the policy decides with the command's
     * connection, the lease ends (lock_version + 1), and then
     * <ul>
     *   <li>REOFFER: the engine lock is released, so the next acquire can take the job;</li>
     *   <li>HOLD: the job is failed with 0 retries through Flowable's failure builder, which moves it to the
     *       dead-letter table (Flowable's "incident"). No acquire offers it until the product releases it with
     *       {@code ManagementService.moveDeadLetterJobToExecutableJob(id, retries)}, which recreates the
     *       external-worker job with the same id.</li>
     * </ul>
     * A lease whose job no longer exists is removed without calling the policy.
     *
     * @param engineWorker the worker that holds the engine lock now, if it is not the lease owner (fetch path)
     */
    private ExpiryPolicy.Decision applyExpiry(Connection c, LeaseTable.Row row, String engineWorker) {
        ExternalWorkerJobEntity job = externalWorkerJobs().findById(row.workId()); // LEDGER-F3
        if (job == null) {
            LeaseTable.remove(c, row.workId());
            return ExpiryPolicy.Decision.REOFFER;
        }
        ExpiryPolicy.ExpiredWork work = new ExpiryPolicy.ExpiredWork(row.workId(), row.workerId(), row.lockVersion(),
                row.attemptId(), row.expiresAt(), job.getJobHandlerConfiguration(), job.getProcessInstanceId(),
                job.getElementId(), activityInstanceId(job.getExecutionId(), job.getElementId()));
        ExpiryPolicy.Decision decision = expiryPolicy.onExpired(work, c);
        LeaseTable.end(c, row.workId());
        String owner = engineWorker != null ? engineWorker : job.getLockOwner();
        if (decision == ExpiryPolicy.Decision.HOLD) {
            if (owner == null) {
                owner = "sangam-expiry-hold";
                job.setLockOwner(owner); // the failure builder requires an owner match
            }
            managementService.createExternalWorkerJobFailureBuilder(row.workId(), owner)
                    .errorMessage("LEASE_EXPIRED_HELD: lease " + row.lockVersion() + " of " + row.workerId()
                            + " expired at " + row.expiresAt() + "; held by the expiry policy")
                    .retries(0)
                    .fail();
        } else if (engineWorker == null) {
            if (owner != null) {
                managementService.unacquireExternalWorkerJob(row.workId(), owner);
            } else {
                job.setLockExpirationTime(null);
            }
        }
        return decision;
    }

    /** Fetch attempts that were rolled back and retried (test and experiment visibility). */
    public long fetchRetries() {
        return fetchRetries.get();
    }

    /** Fetches that returned nothing after {@value #FETCH_ATTEMPTS} conflicting attempts. */
    public long fetchGiveUps() {
        return fetchGiveUps.get();
    }

    /** Continuation jobs run by {@link ContinuationMode#AFTER_COMMIT} that threw (left to Flowable's retries). */
    public long continuationFailures() {
        return continuationFailures.get();
    }

    /** External-worker job rows read by the expiry reset so far (LEDGER-F9 cost record). */
    public long resetRowsRead() {
        return resetRowsRead.get();
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
     * Flowable optimistic-locking conflicts and the dialect codes of VI.1.6 for deadlock, serialization failure and
     * lock timeout (PostgreSQL 40P01/40001/55P03, Oracle ORA-00060/08177/30006, SQL Server 1205/3960/1222, H2 lock
     * timeout HYT00/40001), plus a duplicate key on first use of a fetch-lock or lease row
     * (23505, ORA-00001, SQL Server 2627/2601). Same list as the Operaton adapter.
     */
    static boolean isTransientConflict(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof FlowableOptimisticLockingException) {
                return true;
            }
            if (t instanceof SQLException s) {
                String state = s.getSQLState() == null ? "" : s.getSQLState();
                int code = s.getErrorCode();
                if (state.equals("40001") || state.equals("40P01") || state.equals("55P03") || state.equals("23505")
                        || state.equals("HYT00")) {
                    return true;
                }
                String message = String.valueOf(s.getMessage());
                if (message.startsWith("ORA-") && (code == 60 || code == 8177 || code == 30006 || code == 1)) {
                    return true;
                }
                if (code == 1205 || code == 3960 || code == 1222 || code == 2627 || code == 2601) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * LEDGER-F8: Flowable jobs carry no activity instance id. The closest engine identity is the unfinished runtime
     * ActivityInstance (ACT_RU_ACTINST) of the job's execution and element; the execution id is the fallback.
     */
    private String activityInstanceId(String executionId, String elementId) {
        var ais = runtimeService.createActivityInstanceQuery().executionId(executionId)
                .activityId(elementId).unfinished().list();
        return ais.isEmpty() ? executionId : ais.get(0).getId();
    }

    /**
     * LEDGER-F1: Flowable has no "extend lock" API. A custom command changes LOCK_EXP_TIME_ through the
     * internal {@link ExternalWorkerJobEntityManager}. The lease must be live (LEDGER-F6).
     */
    @Override
    public void extendLock(String workId, String workerId, long lockVersion, Duration newDuration) {
        inEngineTransaction(c -> {
            LeaseTable.check(c, workId, workerId, lockVersion, now());
            Instant expiresAt = now().plus(newDuration);
            externalWorkerJobs().findById(workId).setLockExpirationTime(Date.from(expiresAt));
            LeaseTable.extend(c, workId, expiresAt);
            return null;
        });
    }

    @Override
    public void completeExternal(String workId, String workerId, long lockVersion, Map<String, Object> variables) {
        inEngineTransaction(c -> {
            LeaseTable.check(c, workId, workerId, lockVersion, now()); // LEDGER-F6: Flowable checks only the owner
            String executionId = externalWorkerJobs().findById(workId).getExecutionId();
            ExternalWorkerCompletionBuilder b = managementService.createExternalWorkerCompletionBuilder(workId, workerId);
            if (variables != null) {
                b.variables(variables);
            }
            b.complete();
            LeaseTable.remove(c, workId);
            scheduleContinuation(executionId);
            return null;
        });
    }

    @Override
    public void failExternal(String workId, String workerId, long lockVersion, String errorMessage, int retriesLeft,
                             Duration retryBackoff) {
        inEngineTransaction(c -> {
            LeaseTable.check(c, workId, workerId, lockVersion, now());
            ExternalWorkerJobFailureBuilder b = managementService.createExternalWorkerJobFailureBuilder(workId, workerId)
                    .errorMessage(errorMessage)
                    .retries(Math.max(0, retriesLeft));
            if (retryBackoff != null && !retryBackoff.isZero() && !retryBackoff.isNegative()) {
                // Flowable parks the job with LOCK_EXP_TIME_ = now + backoff and no owner. Only the
                // adapter's reset makes it acquirable again (sweepExpired, LEDGER-F9).
                b.retryTimeout(retryBackoff);
            }
            b.fail();
            LeaseTable.end(c, workId);
            return null;
        });
    }

    @Override
    public void bpmnErrorExternal(String workId, String workerId, long lockVersion, String errorCode,
                                  Map<String, Object> variables) {
        inEngineTransaction(c -> {
            LeaseTable.check(c, workId, workerId, lockVersion, now());
            String executionId = externalWorkerJobs().findById(workId).getExecutionId();
            ExternalWorkerCompletionBuilder b = managementService.createExternalWorkerCompletionBuilder(workId, workerId);
            if (variables != null) {
                b.variables(variables);
            }
            b.bpmnError(errorCode);
            LeaseTable.remove(c, workId);
            scheduleContinuation(executionId);
            return null;
        });
    }

    /** Owner-checked and fenced release (LEDGER-F6); Flowable's unacquire checks the owner as well. */
    @Override
    public void unlockExternal(String workId, String workerId, long lockVersion) {
        inEngineTransaction(c -> {
            LeaseTable.check(c, workId, workerId, lockVersion, now());
            managementService.unacquireExternalWorkerJob(workId, workerId);
            LeaseTable.end(c, workId);
            return null;
        });
    }

    /** LEDGER-F11: remembers the execution whose continuation job runs after the outermost commit. */
    private void scheduleContinuation(String executionId) {
        List<String> pending = pendingContinuations.get();
        if (continuationMode == ContinuationMode.AFTER_COMMIT && pending != null && executionId != null) {
            pending.add(executionId);
        }
    }

    /**
     * LEDGER-F11: runs the continuation jobs of the given executions, each in its own engine transaction
     * ({@code ManagementService.executeJob}, public API). A job that throws is left to Flowable's failed-job handling
     * (retries decremented, retried by {@link #executeDueJobs()}) and counted; the completion is already committed, so
     * the worker call does not fail.
     */
    private void runContinuations(List<String> executionIds) {
        for (String executionId : executionIds) {
            for (Job job : managementService.createJobQuery().executionId(executionId)
                    .handlerType(CONTINUATION_HANDLER).list()) {
                try {
                    managementService.executeJob(job.getId());
                } catch (RuntimeException e) {
                    continuationFailures.incrementAndGet();
                }
            }
        }
    }

    private Instant now() {
        return processEngineConfiguration.getClock().getCurrentTime().toInstant();
    }

    /** LEDGER-F3: internal entity manager access. */
    private ExternalWorkerJobEntityManager externalWorkerJobs() {
        return processEngineConfiguration.getJobServiceConfiguration().getExternalWorkerJobEntityManager();
    }

    // ---------------------------------------------------------------- instance

    /**
     * One command: the instance (and its sub-process instances) is deleted and the SPIKE_LEASE rows of all its
     * external-worker jobs (and dead-letter jobs, i.e. held work) are removed, so a late worker call gets
     * {@code StaleLeaseException(NO_LEASE)} (E7 finding F-7). A case instance is terminated.
     */
    @Override
    public void cancelInstance(String instanceId, String reason) {
        if (cmmnRuntimeService.createCaseInstanceQuery().caseInstanceId(instanceId).count() > 0) {
            cmmnRuntimeService.terminateCaseInstance(instanceId);
            return;
        }
        inEngineTransaction(c -> {
            List<String> workIds = new ArrayList<>();
            collectExternalWorkIds(instanceId, workIds, 0);
            runtimeService.deleteProcessInstance(instanceId, reason);
            for (String workId : workIds) {
                LeaseTable.remove(c, workId);
            }
            return null;
        });
    }

    private void collectExternalWorkIds(String processInstanceId, List<String> out, int depth) {
        if (depth > 64) {
            throw new IllegalStateException("sub-process nesting deeper than 64 under " + processInstanceId);
        }
        managementService.createExternalWorkerJobQuery().processInstanceId(processInstanceId).list()
                .forEach(j -> out.add(j.getId()));
        managementService.createDeadLetterJobQuery().processInstanceId(processInstanceId).externalWorkers().list()
                .forEach(j -> out.add(j.getId()));
        for (ProcessInstance sub : runtimeService.createProcessInstanceQuery()
                .superProcessInstanceId(processInstanceId).list()) {
            collectExternalWorkIds(sub.getId(), out, depth + 1);
        }
    }

    @Override
    public InstanceView getInstance(String instanceId) {
        ProcessInstance pi = runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).singleResult();
        if (pi != null) {
            return new InstanceView(pi.getId(), pi.getProcessDefinitionKey(), pi.getBusinessKey(), false,
                    runtimeService.getActiveActivityIds(instanceId), copy(runtimeService.getVariables(instanceId)));
        }
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery().processInstanceId(instanceId).singleResult();
        if (hpi != null) {
            Map<String, Object> vars = new HashMap<>();
            for (HistoricVariableInstance v : historyService.createHistoricVariableInstanceQuery().processInstanceId(instanceId).list()) {
                if (v.getTaskId() == null) {
                    vars.put(v.getVariableName(), v.getValue());
                }
            }
            return new InstanceView(hpi.getId(), hpi.getProcessDefinitionKey(), hpi.getBusinessKey(), hpi.getEndTime() != null,
                    List.of(), vars);
        }
        CaseInstance ci = cmmnRuntimeService.createCaseInstanceQuery().caseInstanceId(instanceId).singleResult();
        if (ci != null) {
            List<String> active = new ArrayList<>();
            for (PlanItemInstance p : cmmnRuntimeService.createPlanItemInstanceQuery().caseInstanceId(instanceId)
                    .planItemInstanceStateActive().list()) {
                active.add(p.getElementId());
            }
            return new InstanceView(ci.getId(), ci.getCaseDefinitionKey(), ci.getBusinessKey(), false, active,
                    copy(cmmnRuntimeService.getVariables(instanceId)));
        }
        HistoricCaseInstance hci = cmmnHistoryService.createHistoricCaseInstanceQuery().caseInstanceId(instanceId).singleResult();
        if (hci != null) {
            Map<String, Object> vars = new HashMap<>();
            for (HistoricVariableInstance v : cmmnHistoryService.createHistoricVariableInstanceQuery().caseInstanceId(instanceId).list()) {
                if (v.getTaskId() == null) {
                    vars.put(v.getVariableName(), v.getValue());
                }
            }
            return new InstanceView(hci.getId(), hci.getCaseDefinitionKey(), hci.getBusinessKey(), hci.getEndTime() != null,
                    List.of(), vars);
        }
        return null; // unknown id (EngineAdapter#getInstance contract)
    }

    // ---------------------------------------------------------------- jobs / clock

    /**
     * Runs the expiry sweep for all topics (expired leases through the {@link ExpiryPolicy}, elapsed back-offs), moves
     * due timers to executable jobs and executes all executable jobs (BPMN and CMMN, continuation jobs included),
     * repeating until nothing is left (bounded). A job that throws is left to Flowable's failed-job handling
     * (retries decremented, re-scheduled as a timer) and is not counted.
     */
    @Override
    public int executeDueJobs() {
        int executed = 0;
        for (int attempt = 1; ; attempt++) {
            try {
                inEngineTransaction(c -> sweepExpired(c, null));
                break;
            } catch (RuntimeException e) {
                if (!isTransientConflict(e) || attempt >= FETCH_ATTEMPTS) {
                    throw e;
                }
                backoff(attempt);
            }
        }
        for (int round = 0; round < MAX_JOB_ROUNDS; round++) {
            int progress = 0;
            for (Job timer : managementService.createTimerJobQuery().executable().list()) {
                if (isCmmn(timer)) {
                    cmmnManagementService.moveTimerToExecutableJob(timer.getId());
                } else {
                    managementService.moveTimerToExecutableJob(timer.getId());
                }
                progress++;
            }
            for (Job job : managementService.createJobQuery().list()) {
                try {
                    if (isCmmn(job)) {
                        cmmnManagementService.executeJob(job.getId());
                    } else {
                        managementService.executeJob(job.getId());
                    }
                    executed++;
                    progress++;
                } catch (RuntimeException e) {
                    // left to the failed-job handler
                }
            }
            if (progress == 0) {
                break;
            }
        }
        return executed;
    }

    private static boolean isCmmn(Job job) {
        return ScopeTypes.CMMN.equals(job.getScopeType());
    }

    @Override
    public void setClock(Instant now) {
        // The DMN and CMMN engines and the job service share this Clock object (AbstractEngineConfigurator.initClock).
        processEngineConfiguration.getClock().setCurrentTime(Date.from(now));
    }

    // ---------------------------------------------------------------- Q1: shared transaction

    /**
     * LEDGER-F4: runs {@code work} inside a Flowable command and hands it the JDBC connection of the command's
     * MyBatis session (DbSqlSession, an impl class). Engine calls made by {@code work} through this adapter join the
     * same command context and transaction.
     *
     * <p>LEDGER-F5: on rollback the connection is rolled back explicitly. Flowable's standalone transaction context calls
     * {@code SqlSession.rollback()}, which MyBatis skips when MyBatis itself has not written anything (dirty flag), and
     * MyBatis then closes the connection with {@code setAutoCommit(true)}, which commits pending JDBC work.
     *
     * <p>LEDGER-F11: the outermost call runs the continuation jobs registered by {@code completeExternal} /
     * {@code bpmnErrorExternal} after its command has committed ({@link ContinuationMode#AFTER_COMMIT}); after a
     * rollback there is nothing to run.
     */
    @Override
    public <T> T inEngineTransaction(Function<Connection, T> work) {
        if (pendingContinuations.get() != null || Context.getCommandContext() != null) {
            return inCommand(work); // nested: joins the caller's command; the outermost call drains
        }
        List<String> pending = new ArrayList<>();
        pendingContinuations.set(pending);
        T result;
        try {
            result = inCommand(work);
        } finally {
            pendingContinuations.remove();
        }
        if (!pending.isEmpty()) {
            runContinuations(pending);
        }
        return result;
    }

    private <T> T inCommand(Function<Connection, T> work) {
        return managementService.executeCommand(ctx -> {
            Connection c = currentConnection(ctx);
            TransactionContext tx = Context.getTransactionContext();
            if (Boolean.getBoolean("sangam.flowable.disableExplicitRollback")) {
                return work.apply(c); // experiment switch only: shows whether LEDGER-F5 is needed
            }
            tx.addTransactionListener(TransactionState.ROLLINGBACK, cc -> {
                try {
                    c.rollback();
                } catch (SQLException e) {
                    throw new IllegalStateException("rollback of the engine connection failed", e);
                }
            });
            return work.apply(c);
        });
    }

    private static Connection currentConnection(CommandContext ctx) {
        return ctx.getSession(DbSqlSession.class).getSqlSession().getConnection();
    }

    /**
     * Test support for Q1: inside {@link #inEngineTransaction}, flushes pending engine inserts/updates to the shared
     * connection now (instead of at command close) so a test can see engine rows on the same connection.
     */
    public void flushEngineSession() {
        CommandContext ctx = Context.getCommandContext();
        if (ctx == null) {
            throw new IllegalStateException("no active engine command");
        }
        ctx.getSession(DbSqlSession.class).flush();
    }

    @Override
    public String jdbcUrl() {
        return jdbcUrl;
    }

    // ---------------------------------------------------------------- accessors for other spike modules

    public ProcessEngine processEngine() {
        return processEngine;
    }

    public CmmnEngineConfiguration cmmnEngineConfiguration() {
        return cmmnEngineConfiguration;
    }

    public DmnEngineConfiguration dmnEngineConfiguration() {
        return dmnEngineConfiguration;
    }

    @Override
    public void close() {
        try {
            processEngine.close();
        } finally {
            if (ownedDataSource != null) {
                ownedDataSource.close();
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Instant toInstant(Date d) {
        return d == null ? null : d.toInstant();
    }

    private static Map<String, Object> copy(Map<String, Object> m) {
        return m == null ? Map.of() : new HashMap<>(m);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
