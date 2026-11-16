// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sangam.core.api.ExpiryPolicy;
import org.sangam.core.engine.AuditSink;
import org.sangam.core.engine.EngineEventSink;
import org.sangam.core.engine.EngineMetrics;
import org.sangam.core.engine.IncidentSink;
import org.sangam.core.engine.JobIdentityBinder;
import org.sangam.core.engine.TimerCatchUpPolicy;
import org.sangam.core.engine.WorkCalendar;
import org.sangam.core.history.HistoryArchiveGate;

/**
 * Typed configuration facade of the Sangam engine (FLOWABLE-INTO-OPERATON D-53): every setting Sangam changes on Operaton's 5,000-line
 * mutable configuration is here, with the Sangam default and the requirement it serves. JavaBean style, so
 * sangam-server can bind it with {@code @ConfigurationProperties("sangam.engine")}.
 *
 * <p><b>M-098 (SANGAM.md §1.2 W7): frozen after bootstrap.</b> {@link SangamEngineConfigurer#configure} calls
 * {@link #freeze()} once it has applied the settings and registered the plugin; the plugin and every Sangam engine
 * component read them only after that. From then on every setter and {@link #applyEnvironment} throw
 * {@link IllegalStateException}, and the map and set getters return unmodifiable copies, so a running engine's settings
 * cannot drift from what it was built with. Before the freeze (binding, host wiring, tests) the bean is as mutable as
 * before.
 */
public class SangamEngineSettings {

    /** M-098: set once by {@link #freeze()}; never cleared. */
    private volatile boolean frozen;

    // ------------------------------------------------------------------ engine
    private String processEngineName = "sangam";
    /** Tenant used for L2 / audit rows of instances deployed without a tenant id. */
    private String defaultTenantId = "default";
    private String systemAuditScope;
    private String history = "full";
    private boolean jobExecutorActivate = false;
    /**
     * SG-19: Operaton {@code databaseSchemaUpdate} ("false", "true", "create-drop", ...). {@code null} (default) keeps
     * the value the engine configuration already has (Spring: {@code operaton.bpm.database.schema-update} from the
     * profile, "false" on every production database profile). Sangam never forces engine DDL at start-up: schema
     * changes on production go only through the controlled {@code migrate-only} job (deploy README section 2).
     */
    private String engineSchemaUpdate;
    /** FLOWABLE-INTO-OPERATON D-38/FLOWABLE-INTO-OPERATON D-10: one engine per JVM because ClockUtil is static; tests may lift it. */
    private boolean allowMultipleEnginesPerJvm = false;
    /** Run the product changelog before building the engine (R12; production may run it as a Job instead). */
    private boolean applyProductSchema = true;
    /** Run P-1/P-2 after the engine created/upgraded its tables (MySQL only). */
    private boolean applyEnginePatches = true;
    /** IR-8: partition ACT_HI_* by REMOVAL_TIME_ after the build (PostgreSQL, Oracle, SQL Server); env SANGAM_PARTITIONED_HISTORY. */
    private boolean partitionedHistory = false;
    /** FLOWABLE-INTO-OPERATON D-05: the Operaton databaseType to pin on MySQL servers whatever the driver says ("mysql" or "mariadb"). */
    private String mysqlDatabaseType = "mysql";
    /** FLOWABLE-INTO-OPERATON D-56 / R84: SQL Server must run with READ_COMMITTED_SNAPSHOT ON. */
    private boolean requireSqlServerRcsi = true;
    /** Decisions 156 and 205: allow an experimental CockroachDB server (never supported, never silent); env SANGAM_EXPERIMENTAL_COCKROACHDB. */
    private boolean experimentalCockroachDb = false;
    /**
     * M-108, decisions 156 and 323: allow an experimental YugabyteDB YSQL server (never supported, never silent; READ
     * COMMITTED must be in effect); env SANGAM_EXPERIMENTAL_YUGABYTEDB.
     */
    private boolean experimentalYugabyteDb = false;
    /** FLOWABLE-INTO-OPERATON D-06: MySQL JDBC statement timeout in seconds (also applied on the other databases; 0 = off). */
    private int jdbcStatementTimeoutSeconds = 30;
    /**
     * SL-59 (S-R-5 "never hang", P-419): socket read bound set on checkout on every connection of the engine's
     * DataSource that has no network timeout of its own ({@link org.sangam.engine.db.JdbcWaitBounds#boundNetwork});
     * ends reads on dead connections, which no statement timeout can cancel. Must exceed the longest legitimate
     * statement; {@code 0} disables it. A host-set value on the connection is never changed.
     */
    private Duration networkTimeout = org.sangam.engine.db.JdbcWaitBounds.DEFAULT_NETWORK_TIMEOUT;

    // ------------------------------------------------------------------ VI.1 locks and retries
    private Duration lockTimeout = Duration.ofSeconds(2);
    private Duration statementTimeout = Duration.ofSeconds(5);
    private int retryMaxAttempts = 5;
    private Duration retryBase = Duration.ofMillis(20);
    private Duration retryCap = Duration.ofSeconds(1);
    /** L2 backstop at flush for commands that did not lock their roots first (Operaton REST, delegates). */
    private boolean rootLockAtFlush = true;

    // ------------------------------------------------------------------ VI.3 worker leases
    private int fetchAttempts = 20;
    private Duration maxLease = Duration.ofHours(24);

    // ------------------------------------------------------------------ jobs and timers (A-12, R31, FLOWABLE-INTO-OPERATON FC-24)
    private int maxJobExecutionsPerCall = 1_000;
    private int maxExecutionsPerJobDefinitionPerCall = 50;
    /**
     * SL-53 ({@code sangam.engine.timer-zone}): the business zone of timer calendars. Calendar-cadence cycles
     * ({@code R/P1D}, {@code R/P1M}), cron expressions, the date part of durations ({@code P1D}) and the wall-clock
     * buckets of the DST timer guard are computed in this zone; elapsed periods ({@code PT1H}) run on the UTC timeline.
     * Never the JVM zone: the JVM runs in UTC ({@link org.sangam.core.time.EngineTimeZone}), so a date-time literal
     * without an offset is UTC. Default UTC; an embedding host sets it for business cut-offs (for example Asia/Kolkata).
     * Business dates use {@link org.sangam.core.time.BusinessClock}.
     */
    private ZoneId timerZone = ZoneOffset.UTC;
    private TimerCatchUpPolicy timerCatchUpDefault = TimerCatchUpPolicy.FIRE_ONCE;
    private Map<String, TimerCatchUpPolicy> timerCatchUpByProcessKey = new LinkedHashMap<>();
    private int maxCatchUpFires = 100;
    private Map<String, WorkCalendar> workCalendars = new LinkedHashMap<>();
    /** FLOWABLE-INTO-OPERATON FC-24 (config route): SKIP LOCKED acquisition, prioritized jobs, timers first. */
    private boolean jobAcquireWithSkipLocked = true;
    private boolean jobPreferTimers = true;

    // ------------------------------------------------------------------ deployment validation (FLOWABLE-INTO-OPERATON FC-46)
    /** GP-09 / FLOWABLE-INTO-OPERATON D-23: multi-instance above this cardinality is rejected (deploy: literal; runtime: evaluated). */
    // Same default as WS-PACKS' ModelGuard.MAX_MULTI_INSTANCE (50), so the engine and the pack guard never disagree.
    private int multiInstanceMaxCardinality = 50;
    /** FLOWABLE-INTO-OPERATON D-46 / R6: Release 1 has no in-engine scripts. */
    private boolean rejectScriptTasks = true;
    /** FLOWABLE-INTO-OPERATON D-08: CMMN event listeners deploy silently but never fire on Operaton 2.2. */
    private boolean rejectCmmnEventListeners = true;
    /** FLOWABLE-INTO-OPERATON FC-55 (WS-PORTS) enabled: CMMN event listeners are executed, so FLOWABLE-INTO-OPERATON D-08 must not reject them. */
    private boolean cmmnEventListenersEnabled = false;
    /** FLOWABLE-INTO-OPERATON D-12: decisionRefBinding / calledElementBinding default to "deployment" instead of "latest". */
    private boolean defaultBindingDeployment = true;
    /** FLOWABLE-INTO-OPERATON D-51 / R24: DOCTYPE declarations are rejected at deploy. */
    private boolean rejectDoctype = true;
    /**
     * SL-160 / TM-10: tenant-approved Java delegate and listener classes a model may name, beyond Sangam's own
     * ({@code ModelCodeAllowList.SANGAM_CLASSES}); key = tenant id, or {@code *} for every tenant. Anything else is refused
     * at deploy and runs as an external worker.
     */
    private Map<String, Set<String>> deployAllowedClasses = new LinkedHashMap<>();
    /** SL-160: tenant-approved delegate beans ({@code delegateExpression="${bean}"}); key = tenant id or {@code *}. */
    private Map<String, Set<String>> deployAllowedBeans = new LinkedHashMap<>();

    // ------------------------------------------------------------------ history and retention (FLOWABLE-INTO-OPERATON D-13, FLOWABLE-INTO-OPERATON FC-26, R61)
    /** Days of history TTL injected for retention class "default"; null = never cleaned (R61-safe). */
    private Integer defaultHistoryTtlDays = null;
    /** Retention class (sangam:retentionClass) -> TTL days; a class mapped to null is kept forever. */
    private Map<String, Integer> historyTtlDaysByRetentionClass = new LinkedHashMap<>();
    /** FLOWABLE-INTO-OPERATON FC-26: history level (none, activity, audit, full) per {@code processKey} or {@code processKey#activityId}. */
    private Map<String, String> historyLevelByProcessKey = new LinkedHashMap<>();
    /** History event types fed into the audit chain (Operaton HistoryEventTypes names). */
    private Set<String> auditedHistoryEvents = new LinkedHashSet<>(List.of(
            "PROCESS_INSTANCE_START", "PROCESS_INSTANCE_END", "PROCESS_INSTANCE_MIGRATE",
            "TASK_INSTANCE_CREATE", "TASK_INSTANCE_UPDATE", "TASK_INSTANCE_COMPLETE", "TASK_INSTANCE_DELETE",
            "INCIDENT_CREATE", "INCIDENT_RESOLVE", "INCIDENT_DELETE",
            "CASE_INSTANCE_CREATE", "CASE_INSTANCE_CLOSE", "CASE_ACTIVITY_INSTANCE_END"));
    /** History event types published as typed engine events (FLOWABLE-INTO-OPERATON FC-43 / FLOWABLE-INTO-OPERATON FC-25); empty = all. */
    private Set<String> publishedHistoryEvents = new LinkedHashSet<>();

    // ------------------------------------------------------------------ variables (FLOWABLE-INTO-OPERATON D-07, FLOWABLE-INTO-OPERATON FC-28, FLOWABLE-INTO-OPERATON FC-29, FLOWABLE-INTO-OPERATON D-47)
    /** Strings longer than this go to a byte array; null = the database's TEXT_ limit (Oracle 2,000, else 4,000). */
    private Integer largeStringThresholdChars = null;


    // ------------------------------------------------------------------ IR-7 job executor (SCALE 3 defaults)
    // Env (applied by the configurer when applyEnvironment=true): SANGAM_JOB_CORE_POOL_SIZE, SANGAM_JOB_MAX_POOL_SIZE,
    // SANGAM_JOB_QUEUE_CAPACITY, SANGAM_JOB_MAX_JOBS_PER_ACQUISITION, SANGAM_JOB_WAIT_TIME_IN_MILLIS,
    // SANGAM_JOB_WAIT_INCREASE_FACTOR, SANGAM_JOB_MAX_WAIT, SANGAM_JOB_LOCK_TIME_IN_MILLIS, SANGAM_JOB_BACKOFF_TIME_IN_MILLIS,
    // SANGAM_JOB_MAX_BACKOFF, SANGAM_JOB_ACQUIRE_BY_DUE_DATE, SANGAM_JOB_ACQUIRE_BY_PRIORITY,
    // SANGAM_JOB_EXCLUSIVE_OVER_PROCESS_HIERARCHIES, SANGAM_JOB_LOCK_OWNER, SANGAM_JOB_EXECUTOR_ACTIVATE,
    // SANGAM_JOB_VIRTUAL_THREADS (M-054).
    private boolean applyEnvironment = true;
    private int jobCorePoolSize = 16;
    private int jobMaxPoolSize = 32;
    private int jobQueueCapacity = 64;
    private int jobMaxJobsPerAcquisition = 20;
    private int jobWaitTimeInMillis = 500;
    private float jobWaitIncreaseFactor = 2.0f;
    private long jobMaxWait = 5_000;
    private int jobLockTimeInMillis = 300_000;
    private int jobBackoffTimeInMillis = 0;
    private long jobMaxBackoff = 0;
    private boolean jobAcquireByDueDate = true;
    private boolean jobAcquireByPriority = true;
    /**
     * Exclusive jobs of a whole process hierarchy (call activities) acquired together by one node: an exclusive job is
     * not acquired while another exclusive job of the same <b>root</b> instance holds a live lock, and one acquisition
     * batches a root's exclusive jobs (SANGAM.md 4.3 item 3, "on by default"; M-055). Execution of a root's jobs is
     * already serialised by the L2 root lock ({@code GuardedJobHandler}), so this moves the wait from a blocked
     * {@code ROOT_SEQ} row lock to acquisition. {@code false} restores Operaton's per-instance rule; env
     * {@code SANGAM_JOB_EXCLUSIVE_OVER_PROCESS_HIERARCHIES}.
     */
    private boolean jobExclusiveOverProcessHierarchies = true;
    private String jobLockOwner;
    /**
     * M-054 (SANGAM.md §4.4, R5, C7): the job executor's pool threads are JDK virtual threads instead of platform
     * threads. Off by default: Operaton keeps its command context in ThreadLocal stacks (I-6), which work per virtual
     * thread as per platform thread, but the throughput effect needs the soak evidence (R30) before any default change.
     * Pool size, queue and rejection stay exactly as configured above; env {@code SANGAM_JOB_VIRTUAL_THREADS}.
     */
    private boolean jobVirtualThreads = false;
    /**
     * M-054 (C7 "semaphore per job type"): job handler type (for example {@code async-continuation},
     * {@code timer-transition}) -> the most executions of that type running at once on this node. Empty (default) =
     * no bulkhead. A job whose type is at its limit waits up to {@link #jobBulkheadWait} on the executor thread,
     * outside any transaction, and is then handed back unexecuted (its lock released, its retries unchanged).
     */
    private Map<String, Integer> jobBulkheads = new LinkedHashMap<>();
    /** M-054: the longest a job waits for its type's bulkhead before it is handed back; 0 = hand back at once. */
    private Duration jobBulkheadWait = Duration.ofSeconds(10);
    /**
     * M-056 (R39, SANGAM.md X.2.1): partitioned executors on this node. OFF by default and gated by T54 (X.2.1.1); off
     * means the engine never reads or writes {@code EXEC_*} rows. On: new jobs get an {@code EXEC_JOB_SLOT} row and
     * this node's job acquisition is filtered by the slots it owns (contract W2-JOB-SIDE-TABLES-CONTRACT.md).
     */
    private boolean executorPartitioning = false;
    /** M-056: the cluster's fixed slot count S (X.2.1.2 rule 1), 1 to 65,536; never changed online. */
    private int executorSlots = org.sangam.engine.job.ExecutorSlots.DEFAULT_SLOTS;
    /** M-056 (X.2.1.3, X.2.1.4, X.2.1.6): partitions of a new map, lease, safety margin, drain and recovery limits. */
    /**
     * M-057 (owner decision 88): honour {@code sangam:historyLevel} declared in models. OFF by default; on installs the
     * per-definition history level (id 21 in {@code ACT_GE_PROPERTY}), so an existing database keeps its level until an
     * operator turns this on (Operaton refuses a start whose level differs from the stored one).
     */
    private boolean historyModelOverrides = false;
    /**
     * M-021 {@code sangam.test-mode.enabled}: clock steering for test images. OFF by default; on, the engine refuses to
     * start unless the database is H2 and none of {@link #activeProfiles} is a production profile.
     */
    private boolean testMode = false;
    /** The host's active profiles (the server passes Spring's), for the M-021 production-profile refusal. */
    private Set<String> activeProfiles = Set.of();
    /** Decision 151: the replay capture listener; null = off (every production engine). */
    private org.sangam.engine.replay.CaptureListener captureListener;
    /** Decision 151: the host's production resolution given with the listener; null = not stated (capture refused). */
    private Boolean captureProduction;
    /**
     * M-044: the current context's W3C trace id, recorded for each new process instance ({@code PROCESS_TRACE}); the
     * server binds the current OpenTelemetry span. null = the trace id of {@link #getTracing()}'s current context.
     */
    private java.util.function.Supplier<String> traceIdSource;
    /** M-068: pins {@code sangam:operationRef} at deployment (the server binds the API contract store); null = none. */
    private org.sangam.engine.deploy.OperationRefPinning.Pinner operationRefPinner;
    /**
     * M-057 {@code external} history mode: {@code ACT_HI_*} keeps only the audit level for every definition and the
     * full detail goes through the outbox to the external projector (OpenSearch/ES, Kafka, data lake; upsert by
     * event id). OFF by default; refused at startup without an {@code EngineEventSink}. Class-B audit is unchanged.
     */
    private boolean historyExternal = false;
    private int executorPartitions = 8;
    private String executorClusterId = "sangam";
    private Duration executorLeaseTtl = Duration.ofSeconds(15);
    private Duration executorLeaseSafetyMargin = Duration.ofSeconds(3);
    private Duration executorDrainTimeout = Duration.ofSeconds(30);
    private int executorRecoveryBatch = 500;
    private int executorMaxRecoveries = 3;
    /**
     * M-062 (R13): store the creating trace context of every new job and external task ({@code JOB_TRACE_CONTEXT}) so
     * job spans and worker spans can link to it. OFF by default; needs a bound {@link #tracing} implementation.
     */
    private boolean traceLinks = false;

    // ------------------------------------------------------------------ IR-5 outbox-only history
    /** Process/case definition keys whose ACT_HI_* keep only the audit level; full detail only via outbox/read model. */
    private Set<String> outboxOnlyHistoryKeys = new LinkedHashSet<>();

    // ------------------------------------------------------------------ ports
    private ExpiryPolicy expiryPolicy;
    private AuditSink auditSink;
    private IncidentSink incidentSink;
    private EngineEventSink eventSink = EngineEventSink.NONE;
    private EngineMetrics metrics = EngineMetrics.NONE;
    /** VI.4 rule 7: binds the tenant's system identity around job execution; server: {@code TenantContext::runAs}. */
    private JobIdentityBinder jobIdentityBinder;
    /**
     * RM-52 (decision 386): asked before the engine deletes the history of a process tree (every
     * {@code HistoryService} delete, the history cleanup, a cascade delete); a tree it refuses keeps its history and
     * the command fails. Default: {@link HistoryArchiveGate#notConfigured()}, which refuses every tree, so history is
     * never purged until a host configures the archive (server: {@code sangam.history.archive.*}).
     */
    private HistoryArchiveGate historyArchiveGate = HistoryArchiveGate.notConfigured();
    /**
     * M-062 (R13): engine spans per command and job. {@link org.sangam.engine.trace.EngineTracing#NONE} (default)
     * installs nothing; outbound telemetry stays off by default (R56).
     */
    private org.sangam.engine.trace.EngineTracing tracing = org.sangam.engine.trace.EngineTracing.NONE;

    // ------------------------------------------------------------------ getters / setters

    public String getProcessEngineName() { return processEngineName; }
    public void setProcessEngineName(String v) { mutable("processEngineName"); processEngineName = v; }
    public String getDefaultTenantId() { return defaultTenantId; }
    /**
     * SL-91: the tenant key of untenanted engine rows (L2 root lock, job identity, incidents, history) is bound into NOT
     * NULL key columns, and Oracle stores '' as NULL; a null or blank value is refused here, not only by the server wiring.
     */
    public void setDefaultTenantId(String v) {
        mutable("defaultTenantId");
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("defaultTenantId must not be null or blank");
        }
        defaultTenantId = v;
    }
    /**
     * Decision 144 (owner 197): the system audit scope ({@code system:<name>}) that untenanted engine work is attributed
     * to: registered shared deployments, incidents whose tenant could not be resolved, executor and shared-deployment
     * audit. No default; every engine start refuses a missing or invalid value
     * ({@code SystemAuditScope.validate}, setting {@code sangam.engine.system-audit-scope}).
     */
    public String getSystemAuditScope() { return systemAuditScope; }
    public void setSystemAuditScope(String v) { mutable("systemAuditScope"); systemAuditScope = v; }
    public String getHistory() { return history; }
    public void setHistory(String v) { mutable("history"); history = v; }
    public String getEngineSchemaUpdate() { return engineSchemaUpdate; }
    public void setEngineSchemaUpdate(String v) { mutable("engineSchemaUpdate"); engineSchemaUpdate = v; }
    public boolean isJobExecutorActivate() { return jobExecutorActivate; }
    public void setJobExecutorActivate(boolean v) { mutable("jobExecutorActivate"); jobExecutorActivate = v; }
    public boolean isAllowMultipleEnginesPerJvm() { return allowMultipleEnginesPerJvm; }
    public void setAllowMultipleEnginesPerJvm(boolean v) { mutable("allowMultipleEnginesPerJvm"); allowMultipleEnginesPerJvm = v; }
    public boolean isApplyProductSchema() { return applyProductSchema; }
    public void setApplyProductSchema(boolean v) { mutable("applyProductSchema"); applyProductSchema = v; }
    public boolean isPartitionedHistory() { return partitionedHistory; }
    public void setPartitionedHistory(boolean v) { mutable("partitionedHistory"); partitionedHistory = v; }
    public boolean isApplyEnginePatches() { return applyEnginePatches; }
    public void setApplyEnginePatches(boolean v) { mutable("applyEnginePatches"); applyEnginePatches = v; }
    public String getMysqlDatabaseType() { return mysqlDatabaseType; }
    public void setMysqlDatabaseType(String v) { mutable("mysqlDatabaseType"); mysqlDatabaseType = v; }
    public boolean isRequireSqlServerRcsi() { return requireSqlServerRcsi; }
    public void setRequireSqlServerRcsi(boolean v) { mutable("requireSqlServerRcsi"); requireSqlServerRcsi = v; }
    public boolean isExperimentalCockroachDb() { return experimentalCockroachDb; }
    public void setExperimentalCockroachDb(boolean v) { mutable("experimentalCockroachDb"); experimentalCockroachDb = v; }
    public boolean isExperimentalYugabyteDb() { return experimentalYugabyteDb; }
    public void setExperimentalYugabyteDb(boolean v) { mutable("experimentalYugabyteDb"); experimentalYugabyteDb = v; }
    public int getJdbcStatementTimeoutSeconds() { return jdbcStatementTimeoutSeconds; }
    public void setJdbcStatementTimeoutSeconds(int v) { mutable("jdbcStatementTimeoutSeconds"); jdbcStatementTimeoutSeconds = v; }
    public Duration getNetworkTimeout() { return networkTimeout; }
    public void setNetworkTimeout(Duration v) { mutable("networkTimeout"); networkTimeout = v == null ? Duration.ZERO : v; }
    public Duration getLockTimeout() { return lockTimeout; }
    public void setLockTimeout(Duration v) { mutable("lockTimeout"); lockTimeout = v; }
    public Duration getStatementTimeout() { return statementTimeout; }
    public void setStatementTimeout(Duration v) { mutable("statementTimeout"); statementTimeout = v; }
    public int getRetryMaxAttempts() { return retryMaxAttempts; }
    public void setRetryMaxAttempts(int v) { mutable("retryMaxAttempts"); retryMaxAttempts = v; }
    public Duration getRetryBase() { return retryBase; }
    public void setRetryBase(Duration v) { mutable("retryBase"); retryBase = v; }
    public Duration getRetryCap() { return retryCap; }
    public void setRetryCap(Duration v) { mutable("retryCap"); retryCap = v; }
    public boolean isRootLockAtFlush() { return rootLockAtFlush; }
    public void setRootLockAtFlush(boolean v) { mutable("rootLockAtFlush"); rootLockAtFlush = v; }
    public int getFetchAttempts() { return fetchAttempts; }
    public void setFetchAttempts(int v) { mutable("fetchAttempts"); fetchAttempts = v; }
    public Duration getMaxLease() { return maxLease; }
    public void setMaxLease(Duration v) { mutable("maxLease"); maxLease = v; }
    public int getMaxJobExecutionsPerCall() { return maxJobExecutionsPerCall; }
    public void setMaxJobExecutionsPerCall(int v) { mutable("maxJobExecutionsPerCall"); maxJobExecutionsPerCall = v; }
    public int getMaxExecutionsPerJobDefinitionPerCall() { return maxExecutionsPerJobDefinitionPerCall; }
    public void setMaxExecutionsPerJobDefinitionPerCall(int v) { mutable("maxExecutionsPerJobDefinitionPerCall"); maxExecutionsPerJobDefinitionPerCall = v; }
    public ZoneId getTimerZone() { return timerZone; }
    public void setTimerZone(ZoneId v) { mutable("timerZone"); timerZone = java.util.Objects.requireNonNull(v, "sangam.engine.timer-zone"); }
    public TimerCatchUpPolicy getTimerCatchUpDefault() { return timerCatchUpDefault; }
    public void setTimerCatchUpDefault(TimerCatchUpPolicy v) { mutable("timerCatchUpDefault"); timerCatchUpDefault = v; }
    public Map<String, TimerCatchUpPolicy> getTimerCatchUpByProcessKey() { return timerCatchUpByProcessKey; }
    public void setTimerCatchUpByProcessKey(Map<String, TimerCatchUpPolicy> v) { mutable("timerCatchUpByProcessKey"); timerCatchUpByProcessKey = v; }
    public int getMaxCatchUpFires() { return maxCatchUpFires; }
    public void setMaxCatchUpFires(int v) { mutable("maxCatchUpFires"); maxCatchUpFires = v; }
    public Map<String, WorkCalendar> getWorkCalendars() { return workCalendars; }
    public void setWorkCalendars(Map<String, WorkCalendar> v) { mutable("workCalendars"); workCalendars = v; }
    public boolean isJobAcquireWithSkipLocked() { return jobAcquireWithSkipLocked; }
    public void setJobAcquireWithSkipLocked(boolean v) { mutable("jobAcquireWithSkipLocked"); jobAcquireWithSkipLocked = v; }
    public boolean isJobPreferTimers() { return jobPreferTimers; }
    public void setJobPreferTimers(boolean v) { mutable("jobPreferTimers"); jobPreferTimers = v; }
    public int getMultiInstanceMaxCardinality() { return multiInstanceMaxCardinality; }
    public void setMultiInstanceMaxCardinality(int v) { mutable("multiInstanceMaxCardinality"); multiInstanceMaxCardinality = v; }
    public boolean isRejectScriptTasks() { return rejectScriptTasks; }
    public void setRejectScriptTasks(boolean v) { mutable("rejectScriptTasks"); rejectScriptTasks = v; }
    /** FLOWABLE-INTO-OPERATON D-08 rejection is in force unless FLOWABLE-INTO-OPERATON FC-55 is enabled. */
    public boolean isRejectCmmnEventListeners() { return rejectCmmnEventListeners && !cmmnEventListenersEnabled; }
    public boolean isCmmnEventListenersEnabled() { return cmmnEventListenersEnabled; }
    public void setCmmnEventListenersEnabled(boolean v) { mutable("cmmnEventListenersEnabled"); cmmnEventListenersEnabled = v; }
    public void setRejectCmmnEventListeners(boolean v) { mutable("rejectCmmnEventListeners"); rejectCmmnEventListeners = v; }
    public boolean isDefaultBindingDeployment() { return defaultBindingDeployment; }
    public void setDefaultBindingDeployment(boolean v) { mutable("defaultBindingDeployment"); defaultBindingDeployment = v; }
    public boolean isRejectDoctype() { return rejectDoctype; }
    public void setRejectDoctype(boolean v) { mutable("rejectDoctype"); rejectDoctype = v; }
    public Integer getDefaultHistoryTtlDays() { return defaultHistoryTtlDays; }
    public void setDefaultHistoryTtlDays(Integer v) { mutable("defaultHistoryTtlDays"); defaultHistoryTtlDays = v; }
    public Map<String, Integer> getHistoryTtlDaysByRetentionClass() { return historyTtlDaysByRetentionClass; }
    public void setHistoryTtlDaysByRetentionClass(Map<String, Integer> v) { mutable("historyTtlDaysByRetentionClass"); historyTtlDaysByRetentionClass = v; }
    public Map<String, String> getHistoryLevelByProcessKey() { return historyLevelByProcessKey; }
    public void setHistoryLevelByProcessKey(Map<String, String> v) { mutable("historyLevelByProcessKey"); historyLevelByProcessKey = v; }
    public Set<String> getAuditedHistoryEvents() { return auditedHistoryEvents; }
    public void setAuditedHistoryEvents(Set<String> v) { mutable("auditedHistoryEvents"); auditedHistoryEvents = v; }
    public Set<String> getPublishedHistoryEvents() { return publishedHistoryEvents; }
    public void setPublishedHistoryEvents(Set<String> v) { mutable("publishedHistoryEvents"); publishedHistoryEvents = v; }
    public Integer getLargeStringThresholdChars() { return largeStringThresholdChars; }
    public void setLargeStringThresholdChars(Integer v) { mutable("largeStringThresholdChars"); largeStringThresholdChars = v; }
    public ExpiryPolicy getExpiryPolicy() { return expiryPolicy; }
    public void setExpiryPolicy(ExpiryPolicy v) { mutable("expiryPolicy"); expiryPolicy = v; }
    public AuditSink getAuditSink() { return auditSink; }
    public void setAuditSink(AuditSink v) { mutable("auditSink"); auditSink = v; }
    public HistoryArchiveGate getHistoryArchiveGate() { return historyArchiveGate; }
    /** RM-52: a null gate is refused; there is no "purge without archive" setting. */
    public void setHistoryArchiveGate(HistoryArchiveGate v) {
        mutable("historyArchiveGate");
        if (v == null) {
            throw new IllegalArgumentException("historyArchiveGate must not be null");
        }
        historyArchiveGate = v;
    }
    public IncidentSink getIncidentSink() { return incidentSink; }
    public void setIncidentSink(IncidentSink v) { mutable("incidentSink"); incidentSink = v; }
    public EngineEventSink getEventSink() { return eventSink; }
    public void setEventSink(EngineEventSink v) { mutable("eventSink"); eventSink = v == null ? EngineEventSink.NONE : v; }
    public JobIdentityBinder getJobIdentityBinder() { return jobIdentityBinder; }
    public void setJobIdentityBinder(JobIdentityBinder v) { mutable("jobIdentityBinder"); jobIdentityBinder = v; }
    public EngineMetrics getMetrics() { return metrics; }
    public void setMetrics(EngineMetrics v) { mutable("metrics"); metrics = v == null ? EngineMetrics.NONE : v; }
    /** ADR-D-25: object check for route corrections (PROCESS_INSTANCE / MODIFY). Unset = every correction denied. */
    private org.sangam.core.security.SecurityPorts.Authorization routeCorrectionAuthorization;
    public org.sangam.core.security.SecurityPorts.Authorization getRouteCorrectionAuthorization() { return routeCorrectionAuthorization; }
    public void setRouteCorrectionAuthorization(org.sangam.core.security.SecurityPorts.Authorization v) { mutable("routeCorrectionAuthorization"); routeCorrectionAuthorization = v; }

    public boolean isApplyEnvironment() { return applyEnvironment; }
    public void setApplyEnvironment(boolean v) { mutable("applyEnvironment"); applyEnvironment = v; }
    public int getJobCorePoolSize() { return jobCorePoolSize; }
    public void setJobCorePoolSize(int v) { mutable("jobCorePoolSize"); jobCorePoolSize = v; }
    public int getJobMaxPoolSize() { return jobMaxPoolSize; }
    public void setJobMaxPoolSize(int v) { mutable("jobMaxPoolSize"); jobMaxPoolSize = v; }
    public int getJobQueueCapacity() { return jobQueueCapacity; }
    public void setJobQueueCapacity(int v) { mutable("jobQueueCapacity"); jobQueueCapacity = v; }
    public int getJobMaxJobsPerAcquisition() { return jobMaxJobsPerAcquisition; }
    public void setJobMaxJobsPerAcquisition(int v) { mutable("jobMaxJobsPerAcquisition"); jobMaxJobsPerAcquisition = v; }
    public int getJobWaitTimeInMillis() { return jobWaitTimeInMillis; }
    public void setJobWaitTimeInMillis(int v) { mutable("jobWaitTimeInMillis"); jobWaitTimeInMillis = v; }
    public float getJobWaitIncreaseFactor() { return jobWaitIncreaseFactor; }
    public void setJobWaitIncreaseFactor(float v) { mutable("jobWaitIncreaseFactor"); jobWaitIncreaseFactor = v; }
    public long getJobMaxWait() { return jobMaxWait; }
    public void setJobMaxWait(long v) { mutable("jobMaxWait"); jobMaxWait = v; }
    public int getJobLockTimeInMillis() { return jobLockTimeInMillis; }
    public void setJobLockTimeInMillis(int v) { mutable("jobLockTimeInMillis"); jobLockTimeInMillis = v; }
    public int getJobBackoffTimeInMillis() { return jobBackoffTimeInMillis; }
    public void setJobBackoffTimeInMillis(int v) { mutable("jobBackoffTimeInMillis"); jobBackoffTimeInMillis = v; }
    public long getJobMaxBackoff() { return jobMaxBackoff; }
    public void setJobMaxBackoff(long v) { mutable("jobMaxBackoff"); jobMaxBackoff = v; }
    public boolean isJobAcquireByDueDate() { return jobAcquireByDueDate; }
    public void setJobAcquireByDueDate(boolean v) { mutable("jobAcquireByDueDate"); jobAcquireByDueDate = v; }
    public boolean isJobAcquireByPriority() { return jobAcquireByPriority; }
    public void setJobAcquireByPriority(boolean v) { mutable("jobAcquireByPriority"); jobAcquireByPriority = v; }
    public boolean isJobExclusiveOverProcessHierarchies() { return jobExclusiveOverProcessHierarchies; }
    public void setJobExclusiveOverProcessHierarchies(boolean v) { mutable("jobExclusiveOverProcessHierarchies"); jobExclusiveOverProcessHierarchies = v; }
    public String getJobLockOwner() { return jobLockOwner; }
    public void setJobLockOwner(String v) { mutable("jobLockOwner"); jobLockOwner = v; }
    public Set<String> getOutboxOnlyHistoryKeys() { return outboxOnlyHistoryKeys; }
    public void setOutboxOnlyHistoryKeys(Set<String> v) { mutable("outboxOnlyHistoryKeys"); outboxOnlyHistoryKeys = v; }
    public boolean isJobVirtualThreads() { return jobVirtualThreads; }
    public void setJobVirtualThreads(boolean v) { mutable("jobVirtualThreads"); jobVirtualThreads = v; }
    public Map<String, Integer> getJobBulkheads() { return jobBulkheads; }
    public void setJobBulkheads(Map<String, Integer> v) { mutable("jobBulkheads"); jobBulkheads = v; }
    public Duration getJobBulkheadWait() { return jobBulkheadWait; }
    public void setJobBulkheadWait(Duration v) { mutable("jobBulkheadWait"); jobBulkheadWait = v; }
    public boolean isExecutorPartitioning() { return executorPartitioning; }
    public void setExecutorPartitioning(boolean v) { mutable("executorPartitioning"); executorPartitioning = v; }
    public int getExecutorSlots() { return executorSlots; }
    public void setExecutorSlots(int v) { mutable("executorSlots"); executorSlots = v; }
    public boolean isHistoryExternal() { return historyExternal; }
    public void setHistoryExternal(boolean v) { mutable("historyExternal"); historyExternal = v; }
    public org.sangam.engine.deploy.OperationRefPinning.Pinner getOperationRefPinner() { return operationRefPinner; }
    public void setOperationRefPinner(org.sangam.engine.deploy.OperationRefPinning.Pinner v) { mutable("operationRefPinner"); operationRefPinner = v; }
    public java.util.function.Supplier<String> getTraceIdSource() { return traceIdSource; }
    public void setTraceIdSource(java.util.function.Supplier<String> v) { mutable("traceIdSource"); traceIdSource = v; }
    public boolean isTestMode() { return testMode; }
    public Set<String> getActiveProfiles() { return activeProfiles; }

    /**
     * M-021: the host's {@code sangam.test-mode.enabled} and its active profiles, set together. Deliberately not a bean
     * setter, so no {@code sangam.engine.*} property can switch test mode on or blank the profile list.
     */
    public void enableTestMode(boolean enabled, java.util.Collection<String> hostProfiles) {
        mutable("testMode");
        testMode = enabled;
        activeProfiles = hostProfiles == null ? Set.of() : Set.copyOf(hostProfiles);
    }
    public org.sangam.engine.replay.CaptureListener getCaptureListener() { return captureListener; }
    public Boolean getCaptureProduction() { return captureProduction; }

    /**
     * Decision 151: registers the replay capture listener of a test or replay engine (null = off) with the host's own
     * production resolution ({@code ProductionEnvironment} / {@code EnvironmentPolicy}). Deliberately not a bean
     * setter, so no {@code sangam.engine.*} property can switch capture on; the engine refuses to start with a
     * listener in production ({@link org.sangam.engine.replay.CaptureListener#requireAllowed}).
     */
    public void enableReplayCapture(org.sangam.engine.replay.CaptureListener listener, boolean production) {
        mutable("captureListener");
        captureListener = listener;
        captureProduction = production;
    }
    public boolean isHistoryModelOverrides() { return historyModelOverrides; }
    public void setHistoryModelOverrides(boolean v) { mutable("historyModelOverrides"); historyModelOverrides = v; }
    public int getExecutorPartitions() { return executorPartitions; }
    public void setExecutorPartitions(int v) { mutable("executorPartitions"); executorPartitions = v; }
    public String getExecutorClusterId() { return executorClusterId; }
    public void setExecutorClusterId(String v) { mutable("executorClusterId"); executorClusterId = v; }
    public Duration getExecutorLeaseTtl() { return executorLeaseTtl; }
    public void setExecutorLeaseTtl(Duration v) { mutable("executorLeaseTtl"); executorLeaseTtl = v; }
    public Duration getExecutorLeaseSafetyMargin() { return executorLeaseSafetyMargin; }
    public void setExecutorLeaseSafetyMargin(Duration v) { mutable("executorLeaseSafetyMargin"); executorLeaseSafetyMargin = v; }
    public Duration getExecutorDrainTimeout() { return executorDrainTimeout; }
    public void setExecutorDrainTimeout(Duration v) { mutable("executorDrainTimeout"); executorDrainTimeout = v; }
    public int getExecutorRecoveryBatch() { return executorRecoveryBatch; }
    public void setExecutorRecoveryBatch(int v) { mutable("executorRecoveryBatch"); executorRecoveryBatch = v; }
    public int getExecutorMaxRecoveries() { return executorMaxRecoveries; }
    public void setExecutorMaxRecoveries(int v) { mutable("executorMaxRecoveries"); executorMaxRecoveries = v; }
    public Map<String, Set<String>> getDeployAllowedClasses() { return deployAllowedClasses; }
    public void setDeployAllowedClasses(Map<String, Set<String>> v) { mutable("deployAllowedClasses"); deployAllowedClasses = v; }
    public Map<String, Set<String>> getDeployAllowedBeans() { return deployAllowedBeans; }
    public void setDeployAllowedBeans(Map<String, Set<String>> v) { mutable("deployAllowedBeans"); deployAllowedBeans = v; }
    public boolean isTraceLinks() { return traceLinks; }
    public void setTraceLinks(boolean v) { mutable("traceLinks"); traceLinks = v; }
    public org.sangam.engine.trace.EngineTracing getTracing() { return tracing; }
    public void setTracing(org.sangam.engine.trace.EngineTracing v) {
        mutable("tracing");
        tracing = v == null ? org.sangam.engine.trace.EngineTracing.NONE : v;
    }

    /**
     * M-098: freezes these settings (idempotent). Afterwards every setter and {@link #applyEnvironment} throw
     * {@link IllegalStateException}, and the map and set settings are replaced by unmodifiable copies in their order.
     * Called by {@link SangamEngineConfigurer#configure} when it completes.
     */
    public synchronized void freeze() {
        if (frozen) {
            return;
        }
        timerCatchUpByProcessKey = frozenMap(timerCatchUpByProcessKey);
        workCalendars = frozenMap(workCalendars);
        historyTtlDaysByRetentionClass = frozenMap(historyTtlDaysByRetentionClass);
        historyLevelByProcessKey = frozenMap(historyLevelByProcessKey);
        auditedHistoryEvents = frozenSet(auditedHistoryEvents);
        publishedHistoryEvents = frozenSet(publishedHistoryEvents);
        outboxOnlyHistoryKeys = frozenSet(outboxOnlyHistoryKeys);
        jobBulkheads = frozenMap(jobBulkheads);
        deployAllowedClasses = frozenMapOfSets(deployAllowedClasses);
        deployAllowedBeans = frozenMapOfSets(deployAllowedBeans);
        frozen = true;
    }

    /** M-098: {@code true} once {@link #freeze()} has run. Not a bindable property (no setter). */
    public boolean isFrozen() { return frozen; }

    private void mutable(String setting) {
        if (frozen) {
            throw new IllegalStateException("SangamEngineSettings are frozen after bootstrap (M-098): '" + setting
                    + "' cannot change once SangamEngineConfigurer.configure has run; set it before the engine is built");
        }
    }

    private static <K, V> Map<K, V> frozenMap(Map<K, V> m) {
        return m == null ? null : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }

    private static <K, E> Map<K, Set<E>> frozenMapOfSets(Map<K, Set<E>> m) {
        if (m == null) {
            return null;
        }
        Map<K, Set<E>> copy = new LinkedHashMap<>();
        m.forEach((k, v) -> copy.put(k, frozenSet(v)));
        return java.util.Collections.unmodifiableMap(copy);
    }

    private static <E> Set<E> frozenSet(Set<E> s) {
        return s == null ? null : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(s));
    }

    /**
     * IR-7: overrides the job-executor settings (and {@code jobExecutorActivate}) from {@code SANGAM_JOB_*} variables
     * that are present in {@code env}; absent or blank variables leave the setting unchanged.
     *
     * @throws IllegalStateException once the settings are frozen (M-098)
     */
    public void applyEnvironment(Map<String, String> env) {
        mutable("applyEnvironment");
        jobCorePoolSize = intEnv(env, "SANGAM_JOB_CORE_POOL_SIZE", jobCorePoolSize);
        jobMaxPoolSize = intEnv(env, "SANGAM_JOB_MAX_POOL_SIZE", jobMaxPoolSize);
        jobQueueCapacity = intEnv(env, "SANGAM_JOB_QUEUE_CAPACITY", jobQueueCapacity);
        jobMaxJobsPerAcquisition = intEnv(env, "SANGAM_JOB_MAX_JOBS_PER_ACQUISITION", jobMaxJobsPerAcquisition);
        jobWaitTimeInMillis = intEnv(env, "SANGAM_JOB_WAIT_TIME_IN_MILLIS", jobWaitTimeInMillis);
        String f = env.get("SANGAM_JOB_WAIT_INCREASE_FACTOR");
        if (f != null && !f.isBlank()) {
            jobWaitIncreaseFactor = Float.parseFloat(f.trim());
        }
        jobMaxWait = longEnv(env, "SANGAM_JOB_MAX_WAIT", jobMaxWait);
        jobLockTimeInMillis = intEnv(env, "SANGAM_JOB_LOCK_TIME_IN_MILLIS", jobLockTimeInMillis);
        jobBackoffTimeInMillis = intEnv(env, "SANGAM_JOB_BACKOFF_TIME_IN_MILLIS", jobBackoffTimeInMillis);
        jobMaxBackoff = longEnv(env, "SANGAM_JOB_MAX_BACKOFF", jobMaxBackoff);
        jobAcquireByDueDate = boolEnv(env, "SANGAM_JOB_ACQUIRE_BY_DUE_DATE", jobAcquireByDueDate);
        jobAcquireByPriority = boolEnv(env, "SANGAM_JOB_ACQUIRE_BY_PRIORITY", jobAcquireByPriority);
        jobExclusiveOverProcessHierarchies = boolEnv(env, "SANGAM_JOB_EXCLUSIVE_OVER_PROCESS_HIERARCHIES",
                jobExclusiveOverProcessHierarchies);
        jobExecutorActivate = boolEnv(env, "SANGAM_JOB_EXECUTOR_ACTIVATE", jobExecutorActivate);
        jobVirtualThreads = boolEnv(env, "SANGAM_JOB_VIRTUAL_THREADS", jobVirtualThreads);
        partitionedHistory = boolEnv(env, "SANGAM_PARTITIONED_HISTORY", partitionedHistory);
        experimentalCockroachDb = boolEnv(env, "SANGAM_EXPERIMENTAL_COCKROACHDB", experimentalCockroachDb);
        experimentalYugabyteDb = boolEnv(env, "SANGAM_EXPERIMENTAL_YUGABYTEDB", experimentalYugabyteDb);
        String owner = env.get("SANGAM_JOB_LOCK_OWNER");
        if (owner != null && !owner.isBlank()) {
            jobLockOwner = owner.trim();
        }
    }

    private static int intEnv(Map<String, String> env, String name, int dflt) {
        String v = env.get(name);
        return v == null || v.isBlank() ? dflt : Integer.parseInt(v.trim());
    }

    private static long longEnv(Map<String, String> env, String name, long dflt) {
        String v = env.get(name);
        return v == null || v.isBlank() ? dflt : Long.parseLong(v.trim());
    }

    private static boolean boolEnv(Map<String, String> env, String name, boolean dflt) {
        String v = env.get(name);
        return v == null || v.isBlank() ? dflt : Boolean.parseBoolean(v.trim());
    }

    /** Timer catch-up policy for one process definition key (R31). */
    public TimerCatchUpPolicy catchUpFor(String processDefinitionKey) {
        TimerCatchUpPolicy p = processDefinitionKey == null ? null : timerCatchUpByProcessKey.get(processDefinitionKey);
        return p != null ? p : timerCatchUpDefault;
    }
}
