// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

/** A raw engine deployment. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Deployment(
        String id, String name, String source, String tenantId, Instant deploymentTime, List<Object> resources, List<Object> definitions) {
}
