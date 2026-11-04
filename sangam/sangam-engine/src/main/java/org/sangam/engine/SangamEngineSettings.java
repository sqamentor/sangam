// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import java.time.Duration;
import java.time.ZoneId;
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

/**
 * Typed configuration facade of the Sangam engine (D-53): every setting Sangam changes on Operaton's 5,000-line
 * mutable configuration is here, with the Sangam default and the requirement it serves. JavaBean style, so
 * sangam-server can bind it with {@code @ConfigurationProperties("sangam.engine")}.
 */
public class SangamEngineSettings {

    // ------------------------------------------------------------------ engine
    private String processEngineName = "sangam";
    /** Tenant used for L2 / audit rows of instances deployed without a tenant id. */
    private String defaultTenantId = "default";
    private String history = "full";
    private boolean jobExecutorActivate = false;
    /** D-38/D-10: one engine per JVM because ClockUtil is static; tests may lift it. */
    private boolean allowMultipleEnginesPerJvm = false;
    /** Run the product changelog before building the engine (R12; production may run it as a Job instead). */
    private boolean applyProductSchema = true;
    /** Run P-1/P-2 after the engine created/upgraded its tables (MySQL only). */
    private boolean applyEnginePatches = true;
    /** IR-8: partition ACT_HI_* by REMOVAL_TIME_ after the build (PostgreSQL, Oracle, SQL Server); env SANGAM_PARTITIONED_HISTORY. */
    private boolean partitionedHistory = false;
    /** D-05: the Operaton databaseType to pin on MySQL servers whatever the driver says ("mysql" or "mariadb"). */
    private String mysqlDatabaseType = "mysql";
    /** D-56 / R84: SQL Server must run with READ_COMMITTED_SNAPSHOT ON. */
    private boolean requireSqlServerRcsi = true;
    /** D-06: MySQL JDBC statement timeout in seconds (also applied on the other databases; 0 = off). */
    private int jdbcStatementTimeoutSeconds = 30;

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

    // ------------------------------------------------------------------ jobs and timers (A-12, R31, FC-24)
    private int maxJobExecutionsPerCall = 1_000;
    private int maxExecutionsPerJobDefinitionPerCall = 50;
    private ZoneId timerZone = ZoneId.systemDefault();
    private TimerCatchUpPolicy timerCatchUpDefault = TimerCatchUpPolicy.FIRE_ONCE;
    private Map<String, TimerCatchUpPolicy> timerCatchUpByProcessKey = new LinkedHashMap<>();
    private int maxCatchUpFires = 100;
    private Map<String, WorkCalendar> workCalendars = new LinkedHashMap<>();
    /** FC-24 (config route): SKIP LOCKED acquisition, prioritized jobs, timers first. */
    private boolean jobAcquireWithSkipLocked = true;
    private boolean jobPreferTimers = true;

    // ------------------------------------------------------------------ deployment validation (FC-46)
    /** GP-09 / D-23: multi-instance above this cardinality is rejected (deploy: literal; runtime: evaluated). */
    // Same default as WS-PACKS' ModelGuard.MAX_MULTI_INSTANCE (50), so the engine and the pack guard never disagree.
    private int multiInstanceMaxCardinality = 50;
    /** D-46 / R6: Release 1 has no in-engine scripts. */
    private boolean rejectScriptTasks = true;
    /** D-08: CMMN event listeners deploy silently but never fire on Operaton 2.2. */
    private boolean rejectCmmnEventListeners = true;
    /** FC-55 (WS-PORTS) enabled: CMMN event listeners are executed, so D-08 must not reject them. */
    private boolean cmmnEventListenersEnabled = false;
    /** D-12: decisionRefBinding / calledElementBinding default to "deployment" instead of "latest". */
    private boolean defaultBindingDeployment = true;
    /** D-51 / R24: DOCTYPE declarations are rejected at deploy. */
    private boolean rejectDoctype = true;

    // ------------------------------------------------------------------ history and retention (D-13, FC-26, R61)
    /** Days of history TTL injected for retention class "default"; null = never cleaned (R61-safe). */
    private Integer defaultHistoryTtlDays = null;
    /** Retention class (sangam:retentionClass) -> TTL days; a class mapped to null is kept forever. */
    private Map<String, Integer> historyTtlDaysByRetentionClass = new LinkedHashMap<>();
    /** FC-26: history level (none, activity, audit, full) per {@code processKey} or {@code processKey#activityId}. */
    private Map<String, String> historyLevelByProcessKey = new LinkedHashMap<>();
    /** History event types fed into the audit chain (Operaton HistoryEventTypes names). */
    private Set<String> auditedHistoryEvents = new LinkedHashSet<>(List.of(
            "PROCESS_INSTANCE_START", "PROCESS_INSTANCE_END", "PROCESS_INSTANCE_MIGRATE",
            "TASK_INSTANCE_CREATE", "TASK_INSTANCE_UPDATE", "TASK_INSTANCE_COMPLETE", "TASK_INSTANCE_DELETE",
            "INCIDENT_CREATE", "INCIDENT_RESOLVE", "INCIDENT_DELETE",
            "CASE_INSTANCE_CREATE", "CASE_INSTANCE_CLOSE", "CASE_ACTIVITY_INSTANCE_END"));
    /** History event types published as typed engine events (FC-43 / FC-25); empty = all. */
    private Set<String> publishedHistoryEvents = new LinkedHashSet<>();

    // ------------------------------------------------------------------ variables (D-07, FC-28, FC-29, D-47)
    /** Strings longer than this go to a byte array; null = the database's TEXT_ limit (Oracle 2,000, else 4,000). */
    private Integer largeStringThresholdChars = null;


    // ------------------------------------------------------------------ IR-7 job executor (SCALE 3 defaults)
    // Env (applied by the configurer when applyEnvironment=true): SANGAM_JOB_CORE_POOL_SIZE, SANGAM_JOB_MAX_POOL_SIZE,
    // SANGAM_JOB_QUEUE_CAPACITY, SANGAM_JOB_MAX_JOBS_PER_ACQUISITION, SANGAM_JOB_WAIT_TIME_IN_MILLIS,
    // SANGAM_JOB_WAIT_INCREASE_FACTOR, SANGAM_JOB_MAX_WAIT, SANGAM_JOB_LOCK_TIME_IN_MILLIS, SANGAM_JOB_BACKOFF_TIME_IN_MILLIS,
    // SANGAM_JOB_MAX_BACKOFF, SANGAM_JOB_ACQUIRE_BY_DUE_DATE, SANGAM_JOB_ACQUIRE_BY_PRIORITY,
    // SANGAM_JOB_EXCLUSIVE_OVER_PROCESS_HIERARCHIES, SANGAM_JOB_LOCK_OWNER, SANGAM_JOB_EXECUTOR_ACTIVATE.
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
    /** Exclusive jobs of a whole process hierarchy (call activities) acquired together by one node. */
    private boolean jobExclusiveOverProcessHierarchies = false;
    private String jobLockOwner;

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

    // ------------------------------------------------------------------ getters / setters

    public String getProcessEngineName() { return processEngineName; }
    public void setProcessEngineName(String v) { processEngineName = v; }
    public String getDefaultTenantId() { return defaultTenantId; }
    public void setDefaultTenantId(String v) { defaultTenantId = v; }
    public String getHistory() { return history; }
    public void setHistory(String v) { history = v; }
    public boolean isJobExecutorActivate() { return jobExecutorActivate; }
    public void setJobExecutorActivate(boolean v) { jobExecutorActivate = v; }
    public boolean isAllowMultipleEnginesPerJvm() { return allowMultipleEnginesPerJvm; }
    public void setAllowMultipleEnginesPerJvm(boolean v) { allowMultipleEnginesPerJvm = v; }
    public boolean isApplyProductSchema() { return applyProductSchema; }
    public void setApplyProductSchema(boolean v) { applyProductSchema = v; }
    public boolean isPartitionedHistory() { return partitionedHistory; }
    public void setPartitionedHistory(boolean v) { partitionedHistory = v; }
    public boolean isApplyEnginePatches() { return applyEnginePatches; }
    public void setApplyEnginePatches(boolean v) { applyEnginePatches = v; }
    public String getMysqlDatabaseType() { return mysqlDatabaseType; }
    public void setMysqlDatabaseType(String v) { mysqlDatabaseType = v; }
    public boolean isRequireSqlServerRcsi() { return requireSqlServerRcsi; }
    public void setRequireSqlServerRcsi(boolean v) { requireSqlServerRcsi = v; }
    public int getJdbcStatementTimeoutSeconds() { return jdbcStatementTimeoutSeconds; }
    public void setJdbcStatementTimeoutSeconds(int v) { jdbcStatementTimeoutSeconds = v; }
    public Duration getLockTimeout() { return lockTimeout; }
    public void setLockTimeout(Duration v) { lockTimeout = v; }
    public Duration getStatementTimeout() { return statementTimeout; }
    public void setStatementTimeout(Duration v) { statementTimeout = v; }
    public int getRetryMaxAttempts() { return retryMaxAttempts; }
    public void setRetryMaxAttempts(int v) { retryMaxAttempts = v; }
    public Duration getRetryBase() { return retryBase; }
    public void setRetryBase(Duration v) { retryBase = v; }
    public Duration getRetryCap() { return retryCap; }
    public void setRetryCap(Duration v) { retryCap = v; }
    public boolean isRootLockAtFlush() { return rootLockAtFlush; }
    public void setRootLockAtFlush(boolean v) { rootLockAtFlush = v; }
    public int getFetchAttempts() { return fetchAttempts; }
    public void setFetchAttempts(int v) { fetchAttempts = v; }
    public Duration getMaxLease() { return maxLease; }
    public void setMaxLease(Duration v) { maxLease = v; }
    public int getMaxJobExecutionsPerCall() { return maxJobExecutionsPerCall; }
    public void setMaxJobExecutionsPerCall(int v) { maxJobExecutionsPerCall = v; }
    public int getMaxExecutionsPerJobDefinitionPerCall() { return maxExecutionsPerJobDefinitionPerCall; }
    public void setMaxExecutionsPerJobDefinitionPerCall(int v) { maxExecutionsPerJobDefinitionPerCall = v; }
    public ZoneId getTimerZone() { return timerZone; }
    public void setTimerZone(ZoneId v) { timerZone = v; }
    public TimerCatchUpPolicy getTimerCatchUpDefault() { return timerCatchUpDefault; }
    public void setTimerCatchUpDefault(TimerCatchUpPolicy v) { timerCatchUpDefault = v; }
    public Map<String, TimerCatchUpPolicy> getTimerCatchUpByProcessKey() { return timerCatchUpByProcessKey; }
    public void setTimerCatchUpByProcessKey(Map<String, TimerCatchUpPolicy> v) { timerCatchUpByProcessKey = v; }
    public int getMaxCatchUpFires() { return maxCatchUpFires; }
    public void setMaxCatchUpFires(int v) { maxCatchUpFires = v; }
    public Map<String, WorkCalendar> getWorkCalendars() { return workCalendars; }
    public void setWorkCalendars(Map<String, WorkCalendar> v) { workCalendars = v; }
    public boolean isJobAcquireWithSkipLocked() { return jobAcquireWithSkipLocked; }
    public void setJobAcquireWithSkipLocked(boolean v) { jobAcquireWithSkipLocked = v; }
    public boolean isJobPreferTimers() { return jobPreferTimers; }
    public void setJobPreferTimers(boolean v) { jobPreferTimers = v; }
    public int getMultiInstanceMaxCardinality() { return multiInstanceMaxCardinality; }
    public void setMultiInstanceMaxCardinality(int v) { multiInstanceMaxCardinality = v; }
    public boolean isRejectScriptTasks() { return rejectScriptTasks; }
    public void setRejectScriptTasks(boolean v) { rejectScriptTasks = v; }
    /** D-08 rejection is in force unless FC-55 is enabled. */
    public boolean isRejectCmmnEventListeners() { return rejectCmmnEventListeners && !cmmnEventListenersEnabled; }
    public boolean isCmmnEventListenersEnabled() { return cmmnEventListenersEnabled; }
    public void setCmmnEventListenersEnabled(boolean v) { cmmnEventListenersEnabled = v; }
    public void setRejectCmmnEventListeners(boolean v) { rejectCmmnEventListeners = v; }
    public boolean isDefaultBindingDeployment() { return defaultBindingDeployment; }
    public void setDefaultBindingDeployment(boolean v) { defaultBindingDeployment = v; }
    public boolean isRejectDoctype() { return rejectDoctype; }
    public void setRejectDoctype(boolean v) { rejectDoctype = v; }
    public Integer getDefaultHistoryTtlDays() { return defaultHistoryTtlDays; }
    public void setDefaultHistoryTtlDays(Integer v) { defaultHistoryTtlDays = v; }
    public Map<String, Integer> getHistoryTtlDaysByRetentionClass() { return historyTtlDaysByRetentionClass; }
    public void setHistoryTtlDaysByRetentionClass(Map<String, Integer> v) { historyTtlDaysByRetentionClass = v; }
    public Map<String, String> getHistoryLevelByProcessKey() { return historyLevelByProcessKey; }
    public void setHistoryLevelByProcessKey(Map<String, String> v) { historyLevelByProcessKey = v; }
    public Set<String> getAuditedHistoryEvents() { return auditedHistoryEvents; }
    public void setAuditedHistoryEvents(Set<String> v) { auditedHistoryEvents = v; }
    public Set<String> getPublishedHistoryEvents() { return publishedHistoryEvents; }
    public void setPublishedHistoryEvents(Set<String> v) { publishedHistoryEvents = v; }
    public Integer getLargeStringThresholdChars() { return largeStringThresholdChars; }
    public void setLargeStringThresholdChars(Integer v) { largeStringThresholdChars = v; }
    public ExpiryPolicy getExpiryPolicy() { return expiryPolicy; }
    public void setExpiryPolicy(ExpiryPolicy v) { expiryPolicy = v; }
    public AuditSink getAuditSink() { return auditSink; }
    public void setAuditSink(AuditSink v) { auditSink = v; }
    public IncidentSink getIncidentSink() { return incidentSink; }
    public void setIncidentSink(IncidentSink v) { incidentSink = v; }
    public EngineEventSink getEventSink() { return eventSink; }
    public void setEventSink(EngineEventSink v) { eventSink = v == null ? EngineEventSink.NONE : v; }
    public JobIdentityBinder getJobIdentityBinder() { return jobIdentityBinder; }
    public void setJobIdentityBinder(JobIdentityBinder v) { jobIdentityBinder = v; }
    public EngineMetrics getMetrics() { return metrics; }
    public void setMetrics(EngineMetrics v) { metrics = v == null ? EngineMetrics.NONE : v; }

    public boolean isApplyEnvironment() { return applyEnvironment; }
    public void setApplyEnvironment(boolean v) { applyEnvironment = v; }
    public int getJobCorePoolSize() { return jobCorePoolSize; }
    public void setJobCorePoolSize(int v) { jobCorePoolSize = v; }
    public int getJobMaxPoolSize() { return jobMaxPoolSize; }
    public void setJobMaxPoolSize(int v) { jobMaxPoolSize = v; }
    public int getJobQueueCapacity() { return jobQueueCapacity; }
    public void setJobQueueCapacity(int v) { jobQueueCapacity = v; }
    public int getJobMaxJobsPerAcquisition() { return jobMaxJobsPerAcquisition; }
    public void setJobMaxJobsPerAcquisition(int v) { jobMaxJobsPerAcquisition = v; }
    public int getJobWaitTimeInMillis() { return jobWaitTimeInMillis; }
    public void setJobWaitTimeInMillis(int v) { jobWaitTimeInMillis = v; }
    public float getJobWaitIncreaseFactor() { return jobWaitIncreaseFactor; }
    public void setJobWaitIncreaseFactor(float v) { jobWaitIncreaseFactor = v; }
    public long getJobMaxWait() { return jobMaxWait; }
    public void setJobMaxWait(long v) { jobMaxWait = v; }
    public int getJobLockTimeInMillis() { return jobLockTimeInMillis; }
    public void setJobLockTimeInMillis(int v) { jobLockTimeInMillis = v; }
    public int getJobBackoffTimeInMillis() { return jobBackoffTimeInMillis; }
    public void setJobBackoffTimeInMillis(int v) { jobBackoffTimeInMillis = v; }
    public long getJobMaxBackoff() { return jobMaxBackoff; }
    public void setJobMaxBackoff(long v) { jobMaxBackoff = v; }
    public boolean isJobAcquireByDueDate() { return jobAcquireByDueDate; }
    public void setJobAcquireByDueDate(boolean v) { jobAcquireByDueDate = v; }
    public boolean isJobAcquireByPriority() { return jobAcquireByPriority; }
    public void setJobAcquireByPriority(boolean v) { jobAcquireByPriority = v; }
    public boolean isJobExclusiveOverProcessHierarchies() { return jobExclusiveOverProcessHierarchies; }
    public void setJobExclusiveOverProcessHierarchies(boolean v) { jobExclusiveOverProcessHierarchies = v; }
    public String getJobLockOwner() { return jobLockOwner; }
    public void setJobLockOwner(String v) { jobLockOwner = v; }
    public Set<String> getOutboxOnlyHistoryKeys() { return outboxOnlyHistoryKeys; }
    public void setOutboxOnlyHistoryKeys(Set<String> v) { outboxOnlyHistoryKeys = v; }

    /**
     * IR-7: overrides the job-executor settings (and {@code jobExecutorActivate}) from {@code SANGAM_JOB_*} variables
     * that are present in {@code env}; absent or blank variables leave the setting unchanged.
     */
    public void applyEnvironment(Map<String, String> env) {
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
        partitionedHistory = boolEnv(env, "SANGAM_PARTITIONED_HISTORY", partitionedHistory);
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
