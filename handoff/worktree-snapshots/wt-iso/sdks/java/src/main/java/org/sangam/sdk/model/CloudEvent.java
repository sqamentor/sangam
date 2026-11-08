// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;

/** CloudEvents 1.0 structured JSON (R38: inbox de-duplication by tenant, source, id). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudEvent(
        String id, String source, String type, String specversion, String subject, Instant time,
        String datacontenttype, String dataschema, Object data, Map<String, Object> extensions) {
}
