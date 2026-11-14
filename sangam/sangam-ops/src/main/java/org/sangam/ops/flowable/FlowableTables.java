// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import java.util.List;

/**
 * M-135: the Flowable 6.x / 7.x runtime tables and columns the migration reads, in one place.
 *
 * <p><b>Evidence level:</b> every table and column name here was checked against the Flowable engine source (the
 * read-only reference copy, version 8.1.0-SNAPSHOT; paths relative to its {@code modules/} folder):
 * <ul>
 *   <li>ACT_RU_EXECUTION and ACT_RE_PROCDEF: {@code flowable-engine/src/main/resources/org/flowable/db/create/
 *       flowable.h2.create.engine.sql} lines 32-76 and 78-98 (DEPLOYMENT_ID_ line 85, RESOURCE_NAME_ line 86);</li>
 *   <li>ACT_GE_BYTEARRAY, ACT_RU_EVENT_SUBSCR, ACT_RU_IDENTITYLINK, the five job tables, ACT_RU_TASK and
 *       ACT_RU_VARIABLE: {@code flowable-engine-common/src/main/resources/org/flowable/common/db/create/
 *       flowable.h2.create.common.sql} lines 8-15, 110-130, 137-151, 180-346, 439-478 and 548-566.</li>
 * </ul>
 * Facts from that source the reader relies on: ACT_RU_SUSPENDED_JOB and ACT_RU_DEADLETTER_JOB have no LOCK_OWNER_ or
 * LOCK_EXP_TIME_, and the dead-letter table has no RETRIES_ (lines 242-297), which reads as null; ELEMENT_ID_ was
 * added to the job tables by {@code flowable.h2.upgradestep.6500.to.6501.common.sql} lines 4-10 without filling old
 * rows, so a timer created before that version has none (see {@link FlowableInventory}); ACT_RU_EXTERNAL_JOB first
 * appears in {@code flowable.h2.upgradestep.6512.to.6513.common.sql} line 4. Still open: the names were checked against
 * the H2 scripts of one source version; other databases and older versions are confirmed only by the first real-data
 * run (decision 169). The reader never writes to these tables.
 */
public final class FlowableTables {

    private FlowableTables() {
    }

    public static final String PROCDEF = "ACT_RE_PROCDEF";
    public static final String EXECUTION = "ACT_RU_EXECUTION";
    public static final String TASK = "ACT_RU_TASK";
    public static final String IDENTITYLINK = "ACT_RU_IDENTITYLINK";
    public static final String VARIABLE = "ACT_RU_VARIABLE";
    public static final String EVENT_SUBSCR = "ACT_RU_EVENT_SUBSCR";
    /** Async continuations (Flowable "message" jobs). */
    public static final String JOB = "ACT_RU_JOB";
    public static final String TIMER_JOB = "ACT_RU_TIMER_JOB";
    public static final String SUSPENDED_JOB = "ACT_RU_SUSPENDED_JOB";
    public static final String DEADLETTER_JOB = "ACT_RU_DEADLETTER_JOB";
    /** External worker jobs (Flowable 6.6+); absent in older schemas, read as empty. */
    public static final String EXTERNAL_JOB = "ACT_RU_EXTERNAL_JOB";

    /** The job tables, in the order the inventory reads them. */
    public static final List<String> JOB_TABLES = List.of(JOB, TIMER_JOB, SUSPENDED_JOB, DEADLETTER_JOB, EXTERNAL_JOB);

    /** Every table the inventory counts. */
    public static final List<String> ALL = List.of(PROCDEF, EXECUTION, TASK, IDENTITYLINK, VARIABLE, EVENT_SUBSCR, JOB,
            TIMER_JOB, SUSPENDED_JOB, DEADLETTER_JOB, EXTERNAL_JOB);

    // common
    public static final String ID = "ID_";
    public static final String TENANT_ID = "TENANT_ID_";
    public static final String PROC_INST_ID = "PROC_INST_ID_";
    public static final String PROC_DEF_ID = "PROC_DEF_ID_";
    public static final String EXECUTION_ID = "EXECUTION_ID_";

    // ACT_RE_PROCDEF
    public static final String KEY = "KEY_";
    public static final String VERSION = "VERSION_";

    // ACT_RU_EXECUTION
    public static final String PARENT_ID = "PARENT_ID_";
    public static final String SUPER_EXEC = "SUPER_EXEC_";
    public static final String ACT_ID = "ACT_ID_";
    public static final String IS_ACTIVE = "IS_ACTIVE_";
    public static final String IS_SCOPE = "IS_SCOPE_";
    public static final String IS_MI_ROOT = "IS_MI_ROOT_";
    public static final String IS_EVENT_SCOPE = "IS_EVENT_SCOPE_";
    /** 1 active, 2 suspended. */
    public static final String SUSPENSION_STATE = "SUSPENSION_STATE_";
    public static final String BUSINESS_KEY = "BUSINESS_KEY_";
    /** Set when the instance belongs to a CMMN case (case-to-process link). */
    public static final String CALLBACK_TYPE = "CALLBACK_TYPE_";

    // ACT_RU_TASK
    public static final String TASK_DEF_KEY = "TASK_DEF_KEY_";
    public static final String NAME = "NAME_";
    public static final String ASSIGNEE = "ASSIGNEE_";
    public static final String OWNER = "OWNER_";
    /** PENDING or RESOLVED, null when not delegated. */
    public static final String DELEGATION = "DELEGATION_";
    public static final String PRIORITY = "PRIORITY_";
    public static final String DUE_DATE = "DUE_DATE_";
    public static final String PARENT_TASK_ID = "PARENT_TASK_ID_";
    /** "cmmn" for a case task; null or "bpmn" for a process task. */
    public static final String SCOPE_TYPE = "SCOPE_TYPE_";

    // ACT_RU_IDENTITYLINK
    public static final String TASK_ID = "TASK_ID_";
    /** "candidate" for candidate users and groups. */
    public static final String TYPE = "TYPE_";
    public static final String USER_ID = "USER_ID_";
    public static final String GROUP_ID = "GROUP_ID_";

    // ACT_RU_VARIABLE (TYPE_, NAME_, EXECUTION_ID_, PROC_INST_ID_, TASK_ID_ shared above)
    public static final String BYTEARRAY_ID = "BYTEARRAY_ID_";
    public static final String DOUBLE = "DOUBLE_";
    public static final String LONG = "LONG_";
    public static final String TEXT = "TEXT_";

    // ACT_RU_EVENT_SUBSCR
    /** message, signal or compensate. */
    public static final String EVENT_TYPE = "EVENT_TYPE_";
    public static final String EVENT_NAME = "EVENT_NAME_";
    public static final String ACTIVITY_ID = "ACTIVITY_ID_";

    // job tables (ACT_RU_*JOB): the instance column is PROCESS_INSTANCE_ID_, not PROC_INST_ID_
    public static final String JOB_PROCESS_INSTANCE_ID = "PROCESS_INSTANCE_ID_";
    public static final String ELEMENT_ID = "ELEMENT_ID_";
    public static final String LOCK_OWNER = "LOCK_OWNER_";
    public static final String LOCK_EXP_TIME = "LOCK_EXP_TIME_";
    public static final String RETRIES = "RETRIES_";
    public static final String EXCEPTION_MSG = "EXCEPTION_MSG_";
    public static final String DUEDATE = "DUEDATE_";
    public static final String REPEAT = "REPEAT_";
    public static final String HANDLER_TYPE = "HANDLER_TYPE_";
    /** Job TYPE_ of a timer job (suspended and dead-letter tables hold both kinds); flowable-job-service-api Job.java:25. */
    public static final String JOB_TYPE_TIMER = "timer";
    /** JSON configuration of the job handler; for a timer it names the activity (TimerEventHandler.java:28-38). */
    public static final String HANDLER_CFG = "HANDLER_CFG_";
    /**
     * Handler of an asyncBefore continuation: ContinueProcessOperation.java:187 (an asyncAfter leave is
     * {@code async-after}, TakeOutgoingSequenceFlowsOperation.java:133; an async trigger {@code async-trigger},
     * TriggerExecutionOperation.java:64), so only this handler means "the activity has not started yet".
     */
    public static final String HANDLER_ASYNC_CONTINUATION = "async-continuation";
    /** Handler of boundary, intermediate and event-subprocess timers (TriggerTimerEventJobHandler.java:31). */
    public static final String HANDLER_TRIGGER_TIMER = "trigger-timer";
    /**
     * Handler of an external-worker job (ExternalWorkerTaskCompleteJobHandler.java:35, set at
     * ExternalWorkerTaskActivityBehavior.java:91). The same handler in ACT_RU_JOB means the worker already completed.
     */
    public static final String HANDLER_EXTERNAL_WORKER_COMPLETE = "external-worker-complete";
    /** Property of the timer handler configuration that holds the activity id (TimerEventHandler.java:24). */
    public static final String TIMER_CFG_ACTIVITY_ID = "activityId";

    // deployed resources (the BPMN XML of a definition; read only to find source listeners)
    public static final String BYTEARRAY = "ACT_GE_BYTEARRAY";
    public static final String DEPLOYMENT_ID = "DEPLOYMENT_ID_";
    public static final String RESOURCE_NAME = "RESOURCE_NAME_";
    public static final String BYTES = "BYTES_";
}
