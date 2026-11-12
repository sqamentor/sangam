// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

import java.util.Map;

/**
 * Undoes a saga's forward action (M-026). Runs as the external task of the BPMN compensation handler
 * ({@code isForCompensation="true"}); the result variables, errors and outcome-unknown follow {@link TaskHandler}.
 */
@FunctionalInterface
public interface SagaCompensation {
    Map<String, Object> compensate(CompensationContext ctx) throws Exception;
}
