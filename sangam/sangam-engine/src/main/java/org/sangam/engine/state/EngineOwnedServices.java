// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.state;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.BadUserRequestException;
import org.operaton.bpm.engine.batch.Batch;
import org.operaton.bpm.engine.history.HistoricProcessInstanceQuery;
import org.operaton.bpm.engine.impl.ConditionEvaluationBuilderImpl;
import org.operaton.bpm.engine.impl.ExternalTaskServiceImpl;
import org.operaton.bpm.engine.impl.FormServiceImpl;
import org.operaton.bpm.engine.impl.MessageCorrelationAsyncBuilderImpl;
import org.operaton.bpm.engine.impl.MessageCorrelationBuilderImpl;
import org.operaton.bpm.engine.impl.ProcessInstanceModificationBuilderImpl;
import org.operaton.bpm.engine.impl.ProcessInstantiationBuilderImpl;
import org.operaton.bpm.engine.impl.RuntimeServiceImpl;
import org.operaton.bpm.engine.impl.SignalEventReceivedBuilderImpl;
import org.operaton.bpm.engine.impl.TaskServiceImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.interceptor.CommandExecutor;
import org.operaton.bpm.engine.impl.migration.MigrationPlanBuilderImpl;
import org.operaton.bpm.engine.migration.MigrationPlanBuilder;
import org.operaton.bpm.engine.runtime.ConditionEvaluationBuilder;
import org.operaton.bpm.engine.runtime.MessageCorrelationAsyncBuilder;
import org.operaton.bpm.engine.runtime.MessageCorrelationBuilder;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstanceModificationBuilder;
import org.operaton.bpm.engine.runtime.ProcessInstanceModificationInstantiationBuilder;
import org.operaton.bpm.engine.runtime.ProcessInstanceQuery;
import org.operaton.bpm.engine.runtime.ProcessInstantiationBuilder;
import org.operaton.bpm.engine.runtime.SignalEventReceivedBuilder;
import org.operaton.bpm.engine.variable.VariableMap;
import org.sangam.core.engine.EngineOwnedVariables;

/**
 * Engine-owned variables are refused on every caller-facing write path (owner spec SEQ-PARALLEL point 9, increment
 * I-1, integrator ruling option A; PATCHES I-30 / B-49). The engine's runtime, task, form and external-task services
 * are replaced by subclasses of Operaton's own implementations (so {@code ServiceImpl} casts, I-26, and
 * {@code engine-rest}'s casts keep working); every public method that takes caller variables or names a variable to
 * write or remove checks {@link EngineOwnedVariables} first, and the builder factories return builder subclasses that
 * do the same. The engine's own writes ({@code DelegateExecution}, multi-instance behaviours, listeners) never pass
 * through these services, so they are unaffected. {@code EngineOwnedServicesPinTest} fails the build when an Operaton
 * version adds a write method that is not overridden here.
 */
public final class EngineOwnedServices {

    private EngineOwnedServices() {
    }

    /** Installs the four services; call from a plugin's {@code preInit}, before the engine initialises its services. */
    public static void install(ProcessEngineConfigurationImpl cfg) {
        cfg.setRuntimeService(new Runtime());
        cfg.setTaskService(new Tasks());
        cfg.setFormService(new Forms());
        cfg.setExternalTaskService(new ExternalTasks());
    }

    static void checkNames(Collection<?> names) {
        String refusal = EngineOwnedVariables.refusal(names);
        if (refusal != null) {
            throw new BadUserRequestException(refusal);
        }
    }

    static void checkName(String name) {
        checkNames(name == null ? List.of() : List.of(name));
    }

    static void check(Map<?, ?> variables) {
        if (variables != null) {
            checkNames(variables.keySet());
        }
    }

    // ---------------------------------------------------------------- services

    /** Runtime service: process variables, starts, signals, messages, and the builders it creates. */
    public static class Runtime extends RuntimeServiceImpl {

        @Override
        public ProcessInstance startProcessInstanceByKey(String key, Map<String, Object> v) {
            check(v);
            return super.startProcessInstanceByKey(key, v);
        }

        @Override
        public ProcessInstance startProcessInstanceByKey(String key, String businessKey, Map<String, Object> v) {
            check(v);
            return super.startProcessInstanceByKey(key, businessKey, v);
        }

        @Override
        public ProcessInstance startProcessInstanceByKey(String key, String businessKey, String caseInstanceId,
                Map<String, Object> v) {
            check(v);
            return super.startProcessInstanceByKey(key, businessKey, caseInstanceId, v);
        }

        @Override
        public ProcessInstance startProcessInstanceById(String id, Map<String, Object> v) {
            check(v);
            return super.startProcessInstanceById(id, v);
        }

        @Override
        public ProcessInstance startProcessInstanceById(String id, String businessKey, Map<String, Object> v) {
            check(v);
            return super.startProcessInstanceById(id, businessKey, v);
        }

        @Override
        public ProcessInstance startProcessInstanceById(String id, String businessKey, String caseInstanceId,
                Map<String, Object> v) {
            check(v);
            return super.startProcessInstanceById(id, businessKey, caseInstanceId, v);
        }

        @Override
        public void setVariable(String executionId, String name, Object value) {
            checkName(name);
            super.setVariable(executionId, name, value);
        }

        @Override
        public void setVariableLocal(String executionId, String name, Object value) {
            checkName(name);
            super.setVariableLocal(executionId, name, value);
        }

        @Override
        public void setVariables(String executionId, Map<String, ? extends Object> v) {
            check(v);
            super.setVariables(executionId, v);
        }

        @Override
        public void setVariablesLocal(String executionId, Map<String, ? extends Object> v) {
            check(v);
            super.setVariablesLocal(executionId, v);
        }

        @Override
        public Batch setVariablesAsync(List<String> ids, ProcessInstanceQuery q, HistoricProcessInstanceQuery h,
                Map<String, ?> v) {
            check(v);
            return super.setVariablesAsync(ids, q, h, v);
        }

        @Override
        public Batch setVariablesAsync(List<String> ids, Map<String, ?> v) {
            check(v);
            return super.setVariablesAsync(ids, v);
        }

        @Override
        public Batch setVariablesAsync(ProcessInstanceQuery q, Map<String, ?> v) {
            check(v);
            return super.setVariablesAsync(q, v);
        }

        @Override
        public Batch setVariablesAsync(HistoricProcessInstanceQuery h, Map<String, ?> v) {
            check(v);
            return super.setVariablesAsync(h, v);
        }

        @Override
        public void removeVariable(String executionId, String name) {
            checkName(name);
            super.removeVariable(executionId, name);
        }

        @Override
        public void removeVariableLocal(String executionId, String name) {
            checkName(name);
            super.removeVariableLocal(executionId, name);
        }

        @Override
        public void removeVariables(String executionId, Collection<String> names) {
            checkNames(names);
            super.removeVariables(executionId, names);
        }

        @Override
        public void removeVariablesLocal(String executionId, Collection<String> names) {
            checkNames(names);
            super.removeVariablesLocal(executionId, names);
        }

        @Override
        public void updateVariables(String executionId, Map<String, ? extends Object> v, Collection<String> del) {
            check(v);
            checkNames(del);
            super.updateVariables(executionId, v, del);
        }

        @Override
        public void updateVariablesLocal(String executionId, Map<String, ? extends Object> v, Collection<String> del) {
            check(v);
            checkNames(del);
            super.updateVariablesLocal(executionId, v, del);
        }

        @Override
        public void signal(String executionId, String signalName, Object signalData, Map<String, Object> v) {
            check(v);
            super.signal(executionId, signalName, signalData, v);
        }

        @Override
        public void signal(String executionId, Map<String, Object> v) {
            check(v);
            super.signal(executionId, v);
        }

        @Override
        public ProcessInstance startProcessInstanceByMessage(String message, Map<String, Object> v) {
            check(v);
            return super.startProcessInstanceByMessage(message, v);
        }

        @Override
        public ProcessInstance startProcessInstanceByMessage(String message, String businessKey, Map<String, Object> v) {
            check(v);
            return super.startProcessInstanceByMessage(message, businessKey, v);
        }

        @Override
        public ProcessInstance startProcessInstanceByMessageAndProcessDefinitionId(String message, String id,
                Map<String, Object> v) {
            check(v);
            return super.startProcessInstanceByMessageAndProcessDefinitionId(message, id, v);
        }

        @Override
        public ProcessInstance startProcessInstanceByMessageAndProcessDefinitionId(String message, String id,
                String businessKey, Map<String, Object> v) {
            check(v);
            return super.startProcessInstanceByMessageAndProcessDefinitionId(message, id, businessKey, v);
        }

        @Override
        public void signalEventReceived(String signalName, Map<String, Object> v) {
            check(v);
            super.signalEventReceived(signalName, v);
        }

        @Override
        public void signalEventReceived(String signalName, String executionId, Map<String, Object> v) {
            check(v);
            super.signalEventReceived(signalName, executionId, v);
        }

        @Override
        public void messageEventReceived(String messageName, String executionId, Map<String, Object> v) {
            check(v);
            super.messageEventReceived(messageName, executionId, v);
        }

        @Override
        public void correlateMessage(String messageName, Map<String, Object> keys, Map<String, Object> v) {
            check(v);
            super.correlateMessage(messageName, keys, v);
        }

        @Override
        public void correlateMessage(String messageName, String businessKey, Map<String, Object> keys,
                Map<String, Object> v) {
            check(v);
            super.correlateMessage(messageName, businessKey, keys, v);
        }

        @Override
        public void correlateMessage(String messageName, Map<String, Object> keys) {
            // the map holds correlation keys (a read filter), not variables to write
            super.correlateMessage(messageName, keys);
        }

        @Override
        public void correlateMessage(String messageName, String businessKey, Map<String, Object> v) {
            check(v);
            super.correlateMessage(messageName, businessKey, v);
        }

        @Override
        public SignalEventReceivedBuilder createSignalEvent(String signalName) {
            return new Signals(commandExecutor, signalName);
        }

        @Override
        public MessageCorrelationBuilder createMessageCorrelation(String messageName) {
            return new Correlations(commandExecutor, messageName);
        }

        @Override
        public MessageCorrelationAsyncBuilder createMessageCorrelationAsync(String messageName) {
            return new AsyncCorrelations(commandExecutor, messageName);
        }

        @Override
        public ProcessInstanceModificationBuilder createProcessInstanceModification(String processInstanceId) {
            return new Modifications(commandExecutor, processInstanceId);
        }

        @Override
        public ProcessInstantiationBuilder createProcessInstanceById(String processDefinitionId) {
            Instantiations b = new Instantiations(commandExecutor);
            b.byId(processDefinitionId);
            return b;
        }

        @Override
        public ProcessInstantiationBuilder createProcessInstanceByKey(String processDefinitionKey) {
            Instantiations b = new Instantiations(commandExecutor);
            b.byKey(processDefinitionKey);
            return b;
        }

        @Override
        public MigrationPlanBuilder createMigrationPlan(String sourceId, String targetId) {
            return new MigrationPlans(commandExecutor, sourceId, targetId);
        }

        @Override
        public ConditionEvaluationBuilder createConditionEvaluation() {
            return new Conditions(commandExecutor);
        }
    }

    /** Task service: completion, resolution, BPMN error and escalation variables, task variables. */
    public static class Tasks extends TaskServiceImpl {

        @Override
        public void complete(String taskId, Map<String, Object> v) {
            check(v);
            super.complete(taskId, v);
        }

        @Override
        public VariableMap completeWithVariablesInReturn(String taskId, Map<String, Object> v, boolean deserialize) {
            check(v);
            return super.completeWithVariablesInReturn(taskId, v, deserialize);
        }

        @Override
        public void resolveTask(String taskId, Map<String, Object> v) {
            check(v);
            super.resolveTask(taskId, v);
        }

        @Override
        public void setVariable(String taskId, String name, Object value) {
            checkName(name);
            super.setVariable(taskId, name, value);
        }

        @Override
        public void setVariableLocal(String taskId, String name, Object value) {
            checkName(name);
            super.setVariableLocal(taskId, name, value);
        }

        @Override
        public void setVariables(String taskId, Map<String, ? extends Object> v) {
            check(v);
            super.setVariables(taskId, v);
        }

        @Override
        public void setVariablesLocal(String taskId, Map<String, ? extends Object> v) {
            check(v);
            super.setVariablesLocal(taskId, v);
        }

        @Override
        public void updateVariablesLocal(String taskId, Map<String, ? extends Object> v, Collection<String> del) {
            check(v);
            checkNames(del);
            super.updateVariablesLocal(taskId, v, del);
        }

        @Override
        public void updateVariables(String taskId, Map<String, ? extends Object> v, Collection<String> del) {
            check(v);
            checkNames(del);
            super.updateVariables(taskId, v, del);
        }

        @Override
        public void removeVariable(String taskId, String name) {
            checkName(name);
            super.removeVariable(taskId, name);
        }

        @Override
        public void removeVariableLocal(String taskId, String name) {
            checkName(name);
            super.removeVariableLocal(taskId, name);
        }

        @Override
        public void removeVariables(String taskId, Collection<String> names) {
            checkNames(names);
            super.removeVariables(taskId, names);
        }

        @Override
        public void removeVariablesLocal(String taskId, Collection<String> names) {
            checkNames(names);
            super.removeVariablesLocal(taskId, names);
        }

        @Override
        public void handleBpmnError(String taskId, String code, String message, Map<String, Object> v) {
            check(v);
            super.handleBpmnError(taskId, code, message, v);
        }

        @Override
        public void handleEscalation(String taskId, String code, Map<String, Object> v) {
            check(v);
            super.handleEscalation(taskId, code, v);
        }
    }

    /** Form service: start and task form submissions. */
    public static class Forms extends FormServiceImpl {

        @Override
        public ProcessInstance submitStartForm(String id, Map<String, Object> v) {
            check(v);
            return super.submitStartForm(id, v);
        }

        @Override
        public ProcessInstance submitStartForm(String id, String businessKey, Map<String, Object> v) {
            check(v);
            return super.submitStartForm(id, businessKey, v);
        }

        @Override
        public ProcessInstance submitStartFormData(String id, Map<String, String> v) {
            check(v);
            return super.submitStartFormData(id, v);
        }

        @Override
        public ProcessInstance submitStartFormData(String id, String businessKey, Map<String, String> v) {
            check(v);
            return super.submitStartFormData(id, businessKey, v);
        }

        @Override
        public void submitTaskFormData(String taskId, Map<String, String> v) {
            check(v);
            super.submitTaskFormData(taskId, v);
        }

        @Override
        public void submitTaskForm(String taskId, Map<String, Object> v) {
            check(v);
            super.submitTaskForm(taskId, v);
        }

        @Override
        public VariableMap submitTaskFormWithVariablesInReturn(String taskId, Map<String, Object> v, boolean deser) {
            check(v);
            return super.submitTaskFormWithVariablesInReturn(taskId, v, deser);
        }
    }

    /** External-task service: worker completion, failure and BPMN error variables. */
    public static class ExternalTasks extends ExternalTaskServiceImpl {

        @Override
        public void complete(String id, String workerId, Map<String, Object> v) {
            check(v);
            super.complete(id, workerId, v);
        }

        @Override
        public void complete(String id, String workerId, Map<String, Object> v, Map<String, Object> local) {
            check(v);
            check(local);
            super.complete(id, workerId, v, local);
        }

        @Override
        public void handleFailure(String id, String workerId, String message, String details, int retries,
                long timeout, Map<String, Object> v, Map<String, Object> local) {
            check(v);
            check(local);
            super.handleFailure(id, workerId, message, details, retries, timeout, v, local);
        }

        @Override
        public void handleBpmnError(String id, String workerId, String code, String message, Map<String, Object> v) {
            check(v);
            super.handleBpmnError(id, workerId, code, message, v);
        }
    }

    // ---------------------------------------------------------------- builders

    /** Message correlation builder; {@code processInstanceVariablesEqual} / {@code localVariablesEqual} are filters. */
    public static class Correlations extends MessageCorrelationBuilderImpl {

        Correlations(CommandExecutor executor, String messageName) {
            super(executor, messageName);
        }

        @Override
        public MessageCorrelationBuilder setVariable(String name, Object value) {
            checkName(name);
            return super.setVariable(name, value);
        }

        @Override
        public MessageCorrelationBuilder setVariableLocal(String name, Object value) {
            checkName(name);
            return super.setVariableLocal(name, value);
        }

        @Override
        public MessageCorrelationBuilder setVariableToTriggeredScope(String name, Object value) {
            checkName(name);
            return super.setVariableToTriggeredScope(name, value);
        }

        @Override
        public MessageCorrelationBuilder setVariables(Map<String, Object> v) {
            check(v);
            return super.setVariables(v);
        }

        @Override
        public MessageCorrelationBuilder setVariablesLocal(Map<String, Object> v) {
            check(v);
            return super.setVariablesLocal(v);
        }

        @Override
        public MessageCorrelationBuilder setVariablesToTriggeredScope(Map<String, Object> v) {
            check(v);
            return super.setVariablesToTriggeredScope(v);
        }
    }

    /** Asynchronous message correlation builder. */
    public static class AsyncCorrelations extends MessageCorrelationAsyncBuilderImpl {

        AsyncCorrelations(CommandExecutor executor, String messageName) {
            super(executor, messageName);
        }

        @Override
        public MessageCorrelationAsyncBuilder setVariable(String name, Object value) {
            checkName(name);
            return super.setVariable(name, value);
        }

        @Override
        public MessageCorrelationAsyncBuilder setVariables(Map<String, Object> v) {
            check(v);
            return super.setVariables(v);
        }
    }

    /** Process instantiation builder (start with variables, start before/after activities with variables). */
    public static class Instantiations extends ProcessInstantiationBuilderImpl {

        Instantiations(CommandExecutor executor) {
            super(executor);
        }

        void byKey(String key) {
            this.processDefinitionKey = key;
        }

        void byId(String id) {
            this.processDefinitionId = id;
        }

        @Override
        public ProcessInstantiationBuilder setVariable(String name, Object value) {
            checkName(name);
            return super.setVariable(name, value);
        }

        @Override
        public ProcessInstantiationBuilder setVariableLocal(String name, Object value) {
            checkName(name);
            return super.setVariableLocal(name, value);
        }

        @Override
        public ProcessInstantiationBuilder setVariables(Map<String, Object> v) {
            check(v);
            return super.setVariables(v);
        }

        @Override
        public ProcessInstantiationBuilder setVariablesLocal(Map<String, Object> v) {
            check(v);
            return super.setVariablesLocal(v);
        }
    }

    /** Process instance modification builder (variables on start-before/after/transition instructions). */
    public static class Modifications extends ProcessInstanceModificationBuilderImpl {

        Modifications(CommandExecutor executor, String processInstanceId) {
            super(executor, processInstanceId);
        }

        @Override
        public ProcessInstanceModificationInstantiationBuilder setVariable(String name, Object value) {
            checkName(name);
            return super.setVariable(name, value);
        }

        @Override
        public ProcessInstanceModificationInstantiationBuilder setVariableLocal(String name, Object value) {
            checkName(name);
            return super.setVariableLocal(name, value);
        }

        @Override
        public ProcessInstanceModificationInstantiationBuilder setVariables(Map<String, Object> v) {
            check(v);
            return super.setVariables(v);
        }

        @Override
        public ProcessInstanceModificationInstantiationBuilder setVariablesLocal(Map<String, Object> v) {
            check(v);
            return super.setVariablesLocal(v);
        }
    }

    /** Signal builder. */
    public static class Signals extends SignalEventReceivedBuilderImpl {

        Signals(CommandExecutor executor, String signalName) {
            super(executor, signalName);
        }

        @Override
        public SignalEventReceivedBuilder setVariables(Map<String, Object> v) {
            check(v);
            return super.setVariables(v);
        }
    }

    /** Conditional start evaluation builder. */
    public static class Conditions extends ConditionEvaluationBuilderImpl {

        Conditions(CommandExecutor executor) {
            super(executor);
        }

        @Override
        public ConditionEvaluationBuilder setVariable(String name, Object value) {
            checkName(name);
            return super.setVariable(name, value);
        }

        @Override
        public ConditionEvaluationBuilder setVariables(Map<String, Object> v) {
            check(v);
            return super.setVariables(v);
        }
    }

    /** Migration plan builder (variables set on the migrated instances). */
    public static class MigrationPlans extends MigrationPlanBuilderImpl {

        MigrationPlans(CommandExecutor executor, String sourceId, String targetId) {
            super(executor, sourceId, targetId);
        }

        @Override
        public MigrationPlanBuilder setVariables(Map<String, ?> v) {
            check(v);
            return super.setVariables(v);
        }
    }
}
