// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/** {@code POST /process-instances}. The tenant comes from the token (VI.4 rule 6). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StartProcessRequest(
        String key, String businessKey, Map<String, Object> variables, Integer version, String packageHash,
        String formVersion, List<String> startBeforeActivityIds) {
}
