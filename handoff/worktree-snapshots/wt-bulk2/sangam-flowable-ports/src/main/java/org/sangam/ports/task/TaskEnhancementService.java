// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.task;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.task.IdentityLink;
import org.operaton.bpm.engine.task.Task;
import org.sangam.ports.links.InstanceLinkService;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Task enhancements that Flowable has and Operaton exposes only partly:
 * <ul>
 *   <li><b>Sub-tasks.</b> Operaton supports {@code parentTaskId} natively; this adds the Flowable-style create call
 *       (inherits tenant, process/case scope and priority from the parent) and an opt-in rule that a parent cannot be
 *       completed while sub-tasks are open.</li>
 *   <li><b>Task identity links</b> with Flowable's types ({@code participant}, {@code watcher}, custom types) on
 *       top of Operaton's {@code addUserIdentityLink/addGroupIdentityLink}; type names are validated.</li>
 *   <li><b>Process/case instance involvement</b> (Flowable {@code addParticipantUser}, {@code involvedUser}),
 *       absent in Operaton: stored as {@code identity} links in {@code INSTANCE_LINK}.</li>
 * </ul>
 * Authorization (who may add links or complete) is checked by the caller through WS-SECURITY's VI.4 predicate.
 */
public class TaskEnhancementService {

    public static final Set<String> TASK_LINK_TYPES = Set.of("candidate", "participant", "watcher", "reviewer", "starter");

    private final ProcessEngine engine;
    private final InstanceLinkService links;
    private final boolean requireSubTasksClosed;

    public TaskEnhancementService(ProcessEngine engine, InstanceLinkService links, boolean requireSubTasksClosed) {
        this.engine = engine;
        this.links = links;
        this.requireSubTasksClosed = requireSubTasksClosed;
    }

    public Task createSubTask(String parentTaskId, String name, String assignee, Date dueDate, Map<String, Object> variables) {
        TaskService ts = engine.getTaskService();
        Task parent = ts.createTaskQuery().taskId(parentTaskId).singleResult();
        if (parent == null) {
            throw new ProcessEngineException("parent task " + parentTaskId + " not found");
        }
        Task sub = ts.newTask();
        sub.setParentTaskId(parentTaskId);
        sub.setName(name);
        sub.setAssignee(assignee);
        sub.setDueDate(dueDate);
        sub.setPriority(parent.getPriority());
        sub.setTenantId(parent.getTenantId());
        if (parent.getCaseInstanceId() != null) {
            sub.setCaseInstanceId(parent.getCaseInstanceId());
        }
        ts.saveTask(sub);
        if (variables != null && !variables.isEmpty()) {
            ts.setVariablesLocal(sub.getId(), variables);
        }
        return sub;
    }

    public List<Task> subTasks(String parentTaskId) {
        return engine.getTaskService().getSubTasks(parentTaskId);
    }

    /** Completes a task; with {@code requireSubTasksClosed} it fails while sub-tasks are open. */
    public void complete(String taskId, Map<String, Object> variables) {
        if (requireSubTasksClosed && !engine.getTaskService().getSubTasks(taskId).isEmpty()) {
            throw new ProcessEngineException("task " + taskId + " has open sub-tasks");
        }
        engine.getTaskService().complete(taskId, variables);
    }

    public void addUserLink(String taskId, String userId, String type) {
        engine.getTaskService().addUserIdentityLink(taskId, userId, validType(type));
    }

    public void addGroupLink(String taskId, String groupId, String type) {
        engine.getTaskService().addGroupIdentityLink(taskId, groupId, validType(type));
    }

    public void removeUserLink(String taskId, String userId, String type) {
        engine.getTaskService().deleteUserIdentityLink(taskId, userId, validType(type));
    }

    public List<IdentityLink> links(String taskId) {
        return engine.getTaskService().getIdentityLinksForTask(taskId);
    }

    /** Flowable {@code RuntimeService.addParticipantUser} for a process or case instance. */
    public void addInstanceParticipant(String tenant, String scopeType, String instanceId, String userId, String type, String actor) {
        links.addReferenceTyped(tenant, scopeType, instanceId, "user", userId, "identity:" + validType(type), actor);
    }

    public List<String> involvedUsers(String tenant, String scopeType, String instanceId) {
        return links.outgoing(tenant, scopeType, instanceId).stream()
                .filter(l -> l.linkType().startsWith("identity:") && "user".equals(l.targetType()))
                .map(InstanceLinkService.Link::targetId).distinct().toList();
    }

    private static String validType(String type) {
        if (type == null || !(TASK_LINK_TYPES.contains(type) || type.startsWith("x-"))) {
            throw new IllegalArgumentException("identity link type '" + type + "' not allowed (" + TASK_LINK_TYPES + " or x-*)");
        }
        return type;
    }
}
