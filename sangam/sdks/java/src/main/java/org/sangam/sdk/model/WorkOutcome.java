// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/** WorkOutcome (sangam-api.yaml; SL-16): the downstream reference (stored as the ledger providerRef) and details. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkOutcome(String reference, Map<String, Object> details) {
}