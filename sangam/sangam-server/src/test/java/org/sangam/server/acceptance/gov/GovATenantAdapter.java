// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.gov;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.sangam.core.api.EngineAdapter;
import org.sangam.core.engine.SangamEngineAdapter;

/**
 * Sangam's own engine adapter bean, scoped for the gov-a ports in the shared server: every deployment, start, message
 * correlation and task query runs in tenant {@link #TENANT}; external-task topics carry the prefix
 * {@link #TOPIC_PREFIX}; business keys carry the run suffix. The spike ran one fresh engine and database per
 * scenario; in one shared server this keeps process keys, topics, messages and business keys of the gov-a cases apart
 * from every other acceptance test. Nothing else is changed: each call is the product adapter's call.
 */
final class GovATenantAdapter implements EngineAdapter {

    static final String TENANT = "gova";
    static final String TOPIC_PREFIX = "gova-";

    private final SangamEngineAdapter d;
    private final String run;

    GovATenantAdapter(SangamEngineAdapter delegate, String run) {
        this.d = delegate;
        this.run = run;
    }

    String bk(String businessKey) {
        return businessKey == null ? null : businessKey + "~" + run;
    }

    private static String t(String tenantId) {
        return tenantId == null ? TENANT : tenantId;
    }

    private static TaskQuery q(TaskQuery q) {
        return q.tenantId() != null ? q : new TaskQuery(TENANT, q.candidateUser(), q.candidateGroups(),
                q.processInstanceId(), q.taskDefinitionKey(), q.firstResult(), q.maxResults());
    }

    @Override
    public String engineId() {
        return d.engineId();
    }

    @Override
    public String deploy(String tenantId, String deploymentName, Map<String, byte[]> resources) {
        return d.deploy(t(tenantId), deploymentName + "-" + run, resources);
    }

    @Override
    public String startProcess(String tenantId, String processKey, String businessKey, Map<String, Object> variables) {
        return d.startProcess(t(tenantId), processKey, bk(businessKey), variables);
    }

    @Override
    public String startCase(String tenantId, String caseKey, String businessKey, Map<String, Object> variables) {
        return d.startCase(t(tenantId), caseKey, bk(businessKey), variables);
    }

    @Override
    public String correlateMessage(String tenantId, String messageName, String businessKey, Map<String, Object> variables) {
        return d.correlateMessage(t(tenantId), messageName, bk(businessKey), variables);
    }

    @Override
    public List<TaskView> queryTasks(TaskQuery query) {
        return d.queryTasks(q(query));
    }

    @Override
    public long countTasks(TaskQuery query) {
        return d.countTasks(q(query));
    }

    @Override
    public void claimTask(String taskId, String userId) {
        d.claimTask(taskId, userId);
    }

    @Override
    public void completeTask(String taskId, Map<String, Object> variables) {
        d.completeTask(taskId, variables);
    }

    @Override
    public List<ExternalWork> fetchAndLock(String workerId, String topic, int maxItems, Duration lockDuration) {
        return d.fetchAndLock(workerId, TOPIC_PREFIX + topic, maxItems, lockDuration);
    }

    @Override
    public void extendLock(String workId, String workerId, long lockVersion, Duration newDuration) {
        d.extendLock(workId, workerId, lockVersion, newDuration);
    }

    @Override
    public void completeExternal(String workId, String workerId, long lockVersion, Map<String, Object> variables) {
        d.completeExternal(workId, workerId, lockVersion, variables);
    }

    @Override
    public void failExternal(String workId, String workerId, long lockVersion, String errorMessage, int retriesLeft,
                             Duration retryBackoff) {
        d.failExternal(workId, workerId, lockVersion, errorMessage, retriesLeft, retryBackoff);
    }

    @Override
    public void bpmnErrorExternal(String workId, String workerId, long lockVersion, String errorCode,
                                  Map<String, Object> variables) {
        d.bpmnErrorExternal(workId, workerId, lockVersion, errorCode, variables);
    }

    @Override
    public void unlockExternal(String workId, String workerId, long lockVersion) {
        d.unlockExternal(workId, workerId, lockVersion);
    }

    @Override
    public void cancelInstance(String instanceId, String reason) {
        d.cancelInstance(instanceId, reason);
    }

    @Override
    public InstanceView getInstance(String instanceId) {
        return d.getInstance(instanceId);
    }

    @Override
    public int executeDueJobs() {
        return d.executeDueJobs();
    }

    @Override
    public void setClock(Instant now) {
        d.setClock(now);
    }

    @Override
    public <T> T inEngineTransaction(Function<Connection, T> work) {
        return d.inEngineTransaction(work);
    }

    @Override
    public String jdbcUrl() {
        return d.jdbcUrl();
    }

    @Override
    public void close() {
        // the server owns the engine
    }
}
