// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.custody;

import java.time.Instant;
import java.util.Map;

/**
 * A disposal order (destroy, return, auction, transfer out, release) issued under an authority reference;
 * {@code ORDERED} until executed by a different person, or {@code CANCELLED}.
 */
public record DisposalOrder(String id, String itemId, String method, String authorityRef, String status,
                            Instant orderedAt, String orderedBy, Instant executedAt, String executedBy,
                            Map<String, Object> details) {
}
