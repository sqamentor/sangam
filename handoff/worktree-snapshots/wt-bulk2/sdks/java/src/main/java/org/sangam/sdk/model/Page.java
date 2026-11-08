// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One page of a list (ARCHITECTURE section 4): {@code X-Total-Count} and {@code X-Total-Count-Capped: true} when the
 * count was capped (A-16), in which case {@code totalCount} is a lower bound ("more than N").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Page<T>(
        List<T> items, int page, int size, Long totalCount, boolean totalCountCapped) {
}
