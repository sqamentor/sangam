// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

import java.util.Map;

/** A saga's forward action (M-026): a {@link TaskHandler} whose completion also records a {@link SagaRecord}. */
@FunctionalInterface
public interface SagaAction {
    Map<String, Object> run(TaskContext ctx) throws Exception;
}
