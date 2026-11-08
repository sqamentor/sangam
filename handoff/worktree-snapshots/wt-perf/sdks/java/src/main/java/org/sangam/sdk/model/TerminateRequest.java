// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Business cancel / delete with a mandatory reason (VI.5, R23). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TerminateRequest(
        String reason, String reasonCode) {
}
