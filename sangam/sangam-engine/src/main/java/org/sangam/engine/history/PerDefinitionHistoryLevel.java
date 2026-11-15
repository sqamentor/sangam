// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.history;

import java.util.Locale;
import java.util.Map;

import org.operaton.bpm.engine.impl.history.HistoryLevel;
import org.operaton.bpm.engine.impl.history.event.HistoryEventType;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ExternalTaskEntity;
import org.operaton.bpm.engine.impl.persistence.entity.IdentityLinkEntity;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.operaton.bpm.engine.impl.persistence.entity.TaskEntity;
import org.operaton.bpm.engine.impl.persistence.entity.VariableInstanceEntity;

/**
 * History configuration per process definition and per activity (FLOWABLE-INTO-OPERATON FC-26, FLOWABLE-INTO-OPERATON D-49): Operaton has one global level; this
 * level delegates to a configured level ({@code none | activity | audit | full}) per entry, most specific first:
 * {@code <processKey>#<activityId>}, then {@code <processKey>}, then the base level. The activity is the execution's
 * current activity, the task definition key (also for an identity link of a task), or the job's / external task's activity. Installed only when overrides are configured. Its id ({@value #ID}) is stored by the engine in
 * {@code ACT_GE_PROPERTY}, so every node must use the same configuration.
 */
public final class PerDefinitionHistoryLevel implements HistoryLevel {

    public static final int ID = 21;
    public static final String NAME = "sangam-per-definition";

    private final HistoryLevel base;
    private final Map<String, HistoryLevel> byKey;
    private final ModelHistoryLevels models;

    public PerDefinitionHistoryLevel(HistoryLevel base, Map<String, String> levelByProcessKey) {
        this(base, levelByProcessKey, null);
    }

    /**
     * M-057 (decision 88): with {@code models} (opt-in {@code historyModelOverrides}), a model's own
     * {@code sangam:historyLevel} applies where the operator configured nothing. Order, most specific first:
     * settings {@code key#activity}, settings {@code key}, model {@code definitionId#activity}, model
     * {@code definitionId}, the base level. The operator's settings win so a model can always be corrected.
     */
    public PerDefinitionHistoryLevel(HistoryLevel base, Map<String, String> levelByProcessKey, ModelHistoryLevels models) {
        this.base = base;
        this.byKey = new java.util.HashMap<>();
        levelByProcessKey.forEach((k, v) -> byKey.put(k, level(v)));
        this.models = models;
    }

    static HistoryLevel level(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "none" -> HistoryLevel.HISTORY_LEVEL_NONE;
            case "activity" -> HistoryLevel.HISTORY_LEVEL_ACTIVITY;
            case "audit" -> HistoryLevel.HISTORY_LEVEL_AUDIT;
            case "full" -> HistoryLevel.HISTORY_LEVEL_FULL;
            default -> throw new IllegalArgumentException("FLOWABLE-INTO-OPERATON FC-26: unknown history level " + name);
        };
    }

    @Override
    public int getId() {
        return ID;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isHistoryEventProduced(HistoryEventType eventType, Object entity) {
        String key = keyOf(entity);
        HistoryLevel l = null;
        if (key != null) {
            String activity = activityOf(entity);
            if (activity != null) {
                l = byKey.get(key + "#" + activity);
            }
            if (l == null) {
                l = byKey.get(key);
            }
            if (l == null && models != null) {
                String defId = definitionIdOf(entity);
                if (defId != null) {
                    String declared = activity != null ? models.get(defId + "#" + activity) : null;
                    if (declared == null) {
                        declared = models.get(defId);
                    }
                    l = declared == null ? null : level(declared);
                }
            }
        }
        return (l != null ? l : base).isHistoryEventProduced(eventType, entity);
    }

    private static String definitionIdOf(Object entity) {
        if (entity instanceof ExecutionEntity e) {
            return e.getProcessDefinitionId();
        } else if (entity instanceof TaskEntity t) {
            return t.getProcessDefinitionId();
        } else if (entity instanceof VariableInstanceEntity v) {
            return v.getProcessDefinitionId();
        } else if (entity instanceof ExternalTaskEntity x) {
            return x.getProcessDefinitionId();
        } else if (entity instanceof JobEntity j) {
            return j.getProcessDefinitionId();
        } else if (entity instanceof IdentityLinkEntity l) {
            return definitionIdOf(l);
        }
        return null;
    }

    /**
     * The definition of an identity link: its own (a link of a process definition), else its task's. Without this an
     * identity link of a task (candidate, assignee, owner) had no key and its history followed the base level, so a
     * definition configured {@code none} still got its identity-link log.
     */
    private static String definitionIdOf(IdentityLinkEntity l) {
        if (l.getProcessDefId() != null) {
            return l.getProcessDefId();
        }
        TaskEntity task = l.getTask();
        return task == null ? null : task.getProcessDefinitionId();
    }

    private static String activityOf(Object entity) {
        if (entity instanceof ExecutionEntity e) {
            return e.getActivityId();
        } else if (entity instanceof TaskEntity t) {
            return t.getTaskDefinitionKey();
        } else if (entity instanceof ExternalTaskEntity x) {
            return x.getActivityId();
        } else if (entity instanceof JobEntity j) {
            return j.getActivityId();
        } else if (entity instanceof IdentityLinkEntity l) {
            TaskEntity task = l.getTask();
            return task == null ? null : task.getTaskDefinitionKey();
        }
        return null;
    }

    private static String keyOf(Object entity) {
        String pdId = null;
        if (entity instanceof ExecutionEntity e) {
            pdId = e.getProcessDefinitionId();
        } else if (entity instanceof TaskEntity t) {
            pdId = t.getProcessDefinitionId();
        } else if (entity instanceof VariableInstanceEntity v) {
            pdId = v.getProcessDefinitionId();
        } else if (entity instanceof ExternalTaskEntity x) {
            return x.getProcessDefinitionKey();
        } else if (entity instanceof JobEntity j) {
            return j.getProcessDefinitionKey();
        } else if (entity instanceof IdentityLinkEntity l) {
            pdId = definitionIdOf(l);
        }
        if (pdId == null) {
            return null;
        }
        int i = pdId.indexOf(':');
        return i < 0 ? pdId : pdId.substring(0, i);
    }
}
