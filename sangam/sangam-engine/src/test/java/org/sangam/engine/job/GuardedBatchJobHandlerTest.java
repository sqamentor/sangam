// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.batch.BatchJobHandler;
import org.operaton.bpm.engine.impl.jobexecutor.AsyncContinuationJobHandler;
import org.operaton.bpm.engine.impl.jobexecutor.JobHandler;
import org.operaton.bpm.engine.impl.ModificationBatchJobHandler;

/**
 * OPERATON-UI-PARITY P01/P05/P27: Operaton's {@code BatchBuilder} casts the job-handler map entry of a batch type to
 * {@link BatchJobHandler}. Wrapping it in a plain {@link GuardedJobHandler} made every engine batch fail with a
 * ClassCastException (seen as HTTP 500 on {@code /engine-rest/modification/executeAsync}). The wrapper must keep the
 * batch contract and delegate it unchanged. End to end: sangam-server {@code EngineRestUiParityTest}.
 */
class GuardedBatchJobHandlerTest {

    static JobHandler<?> wrap(JobHandler<?> h) {
        return GuardedJobHandler.wrap(h, null, null, null, null, null, null, null, null, "dev", null);
    }

    @Test
    void aBatchHandlerStaysABatchHandlerAndDelegatesTheBatchContract() {
        ModificationBatchJobHandler engine = new ModificationBatchJobHandler();
        JobHandler<?> wrapped = wrap(engine);
        BatchJobHandler<?> batch = assertInstanceOf(BatchJobHandler.class, wrapped);
        assertInstanceOf(GuardedBatchJobHandler.class, wrapped);
        assertEquals(engine.getType(), batch.getType());
        assertSame(engine.getJobDeclaration(), batch.getJobDeclaration());
        assertSame(wrapped, wrap(wrapped), "wrapping is idempotent");
    }

    @Test
    void otherHandlersAreGuardedButNotBatchHandlers() {
        JobHandler<?> wrapped = wrap(new AsyncContinuationJobHandler());
        assertInstanceOf(GuardedJobHandler.class, wrapped);
        assertFalse(wrapped instanceof BatchJobHandler);
    }
}
