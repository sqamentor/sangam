// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

/**
 * M-135: stops and drains the Flowable side before any state is copied. Flowable's async executor, external workers and
 * REST clients are outside this product, so the operator binds this port to whatever stops them (an executor switch,
 * a maintenance page, a deployment scale-down). The pipeline only checks it.
 *
 * <p>Contract: after {@link #freeze} returns, no Flowable job executes and no client changes a Flowable instance until
 * {@link #release}; {@link #drained} is true once every job that was running when the freeze started has ended.
 * The pipeline also re-reads the source and refuses while any job is still locked.
 */
public interface WorkerFreeze {

    void freeze(String runId);

    boolean frozen(String runId);

    boolean drained(String runId);

    void release(String runId);
}
