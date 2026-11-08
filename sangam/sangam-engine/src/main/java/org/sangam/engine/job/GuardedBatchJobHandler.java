// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import org.operaton.bpm.engine.impl.batch.BatchEntity;
import org.operaton.bpm.engine.impl.batch.BatchJobConfiguration;
import org.operaton.bpm.engine.impl.batch.BatchJobHandler;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.jobexecutor.JobDeclaration;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.operaton.bpm.engine.impl.persistence.entity.MessageEntity;

/**
 * {@link GuardedJobHandler} for a batch job handler. Operaton's {@code BatchBuilder} looks the batch type up in the
 * job-handler map and casts it to {@link BatchJobHandler}; a plain wrapper made every batch (async modification,
 * migration, set removal time, delete, ...) fail with a ClassCastException. Job execution goes through the guard
 * (identity, lock timeouts, L2 lock); the batch contract (configuration, job creation and deletion) goes to the
 * engine's handler unchanged.
 */
public final class GuardedBatchJobHandler<T> implements BatchJobHandler<T> {

    private final BatchJobHandler<T> batch;
    private final GuardedJobHandler<BatchJobConfiguration> guarded;

    public GuardedBatchJobHandler(BatchJobHandler<T> batch, GuardedJobHandler<BatchJobConfiguration> guarded) {
        this.batch = batch;
        this.guarded = guarded;
    }

    public BatchJobHandler<T> delegate() {
        return batch;
    }

    @Override
    public String getType() {
        return batch.getType();
    }

    @Override
    public void execute(BatchJobConfiguration configuration, ExecutionEntity execution, CommandContext cc, String tenantId) {
        guarded.execute(configuration, execution, cc, tenantId);
    }

    @Override
    public BatchJobConfiguration newConfiguration(String canonicalString) {
        return batch.newConfiguration(canonicalString);
    }

    @Override
    public void onDelete(BatchJobConfiguration configuration, JobEntity jobEntity) {
        batch.onDelete(configuration, jobEntity);
    }

    @Override
    public byte[] writeConfiguration(T configuration) {
        return batch.writeConfiguration(configuration);
    }

    @Override
    public T readConfiguration(byte[] serializedConfiguration) {
        return batch.readConfiguration(serializedConfiguration);
    }

    @Override
    public JobDeclaration<?, MessageEntity> getJobDeclaration() {
        return batch.getJobDeclaration();
    }

    @Override
    public boolean createJobs(BatchEntity batchEntity) {
        return batch.createJobs(batchEntity);
    }

    @Override
    public void deleteJobs(BatchEntity batchEntity) {
        batch.deleteJobs(batchEntity);
    }

    @Override
    public int calculateInvocationsPerBatchJob(String batchType, T configuration) {
        return batch.calculateInvocationsPerBatchJob(batchType, configuration);
    }
}
