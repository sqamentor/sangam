// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Body of {@code POST /work/fetch-and-lock}; the server holds the request up to {@code asyncResponseTimeoutMs} (long poll). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FetchAndLockRequest(
        String workerId, List<FetchTopic> topics, int maxTasks, long asyncResponseTimeoutMs, Boolean usePriority) {
}
