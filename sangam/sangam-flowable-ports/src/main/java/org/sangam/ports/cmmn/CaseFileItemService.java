// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import java.time.Instant;
import java.util.List;

import org.operaton.bpm.engine.ProcessEngine;

/** Owner ruling C1 (option A): CMMN case file items. Fail-first stub: no state is recorded yet. */
public class CaseFileItemService {

    public record CaseFileItemState(String tenantId, String caseInstanceId, String itemId, String variableName,
                                    String definitionType, String state, Instant updatedAt) {
    }

    private final ProcessEngine engine;

    public CaseFileItemService(ProcessEngine engine) {
        this.engine = engine;
    }

    public List<CaseFileItemState> states(String caseInstanceId) {
        return List.of();
    }
}
