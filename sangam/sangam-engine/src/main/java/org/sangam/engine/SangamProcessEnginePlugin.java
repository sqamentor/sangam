// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.calendar.DueDateBusinessCalendar;
import org.operaton.bpm.engine.impl.calendar.DurationBusinessCalendar;
import org.operaton.bpm.engine.impl.calendar.MapBusinessCalendarManager;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cmmn.transformer.CmmnTransformListener;
import org.operaton.bpm.engine.impl.history.HistoryLevel;
import org.operaton.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.operaton.bpm.engine.impl.incident.IncidentHandler;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;
import org.operaton.bpm.engine.impl.jobexecutor.DefaultJobExecutor;
import org.operaton.bpm.engine.impl.jobexecutor.JobHandler;
import org.operaton.bpm.engine.impl.persistence.StrongUuidGenerator;
import org.operaton.bpm.engine.impl.variable.serializer.TypedValueSerializer;
import org.sangam.engine.deploy.SangamBpmnParseListener;
import org.sangam.engine.deploy.SangamCmmnTransformListener;
import org.sangam.engine.history.OutboxOnlyDbHistoryEventHandler;
import org.sangam.engine.history.PerDefinitionHistoryLevel;
import org.sangam.engine.history.SangamHistoryEventHandler;
import org.sangam.engine.incident.SangamIncidentHandler;
import org.sangam.engine.job.CountingFailedJobCommandFactory;
import org.sangam.engine.job.GuardedJobHandler;
import org.sangam.engine.job.JobIdentitySupport;
import org.sangam.engine.job.JobMetricsInterceptor;
import org.sangam.engine.lock.RootLockFlushListener;
import org.sangam.engine.timer.DstSafeCycleBusinessCalendar;
import org.sangam.engine.timer.TaggedDurationBusinessCalendar;
import org.sangam.engine.tx.RollbackGuardInterceptor;
import org.sangam.engine.variables.JsonValueSerializer;
import org.sangam.engine.variables.LargeStringValueSerializer;

/**
 * The Sangam {@link ProcessEnginePlugin} (ADR-D-16: Operaton as a dependency, extended through plugins only). It
 * registers every engine-side fix of ARCHITECTURE §1.6 that needs an engine hook:
 *
 * <table>
 *   <caption>What is registered where</caption>
 *   <tr><td>Q1 / D-22 explicit rollback + L2 flush backstop</td><td>post command interceptors (REQUIRED and
 *       REQUIRES_NEW): {@link RollbackGuardInterceptor}, {@link RootLockFlushListener}</td></tr>
 *   <tr><td>L2 root lock and lock timeouts on job execution (A-11, D-24); DST timer guard (A-12)</td><td>every job
 *       handler wrapped in {@link GuardedJobHandler} (postInit)</td></tr>
 *   <tr><td>DST-safe cycles, catch-up, Rn semantics (A-12, R31, D-01/02/29/30/31); calendar per timer (FC-23)</td>
 *       <td>business calendar manager: {@link DstSafeCycleBusinessCalendar}, {@link TaggedDurationBusinessCalendar}</td></tr>
 *   <tr><td>Long strings past the 4,000 / 2,000-char limit (D-07, FC-29); JSON variables (FC-28, D-47)</td><td>custom
 *       pre variable serializers</td></tr>
 *   <tr><td>Deploy-time rules: GP-09 MI guard, script tasks (D-46), bindings (D-12), TTL (D-13), catch-up (R31);
 *       CMMN listeners (D-08), process-task exit (D-26), case-task outcome (D-25)</td><td>BPMN parse listener, CMMN
 *       transform listener</td></tr>
 *   <tr><td>History → audit chain and typed events (R42, FC-43, FC-25); per-definition history (FC-26)</td><td>custom
 *       history event handler; custom history level</td></tr>
 *   <tr><td>Incidents → reconciliation</td><td>custom incident handlers (failedJob, failedExternalTask)</td></tr>
 *   <tr><td>Engine defaults: authorization off (VI.4 is the product's), Java serialization off (D-47), XXE off (D-51),
 *       isolation check on (D-56), SKIP LOCKED + timers first + priorities (FC-24 config route), UUID ids, history TTL
 *       not enforced (R61 decides per retention class)</td><td>preInit</td></tr>
 * </table>
 *
 * MySQL P-1/P-2, READ COMMITTED per connection and the SQL Server RCSI check run in {@link SangamEngineConfigurer}
 * around the engine build, because they need the DataSource rather than the configuration.
 */
public class SangamProcessEnginePlugin implements ProcessEnginePlugin {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SangamProcessEnginePlugin.class);

    private final SangamEngineComponents components;

    public SangamProcessEnginePlugin(SangamEngineComponents components) {
        this.components = components;
    }

    public SangamEngineComponents components() {
        return components;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl cfg) {
        SangamEngineSettings s = components.settings();

        // engine defaults
        cfg.setIdGenerator(new StrongUuidGenerator());
        cfg.setAuthorizationEnabled(false);
        cfg.setJavaSerializationFormatEnabled(false);
        cfg.setDefaultSerializationFormat(JsonValueSerializer.FORMAT);
        cfg.setEnableXxeProcessing(false);
        cfg.setSkipIsolationLevelCheck(false);
        cfg.setEnforceHistoryTimeToLive(false);
        cfg.setJobExecutorAcquireWithSkipLocked(s.isJobAcquireWithSkipLocked());
        cfg.setJobExecutorPreferTimerJobs(s.isJobPreferTimers());
        cfg.setProducePrioritizedJobs(true);
        // IR-7: job executor sizing and acquisition (SCALE 3 defaults; SANGAM_JOB_* env)
        if (cfg.getJobExecutor() == null) {
            DefaultJobExecutor je = new DefaultJobExecutor();
            je.setCorePoolSize(s.getJobCorePoolSize());
            je.setMaxPoolSize(s.getJobMaxPoolSize());
            je.setQueueSize(s.getJobQueueCapacity());
            je.setMaxJobsPerAcquisition(s.getJobMaxJobsPerAcquisition());
            je.setWaitTimeInMillis(s.getJobWaitTimeInMillis());
            je.setWaitIncreaseFactor(s.getJobWaitIncreaseFactor());
            je.setMaxWait(s.getJobMaxWait());
            je.setLockTimeInMillis(s.getJobLockTimeInMillis());
            je.setBackoffTimeInMillis(s.getJobBackoffTimeInMillis());
            je.setMaxBackoff(s.getJobMaxBackoff());
            if (s.getJobLockOwner() != null) {
                je.setLockOwner(s.getJobLockOwner());
            }
            cfg.setJobExecutor(je);
        }
        cfg.setJobExecutorAcquireByDueDate(s.isJobAcquireByDueDate());
        cfg.setJobExecutorAcquireByPriority(s.isJobAcquireByPriority());
        cfg.setJobExecutorAcquireExclusiveOverProcessHierarchies(s.isJobExclusiveOverProcessHierarchies());
        if (s.getJdbcStatementTimeoutSeconds() > 0) {
            cfg.setJdbcStatementTimeout(s.getJdbcStatementTimeoutSeconds());
        }

        // Q1 rollback guard + L2 flush backstop on every command
        RootLockFlushListener flush = s.isRootLockAtFlush()
                ? new RootLockFlushListener(components.rootLock(), s.getDefaultTenantId()) : null;
        cfg.setCustomPostCommandInterceptorsTxRequired(append(append(cfg.getCustomPostCommandInterceptorsTxRequired(),
                new RollbackGuardInterceptor(flush)), new JobMetricsInterceptor(components.metrics())));
        cfg.setCustomPostCommandInterceptorsTxRequiresNew(append(cfg.getCustomPostCommandInterceptorsTxRequiresNew(),
                new RollbackGuardInterceptor(flush)));

        // variables: long strings and JSON first, so they win over Operaton's string / Java serializers
        int threshold = s.getLargeStringThresholdChars() != null ? s.getLargeStringThresholdChars()
                : components.dialect().engineTextLimitChars();
        List<TypedValueSerializer<?>> pre = new ArrayList<>();
        pre.add(new LargeStringValueSerializer(threshold));
        pre.add(new JsonValueSerializer());
        if (cfg.getCustomPreVariableSerializers() != null) {
            pre.addAll(cfg.getCustomPreVariableSerializers());
        }
        cfg.setCustomPreVariableSerializers(pre);

        // timers: DST-safe cycle calendar, tagged duration calendar, Operaton's due-date calendar
        MapBusinessCalendarManager calendars = new MapBusinessCalendarManager();
        calendars.addBusinessCalendar(DurationBusinessCalendar.NAME, new TaggedDurationBusinessCalendar(
                s.getWorkCalendars(), components.clock(), s.getTimerZone()));
        calendars.addBusinessCalendar(DueDateBusinessCalendar.NAME, new DueDateBusinessCalendar());
        calendars.addBusinessCalendar(DstSafeCycleBusinessCalendar.NAME, new DstSafeCycleBusinessCalendar(
                components.clock(), components.timerGuard(), components.timerPolicies()));
        cfg.setBusinessCalendarManager(calendars);

        // deploy-time validation
        List<BpmnParseListener> bpmn = cfg.getCustomPostBPMNParseListeners() == null ? new ArrayList<>()
                : new ArrayList<>(cfg.getCustomPostBPMNParseListeners());
        bpmn.add(new SangamBpmnParseListener(s, components.timerPolicies()));
        cfg.setCustomPostBPMNParseListeners(bpmn);
        List<CmmnTransformListener> cmmn = cfg.getCustomPostCmmnTransformListeners() == null ? new ArrayList<>()
                : new ArrayList<>(cfg.getCustomPostCmmnTransformListeners());
        cmmn.add(new SangamCmmnTransformListener(s));
        cfg.setCustomPostCmmnTransformListeners(cmmn);

        // history: audit chain + typed events; per-definition levels
        List<HistoryEventHandler> history = cfg.getCustomHistoryEventHandlers() == null ? new ArrayList<>()
                : new ArrayList<>(cfg.getCustomHistoryEventHandlers());
        history.add(new SangamHistoryEventHandler(components.auditSink(), s.getEventSink(), s.getAuditedHistoryEvents(),
                s.getPublishedHistoryEvents(), s.getDefaultTenantId()));
        if (!s.getOutboxOnlyHistoryKeys().isEmpty()) {
            // IR-5: Operaton's DB handler replaced by one that keeps only the audit level for the listed definitions
            cfg.setEnableDefaultDbHistoryEventHandler(false);
            history.add(0, new OutboxOnlyDbHistoryEventHandler(s.getOutboxOnlyHistoryKeys()));
            if (s.getEventSink() == org.sangam.core.engine.EngineEventSink.NONE) {
                LOG.warn("IR-5: outbox-only history for {} but no EngineEventSink is bound: their history detail is "
                        + "written nowhere", s.getOutboxOnlyHistoryKeys());
            }
        }
        cfg.setCustomHistoryEventHandlers(history);
        if (!s.getHistoryLevelByProcessKey().isEmpty()) {
            HistoryLevel base = PerDefinitionHistoryLevelBase.of(s.getHistory());
            List<HistoryLevel> levels = cfg.getCustomHistoryLevels() == null ? new ArrayList<>()
                    : new ArrayList<>(cfg.getCustomHistoryLevels());
            levels.add(new PerDefinitionHistoryLevel(base, s.getHistoryLevelByProcessKey()));
            cfg.setCustomHistoryLevels(levels);
            cfg.setHistory(PerDefinitionHistoryLevel.NAME);
        }

        // incidents -> reconciliation
        List<IncidentHandler> incidents = cfg.getCustomIncidentHandlers() == null ? new ArrayList<>()
                : new ArrayList<>(cfg.getCustomIncidentHandlers());
        incidents.add(new SangamIncidentHandler("failedJob", components.incidentSink(), s.getDefaultTenantId()));
        incidents.add(new SangamIncidentHandler("failedExternalTask", components.incidentSink(), s.getDefaultTenantId()));
        cfg.setCustomIncidentHandlers(incidents);
    }

    @Override
    public void postInit(ProcessEngineConfigurationImpl cfg) {
        SangamEngineSettings s = components.settings();
        // sangam_jobs_failed_total: every failed job execution goes through the failed-job command factory
        cfg.setFailedJobCommandFactory(new CountingFailedJobCommandFactory(cfg.getFailedJobCommandFactory(),
                components.metrics()));
        if (s.getJobIdentityBinder() == null) {
            LOG.warn("VI.4 rule 7: no JobIdentityBinder installed; jobs bind the tenant system identity only "
                    + "engine-locally (JobIdentityBinder.current()). Install TenantContext::runAs in the server.");
        }
        JobIdentitySupport identity = new JobIdentitySupport(s.getJobIdentityBinder(), s.getDefaultTenantId());
        Map<String, JobHandler<?>> handlers = cfg.getJobHandlers();
        for (Map.Entry<String, JobHandler<?>> e : handlers.entrySet()) {
            e.setValue(GuardedJobHandler.wrap(e.getValue(), components.dialect(), components.rootLock(),
                    components.timerGuard(), components.timerPolicies(), components.clock(), components.metrics(),
                    s.getLockTimeout(), s.getStatementTimeout(), s.getDefaultTenantId(), identity));
        }
    }

    @Override
    public void postProcessEngineBuild(ProcessEngine processEngine) {
        // DataSource-level steps (P-1/P-2) run in SangamEngineConfigurer after the build.
    }

    private static List<CommandInterceptor> append(List<CommandInterceptor> list, CommandInterceptor i) {
        List<CommandInterceptor> out = list == null ? new ArrayList<>() : new ArrayList<>(list);
        out.add(i);
        return out;
    }

    /** Base level of the per-definition history level: the configured global history name. */
    static final class PerDefinitionHistoryLevelBase {
        private PerDefinitionHistoryLevelBase() {
        }

        static HistoryLevel of(String history) {
            return switch (history == null ? "full" : history.toLowerCase(java.util.Locale.ROOT)) {
                case "none" -> HistoryLevel.HISTORY_LEVEL_NONE;
                case "activity" -> HistoryLevel.HISTORY_LEVEL_ACTIVITY;
                case "audit" -> HistoryLevel.HISTORY_LEVEL_AUDIT;
                default -> HistoryLevel.HISTORY_LEVEL_FULL;
            };
        }
    }
}
