// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;

import org.sangam.rest.port.ApiProblem;

/**
 * SANGAM-PAYMENT-LOOP (IFMS E-31 §6, IFMS [REPLY]): a withdrawal or cancellation of a case whose payment outcome is
 * unknown is refused by the pack (LifecycleStateDelegate, "409 OUTCOME_UNKNOWN: ..."). Every engine call of the REST
 * layer (message correlation included) goes through {@link OperatonSupport#call}, which maps that coded refusal to
 * HTTP 409 with the code OUTCOME_UNKNOWN, not to a generic 422 ENGINE_REJECTED.
 */
class OutcomeUnknownProblemTest {

    @Test
    void theCodedRefusalIsHttp409OutcomeUnknown() {
        String refusal = "409 OUTCOME_UNKNOWN: the case has an operation with an unknown outcome (RECON-1); "
                + "cancellation waits for the reconciliation evidence";
        ApiProblem p = assertThrows(ApiProblem.class, () -> OperatonSupport.call(() -> {
            throw new ProcessEngineException(refusal);
        }));
        assertEquals(409, p.status());
        assertEquals("OUTCOME_UNKNOWN", p.code());
    }
}
