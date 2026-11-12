// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a saga's forward action leaves for its compensation (M-026, R36): the forward work's stable operationId, its
 * provider reference and its work id. Stored as the process variable {@link Saga#recordVariable(String)} when the
 * forward action completes.
 */
public record SagaRecord(String step, String operationId, String providerRef, String workId) {

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", step);
        m.put("operationId", operationId);
        m.put("providerRef", providerRef);
        m.put("workId", workId);
        return m;
    }

    static SagaRecord fromMap(Map<?, ?> m) {
        if (m == null || m.get("operationId") == null) {
            return null;
        }
        return new SagaRecord(str(m.get("step")), str(m.get("operationId")), str(m.get("providerRef")),
                str(m.get("workId")));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
