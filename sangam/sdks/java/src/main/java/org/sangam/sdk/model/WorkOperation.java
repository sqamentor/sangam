// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * WorkOperation (sangam-api.yaml; SL-16): the operation-ledger entry of a leased work item. The server records the
 * intent for {@code operationId} when it leases the work (R37). {@code mayDispatch=false} with state SUCCEEDED: the
 * downstream already succeeded; complete from {@code storedOutcome} ({output, providerRef, duplicate}) without calling
 * it again. {@code attempts > 1} is a repeat attempt (class B: look the outcome up first).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkOperation(String state, int attempts, String capabilityClass, boolean mayDispatch,
                            Map<String, Object> storedOutcome) {

    public boolean succeeded() {
        return "SUCCEEDED".equals(state);
    }
}