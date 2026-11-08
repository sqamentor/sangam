// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.listener;

import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.TaskListener;
import org.operaton.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.pvm.process.ScopeImpl;
import org.operaton.bpm.engine.impl.task.TaskDefinition;
import org.operaton.bpm.engine.impl.util.xml.Element;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Global user task listeners set by configuration, with no model change. Each rule names events, a process-key pattern and a handler
 * id. At parse time matching rules attach to the user task. A handler with {@code failTask=false} cannot fail the task: its exception
 * is logged. Applies to definitions parsed after the configuration is loaded, so already-cached definitions change on the next engine
 * start or redeployment.
 */
public final class GlobalTaskListeners {

    public record Rule(String id, List<String> events, Pattern processKey, String handler, boolean failTask) {
    }

    /** Named handler, for example publishing to the outbox. It runs in the engine transaction. */
    @FunctionalInterface
    public interface Handler {
        void onTask(DelegateTask task, String ruleId);
    }

    private final List<Rule> rules;
    private final Map<String, Handler> handlers;

    public GlobalTaskListeners(List<Rule> rules, Map<String, Handler> handlers) {
        for (Rule r : rules) {
            if (!handlers.containsKey(r.handler())) {
                throw new IllegalArgumentException("rule " + r.id() + " names an unknown handler " + r.handler());
            }
        }
        this.rules = List.copyOf(rules);
        this.handlers = Map.copyOf(handlers);
    }

    /** Add to {@code customPostBPMNParseListeners} through the engine plugin. */
    public BpmnParseListener parseListener() {
        return new BpmnParseListener() {
            @Override
            public void parseUserTask(Element element, ScopeImpl scope, ActivityImpl activity) {
                if (!(activity.getActivityBehavior() instanceof UserTaskActivityBehavior b)) {
                    return;
                }
                // At parse time the process definition is the entity, whose key is already set from the BPMN process id.
                var pd = activity.getProcessDefinition();
                attach(b.getTaskDefinition(), pd instanceof org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity e ? e.getKey() : pd.getId());
            }
        };
    }

    void attach(TaskDefinition td, String processKey) {
        for (Rule r : rules) {
            if (r.processKey().matcher(processKey).matches()) {
                TaskListener l = listener(r);
                r.events().forEach(ev -> td.addBuiltInTaskListener(ev, l));
            }
        }
    }

    private TaskListener listener(Rule r) {
        Handler h = handlers.get(r.handler());
        return task -> {
            try {
                h.onTask(task, r.id());
            } catch (RuntimeException e) {
                if (r.failTask()) {
                    throw e;
                }
                org.slf4j.LoggerFactory.getLogger(GlobalTaskListeners.class).warn("global listener {} failed on task {}: {}", r.id(), task.getId(), e.toString());
            }
        };
    }
}
