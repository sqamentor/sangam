// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Answer of {@code POST /events/{channel}}: ACCEPTED, DUPLICATE or BUFFERED. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PublishResult(
        String eventId, String status, String eventDefinitionKey, List<String> startedInstanceIds, List<String> correlatedInstanceIds) {
}
