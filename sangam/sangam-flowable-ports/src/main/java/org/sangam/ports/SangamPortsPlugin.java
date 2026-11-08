// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports;

import org.operaton.bpm.dmn.engine.DmnEngineConfiguration;
import org.operaton.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cmmn.deployer.CmmnDeployer;
import org.operaton.bpm.engine.impl.cmmn.transformer.CmmnTransformListener;
import org.operaton.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.operaton.bpm.engine.impl.jobexecutor.JobHandler;
import org.operaton.bpm.engine.impl.persistence.deploy.Deployer;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.adhoc.SangamBpmnParseFactory;
import org.sangam.ports.bpmn.BpmnExtensionsParseListener;
import org.sangam.ports.bpmn.SangamModelValidator;
import org.sangam.ports.cmmn.CmmnTimerJobHandler;
import org.sangam.ports.cmmn.PortsCmmnTransformListener;
import org.sangam.ports.cmmn.SangamCmmnElementHandlerRegistry;
import org.sangam.ports.debug.DebuggerService;
import org.sangam.ports.dmn.PriorityHitPolicies;
import org.sangam.ports.events.EventLog;
import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.events.EventRegistryParseListener;
import org.sangam.ports.events.OutboundEventService;
import org.sangam.ports.history.EngineEventBus;
import org.sangam.ports.history.PortsHistoryEventHandler;
import org.sangam.ports.jobs.SplitJobExecutors;
import org.sangam.ports.links.InstanceLinkService;
import org.sangam.ports.task.TaskEventDispatcher;
import org.sangam.ports.task.TaskLifecycleService;
import org.sangam.ports.variables.MultiInstanceAggregation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Registers every engine-side Flowable port (ADR-D-16 plugin). Register it <b>after</b> WS-ENGINE's
 * {@code SangamProcessEnginePlugin}: its listeners are appended to the same lists, so WS-ENGINE's validations
 * (scripts, MI limit, bindings, TTL) see Operaton's behaviours first and the ports' behaviour replacements run last.
 *
 * <ul>
 *   <li>preInit: BPMN parse factory (P-1 ad-hoc), post BPMN parse listeners (event registry, FLOWABLE-INTO-OPERATON FC-12/13/22, FLOWABLE-INTO-OPERATON FC-21, task
 *       events, optional debugger), CMMN transform listener (FLOWABLE-INTO-OPERATON FC-55 marks, FLOWABLE-INTO-OPERATON FC-56 lifecycle listeners), CMMN timer job
 *       handler, history handler (FLOWABLE-INTO-OPERATON FC-43 bus, FLOWABLE-INTO-OPERATON FC-32 links, optional FLOWABLE-INTO-OPERATON FC-25 read-model feed), DMN hit policies (FLOWABLE-INTO-OPERATON FC-54),
 *       pre-deployer validator (FLOWABLE-INTO-OPERATON FC-46), split job executors / hand-off (FLOWABLE-INTO-OPERATON FC-24).</li>
 *   <li>postInit: CMMN element handler registry with event listeners (FLOWABLE-INTO-OPERATON FC-55).</li>
 *   <li>postProcessEngineBuild: {@link PortsContext} install; timer executor start.</li>
 * </ul>
 */
public class SangamPortsPlugin implements ProcessEnginePlugin {

    private final PortsSettings settings;
    private final EngineEventBus bus = new EngineEventBus();
    private final DebuggerService debuggerOrNull;
    private final PortsCmmnTransformListener cmmnListener;
    private SplitJobExecutors splitExecutors;
    private Transactions tx;
    private InstanceLinkService links;
    private TaskLifecycleService taskLifecycle;
    private MultiInstanceAggregation aggregation;
    private EventRegistry eventRegistry;
    private OutboundEventService outboundEvents;

    public SangamPortsPlugin(PortsSettings settings, DebuggerService debuggerOrNull) {
        this.settings = settings;
        this.debuggerOrNull = debuggerOrNull;
        this.cmmnListener = new PortsCmmnTransformListener(settings.isCmmnLifecycleListeners());
    }

    public EngineEventBus eventBus() {
        return bus;
    }

    public PortsCmmnTransformListener cmmnListener() {
        return cmmnListener;
    }

    public SplitJobExecutors splitJobExecutors() {
        return splitExecutors;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl cfg) {
        tx = commandTransactions(cfg);
        links = new InstanceLinkService(settings.getDialect(), tx);
        taskLifecycle = new TaskLifecycleService(settings.getDialect(), tx);
        aggregation = new MultiInstanceAggregation(settings.getDialect());

        if (settings.isAdHocSubProcess()) {
            cfg.setBpmnParseFactory(new SangamBpmnParseFactory());
        }

        List<BpmnParseListener> bpmn = cfg.getCustomPostBPMNParseListeners() == null ? new ArrayList<>()
                : new ArrayList<>(cfg.getCustomPostBPMNParseListeners());
        if (settings.isEventRegistry()) {
            bpmn.add(new EventRegistryParseListener());
            eventRegistry = new EventRegistry(settings.getDialect(), tx, Duration.ofSeconds(10));
            if (settings.getOutbox() != null) {
                outboundEvents = new OutboundEventService(eventRegistry, settings.getOutbox(),
                        new EventLog(settings.getDialect(), settings.isStoreEventPayloads()));
            }
        }
        if (settings.isBpmnBehaviourExtensions()) {
            bpmn.add(new BpmnExtensionsParseListener(settings.isSkipExpressionsAlwaysEnabled(), bus));
        }
        if (settings.isMultiInstanceAggregation()) {
            bpmn.add(aggregation);
        }
        if (settings.isTaskEvents()) {
            bpmn.add(new TaskEventDispatcher(bus, taskLifecycle).parseListener());
        }
        if (debuggerOrNull != null) {
            bpmn.add(debuggerOrNull.parseListener());
        }
        cfg.setCustomPostBPMNParseListeners(bpmn);

        List<CmmnTransformListener> cmmn = cfg.getCustomPostCmmnTransformListeners() == null ? new ArrayList<>()
                : new ArrayList<>(cfg.getCustomPostCmmnTransformListeners());
        cmmn.add(cmmnListener);
        cfg.setCustomPostCmmnTransformListeners(cmmn);

        if (settings.isCmmnEventListeners()) {
            List<JobHandler<?>> handlers = cfg.getCustomJobHandlers() == null ? new ArrayList<>() : new ArrayList<>(cfg.getCustomJobHandlers());
            handlers.add(new CmmnTimerJobHandler());
            cfg.setCustomJobHandlers(handlers);
        }

        List<HistoryEventHandler> history = cfg.getCustomHistoryEventHandlers() == null ? new ArrayList<>()
                : new ArrayList<>(cfg.getCustomHistoryEventHandlers());
        history.add(new PortsHistoryEventHandler(settings.isReadModelFeed() ? settings.getOutbox() : null,
                settings.getReadModelExtraDestinations(), bus, settings.isEntityLinks() ? links : null));
        cfg.setCustomHistoryEventHandlers(history);

        if (settings.isDmnPriorityHitPolicies()) {
            DefaultDmnEngineConfiguration dmn = cfg.getDmnEngineConfiguration() != null ? cfg.getDmnEngineConfiguration()
                    : (DefaultDmnEngineConfiguration) DmnEngineConfiguration.createDefaultDmnEngineConfiguration();
            PriorityHitPolicies.install(dmn);
            cfg.setDmnEngineConfiguration(dmn);
        }

        if (settings.isPreDeployValidator()) {
            List<Deployer> pre = cfg.getCustomPreDeployers() == null ? new ArrayList<>() : new ArrayList<>(cfg.getCustomPreDeployers());
            pre.add(new SangamModelValidator(new SangamModelValidator.Options(settings.isAdHocSubProcess())).deployer());
            cfg.setCustomPreDeployers(pre);
        }

        if (settings.isSplitJobExecutors() || settings.isAsyncJobHandOff()) {
            splitExecutors = new SplitJobExecutors(settings.getDialect(), settings.getTimerThreads(), settings.isGlobalAcquireLock(),
                    settings.isAsyncJobHandOff());
            splitExecutors.preInit(cfg);
        }
    }

    @Override
    public void postInit(ProcessEngineConfigurationImpl cfg) {
        if (settings.isCmmnEventListeners() && cfg.getDeployers() != null) {
            for (Deployer d : cfg.getDeployers()) {
                if (d instanceof CmmnDeployer cd && cd.getTransformer() != null) {
                    cd.getTransformer().setCmmnElementHandlerRegistry(new SangamCmmnElementHandlerRegistry());
                }
            }
        }
    }

    @Override
    public void postProcessEngineBuild(ProcessEngine processEngine) {
        PortsContext.install(new PortsContext(settings.getDialect(), outboundEvents, bus, taskLifecycle, aggregation));
        if (splitExecutors != null) {
            splitExecutors.postBuild(processEngine);
        }
    }

    public EventRegistry eventRegistry() {
        return eventRegistry;
    }

    public InstanceLinkService links() {
        return links;
    }

    /** Product transactions that join or open an engine command of this configuration. */
    private static Transactions commandTransactions(ProcessEngineConfigurationImpl cfg) {
        return new Transactions() {
            @Override
            public <T> T inTransaction(java.util.function.Function<java.sql.Connection, T> work) {
                return cfg.getCommandExecutorTxRequired().execute(cc -> work.apply(cc.getDbSqlSession().getSqlSession().getConnection()));
            }
        };
    }
}
