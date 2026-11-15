// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.operaton.bpm.engine.impl.jobexecutor.JobHandler;
import org.operaton.bpm.engine.impl.persistence.StrongUuidGenerator;
import org.operaton.bpm.engine.impl.variable.serializer.TypedValueSerializer;
import org.operaton.bpm.dmn.engine.DmnEngineConfiguration;
import org.operaton.bpm.dmn.engine.delegate.DmnDecisionEvaluationListener;
import org.operaton.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.sangam.engine.deploy.SangamBpmnParseListener;
import org.sangam.engine.feel.FeelBindingDeployer;
import org.sangam.engine.feel.FeelBindingStore;
import org.sangam.engine.feel.FeelPinningListener;
import org.sangam.engine.deploy.SangamCmmnTransformListener;
import org.sangam.engine.history.MandatoryAuditHistoryEventHandler;
import org.sangam.engine.history.MandatoryAuditHistoryEventProducer;
import org.sangam.engine.history.MandatoryAuditHistoryLevel;
import org.sangam.engine.history.OutboxOnlyDbHistoryEventHandler;
import org.sangam.engine.history.PerDefinitionHistoryLevel;
import org.sangam.engine.history.SangamHistoryEventHandler;
import org.sangam.engine.incident.SangamIncidentHandler;
import org.sangam.engine.job.CountingFailedJobCommandFactory;
import org.sangam.engine.job.GuardedJobHandler;
import org.sangam.engine.job.JobIdentitySupport;
import org.sangam.engine.job.JobExecutorSetup;
import org.sangam.engine.job.JobMetricsInterceptor;
import org.sangam.engine.lock.RootLockFlushListener;
import org.sangam.engine.timer.DstSafeCycleBusinessCalendar;
import org.sangam.engine.timer.TaggedDurationBusinessCalendar;
import org.sangam.engine.trace.EngineTracing;
import org.sangam.engine.trace.TracingCommandInterceptor;
import org.sangam.engine.tx.RollbackGuardInterceptor;
import org.sangam.engine.variables.JsonValueSerializer;
import org.sangam.engine.variables.LargeStringValueSerializer;

/**
 * The Sangam {@link ProcessEnginePlugin} (ADR-D-16: Operaton as a dependency, extended through plugins only). It
 * registers every engine-side fix of ARCHITECTURE §1.6 that needs an engine hook:
 *
 * <table>
 *   <caption>What is registered where</caption>
 *   <tr><td>Q1 / FLOWABLE-INTO-OPERATON D-22 explicit rollback + L2 flush backstop</td><td>post command interceptors (REQUIRED and
 *       REQUIRES_NEW): {@link RollbackGuardInterceptor}, {@link RootLockFlushListener}</td></tr>
 *   <tr><td>L2 root lock and lock timeouts on job execution (A-11, FLOWABLE-INTO-OPERATON D-24); DST timer guard (A-12)</td><td>every job
 *       handler wrapped in {@link GuardedJobHandler} (postInit)</td></tr>
 *   <tr><td>DST-safe cycles, catch-up, Rn semantics (A-12, R31, FLOWABLE-INTO-OPERATON D-01/02/29/30/31); calendar per timer (FLOWABLE-INTO-OPERATON FC-23)</td>
 *       <td>business calendar manager: {@link DstSafeCycleBusinessCalendar}, {@link TaggedDurationBusinessCalendar}</td></tr>
 *   <tr><td>Long strings past the 4,000 / 2,000-char limit (FLOWABLE-INTO-OPERATON D-07, FLOWABLE-INTO-OPERATON FC-29); JSON variables (FLOWABLE-INTO-OPERATON FC-28, FLOWABLE-INTO-OPERATON D-47)</td><td>custom
 *       pre variable serializers</td></tr>
 *   <tr><td>Deploy-time rules: GP-09 MI guard, script tasks (FLOWABLE-INTO-OPERATON D-46), bindings (FLOWABLE-INTO-OPERATON D-12), TTL (FLOWABLE-INTO-OPERATON D-13), catch-up (R31);
 *       CMMN listeners (FLOWABLE-INTO-OPERATON D-08), process-task exit (FLOWABLE-INTO-OPERATON D-26), case-task outcome (FLOWABLE-INTO-OPERATON D-25)</td><td>BPMN parse listener, CMMN
 *       transform listener</td></tr>
 *   <tr><td>History → audit chain and typed events (R42, FLOWABLE-INTO-OPERATON FC-43, FLOWABLE-INTO-OPERATON FC-25); per-definition history (FLOWABLE-INTO-OPERATON FC-26)</td><td>custom
 *       history event handler; custom history level</td></tr>
 *   <tr><td>Incidents → reconciliation</td><td>custom incident handlers (failedJob, failedExternalTask)</td></tr>
 *   <tr><td>SL-45: deployments and batches of a tenant-scoped engine identity belong to its tenant</td><td>custom
 *       session factories: {@link org.sangam.engine.deploy.TenantScopedCreation}</td></tr>
 *   <tr><td>Engine defaults: authorization off (VI.4 is the product's), Java serialization off (FLOWABLE-INTO-OPERATON D-47), XXE off (FLOWABLE-INTO-OPERATON D-51),
 *       isolation check on (FLOWABLE-INTO-OPERATON D-56), SKIP LOCKED + timers first + priorities (FLOWABLE-INTO-OPERATON FC-24 config route), UUID ids, history TTL
 *       not enforced (R61 decides per retention class)</td><td>preInit</td></tr>
 * </table>
 *
 * MySQL P-1/P-2, READ COMMITTED per connection and the SQL Server RCSI check run in {@link SangamEngineConfigurer}
 * around the engine build, because they need the DataSource rather than the configuration.
 */
public class SangamProcessEnginePlugin implements ProcessEnginePlugin {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SangamProcessEnginePlugin.class);

    private final SangamEngineComponents components;
    /** M-012: the audit-chain handler created in preInit, the audit-only target of the postInit wrapper. */
    private SangamHistoryEventHandler auditHandler;
    /** M-012: the wrappers registered for the global activity / audit levels (also used for history=variable). */
    private MandatoryAuditHistoryLevel activityLevel;
    private MandatoryAuditHistoryLevel auditLevel;
    /** IR-7 / M-054 / M-056 / M-062: the job executor setup of preInit, checked again in postInit. */
    private JobExecutorSetup jobSetup;
    private org.sangam.engine.history.ModelHistoryLevels modelHistory;

    public SangamProcessEnginePlugin(SangamEngineComponents components) {
        this.components = components;
    }

    public SangamEngineComponents components() {
        return components;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl cfg) {
        SangamEngineSettings s = components.settings();
        // M-021: test mode (clock steering) only on H2 and never with a production profile; refused before anything else
        org.sangam.engine.clock.TestModeClock.requireAllowed(s.isTestMode(),
                components.dialect() == org.sangam.engine.db.EngineDialect.H2, s.getActiveProfiles());
        // decision 151: a replay capture listener only where the host stated "not production"
        org.sangam.engine.replay.CaptureListener.requireAllowed(s.getCaptureListener(), s.getCaptureProduction(),
                s.getActiveProfiles());

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
        // IR-7: job executor sizing and acquisition (SCALE 3 defaults; SANGAM_JOB_* env); M-054 virtual threads and
        // bulkheads, M-056 partitioned acquisition, M-062 trace links: all opt-in, off = Operaton's executor as is
        jobSetup = new JobExecutorSetup(s, components.dialect(), () -> components.clock().now(), components.workTenants());
        List<java.util.function.Supplier<CommandInterceptor>> jobSideRows = jobSetup.preInit(cfg);
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
        // decision 150, rulings 3 and 4: acquisition and fetch read the UTC conversion gate with a lock held to commit
        cfg.setCustomPostCommandInterceptorsTxRequired(append(cfg.getCustomPostCommandInterceptorsTxRequired(),
                new org.sangam.engine.job.UtcConversionHardStop(components.dialect(), s.getLockTimeout())));
        cfg.setCustomPostCommandInterceptorsTxRequiresNew(append(cfg.getCustomPostCommandInterceptorsTxRequiresNew(),
                new org.sangam.engine.job.UtcConversionHardStop(components.dialect(), s.getLockTimeout())));
        // M-056 / M-062: job side rows (EXEC_JOB_SLOT, JOB_TRACE_CONTEXT) in the creating command; none when both are off
        for (java.util.function.Supplier<CommandInterceptor> i : jobSideRows) { // one object per chain
            cfg.setCustomPostCommandInterceptorsTxRequired(append(cfg.getCustomPostCommandInterceptorsTxRequired(), i.get()));
            cfg.setCustomPostCommandInterceptorsTxRequiresNew(append(cfg.getCustomPostCommandInterceptorsTxRequiresNew(),
                    i.get()));
        }
        // M-044: the starting trace id of every new process instance, in the creating command (PROCESS_TRACE)
        org.sangam.engine.trace.ProcessTraceRecorder processTraces = new org.sangam.engine.trace.ProcessTraceRecorder(
                s.getTraceIdSource() != null ? s.getTraceIdSource()
                        : org.sangam.engine.trace.ProcessTraceRecorder.fromTracing(s.getTracing()),
                () -> components.clock().now().toEpochMilli());
        // one interceptor object per chain: an interceptor's next is set by the chain it is in
        cfg.setCustomPostCommandInterceptorsTxRequired(append(cfg.getCustomPostCommandInterceptorsTxRequired(),
                processTraces.interceptor()));
        cfg.setCustomPostCommandInterceptorsTxRequiresNew(append(cfg.getCustomPostCommandInterceptorsTxRequiresNew(),
                processTraces.interceptor()));
        if (components.capture().active()) { // decision 151: TIMER_SCHEDULED at command close, one object per chain
            cfg.setCustomPostCommandInterceptorsTxRequired(append(cfg.getCustomPostCommandInterceptorsTxRequired(),
                    components.capture().interceptor()));
            cfg.setCustomPostCommandInterceptorsTxRequiresNew(append(cfg.getCustomPostCommandInterceptorsTxRequiresNew(),
                    components.capture().interceptor()));
        }
        // M-062 (R13): a span per command, covering flush and commit (pre interceptor); nothing when tracing is NONE
        if (s.getTracing() != EngineTracing.NONE) {
            cfg.setCustomPreCommandInterceptorsTxRequired(append(cfg.getCustomPreCommandInterceptorsTxRequired(),
                    new TracingCommandInterceptor(s.getTracing())));
            cfg.setCustomPreCommandInterceptorsTxRequiresNew(append(cfg.getCustomPreCommandInterceptorsTxRequiresNew(),
                    new TracingCommandInterceptor(s.getTracing())));
        }

        // SL-45: deployments and batches of a tenant-scoped engine identity belong to its tenant (Operaton has no
        // tenant check for either; the batch's jobs then run, and are checked, as that tenant); SL-73: so do its
        // standalone tasks
        List<org.operaton.bpm.engine.impl.interceptor.SessionFactory> sessions = cfg.getCustomSessionFactories() == null
                ? new ArrayList<>() : new ArrayList<>(cfg.getCustomSessionFactories());
        sessions.addAll(org.sangam.engine.deploy.TenantScopedCreation.sessionFactories());
        // decision 331: a delayed suspension or activation timer belongs to its definition's tenant (B-46)
        sessions.add(org.sangam.engine.deploy.DelayedStateChangeTenant.sessionFactory());
        // SL-177: PROCESS_TRACE rows go with the instance's history (B-36)
        sessions.add(org.sangam.engine.trace.ProcessTraceHistoryRemoval.sessionFactory());
        cfg.setCustomSessionFactories(sessions);

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
        // decision 138: BigDecimal variables as lossless decimal text (type id sangam-decimal-v1), first (B-37)
        org.sangam.packs.engine.DecimalValueSerializer.register(cfg);

        // timers: DST-safe cycle calendar, tagged duration calendar, Operaton's due-date calendar
        MapBusinessCalendarManager calendars = new MapBusinessCalendarManager();
        calendars.addBusinessCalendar(DurationBusinessCalendar.NAME, new TaggedDurationBusinessCalendar(
                s.getWorkCalendars(), components.clock(), s.getTimerZone()));
        calendars.addBusinessCalendar(DueDateBusinessCalendar.NAME, new DueDateBusinessCalendar());
        calendars.addBusinessCalendar(DstSafeCycleBusinessCalendar.NAME, new DstSafeCycleBusinessCalendar(
                components.clock(), components.timerGuard(), components.timerPolicies()));
        cfg.setBusinessCalendarManager(calendars);

        // R10: FEEL engine pinned per model version (deploy-time binding, run-time refusal of a mismatch)
        FeelBindingStore feel = new FeelBindingStore(components.dialect());
        List<org.operaton.bpm.engine.impl.persistence.deploy.Deployer> postDeployers =
                cfg.getCustomPostDeployers() == null ? new ArrayList<>() : new ArrayList<>(cfg.getCustomPostDeployers());
        postDeployers.add(new FeelBindingDeployer(feel, components.clock()));
        cfg.setCustomPostDeployers(postDeployers);
        DefaultDmnEngineConfiguration dmn = cfg.getDmnEngineConfiguration() != null ? cfg.getDmnEngineConfiguration()
                : (DefaultDmnEngineConfiguration) DmnEngineConfiguration.createDefaultDmnEngineConfiguration();
        List<DmnDecisionEvaluationListener> dmnListeners = dmn.getCustomPreDecisionEvaluationListeners() == null
                ? new ArrayList<>() : new ArrayList<>(dmn.getCustomPreDecisionEvaluationListeners());
        dmnListeners.add(new FeelPinningListener(feel, components.clock()));
        dmn.setCustomPreDecisionEvaluationListeners(dmnListeners);
        // ADR-D-26 (SG-33/34): exact numbers, strict FEEL evaluation, strict null input; decision 151: with a replay
        // capture listener (never in production) FEEL now()/today() read the engine clock and each read is captured
        if (s.getCaptureListener() != null) {
            org.sangam.feel.SangamFeel.install(dmn, new org.sangam.engine.replay.CapturingFeelClock(
                    () -> components.clock().now(), org.sangam.core.time.EngineTimeZone.ZONE, s.getCaptureListener()));
        } else {
            org.sangam.feel.SangamFeel.install(dmn);
        }
        List<org.operaton.bpm.engine.impl.persistence.deploy.Deployer> preDeployers =
                cfg.getCustomPreDeployers() == null ? new ArrayList<>() : new ArrayList<>(cfg.getCustomPreDeployers());
        preDeployers.add(new org.sangam.engine.feel.DmnModelLint.DeployRule()); // ADR-D-26: deploy-time DMN type rules
        preDeployers.add(new org.sangam.engine.deploy.OperationRefPinning.DeployRule(s.getOperationRefPinner())); // M-068
        // SL-160 / TM-10: only reviewed Java classes and delegate beans in new BPMN / CMMN deployments
        preDeployers.add(new org.sangam.engine.deploy.ModelCodeAllowList.DeployRule(s.getDeployAllowedClasses(),
                s.getDeployAllowedBeans()));
        cfg.setCustomPreDeployers(preDeployers);
        cfg.setDmnEngineConfiguration(dmn);

        // deploy-time validation
        List<BpmnParseListener> bpmn = cfg.getCustomPostBPMNParseListeners() == null ? new ArrayList<>()
                : new ArrayList<>(cfg.getCustomPostBPMNParseListeners());
        modelHistory = s.isHistoryModelOverrides() ? new org.sangam.engine.history.ModelHistoryLevels() : null; // M-057
        bpmn.add(new SangamBpmnParseListener(s, components.timerPolicies(), modelHistory));
        cfg.setCustomPostBPMNParseListeners(bpmn);
        if (modelHistory != null) {
            // a parsed definition has no id yet: the declarations are registered once the engine's deployer set it
            List<org.operaton.bpm.engine.impl.persistence.deploy.Deployer> afterBpmn =
                    new ArrayList<>(cfg.getCustomPostDeployers());
            afterBpmn.add(modelHistory.deployRule());
            cfg.setCustomPostDeployers(afterBpmn);
        }
        // SG-08 (a): scope compensated through its children triggers them one at a time (replaces "compensate")
        List<org.operaton.bpm.engine.impl.event.EventHandler> eventHandlers = cfg.getCustomEventHandlers() == null
                ? new ArrayList<>() : new ArrayList<>(cfg.getCustomEventHandlers());
        eventHandlers.add(new org.sangam.engine.deploy.SequentialCompensation.EventHandler());
        if (components.capture().active()) { // decision 151: MESSAGE_DELIVERED, then Operaton's message handling
            eventHandlers.add(components.capture().messageEventHandler());
        }
        cfg.setCustomEventHandlers(eventHandlers);
        List<CmmnTransformListener> cmmn = cfg.getCustomPostCmmnTransformListeners() == null ? new ArrayList<>()
                : new ArrayList<>(cfg.getCustomPostCmmnTransformListeners());
        cmmn.add(new SangamCmmnTransformListener(s));
        cfg.setCustomPostCmmnTransformListeners(cmmn);
        // SG-10: completed CMMN items are written COMPLETED, not TERMINATED, in the engine's own history
        if (cfg.getCmmnHistoryEventProducer() == null) {
            cfg.setCmmnHistoryEventProducer(new org.sangam.engine.history.CompletionAwareCmmnHistoryEventProducer());
        }

        // history: audit chain + typed events; per-definition levels
        List<HistoryEventHandler> history = cfg.getCustomHistoryEventHandlers() == null ? new ArrayList<>()
                : new ArrayList<>(cfg.getCustomHistoryEventHandlers());
        auditHandler = new SangamHistoryEventHandler(components.auditSink(), s.getEventSink(), s.getAuditedHistoryEvents(),
                s.getPublishedHistoryEvents(), s.getDefaultTenantId());
        history.add(auditHandler);
        if (s.isHistoryExternal() && s.getEventSink() == org.sangam.core.engine.EngineEventSink.NONE) {
            // M-057 external mode: the detail goes only to the outbox -> external projector; without a sink it is lost
            throw new org.operaton.bpm.engine.ProcessEngineException("M-057: sangam.engine.history-external is on but no "
                    + "EngineEventSink is bound; history detail would be written nowhere (refused at startup)");
        }
        if (!s.getOutboxOnlyHistoryKeys().isEmpty() || s.isHistoryExternal()) {
            // IR-5: Operaton's DB handler replaced by one that keeps only the audit level for the listed definitions
            // (M-057 external mode: for every definition)
            cfg.setEnableDefaultDbHistoryEventHandler(false);
            Set<String> keys = new LinkedHashSet<>(s.getOutboxOnlyHistoryKeys());
            if (s.isHistoryExternal()) {
                keys.add(OutboxOnlyDbHistoryEventHandler.ALL);
            }
            history.add(0, new OutboxOnlyDbHistoryEventHandler(keys));
            if (s.getEventSink() == org.sangam.core.engine.EngineEventSink.NONE) {
                LOG.warn("IR-5: outbox-only history for {} but no EngineEventSink is bound: their history detail is "
                        + "written nowhere", s.getOutboxOnlyHistoryKeys());
            }
        }
        cfg.setCustomHistoryEventHandlers(history);
        // M-012 (X.2.3, T62): class-B audit is independent of the history level. Each level below "full" is replaced
        // by a wrapper with the same id and name (Operaton picks the last level whose name matches, and the custom
        // levels come after its built-ins) that lets the audited event types through as audit-only events.
        Set<String> mandatory = new LinkedHashSet<>(s.getAuditedHistoryEvents());
        List<HistoryLevel> levels = cfg.getCustomHistoryLevels() == null ? new ArrayList<>()
                : new ArrayList<>(cfg.getCustomHistoryLevels());
        if (!s.getHistoryLevelByProcessKey().isEmpty() || modelHistory != null) {
            HistoryLevel base = PerDefinitionHistoryLevelBase.of(s.getHistory());
            levels.add(new MandatoryAuditHistoryLevel(
                    new PerDefinitionHistoryLevel(base, s.getHistoryLevelByProcessKey(), modelHistory), mandatory));
            cfg.setHistory(PerDefinitionHistoryLevel.NAME);
        }
        // Global "none" is not wrapped: it is refused at startup (owner decision 2026-11-12, refuseGlobalHistoryNone).
        activityLevel = new MandatoryAuditHistoryLevel(HistoryLevel.HISTORY_LEVEL_ACTIVITY, mandatory);
        auditLevel = new MandatoryAuditHistoryLevel(HistoryLevel.HISTORY_LEVEL_AUDIT, mandatory);
        levels.add(activityLevel);
        levels.add(auditLevel);
        cfg.setCustomHistoryLevels(levels);
        // the root END event of an audit-only tree must not set removal times on ACT_HI_* rows
        if (cfg.getHistoryEventProducer() == null) {
            cfg.setHistoryEventProducer(new MandatoryAuditHistoryEventProducer());
        } else if (!(cfg.getHistoryEventProducer() instanceof MandatoryAuditHistoryEventProducer)) {
            LOG.warn("M-012: a custom HistoryEventProducer ({}) is set; an audit-only root end event may still set "
                    + "removal times on ACT_HI_* rows", cfg.getHistoryEventProducer().getClass().getName());
        }

        // incidents -> reconciliation
        List<IncidentHandler> incidents = cfg.getCustomIncidentHandlers() == null ? new ArrayList<>()
                : new ArrayList<>(cfg.getCustomIncidentHandlers());
        // decision 144: the incident tenant is resolved (WorkTenants); an unresolved one goes to the system audit scope only
        incidents.add(new SangamIncidentHandler("failedJob", components.incidentSink(), components.auditSink(),
                components.workTenants()));
        incidents.add(new SangamIncidentHandler("failedExternalTask", components.incidentSink(), components.auditSink(),
                components.workTenants()));
        // decision 301 (ruling on W4-E INFO 07:26:42Z): the X.2.1.6 recovery-loop incident too, so it gets the
        // reconciliation row and exactly one INCIDENT_OPENED like every other incident the engine opens
        incidents.add(new SangamIncidentHandler(org.sangam.engine.job.PartitionLeaseManager.RECOVERY_LOOP,
                components.incidentSink(), components.auditSink(), components.workTenants()));
        // ADR-D-25: AMBIGUOUS_ROUTE / NO_MATCHING_ROUTE at a strict exclusive gateway
        // SG-35: also audited in the hash chain (ROUTING_INCIDENT with the stable code), never a silent park
        incidents.add(new org.sangam.engine.incident.RoutingIncidentHandler(
                org.sangam.engine.deploy.StrictExclusiveGatewayBehavior.INCIDENT_TYPE,
                components.incidentSink(), components.auditSink(), components.workTenants()));
        cfg.setCustomIncidentHandlers(incidents);
    }

    @Override
    public void postInit(ProcessEngineConfigurationImpl cfg) {
        SangamEngineSettings s = components.settings();
        // M-012 (owner decision 2026-11-12): fail closed before anything else when the resolved level is global none
        refuseGlobalHistoryNone(cfg.getHistoryLevel(), cfg.getHistory());
        // M-012 (integrator decision, evidence below): history level "auto" is refused as well
        refuseHistoryAuto(cfg.getHistoryLevel(), cfg.getHistory());
        // M-012 (integrator decision): Operaton maps the deprecated alias history=variable straight to its built-in
        // activity level (initHistoryLevel), bypassing the name lookup and so the mandatory-audit wrapper. A built-in
        // activity/audit object here can only come from such a bypass; it gets the wrapper with the same id and name.
        // Safe here: until postInit only the parse-listener factories read the level, and only for NONE.equals(..).
        cfg.setHistoryLevel(mandatoryAudit(cfg.getHistoryLevel()));
        // M-012: after Operaton built its handler from the DB handler and every plugin's custom handlers, audit-only
        // events (MandatoryAuditHistoryLevel) are routed to the audit chain only
        if (auditHandler != null && cfg.getHistoryEventHandler() != null
                && !(cfg.getHistoryEventHandler() instanceof MandatoryAuditHistoryEventHandler)) {
            cfg.setHistoryEventHandler(new MandatoryAuditHistoryEventHandler(cfg.getHistoryEventHandler(), auditHandler));
        }
        // M-056: partitioned acquisition still installed and its configuration supported (fail closed)
        if (jobSetup != null) {
            jobSetup.postInit(cfg);
        }
        // sangam_jobs_failed_total: every failed job execution goes through the failed-job command factory
        cfg.setFailedJobCommandFactory(new CountingFailedJobCommandFactory(cfg.getFailedJobCommandFactory(),
                components.metrics()));
        if (s.getJobIdentityBinder() == null) {
            LOG.warn("VI.4 rule 7: no JobIdentityBinder installed; jobs bind the tenant system identity only "
                    + "on the job's engine command (JobIdentitySupport.current()). Install TenantContext::runAs in the server.");
        }
        JobIdentitySupport identity = new JobIdentitySupport(s.getJobIdentityBinder(), components.workTenants());
        Map<String, JobHandler<?>> handlers = cfg.getJobHandlers();
        for (Map.Entry<String, JobHandler<?>> e : handlers.entrySet()) {
            e.setValue(GuardedJobHandler.wrap(e.getValue(), components.dialect(), components.rootLock(),
                    components.timerGuard(), components.timerPolicies(), components.clock(), components.metrics(),
                    s.getLockTimeout(), s.getStatementTimeout(), s.getDefaultTenantId(), identity, components.capture()));
        }
    }

    @Override
    public void postProcessEngineBuild(ProcessEngine processEngine) {
        // DataSource-level steps (P-1/P-2) run in SangamEngineConfigurer after the build.
        // M-012: history level "auto" is resolved from the database only during the build; check the result too
        ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
        try {
            refuseGlobalHistoryNone(cfg.getHistoryLevel(), null);
        } catch (RuntimeException e) {
            processEngine.close();
            throw e;
        }
        // decision 146: one resize service per engine, on every node, reachable by the host through the components;
        // decision 144: its audit (and the ownership audit) is written under the system audit scope
        SangamEngineSettings s = components.settings();
        String scope = components.workTenants().systemAuditScope();
        if (components.partitionResize() == null) {
            components.partitionResize(new org.sangam.engine.job.PartitionResize(cfg.getDataSource(),
                    components.dialect().coreDialect(), s.getExecutorClusterId(), s.getExecutorSlots(),
                    components.auditSink(), scope, s.getExecutorRecoveryBatch()));
        }
        // M-056 (C3-04): partition leases, only when partitioning is on; ownership audit + executor metrics
        jobSetup.startLeases(processEngine, components.metrics(), components.auditSink(), scope,
                components.partitionResize());
    }

    /**
     * M-012, owner decision 2026-11-12 (SANGAM.md X.2.3, T62): the global history level {@code none} is refused at
     * startup, failing closed, in every embedding (server, starter, tests). Operaton's built-in {@code none} level
     * produces no history events at all, so the class-B audit chain would receive none. A per-definition {@code none}
     * ({@code historyLevelByProcessKey}) is allowed: it runs through {@link MandatoryAuditHistoryLevel}, which keeps
     * the audit.
     *
     * @param resolved the engine's resolved level (may be {@code null} before "auto" is resolved)
     * @param history  the configured level name, or {@code null}
     */
    static void refuseGlobalHistoryNone(HistoryLevel resolved, String history) {
        boolean none = resolved != null ? HistoryLevel.HISTORY_LEVEL_NONE.equals(resolved)
                : history != null && "none".equalsIgnoreCase(history.trim());
        if (none) {
            throw new org.operaton.bpm.engine.ProcessEngineException(GLOBAL_NONE_REFUSED);
        }
    }

    /**
     * M-012 (integrator decision): history level {@code auto} is refused at startup, failing closed. Operaton resolves
     * it from {@code ACT_GE_PROPERTY} only inside the {@code ProcessEngineImpl} constructor
     * ({@code executeSchemaOperations} -> {@code DefaultHistoryLevelSetupCommand.determineAutoHistoryLevel}), i.e. after
     * {@code postInit}, and the same constructor then runs the bootstrap command and
     * {@code JobExecutor.registerProcessEngine}, which starts the acquisition thread when the executor auto-activates,
     * all before {@code postProcessEngineBuild} (javap, Operaton 2.2.0). Jobs could then produce class-B events
     * through the unwrapped built-in level before a wrapper could be installed, and Operaton's history listeners cache
     * the level they first see. So no point after the resolution is safe for the wrapper.
     */
    static void refuseHistoryAuto(HistoryLevel resolved, String history) {
        if (resolved == null && history != null && "auto".equalsIgnoreCase(history.trim())) {
            throw new org.operaton.bpm.engine.ProcessEngineException(AUTO_REFUSED);
        }
    }

    static final String AUTO_REFUSED = "Sangam refuses the history level 'auto' (SANGAM.md X.2.3: the class-B audit "
            + "can never be switched off, and an 'auto' level is resolved from the database only after Sangam's "
            + "mandatory-audit level is installed, while jobs may already run). Setting: operaton.bpm.history-level / "
            + "sangam.engine.history / ProcessEngineConfiguration.history = auto. Set an explicit level instead: "
            + "'activity', 'audit' or 'full' (the level this database was created with; 'none' is refused too).";
    /** The mandatory-audit wrapper for a built-in activity/audit level object; any other level is returned as is. */
    HistoryLevel mandatoryAudit(HistoryLevel resolved) {
        if (resolved == HistoryLevel.HISTORY_LEVEL_ACTIVITY && activityLevel != null) {
            return activityLevel;
        }
        if (resolved == HistoryLevel.HISTORY_LEVEL_AUDIT && auditLevel != null) {
            return auditLevel;
        }
        return resolved;
    }

    static final String GLOBAL_NONE_REFUSED = "Sangam refuses the global history level 'none' (SANGAM.md X.2.3: the "
            + "class-B audit can never be switched off, and Operaton's 'none' level produces no history events for the "
            + "audit chain). Setting: operaton.bpm.history-level / sangam.engine.history / "
            + "ProcessEngineConfiguration.history = none. Use history level 'activity' instead, or keep a higher global "
            + "level and set 'none' per definition (sangam.engine.history-level-by-process-key.<processKey>=none).";

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
            return switch (history == null ? "full" : history.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "none" -> HistoryLevel.HISTORY_LEVEL_NONE;
                // the deprecated alias "variable" is Operaton's activity level (initHistoryLevel), not full
                case "activity", "variable" -> HistoryLevel.HISTORY_LEVEL_ACTIVITY;
                // integrator decision: "auto" is refused as a per-definition base too (same message as global auto)
                case "auto" -> throw new org.operaton.bpm.engine.ProcessEngineException(AUTO_REFUSED);
                case "audit" -> HistoryLevel.HISTORY_LEVEL_AUDIT;
                default -> HistoryLevel.HISTORY_LEVEL_FULL;
            };
        }
    }
}
