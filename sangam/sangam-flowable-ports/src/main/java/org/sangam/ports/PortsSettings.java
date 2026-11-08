// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Outbox;

import java.util.ArrayList;
import java.util.List;

/**
 * Switches and shared parts of the Flowable ports (one object, bound from {@code sangam.ports.*} by the server).
 * Every port is on by default except the ones that change engine-wide behaviour beyond WS-ENGINE's defaults
 * (split job executors, hand-off, read-model feed, debugger).
 */
public class PortsSettings {

    private Dialect dialect = Dialect.POSTGRESQL;
    private Outbox outbox;

    private boolean adHocSubProcess = true;            // FLOWABLE-INTO-OPERATON FC-01, patch P-1
    private boolean eventRegistry = true;               // FLOWABLE-INTO-OPERATON FC-02..04, FLOWABLE-INTO-OPERATON FC-43
    private boolean bpmnBehaviourExtensions = true;     // FLOWABLE-INTO-OPERATON FC-12, FLOWABLE-INTO-OPERATON FC-13, FLOWABLE-INTO-OPERATON FC-22
    private boolean skipExpressionsAlwaysEnabled = false;
    private boolean multiInstanceAggregation = true;    // FLOWABLE-INTO-OPERATON FC-21
    private boolean taskEvents = true;                  // task listeners on more events, FLOWABLE-INTO-OPERATON FC-52
    private boolean entityLinks = true;                 // FLOWABLE-INTO-OPERATON FC-31/32
    private boolean readModelFeed = false;              // FLOWABLE-INTO-OPERATON FC-25 projection / FLOWABLE-INTO-OPERATON FC-20
    private List<String> readModelExtraDestinations = new ArrayList<>();
    private boolean cmmnEventListeners = true;          // FLOWABLE-INTO-OPERATON FC-55 (WS-ENGINE: rejectCmmnEventListeners=false)
    private boolean cmmnLifecycleListeners = true;      // FLOWABLE-INTO-OPERATON FC-56 part
    private boolean dmnPriorityHitPolicies = true;      // FLOWABLE-INTO-OPERATON FC-54
    private boolean preDeployValidator = true;          // FLOWABLE-INTO-OPERATON FC-46 complement
    private boolean splitJobExecutors = false;          // FLOWABLE-INTO-OPERATON FC-24
    private int timerThreads = 2;
    private boolean globalAcquireLock = false;          // FLOWABLE-INTO-OPERATON FC-24
    private boolean asyncJobHandOff = false;            // FLOWABLE-INTO-OPERATON FC-24
    private boolean storeEventPayloads = false;         // EVT_LOG payloads (R61: off)

    public Dialect getDialect() { return dialect; }
    public void setDialect(Dialect v) { dialect = v; }
    public Outbox getOutbox() { return outbox; }
    public void setOutbox(Outbox v) { outbox = v; }
    public boolean isAdHocSubProcess() { return adHocSubProcess; }
    public void setAdHocSubProcess(boolean v) { adHocSubProcess = v; }
    public boolean isEventRegistry() { return eventRegistry; }
    public void setEventRegistry(boolean v) { eventRegistry = v; }
    public boolean isBpmnBehaviourExtensions() { return bpmnBehaviourExtensions; }
    public void setBpmnBehaviourExtensions(boolean v) { bpmnBehaviourExtensions = v; }
    public boolean isSkipExpressionsAlwaysEnabled() { return skipExpressionsAlwaysEnabled; }
    public void setSkipExpressionsAlwaysEnabled(boolean v) { skipExpressionsAlwaysEnabled = v; }
    public boolean isMultiInstanceAggregation() { return multiInstanceAggregation; }
    public void setMultiInstanceAggregation(boolean v) { multiInstanceAggregation = v; }
    public boolean isTaskEvents() { return taskEvents; }
    public void setTaskEvents(boolean v) { taskEvents = v; }
    public boolean isEntityLinks() { return entityLinks; }
    public void setEntityLinks(boolean v) { entityLinks = v; }
    public boolean isReadModelFeed() { return readModelFeed; }
    public void setReadModelFeed(boolean v) { readModelFeed = v; }
    public List<String> getReadModelExtraDestinations() { return readModelExtraDestinations; }
    public void setReadModelExtraDestinations(List<String> v) { readModelExtraDestinations = v; }
    public boolean isCmmnEventListeners() { return cmmnEventListeners; }
    public void setCmmnEventListeners(boolean v) { cmmnEventListeners = v; }
    public boolean isCmmnLifecycleListeners() { return cmmnLifecycleListeners; }
    public void setCmmnLifecycleListeners(boolean v) { cmmnLifecycleListeners = v; }
    public boolean isDmnPriorityHitPolicies() { return dmnPriorityHitPolicies; }
    public void setDmnPriorityHitPolicies(boolean v) { dmnPriorityHitPolicies = v; }
    public boolean isPreDeployValidator() { return preDeployValidator; }
    public void setPreDeployValidator(boolean v) { preDeployValidator = v; }
    public boolean isSplitJobExecutors() { return splitJobExecutors; }
    public void setSplitJobExecutors(boolean v) { splitJobExecutors = v; }
    public int getTimerThreads() { return timerThreads; }
    public void setTimerThreads(int v) { timerThreads = v; }
    public boolean isGlobalAcquireLock() { return globalAcquireLock; }
    public void setGlobalAcquireLock(boolean v) { globalAcquireLock = v; }
    public boolean isAsyncJobHandOff() { return asyncJobHandOff; }
    public void setAsyncJobHandOff(boolean v) { asyncJobHandOff = v; }
    public boolean isStoreEventPayloads() { return storeEventPayloads; }
    public void setStoreEventPayloads(boolean v) { storeEventPayloads = v; }
}
