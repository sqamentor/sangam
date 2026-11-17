// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import java.time.Instant;
import java.util.List;

import org.operaton.bpm.engine.ProcessEngine;

/** PATCHES.md P-5 case migration (A1). Fail-first stub: no validation, no rebind, no audit yet. */
public class CaseMigrationService {

    public record MigrationRecord(String id, String tenantId, String caseInstanceId, String sourceDefinitionId,
                                  String targetDefinitionId, String migratedBy, String reason, int executions,
                                  Instant createdAt) {
    }

    public static final class CaseMigrationException extends RuntimeException {
        private final List<String> problems;

        public CaseMigrationException(List<String> problems) {
            super("case migration rejected: " + problems);
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    private final ProcessEngine engine;

    public CaseMigrationService(ProcessEngine engine) {
        this.engine = engine;
    }

    public MigrationRecord migrate(String caseInstanceId, String targetCaseDefinitionId, String user, String reason) {
        return new MigrationRecord(null, null, caseInstanceId, null, targetCaseDefinitionId, user, reason, 0, Instant.now());
    }

    public List<MigrationRecord> history(String caseInstanceId) {
        return List.of();
    }
}
